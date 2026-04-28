package com.project.phm.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.project.phm.utils.CsvUtils;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV服务类，处理CSV文件的上传、解析、入库等业务逻辑
 */
@Service
public class CsvService {

    private final DbUtils dbUtils;
    private final DataValidationUtils validationUtils;

    public CsvService(DbUtils dbUtils, DataValidationUtils validationUtils) {
        this.dbUtils = dbUtils;
        this.validationUtils = validationUtils;
    }

    /**
     * 获取所有CSV表
     * @return 表名列表
     */
    public List<String> getAllTables() {
        return dbUtils.getAllCsvTables();
    }

    /**
     * 上传CSV文件并入库
     * @param file CSV文件
     * @param tableName 表名
     * @return 处理结果
     */
    public Map<String, Object> uploadCsv(MultipartFile file, String tableName) throws Exception {
        // 验证表名
        if (!CsvUtils.isValidTableName(tableName)) {
            throw new IllegalArgumentException("表名必须以csv_开头");
        }

        // 流式解析CSV
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

        // 计算原始数据哈希并保存（用于后续导出校验）
        String originalDataHash = validationUtils.calculateDataHash(fullData);
        dbUtils.saveTableMetadata(tableName, "original_data_hash", originalDataHash);
        dbUtils.saveTableMetadata(tableName, "original_row_count", String.valueOf(expectedRows));

        // 检查是否需要建表
        if (!dbUtils.tableExists(tableName)) {
            dbUtils.createTable(tableName, columns);
        } else {
            // 表已存在，先清空表数据（避免重复）
            dbUtils.truncateTable(tableName);
        }

        // 批量入库
        int successCount = dbUtils.batchInsert(tableName, columns, dataRows);
        int failureCount = expectedRows - successCount;

        // 存储阶段校验
        Map<String, Object> storageValidation = validationUtils.validateStorage(expectedRows, successCount);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("fileName", file.getOriginalFilename());
        result.put("totalCount", expectedRows);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);
        result.put("originalDataHash", originalDataHash);
        result.put("storageValidation", storageValidation);
        return result;
    }

    /**
     * 查询表数据列表
     * @param tableName 表名
     * @return 数据列表
     */
    public List<Map<String, Object>> listData(String tableName) {
        validateTableName(tableName);
        return dbUtils.queryList(tableName);
    }

    /**
     * 分页查询表数据
     * @param tableName 表名
     * @param page 页码
     * @param size 每页大小
     * @return 分页数据
     */
    public Map<String, Object> pageData(String tableName, int page, int size) {
        validateTableName(tableName);
        return dbUtils.queryPage(tableName, page, size);
    }

    /**
     * 删除单条数据
     * @param tableName 表名
     * @param id ID
     * @return 是否成功
     */
    public boolean deleteData(String tableName, int id) {
        validateTableName(tableName);
        return dbUtils.deleteById(tableName, id);
    }

    /**
     * 清空表数据
     * @param tableName 表名
     */
    public void truncateTable(String tableName) {
        validateTableName(tableName);
        dbUtils.truncateTable(tableName);
    }

    /**
     * 删除表
     * @param tableName 表名
     */
    public void dropTable(String tableName) {
        validateTableName(tableName);
        dbUtils.dropTable(tableName);
    }

    /**
     * 导出表数据为CSV
     * @param tableName 表名
     * @return 数据列表，第一行为表头
     */
    public List<String[]> exportCsv(String tableName) {
        validateTableName(tableName);
        
        // 获取列名（排除ID列）
        List<String> columns = dbUtils.getColumnNames(tableName);
        List<String> dataColumns = columns.stream()
                .filter(col -> !"id".equalsIgnoreCase(col))
                .collect(java.util.stream.Collectors.toList());
        
        // 获取数据
        List<Map<String, Object>> data = dbUtils.queryList(tableName);
        
        // 构建CSV数据
        List<String[]> csvData = new ArrayList<>();
        
        // 添加表头
        csvData.add(dataColumns.toArray(new String[0]));
        
        // 添加数据行
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
     * @param tableName 表名
     * @param originalData 原始上传数据（用于对比）
     * @return 包含校验结果的导出数据
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
     * 验证表名
     * @param tableName 表名
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
