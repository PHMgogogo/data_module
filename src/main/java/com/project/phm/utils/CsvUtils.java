package com.project.phm.utils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CSV工具类，用于列名与表名处理
 */
public class CsvUtils {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "^csv_[\\p{L}\\p{N}_]+$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * 判断列名是否为时间戳列。
     * @param colName 列名
     * @return 是否时间戳列
     */
    public static boolean isTimestampColumn(String colName) {
        if (colName == null) return false;
        String lower = colName.toLowerCase().trim();
        return lower.equals("time") || lower.equals("timestamp")
                || lower.equals("date") || lower.equals("datetime")
                || lower.contains("date") || lower.contains("time");
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
     * 将用户输入规范化为数据库表名。
     *
     * <p>允许用户传文件名（如 {@code 2026_afd_data.csv}）或完整表名
     * （如 {@code csv_2026_afd_data.csv}），统一生成
     * {@code csv_2026_afd_data}。</p>
     *
     * @param tableName 用户输入的表名
     * @return 带 csv_ 前缀的合法表名
     */
    public static String normalizeTableName(String tableName) {
        String value = tableName == null ? "" : tableName.trim();
        if (value.regionMatches(true, 0, "csv_", 0, 4)) {
            value = value.substring(4).trim();
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            value = value.substring(0, value.length() - 4).trim();
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("表名不能为空");
        }

        String fullTableName = "csv_" + value;
        if (!isValidTableName(fullTableName)) {
            throw new IllegalArgumentException("表名只能包含字母、数字和下划线");
        }
        return fullTableName;
    }

    /**
     * 验证表名是否为合法的 csv_ 表标识符。
     *
     * @param tableName 表名
     * @return 是否合法
     */
    public static boolean isValidTableName(String tableName) {
        return tableName != null && TABLE_NAME_PATTERN.matcher(tableName).matches();
    }
}
