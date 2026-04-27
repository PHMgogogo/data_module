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
     * 处理列名，将非法字符替换为下划线
     * 特别处理：避免与自动创建的ID主键列冲突
     * @param columnName 原始列名
     * @return 处理后的列名
     */
    public static String processColumnName(String columnName) {
        String processed = columnName.trim().replaceAll("[\\s\\p{InCJKUnifiedIdeographs}~!@#$%^&*()_+`\\-=\\[\\]\\\\\\{}|;':\",./<>?]", "_");
        
        // 避免与自动创建的ID主键列冲突（达梦数据库大小写不敏感）
        if ("id".equalsIgnoreCase(processed)) {
            return "csv_id";
        }
        
        return processed;
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
