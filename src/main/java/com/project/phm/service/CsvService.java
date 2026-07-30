package com.project.phm.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.project.phm.adapter.dto.UnifiedTimeSeriesResponse;
import com.project.phm.adapter.dto.UnifiedTimeSeriesResponse.ParameterEntry;
import com.project.phm.entity.ConfigItem;
import com.project.phm.entity.Sortie;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
        return uploadCsv(file, tableName, null, null);
    }

    /**
     * 上传CSV文件并入库（含飞机构型 + 架次关联）
     *
     * @param file         CSV文件
     * @param tableName    达梦表名（需以 csv_ 开头）
     * @param parentItemId 父级构型项目ID（可选）
     * @param sortieId     架次ID（可选，提供后自动从架次获取机号）
     * @return 处理结果
     */
    public Map<String, Object> uploadCsv(MultipartFile file, String tableName,
                                          Long parentItemId, Long sortieId) throws Exception {
        long startTime = System.currentTimeMillis();

        // 从架次自动解析机号
        String aircraftNumber = null;
        if (sortieId != null) {
            Sortie sortie = aircraftConfigService.getSortie(sortieId);
            if (sortie == null) {
                throw new IllegalArgumentException("架次不存在: " + sortieId);
            }
            aircraftNumber = sortie.getAircraftNumber();
        }

        // 验证表名
        if (!CsvUtils.isValidTableName(tableName)) {
            throw new IllegalArgumentException("表名必须以csv_开头");
        }

        // 校验表名是否已存在
        if (dbUtils.tableExists(tableName)) {
            String existingTailNumber = dbUtils.getTableMetadata(tableName, "tail_number");
            String message = String.format("表 [%s] 已存在", tableName);
            if (existingTailNumber != null) {
                message += String.format(" (已关联机号: %s)", existingTailNumber);
            }
            throw new IllegalArgumentException(message);
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
        if (aircraftNumber != null && !aircraftNumber.trim().isEmpty()) {
            dbUtils.saveTableMetadata(tableName, "tail_number", aircraftNumber);
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
        if ((aircraftNumber != null && !aircraftNumber.trim().isEmpty()) || parentItemId != null) {
            try {
                aircraftConfigService.createDataMapping(
                    aircraftNumber, parentItemId, sortieId, deviceName,
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
        result.put("aircraftNumber", aircraftNumber);
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
        // 1. 先删除关联的构型映射记录
        String deviceName = tableName.startsWith("csv_") ? tableName.substring(4) : tableName;
        aircraftConfigService.deleteMappingsByCsvTableName(deviceName);
        // 2. 再删除元数据
        dbUtils.deleteTableMetadata(tableName);
        // 3. 最后删除表
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
        if (metaTail != null) overview.put("aircraftNumber", metaTail);

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
     * 导出指定列的子集 CSV
     *
     * @param tableName      表名（需以 csv_ 开头）
     * @param columnNames    要导出的列名列表
     * @param outputStream   输出流
     */
    public void exportCsvColumns(String tableName, List<String> columnNames, OutputStream outputStream) throws Exception {
        validateTableName(tableName);

        // 校验请求的列名都存在于表中
        List<String> allColumns = dbUtils.getColumnNames(tableName);
        List<String> missingCols = columnNames.stream()
                .filter(c -> allColumns.stream().noneMatch(col -> col.equalsIgnoreCase(c)))
                .collect(Collectors.toList());
        if (!missingCols.isEmpty()) {
            throw new IllegalArgumentException("列不存在: " + String.join(", ", missingCols));
        }

        // 构建 SELECT 查询（使用原始大小写列名）
        String colList = columnNames.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + colList + " FROM " + tableName + " ORDER BY ID";
        List<Map<String, Object>> rows = dbUtils.queryForList(sql);

        // 组装 CSV 数据（表头 + 数据行）
        List<String[]> csvData = new ArrayList<>();
        csvData.add(columnNames.toArray(new String[0]));
        for (Map<String, Object> row : rows) {
            String[] rowData = new String[columnNames.size()];
            for (int i = 0; i < columnNames.size(); i++) {
                String col = columnNames.get(i);
                Object value = row.get(col.toUpperCase());
                if (value == null) {
                    value = row.get(col.toLowerCase());
                }
                rowData[i] = value != null ? value.toString() : "";
            }
            csvData.add(rowData);
        }

        writeCsvToStream(csvData, outputStream);
    }

    /**
     * 查询时序数据并返回统一格式响应（timestamps + parameters）。
     *
     * <p>实现「统一接口调用.md」定义的时序数据查询接口。
     * 自动从元数据或列名推断时间戳列，将其作为时间轴；其余请求列作为参数值序列。</p>
     *
     * <p><b>无时间戳列的表</b>：如果无法推断时间戳列，则 timestamps 返回空列表，
     * parameters.name 使用真实的列名，values 按行序排列。</p>
     *
     * @param tableName 表名（含 csv_ 前缀）
     * @param paralist  要查询的参数名列表
     * @return 统一时序响应
     */
    public UnifiedTimeSeriesResponse queryTimeSeries(String tableName, List<String> paralist) {
        validateTableName(tableName);

        List<String> allColumns = dbUtils.getColumnNames(tableName);

        // 1. 校验请求的列名在表中实际存在
        if (paralist != null) {
            List<String> missingCols = paralist.stream()
                    .filter(p -> p != null && !p.isEmpty()
                            && allColumns.stream().noneMatch(c -> c.equalsIgnoreCase(p)))
                    .collect(Collectors.toList());
            if (!missingCols.isEmpty()) {
                String available = allColumns.stream()
                        .filter(c -> !"id".equalsIgnoreCase(c))
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("列不存在于表中: " + String.join(", ", missingCols)
                        + "。表中实际列: [" + available + "]");
            }
        }

        // 2. 确定时间戳列（可能为 null）
        String timestampColumn = findTimestampColumn(tableName, allColumns);

        // 3. 构建查询列集合：时间戳列（如有）+ 请求的参数列（去重）
        Set<String> queryCols = new LinkedHashSet<>();
        if (timestampColumn != null) {
            queryCols.add(timestampColumn);
        }
        if (paralist != null) {
            for (String param : paralist) {
                if (param == null || param.isEmpty()) continue;
                if (timestampColumn != null && param.equalsIgnoreCase(timestampColumn)) continue;
                // 已通过上方校验确认列存在，此处只取用于保留原始大小写
                String matched = findColumnIgnoreCase(allColumns, param);
                queryCols.add(matched != null ? matched : param);
            }
        }
        if (queryCols.isEmpty()) {
            throw new IllegalArgumentException("没有可查询的列");
        }

        // 4. 执行查询
        String colList = queryCols.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + colList + " FROM " + tableName + " ORDER BY ID";
        List<Map<String, Object>> rows = dbUtils.queryForList(sql);

        // 4. 提取时间轴（如有时间戳列）
        List<Long> timestamps = new ArrayList<>();
        if (timestampColumn != null) {
            for (Map<String, Object> row : rows) {
                Object ts = getValueIgnoreCase(row, timestampColumn);
                timestamps.add(convertToTimestamp(ts));
            }
        }

        // 5. 提取各参数值序列
        List<ParameterEntry> parameters = new ArrayList<>();
        for (String col : queryCols) {
            if (timestampColumn != null && col.equalsIgnoreCase(timestampColumn)) continue;
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                values.add(getValueIgnoreCase(row, col));
            }
            parameters.add(ParameterEntry.of(col, values));
        }

        UnifiedTimeSeriesResponse resp = new UnifiedTimeSeriesResponse();
        resp.setTimestamps(timestamps);
        resp.setParameters(parameters);
        return resp;
    }

    /**
     * 从元数据或列名推断时间戳列。
     * 优先从表元数据 {@code timestamp_column} 读取，否则按常见命名匹配。
     */
    private String findTimestampColumn(String tableName, List<String> allColumns) {
        // 优先从元数据读取
        String metaCol = dbUtils.getTableMetadata(tableName, "timestamp_column");
        if (metaCol != null && !metaCol.isEmpty()) {
            String matched = findColumnIgnoreCase(allColumns, metaCol);
            if (matched != null) return matched;
        }

        // 按常见命名匹配
        for (String col : allColumns) {
            if (IoTDbUtils.isTimestampColumn(col) || "时间戳".equals(col)
                    || "timestamp".equalsIgnoreCase(col)) {
                return col;
            }
        }
        return null;
    }

    /**
     * 在列名列表中忽略大小写查找目标列。
     *
     * @return 匹配到的实际列名，未找到返回 null
     */
    private String findColumnIgnoreCase(List<String> columns, String target) {
        for (String col : columns) {
            if (col.equalsIgnoreCase(target)) return col;
        }
        return null;
    }

    /**
     * 从行数据中忽略大小写获取列值（达梦默认返回大写列名）。
     */
    private Object getValueIgnoreCase(Map<String, Object> row, String column) {
        Object val = row.get(column.toUpperCase());
        if (val == null) val = row.get(column.toLowerCase());
        return val;
    }

    /**
     * 将数据库中的时间戳值转为 Long 毫秒时间戳。
     *
     * <p>支持：Long/Integer（直接返回）、数字字符串、日期时间字符串。</p>
     */
    private static Long convertToTimestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String str = value.toString().trim();
        if (str.isEmpty()) return null;
        // 纯数字字符串 → 直接解析
        if (str.matches("-?\\d+(\\.\\d+)?")) {
            double num = Double.parseDouble(str);
            return (long) num;
        }
        // 日期时间字符串 → 尝试常见格式
        for (String fmt : new String[]{"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"}) {
            try {
                LocalDateTime dt = LocalDateTime.parse(str, DateTimeFormatter.ofPattern(fmt));
                return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        // 无法解析时，返回 null
        log.warn("无法解析的时间戳值: {}", str);
        return null;
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
