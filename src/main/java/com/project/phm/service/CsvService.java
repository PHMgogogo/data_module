package com.project.phm.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.project.phm.entity.ConfigItem;
import com.project.phm.utils.*;
import com.project.phm.utils.CsvColumnAnalyzer.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSV服务类，处理CSV文件的上传、解析、入库等业务逻辑
 *
 * 数据流向：
 *   CSV文件 → 达梦数据库 csv_xxx 表
 *   列属性分析 → CsvColumnAnalyzer → 自动保存列类型元数据
 *   飞机构型关联 → 保存 tail_number 元数据 + config_data_mapping
 */
@Service
public class CsvService {

    private static final Logger log = LoggerFactory.getLogger(CsvService.class);

    private final DbUtils dbUtils;
    private final DataValidationUtils validationUtils;
    private final CsvColumnAnalyzer columnAnalyzer;
    private final AircraftConfigService aircraftConfigService;

    public CsvService(DbUtils dbUtils, DataValidationUtils validationUtils,
                      CsvColumnAnalyzer columnAnalyzer,
                      AircraftConfigService aircraftConfigService) {
        this.dbUtils = dbUtils;
        this.validationUtils = validationUtils;
        this.columnAnalyzer = columnAnalyzer;
        this.aircraftConfigService = aircraftConfigService;
    }

    /**
     * 获取所有CSV表
     */
    public List<String> getAllTables() {
        return dbUtils.getAllCsvTables();
    }

    /**
     * 上传CSV文件并入库（默认不带飞机构型关联）
     */
    public Map<String, Object> uploadCsv(MultipartFile file, String tableName) throws Exception {
        return uploadCsv(file, tableName, null, null, null);
    }

