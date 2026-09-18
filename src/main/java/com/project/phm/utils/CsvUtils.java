package com.project.phm.utils;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV工具类，用于解析和生成CSV文件
 */
public class CsvUtils {

    /**
     * 解析CSV文件
     * @param inputStream CSV文件输入流
     * @return 解析后的二维列表
     * @throws IOException IO异常
     * @throws CsvException CSV解析异常
     */
    public static List<String[]> parseCsv(InputStream inputStream) throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, "UTF-8"))) {
            return reader.readAll();
        }
    }

    /**
     * 生成CSV文件
     * @param data 数据列表
     * @param outputStream 输出流
     * @throws IOException IO异常
     */
    public static void generateCsv(List<String[]> data, OutputStream outputStream) throws IOException {
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(outputStream, "UTF-8"))) {
            writer.writeAll(data);
        }
    }

    /**
     * 保留 CSV 原始列名，供达梦数据库以引用标识符方式建表。
     *
     * <p>仅去除首尾空白和 UTF-8 BOM；中文、空格及其它字符均保留。
     * 如果原始列名为 ID，会改名以避免与自动主键冲突。</p>
     */
    public static String processColumnName(String columnName) {
        String processed = columnName == null ? "" : columnName.trim();
        if (processed.startsWith("\uFEFF")) {
            processed = processed.substring(1);
        }
        // 避免与自动创建的ID主键列冲突（达梦数据库大小写不敏感）
        if ("id".equalsIgnoreCase(processed)) {
            return "csv_id";
        }
        return processed;
    }

    /** 旧版 IoTDB 列名清洗：将中文和特殊字符替换为下划线。 */
    public static String sanitizeColumnName(String columnName) {
        String processed = columnName == null ? "" : columnName.trim()
                .replaceAll("[\\s\\p{InCJKUnifiedIdeographs}~!@#$%^&*()_+`\\-=\\[\\]\\\\\\{}|;':\",./<>?]", "_");
        if ("id".equalsIgnoreCase(processed)) {
            return "csv_id";
        }
        return processed;
    }

    /** 校验表头非空且不存在重名列。 */
    public static void validateColumnNames(List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            throw new IllegalArgumentException("CSV文件缺少表头");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String column : columnNames) {
            if (column == null || column.trim().isEmpty()) {
                throw new IllegalArgumentException("CSV表头存在空列名");
            }
            String normalized = column.trim().toLowerCase();
            if (!seen.add(normalized)) {
                throw new IllegalArgumentException("CSV表头存在重复列名: " + column);
            }
        }
    }

    /**
     * 验证表名是否以csv_开头（不区分大小写）
     * @param tableName 表名
     * @return 是否合法
     */
    public static boolean isValidTableName(String tableName) {
        return tableName != null && tableName.toLowerCase().startsWith("csv_");
    }
}
