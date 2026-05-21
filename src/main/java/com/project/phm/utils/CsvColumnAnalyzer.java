package com.project.phm.utils;

import com.opencsv.CSVReader;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.util.*;

/**
 * CSV列分析器 — 解析CSV文件头+样本行，自动推断列类型并生成构型模板
 *
 * 功能:
 *   1. 检测时间戳列（date, time, timestamp等）
 *   2. 对采样数据推断列类型（INT64/DOUBLE/BOOLEAN/TEXT）
 *   3. 生成 IoTDB 列类型映射
 *   4. 生成构型模板建议（每列对应一个设备/传感器）
 */
@Component
public class CsvColumnAnalyzer {

    /** 采样行数 */
    private static final int SAMPLE_SIZE = 20;

    /**
     * 分析结果
     */
    public static class AnalysisResult {
        private List<String> columns;              // 所有列名（处理后的）
        private List<String> originalColumns;      // 原始列名
        private String timestampColumn;            // 检测到的时间戳列名
        private Map<String, String> columnTypes;   // 列名→数据类型
        private List<String> numericColumns;       // 数值型列（IoTDB measurement）
        private List<String> textColumns;          // 文本型列（可作标签）
        private int totalRows;
        private List<Map<String, String>> sampleData;  // 采样数据

        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }

        public List<String> getOriginalColumns() { return originalColumns; }
        public void setOriginalColumns(List<String> originalColumns) { this.originalColumns = originalColumns; }

        public String getTimestampColumn() { return timestampColumn; }
        public void setTimestampColumn(String timestampColumn) { this.timestampColumn = timestampColumn; }

        public Map<String, String> getColumnTypes() { return columnTypes; }
        public void setColumnTypes(Map<String, String> columnTypes) { this.columnTypes = columnTypes; }

        public List<String> getNumericColumns() { return numericColumns; }
        public void setNumericColumns(List<String> numericColumns) { this.numericColumns = numericColumns; }

        public List<String> getTextColumns() { return textColumns; }
        public void setTextColumns(List<String> textColumns) { this.textColumns = textColumns; }

        public int getTotalRows() { return totalRows; }
        public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

        public List<Map<String, String>> getSampleData() { return sampleData; }
        public void setSampleData(List<Map<String, String>> sampleData) { this.sampleData = sampleData; }

        /** 建议构型模板标签：基于数值列生成的传感器描述 */
        public String suggestTemplateName() {
            if (numericColumns == null || numericColumns.isEmpty()) return "unknown_sensor";
            String first = numericColumns.get(0);
            // 取第一个数值列名前缀作为模板名
            if (first.contains("_")) {
                return first.substring(0, first.lastIndexOf('_'));
            }
            return first;
        }
    }

    /**
     * 分析CSV文件
     */
    public AnalysisResult analyze(MultipartFile file) throws Exception {
        AnalysisResult result = new AnalysisResult();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            // 读表头
            String[] header = reader.readNext();
            if (header == null || header.length == 0) {
                throw new IllegalArgumentException("CSV文件为空");
            }

            List<String> originalCols = Arrays.asList(header);
            List<String> processedCols = new ArrayList<>();
            for (String col : originalCols) {
                processedCols.add(CsvUtils.processColumnName(col));
            }
            result.setOriginalColumns(originalCols);
            result.setColumns(processedCols);

            // 读数据行（采样）
            String[] row;
            int rowCount = 0;
            List<Map<String, String>> samples = new ArrayList<>();

            // 第一遍：收集样本
            while ((row = reader.readNext()) != null && samples.size() < SAMPLE_SIZE) {
                if (isRowEmpty(row)) continue;
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < processedCols.size() && i < row.length; i++) {
                    rowMap.put(processedCols.get(i), row[i] != null ? row[i].trim() : "");
                }
                samples.add(rowMap);
                rowCount++;
            }

            // 继续统计总行数
            while ((row = reader.readNext()) != null) {
                if (!isRowEmpty(row)) rowCount++;
            }

            result.setTotalRows(rowCount);
            result.setSampleData(samples);

            // 推断列类型
            Map<String, String> columnTypes = new LinkedHashMap<>();
            List<String> numericCols = new ArrayList<>();
            List<String> textCols = new ArrayList<>();
            String timestampCol = null;

            boolean timestampFound = false;

            for (String col : processedCols) {
                if (!timestampFound && IoTDbUtils.isTimestampColumn(col)) {
                    timestampCol = col;
                    timestampFound = true;
                    columnTypes.put(col, "TIMESTAMP");
                    continue;
                }

                // 从采样数据推断类型
                String dtype = inferColumnType(col, samples);
                columnTypes.put(col, dtype);
                if ("TEXT".equals(dtype)) {
                    textCols.add(col);
                } else {
                    numericCols.add(col);
                }
            }

            result.setTimestampColumn(timestampCol);
            result.setColumnTypes(columnTypes);
            result.setNumericColumns(numericCols);
            result.setTextColumns(textCols);

            return result;
        }
    }

    private boolean isRowEmpty(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private String inferColumnType(String colName, List<Map<String, String>> samples) {
        if (samples.isEmpty()) return "TEXT";

        boolean allInt = true;
        boolean allNum = true;

        for (Map<String, String> row : samples) {
            String val = row.get(colName);
            if (val == null || val.trim().isEmpty()) continue;
            val = val.trim();

            // 尝试整数
            boolean isInt;
            try {
                Long.parseLong(val);
                isInt = true;
            } catch (NumberFormatException e) {
                isInt = false;
            }

            // 尝试浮点
            boolean isNum;
            try {
                Double.parseDouble(val);
                isNum = true;
            } catch (NumberFormatException e) {
                isNum = false;
            }

            if (!isInt) allInt = false;
            if (!isNum) allNum = false;
        }

        if (allInt) return "INT64";
        if (allNum) return "DOUBLE";
        return "TEXT";
    }

    /**
     * 根据分析结果生成构型模板（传感器级ConfigItem建议）
     */
    public List<Map<String, String>> generateTemplateItems(AnalysisResult analysis) {
        List<Map<String, String>> items = new ArrayList<>();

        for (String col : analysis.getNumericColumns()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("columnName", col);
            item.put("equipmentName", colToEquipmentName(col));
            item.put("partNumber", "");
            item.put("itemType", "EQUIPMENT");
            items.add(item);
        }

        return items;
    }

    private String colToEquipmentName(String col) {
        // fan_vibration → 风扇振动传感器
        // n1_speed → N1转速传感器
        // egt_actual → EGT实际值传感器
        StringBuilder sb = new StringBuilder();
        String[] parts = col.split("_");
        for (String part : parts) {
            if (part.equalsIgnoreCase("sensor") || part.equalsIgnoreCase("probe")) {
                sb.append(part);
            } else {
                sb.append(part).append(" ");
            }
        }
        if (!col.toLowerCase().contains("sensor")) {
            sb.append("传感器");
        }
        return sb.toString().trim();
    }
}