    /**
     * 上传CSV文件并入库（含飞机构型关联）
     *
     * @param file         CSV文件
     * @param tableName    达梦表名（需以 csv_ 开头）
     * @param tailNumber   机号（用于构型关联，可选）
     * @param parentItemId 父级构型项目ID（可选）
     * @param dataType     数据类型 RAW/DIAGNOSIS/EVALUATION/PREDICTION（可选）
     * @return 处理结果
     */
    public Map<String, Object> uploadCsv(MultipartFile file, String tableName,
                                          String tailNumber, Long parentItemId,
                                          String dataType) throws Exception {
        long startTime = System.currentTimeMillis();

        // 验证表名
        if (!CsvUtils.isValidTableName(tableName)) {
            throw new IllegalArgumentException("表名必须以csv_开头");
        }

        // 1. 列类型分析（依赖 MultipartFile 可重复读取的特性）
        AnalysisResult analysis = columnAnalyzer.analyze(file);

        // 2. 流式解析CSV
        List<String> columns = new ArrayList<>();
        List<String[]> dataRows = new ArrayList<>();
        List<String[]> fullData = new ArrayList<>();
        boolean firstRow = true;

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (firstRow) {
                    for (String columnName : row) {
                        columns.add(CsvUtils.processColumnName(columnName));
                    }
                    fullData.add(columns.toArray(new String[0]));
                    firstRow = false;
                } else {
                    dataRows.add(row);
                    fullData.add(row);
                }
            }
        }

        if (columns.isEmpty()) {
            throw new IllegalArgumentException("CSV文件为空");
        }

        int expectedRows = dataRows.size();

        // 3. 计算原始数据哈希并保存
        String originalDataHash = validationUtils.calculateDataHash(fullData);
        dbUtils.saveTableMetadata(tableName, "original_data_hash", originalDataHash);
        dbUtils.saveTableMetadata(tableName, "original_row_count", String.valueOf(expectedRows));

        // 4. 保存列类型元数据
        if (analysis.getColumnTypes() != null && !analysis.getColumnTypes().isEmpty()) {
            dbUtils.saveTableMetadata(tableName, "column_types", analysis.getColumnTypes().toString());
        }
        if (analysis.getNumericColumns() != null && !analysis.getNumericColumns().isEmpty()) {
            dbUtils.saveTableMetadata(tableName, "numeric_columns", String.join(",", analysis.getNumericColumns()));
        }
        if (analysis.getTimestampColumn() != null) {
            dbUtils.saveTableMetadata(tableName, "timestamp_column", analysis.getTimestampColumn());
        }

        // 5. 保存机号关联元数据
        String deviceName = tableName.startsWith("csv_") ? tableName.substring(4) : tableName;
        if (tailNumber != null && !tailNumber.trim().isEmpty()) {
            dbUtils.saveTableMetadata(tableName, "tail_number", tailNumber);
        }

        // 6. 检查是否需要建表
        if (!dbUtils.tableExists(tableName)) {
            dbUtils.createTable(tableName, columns);
        } else {
            dbUtils.truncateTable(tableName);
        }

        // 7. 批量入库
        int successCount = dbUtils.batchInsert(tableName, columns, dataRows);
        int failureCount = expectedRows - successCount;

        // 8. 存储阶段校验
        Map<String, Object> storageValidation = validationUtils.validateStorage(expectedRows, successCount);

        // 9. 创建构型数据关联
        if (tailNumber != null && !tailNumber.trim().isEmpty() && parentItemId != null) {
            try {
                aircraftConfigService.createDataMapping(
                    tailNumber, parentItemId, deviceName,
                    dataType != null ? dataType : "RAW",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                );
            } catch (Exception e) {
                log.warn("创建数据关联失败: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // 10. 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", file.getOriginalFilename());
        result.put("tableName", tableName);
        result.put("deviceName", deviceName);
        result.put("tailNumber", tailNumber);
        result.put("totalCount", expectedRows);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);
        result.put("originalDataHash", originalDataHash);
        result.put("storageValidation", storageValidation);
        result.put("numericColumns", analysis.getNumericColumns());
        result.put("textColumns", analysis.getTextColumns());
        result.put("timestampColumn", analysis.getTimestampColumn());
        result.put("processingTimeMs", elapsed);
        result.put("message", "数据已写入达梦数据库");
        return result;
    }

    // ==================== 数据查询 ====================

    /**
     * 查询表数据列表
     */
    public List<Map<String, Object>> listData(String tableName) {
        validateTableName(tableName);
        return dbUtils.queryList(tableName);
    }

    /**
     * 分页查询表数据
     */
    public Map<String, Object> pageData(String tableName, int page, int size) {
        validateTableName(tableName);
        return dbUtils.queryPage(tableName, page, size);
    }

    /**
     * 删除单条数据
     */
    public boolean deleteData(String tableName, int id) {
        validateTableName(tableName);
        return dbUtils.deleteById(tableName, id);
    }

    /**
     * 清空表数据
     */
    public void truncateTable(String tableName) {
        validateTableName(tableName);
        dbUtils.truncateTable(tableName);
    }

    /**
     * 删除表
     */
    public void dropTable(String tableName) {
        validateTableName(tableName);
        dbUtils.dropTable(tableName);
    }

    /**
     * 导出表数据为CSV
     */
    public List<String[]> exportCsv(String tableName) {
        validateTableName(tableName);

        List<String> columns = dbUtils.getColumnNames(tableName);
        List<String> dataColumns = columns.stream()
                .filter(col -> !"id".equalsIgnoreCase(col))
                .collect(Collectors.toList());

        List<Map<String, Object>> data = dbUtils.queryList(tableName);

        List<String[]> csvData = new ArrayList<>();
        csvData.add(dataColumns.toArray(new String[0]));

        for (Map<String, Object> row : data) {
            String[] rowData = new String[dataColumns.size()];
            for (int i = 0; i < dataColumns.size(); i++) {
                String column = dataColumns.get(i);
                Object value = row.get(column.toUpperCase());
                if (value == null) {
                    value = row.get(column.toLowerCase());
                }
                rowData[i] = value != null ? value.toString() : "";
            }
            csvData.add(rowData);
        }

        return csvData;
    }

    /**
     * 导出表数据并进行完整性校验
     */
    public Map<String, Object> exportCsvWithValidation(String tableName, List<String[]> originalData) {
        List<String[]> exportedData = exportCsv(tableName);

        Map<String, Object> result = new HashMap<>();
        result.put("exportedData", exportedData);

        int expectedRows = originalData != null ? originalData.size() - 1 : dbUtils.getTotalCount(tableName);
        int actualRows = exportedData.size() - 1;

        if (originalData != null) {
            Map<String, Object> validation = validationUtils.validateExportConsistency(originalData, exportedData);
            result.put("validation", validation);
        } else {
            Map<String, Object> validation = validationUtils.validateExport(expectedRows, actualRows);
            result.put("validation", validation);
        }

        return result;
    }

    /**
     * 将CSV数据写入输出流
     */
    public void writeCsvToStream(List<String[]> data, OutputStream outputStream) throws Exception {
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(outputStream, "UTF-8"))) {
            writer.writeAll(data);
        }
    }

    /**
     * 获取表数据总览（列信息、行数、数值列统计、时间范围）
     */
    public Map<String, Object> getOverview(String tableName) {
        validateTableName(tableName);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("tableName", tableName);

        String deviceName = tableName.startsWith("csv_") ? tableName.substring(4) : tableName;
        overview.put("deviceName", deviceName);

        // 机号（从元数据）
        String metaTail = dbUtils.getTableMetadata(tableName, "tail_number");
        if (metaTail != null) overview.put("tailNumber", metaTail);

        // 1. 获取列名（排除ID）
        List<String> allColumns = dbUtils.getColumnNames(tableName);
        List<String> dataColumns = allColumns.stream()
                .filter(col -> !"id".equalsIgnoreCase(col))
                .collect(Collectors.toList());
        overview.put("dataColumns", dataColumns);

        // 2. 总行数
        int totalRows = dbUtils.getTotalCount(tableName);
        overview.put("totalRows", totalRows);

        // 3. 从元数据中读取列类型
        String columnTypesStr = dbUtils.getTableMetadata(tableName, "column_types");
        String numericColsStr = dbUtils.getTableMetadata(tableName, "numeric_columns");
        String timestampColStr = dbUtils.getTableMetadata(tableName, "timestamp_column");

        Map<String, String> columnTypes = new LinkedHashMap<>();
        List<String> numericCols = new ArrayList<>();
        List<String> textCols = new ArrayList<>();
        String timestampColumn = null;

        if (timestampColStr != null && !timestampColStr.isEmpty()) {
            timestampColumn = timestampColStr;
        }

        if (columnTypesStr != null && columnTypesStr.startsWith("{") && columnTypesStr.endsWith("}")) {
            String inner = columnTypesStr.substring(1, columnTypesStr.length() - 1);
            if (!inner.isEmpty()) {
                String[] pairs = inner.split(", ");
                for (String pair : pairs) {
                    int eqIdx = pair.indexOf('=');
                    if (eqIdx > 0) {
                        columnTypes.put(pair.substring(0, eqIdx), pair.substring(eqIdx + 1));
                    }
                }
            }
        }

        if (numericColsStr != null && !numericColsStr.isEmpty()) {
            numericCols = Arrays.asList(numericColsStr.split(","));
        }

        if (!columnTypes.isEmpty()) {
            for (Map.Entry<String, String> entry : columnTypes.entrySet()) {
                if (!numericCols.contains(entry.getKey())) {
                    textCols.add(entry.getKey());
                }
            }
        }

        overview.put("columnTypes", columnTypes);
        overview.put("numericColumns", numericCols);
        overview.put("textColumns", textCols);
        overview.put("timestampColumn", timestampColumn);

        // 4. 构建兼容前端的 columns 详情（带 name/type/role）
        List<Map<String, String>> columnDetails = new ArrayList<>();
        for (String col : dataColumns) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("name", col);
            String type = columnTypes.getOrDefault(col, "TEXT");
            info.put("type", type);
            String role;
            if (col.equalsIgnoreCase(timestampColumn) || IoTDbUtils.isTimestampColumn(col)) {
                role = "time";
            } else if (numericCols.contains(col)) {
                role = "measurement";
            } else {
                role = "tag";
            }
            info.put("role", role);
            columnDetails.add(info);
        }
        overview.put("columns", columnDetails);

        // 5. 数值列统计 (CAST 为 DOUBLE)
        if (!numericCols.isEmpty()) {
            Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
            for (String col : numericCols) {
                try {
                    String sql = String.format(
                        "SELECT MIN(CAST(%s AS DOUBLE)) as min_val, MAX(CAST(%s AS DOUBLE)) as max_val, " +
                        "AVG(CAST(%s AS DOUBLE)) as avg_val, COUNT(%s) as cnt FROM %s WHERE %s IS NOT NULL AND %s != ''",
                        col, col, col, col, tableName, col, col);
                    List<Map<String, Object>> rows = dbUtils.queryForList(sql);
                    if (!rows.isEmpty()) {
                        Map<String, Object> row = rows.get(0);
                        Map<String, Object> colStat = new LinkedHashMap<>();
                        if (row.get("min_val") != null) colStat.put("min", row.get("min_val"));
                        if (row.get("max_val") != null) colStat.put("max", row.get("max_val"));
                        if (row.get("avg_val") != null) colStat.put("avg", row.get("avg_val"));
                        if (row.get("cnt") != null) colStat.put("count", row.get("cnt"));
                        stats.put(col, colStat);
                    }
                } catch (Exception e) {
                    log.warn("列统计失败(col={}): {}", col, e.getMessage());
                }
            }
            overview.put("columnStats", stats);
        }

        // 6. 时间范围（首尾行的第一个时间列）
        if (totalRows > 0 && !dataColumns.isEmpty()) {
            try {
                String timeCol = timestampColumn;
                if (timeCol == null) {
                    for (String col : dataColumns) {
                        if (IoTDbUtils.isTimestampColumn(col)) {
                            timeCol = col;
                            break;
                        }
                    }
                }
                if (timeCol == null) timeCol = dataColumns.get(0);

                String upperCol = timeCol.toUpperCase();
                String firstSql = "SELECT " + upperCol + " FROM " + tableName + " WHERE ID = (SELECT MIN(ID) FROM " + tableName + ")";
                String lastSql = "SELECT " + upperCol + " FROM " + tableName + " WHERE ID = (SELECT MAX(ID) FROM " + tableName + ")";
                List<Map<String, Object>> firstRow = dbUtils.queryForList(firstSql);
                List<Map<String, Object>> lastRow = dbUtils.queryForList(lastSql);
                if (!firstRow.isEmpty() && firstRow.get(0).get(upperCol) != null) {
                    Object start = firstRow.get(0).get(upperCol);
                    overview.put("startTime", start);
                    overview.put("startTimeFormatted", start != null ? start.toString() : null);
                }
                if (!lastRow.isEmpty() && lastRow.get(0).get(upperCol) != null) {
                    Object end = lastRow.get(0).get(upperCol);
                    overview.put("endTime", end);
                    overview.put("endTimeFormatted", end != null ? end.toString() : null);
                }
            } catch (Exception e) {
                log.warn("获取时间范围失败: {}", e.getMessage());
            }
        }

        return overview;
    }

    /**
     * 按数值范围查询数据
     */
    public List<Map<String, Object>> queryWithValueRange(String tableName, String column,
                                                          Double minVal, Double maxVal, int limit) {
        validateTableName(tableName);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(tableName).append(" WHERE 1=1");

        if (minVal != null) {
            sql.append(" AND CAST(").append(column).append(" AS DOUBLE) >= ").append(minVal);
        }
        if (maxVal != null) {
            sql.append(" AND CAST(").append(column).append(" AS DOUBLE) <= ").append(maxVal);
        }

        sql.append(" ORDER BY ID");

        // Dameng 使用 TOP N 语法
        String fullSql = sql.toString().replaceFirst("SELECT", "SELECT TOP " + limit);
        try {
            return dbUtils.queryForList(fullSql);
        } catch (Exception e) {
            log.warn("范围查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按数值范围分页查询数据
     */
    public Map<String, Object> queryPageWithRange(String tableName, String column,
                                                   Double minVal, Double maxVal,
                                                   int page, int size) {
        validateTableName(tableName);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (minVal != null) {
            where.append(" AND CAST(").append(column).append(" AS DOUBLE) >= ").append(minVal);
        }
        if (maxVal != null) {
            where.append(" AND CAST(").append(column).append(" AS DOUBLE) <= ").append(maxVal);
        }

        // 统计总数
        String countSql = "SELECT COUNT(*) FROM " + tableName + where;
        Integer totalObj = dbUtils.queryForObject(countSql, Integer.class);
        int total = totalObj != null ? totalObj : 0;

        if (total == 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", Collections.emptyList());
            result.put("total", 0);
            result.put("page", page);
            result.put("size", size);
            return result;
        }

        // 分页查询（达梦 ROW_NUMBER 语法）
        int start = (page - 1) * size + 1;
        int end = page * size;
        String querySql = "SELECT * FROM (SELECT t.*, ROW_NUMBER() OVER(ORDER BY ID) AS ROWNUM_ FROM "
                          + tableName + where + " ORDER BY ID) t WHERE ROWNUM_ BETWEEN ? AND ?";

        List<Map<String, Object>> data = dbUtils.queryForList(querySql, start, end);
        for (Map<String, Object> row : data) {
            row.remove("ROWNUM_");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 执行自定义 SQL 查询（仅支持 SELECT）
     * <p>
     * 流程: 安全检查 → 直接执行（数据库原生语法校验）→ 返回结果
     * 任何执行异常立即以 IllegalArgumentException 抛出（由 Controller 转换为 400 响应）
     */
    public Map<String, Object> executeSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL语句不能为空");
        }

        String trimmedSql = sql.trim();
        String upperSql = trimmedSql.toUpperCase();

        // 安全检查：只允许 SELECT 语句
        if (!upperSql.startsWith("SELECT")) {
            throw new IllegalArgumentException("仅支持 SELECT 查询语句");
        }

        // 直接执行查询，由达梦数据库完成语法校验
        // 语法错误时仅返回 SQL 执行错误，不暴露数据库内部细节
        List<Map<String, Object>> data;
        try {
            data = dbUtils.executeQuery(trimmedSql);
        } catch (Exception e) {
            log.warn("SQL语法错误: {}", e.getMessage());
            throw new IllegalArgumentException("SQL执行错误");
        }

        // 构建结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        if (!data.isEmpty()) {
            result.put("columns", new ArrayList<>(data.get(0).keySet()));
        } else {
            result.put("columns", Collections.emptyList());
        }
        result.put("totalRows", data.size());
        return result;
    }

    /**
     * 验证表名
     */
    private void validateTableName(String tableName) {
        if (!CsvUtils.isValidTableName(tableName)) {
            throw new IllegalArgumentException("表名必须以csv_开头");
        }
        if (!dbUtils.tableExists(tableName)) {
            throw new IllegalArgumentException("表不存在");
        }
    }
}
