package com.project.phm.service;

import com.opencsv.CSVReader;
import com.project.phm.mapper.AircraftConfigMapper;
import com.project.phm.utils.CsvColumnAnalyzer;
import com.project.phm.utils.CsvColumnAnalyzer.AnalysisResult;
import com.project.phm.utils.CsvUtils;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import com.project.phm.utils.IoTDbUtils;
import com.project.phm.entity.ConfigItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IoTDB 版 CSV 上传服务
 *
 * 数据流向:
 *   CSV文件 → IoTDB (时序数据)
 *   CSV列属性分析 → CsvColumnAnalyzer → 自动创建 ConfigItem (达梦管理库)
 *   元数据(哈希/行数/关联) → 达梦 csv_table_metadata + config_data_mapping
 */
@Service
public class IotCsvService {

    private static final Logger log = LoggerFactory.getLogger(IotCsvService.class);

    private final IoTDbUtils iotDb;
    private final DbUtils dbUtils;
    private final DataValidationUtils validationUtils;
    private final CsvColumnAnalyzer columnAnalyzer;
    private final AircraftConfigService configService;
    private final AircraftConfigMapper aircraftConfigMapper;

    public IotCsvService(IoTDbUtils iotDb, DbUtils dbUtils,
                         DataValidationUtils validationUtils,
                         CsvColumnAnalyzer columnAnalyzer,
                         AircraftConfigService configService,
                         AircraftConfigMapper aircraftConfigMapper) {
        this.iotDb = iotDb;
        this.dbUtils = dbUtils;
        this.validationUtils = validationUtils;
        this.columnAnalyzer = columnAnalyzer;
        this.configService = configService;
        this.aircraftConfigMapper = aircraftConfigMapper;
    }

    /**
     * 分析CSV列属性（上传前调用，返回列类型和构型模板建议）
     */
    public AnalysisResult analyzeColumns(MultipartFile file) throws Exception {
        return columnAnalyzer.analyze(file);
    }

    /**
     * 上传CSV → IoTDB + 自动创建构型模板
     *
     * @param file       CSV文件
     * @param deviceName IoTDB设备名（去掉csv_前缀）
     * @param tailNumber 机号（必须，用于构型关联）
     * @param parentItemId 父级构型项目ID（模板创建到此节点下）
     * @param dataType   数据类型 RAW/DIAGNOSIS/EVALUATION/PREDICTION
     */
    public Map<String, Object> uploadCsv(MultipartFile file, String deviceName,
                                          String tailNumber, Long parentItemId,
                                          String dataType) throws Exception {
        long startTime = System.currentTimeMillis();

        // 1. 解析CSV全部数据
        List<String> columns = new ArrayList<>();
        List<Map<String, String>> dataRows = new ArrayList<>();
        String timestampCol = null;

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String[] header = reader.readNext();
            if (header == null || header.length == 0) {
                throw new IllegalArgumentException("CSV文件为空");
            }
            for (String col : header) {
                columns.add(CsvUtils.processColumnName(col));
            }

            // 检测时间戳列
            for (String col : columns) {
                if (IoTDbUtils.isTimestampColumn(col)) {
                    timestampCol = col;
                    break;
                }
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (isRowEmpty(row)) continue;
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < columns.size() && i < row.length; i++) {
                    rowMap.put(columns.get(i), row[i] != null ? row[i].trim() : "");
                }
                dataRows.add(rowMap);
            }
        }

        if (dataRows.isEmpty()) {
            throw new IllegalArgumentException("CSV文件没有有效数据行");
        }

        // 处理Date列+Time列分离的情况 → 合并为完整时间戳
        if (timestampCol != null) {
            String dateColName = null;
            for (String col : columns) {
                String lower = col.toLowerCase();
                if (lower.equals("date") || lower.equals("flight_date") || lower.equals("utc_date")) {
                    dateColName = col;
                    break;
                }
            }
            if (dateColName != null && !dateColName.equals(timestampCol)) {
                for (Map<String, String> row : dataRows) {
                    String dateVal = row.get(dateColName);
                    String timeVal = row.get(timestampCol);
                    if (dateVal != null && timeVal != null && !dateVal.isEmpty() && !timeVal.isEmpty()) {
                        row.put(timestampCol, dateVal + " " + timeVal);
                    }
                }
            }
        }

        // 2. 分析列类型 — 用AnalysisResult
        AnalysisResult analysis = columnAnalyzer.analyze(file);

        // 3. 创建IoTDB timeseries（所有非时间戳列，含TEXT类型）
        String cleanDeviceName = sanitizeDeviceName(deviceName);
        Map<String, String> allTypes = new LinkedHashMap<>(analysis.getColumnTypes());
        if (timestampCol != null) allTypes.remove(timestampCol);
        if (analysis.getTimestampColumn() != null) allTypes.remove(analysis.getTimestampColumn());
        if (!allTypes.isEmpty()) {
            iotDb.createAlignedTimeseries(tailNumber, cleanDeviceName, allTypes);
        }

        // 4. 插入数据到IoTDB
        iotDb.insertAlignedRows(tailNumber, cleanDeviceName, timestampCol, dataRows);

        // 5. 自动创建构型模板 — 每个数值列生成一个ConfigItem
        List<Long> createdItemIds = new ArrayList<>();
        if (parentItemId != null && !analysis.getNumericColumns().isEmpty()) {
            for (String col : analysis.getNumericColumns()) {
                try {
                    ConfigItem item = new ConfigItem();
                    // 自动根据机型查找 modelCode
                    String modelCode = findModelCodeByTailNumber(tailNumber);
                    if (modelCode != null) {
                        item.setModelCode(modelCode);
                    }
                    item.setParentItemId(parentItemId);
                    item.setEquipmentName(colToDisplayName(col));
                    item.setPartNumber("");
                    item.setItemType("EQUIPMENT");
                    Long itemId = configService.addItem(item);
                    createdItemIds.add(itemId);
                } catch (Exception e) {
                    log.warn("自动创建构型项目失败(列={}): {}", col, e.getMessage());
                }
            }
        }

        // 6. 保存元数据到达梦
        saveMetadata(cleanDeviceName, tailNumber, dataRows, analysis);

        // 7. 创建数据关联
        if (tailNumber != null && !tailNumber.trim().isEmpty()) {
            configService.createDataMapping(
                tailNumber, parentItemId, cleanDeviceName,
                dataType != null ? dataType : "RAW",
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
            );
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // 构建结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", file.getOriginalFilename());
        result.put("deviceName", cleanDeviceName);
        result.put("tailNumber", tailNumber);
        result.put("totalRows", dataRows.size());
        result.put("numericColumns", analysis.getNumericColumns());
        result.put("textColumns", analysis.getTextColumns());
        result.put("timestampColumn", analysis.getTimestampColumn());
        result.put("createdItems", createdItemIds.size());
        result.put("processingTimeMs", elapsed);
        result.put("message", "数据已写入IoTDB，构型模板已创建");
        return result;
    }

    // ==================== 数据查询 ====================

    /**
     * 查询IoTDB设备数据（首页）
     */
    public List<Map<String, Object>> queryDeviceData(String tailNumber, String deviceName, int limit) {
        return iotDb.queryLatest(tailNumber, sanitizeDeviceName(deviceName), limit);
    }

    /**
     * 根据机号查询所有设备数据
     */
    public Map<String, Object> queryByTailNumber(String tailNumber, int page, int size) {
        List<String> devices = iotDb.listDevices(tailNumber);
        // 分页取设备
        int total = devices.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<String> pageDevices = devices.subList(fromIndex, toIndex);

        List<Map<String, Object>> resultData = new ArrayList<>();
        for (String device : pageDevices) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("device", device);
            entry.put("latestData", iotDb.queryLatest(tailNumber, device, 5));
            resultData.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", resultData);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ==================== 数据总览 ====================

    /**
     * 获取设备数据总览（行数、时间范围、列类型、数值列统计）
     */
    public Map<String, Object> getDataOverview(String tailNumber, String deviceName) {
        Map<String, Object> overview = new LinkedHashMap<>();
        String cleanName = sanitizeDeviceName(deviceName);

        overview.put("deviceName", cleanName);
        overview.put("tailNumber", tailNumber);

        // 1. 传感器列及类型（从IoTDB）
        Map<String, String> sensors = iotDb.listSensorsWithTypes(tailNumber, cleanName);
        overview.put("sensors", sensors);

        // 2. 列信息列表
        List<Map<String, String>> columnInfoList = new ArrayList<>();
        columnInfoList.add(createColInfo("Time", "TIMESTAMP", "time"));
        List<String> numericCols = new ArrayList<>();
        for (Map.Entry<String, String> entry : sensors.entrySet()) {
            String type = entry.getValue();
            String role = "DOUBLE".equals(type) || "FLOAT".equals(type) || "INT64".equals(type) || "INT32".equals(type)
                    ? "measurement" : "tag";
            columnInfoList.add(createColInfo(entry.getKey(), type, role));
            if ("measurement".equals(role)) numericCols.add(entry.getKey());
        }
        overview.put("columns", columnInfoList);
        overview.put("numericColumns", numericCols);

        // 3. 总行数
        long totalRows = iotDb.queryCount(tailNumber, cleanName);
        overview.put("totalRows", totalRows);

        // 4. 时间范围
        Map<String, Object> timeRange = iotDb.getTimeRange(tailNumber, cleanName);
        if (!timeRange.isEmpty()) {
            overview.put("timeRange", timeRange);

            // 格式化时间戳为可读字符串
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            if (timeRange.get("startTime") instanceof Number) {
                overview.put("startTimeFormatted",
                        sdf.format(new Date(((Number) timeRange.get("startTime")).longValue())));
            }
            if (timeRange.get("endTime") instanceof Number) {
                overview.put("endTimeFormatted",
                        sdf.format(new Date(((Number) timeRange.get("endTime")).longValue())));
            }
        }

        // 5. 数值列统计（min/max/avg）
        if (!numericCols.isEmpty()) {
            Map<String, Map<String, Object>> colStats = iotDb.queryColumnStats(tailNumber, cleanName, numericCols);
            overview.put("columnStats", colStats);
        }

        return overview;
    }

    /**
     * 按数值范围查询数据
     */
    public List<Map<String, Object>> queryWithValueRange(String tailNumber, String deviceName,
                                                          String column, Double minVal, Double maxVal,
                                                          int limit) {
        return iotDb.queryWithValueFilter(tailNumber, sanitizeDeviceName(deviceName),
                column, minVal, maxVal, limit);
    }

    private Map<String, String> createColInfo(String name, String type, String role) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("type", type);
        info.put("role", role);
        return info;
    }

    // ==================== 辅助 ====================

    private boolean isRowEmpty(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private String sanitizeDeviceName(String name) {
        // 去掉csv_前缀
        String cleaned = name.toLowerCase();
        if (cleaned.startsWith("csv_")) {
            cleaned = cleaned.substring(4);
        }
        // 非法字符替换为下划线
        return cleaned.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String colToDisplayName(String colName) {
        // fan_vibration → 风扇振动传感器
        // n1_speed → N1转速传感器
        if (colName.toLowerCase().contains("sensor")) {
            return colName;
        }
        // 将下划线分隔的英文转为中文描述
        String[] parts = colName.split("_");
        return String.join(" ", parts) + " 传感器";
    }

    /**
     * 列出某机号下所有IoTDB设备
     */
    public List<String> listDevices(String tailNumber) {
        return iotDb.listDevices(tailNumber);
    }

    private String findModelCodeByTailNumber(String tailNumber) {
        try {
            com.project.phm.entity.AircraftConfig config = aircraftConfigMapper.selectById(tailNumber);
            return config != null ? config.getModelCode() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveMetadata(String deviceName, String tailNumber,
                              List<Map<String, String>> dataRows,
                              AnalysisResult analysis) {
        try {
            // 计算哈希
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> row : dataRows) {
                for (String v : row.values()) {
                    sb.append(v != null ? v : "").append("|");
                }
                sb.append("\n");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexStr = new StringBuilder();
            for (byte b : hash) hexStr.append(String.format("%02x", b));

            // 保存到达梦 csv_table_metadata
            dbUtils.saveTableMetadata(deviceName, "original_data_hash", hexStr.toString());
            dbUtils.saveTableMetadata(deviceName, "original_row_count", String.valueOf(dataRows.size()));
            dbUtils.saveTableMetadata(deviceName, "tail_number", tailNumber);
            dbUtils.saveTableMetadata(deviceName, "column_types", analysis.getColumnTypes().toString());
            dbUtils.saveTableMetadata(deviceName, "numeric_columns", String.join(",", analysis.getNumericColumns()));
        } catch (Exception e) {
            log.warn("保存元数据失败: {}", e.getMessage());
        }
    }
}
