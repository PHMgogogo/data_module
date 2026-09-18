package com.project.phm.utils;

import com.opencsv.CSVReader;
import com.project.phm.entity.ValidationResult;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据校验工具类
 */
@Component
public class DataValidationUtils {

    private static final int MAX_SAMPLE_ROWS = 5;

    /**
     * 上传阶段校验 - 上传前预览
     * @param file CSV文件
     * @return 校验结果
     */
    public ValidationResult validateUpload(MultipartFile file) {
        ValidationResult result = new ValidationResult();

        try {
            // 1. 文件基本校验
            if (file == null || file.isEmpty()) {
                result.addError("文件为空，请重新选择");
                return result;
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
                result.addError("文件格式错误，请选择CSV文件");
                return result;
            }

            // 2. 解析校验
            try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
                String[] header = reader.readNext();
                
                if (header == null || header.length == 0) {
                    result.addError("CSV文件没有表头");
                    return result;
                }

                // 处理列名
                List<String> columns = new ArrayList<>();
                for (String columnName : header) {
                    columns.add(CsvUtils.processColumnName(columnName));
                }
                result.setColumns(columns);

                // 列名校验
                if (columns.size() < 2) {
                    result.addWarning("列数较少，请确认数据格式");
                }

                // 3. 数据行校验
                int rowCount = 0;
                int emptyCount = 0;
                String[] row;
                List<String[]> sampleData = new ArrayList<>();

                while ((row = reader.readNext()) != null) {
                    rowCount++;

                    // 空行检测
                    boolean isEmpty = true;
                    for (String cell : row) {
                        if (cell != null && !cell.trim().isEmpty()) {
                            isEmpty = false;
                            break;
                        }
                    }
                    if (isEmpty) {
                        emptyCount++;
                        continue;
                    }

                    // 列数不一致检测
                    if (row.length != columns.size()) {
                        result.addWarning(String.format("第%d行列数不一致：表头%d列，本行%d列", 
                                rowCount, columns.size(), row.length));
                    }

                    // 采样数据
                    if (sampleData.size() < MAX_SAMPLE_ROWS) {
                        sampleData.add(row);
                    }
                }

                result.setTotalRows(rowCount);
                result.setValidRows(rowCount - emptyCount);
                result.setInvalidRows(emptyCount);
                result.setSampleData(sampleData);

                // 4. 统计校验
                if (rowCount == 0) {
                    result.addWarning("文件没有数据行");
                } else if (emptyCount > 0) {
                    result.addWarning(String.format("发现%d个空行，将被忽略", emptyCount));
                }

                // 5. 文件大小警告
                long fileSize = file.getSize();
                if (fileSize > 100 * 1024 * 1024) { // 100MB
                    result.addWarning("文件较大(" + (fileSize / 1024 / 1024) + "MB)，可能需要较长时间处理");
                }

            }

        } catch (Exception e) {
            result.addError("文件解析失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * 存储阶段校验 - 插入前后一致性检查
     * @param expected 预期行数
     * @param actual 实际插入行数
     * @return 校验结果
     */
    public Map<String, Object> validateStorage(int expected, int actual) {
        Map<String, Object> result = new HashMap<>();
        result.put("expectedRows", expected);
        result.put("actualRows", actual);
        result.put("isConsistent", expected == actual);
        result.put("diffRows", Math.abs(expected - actual));
        
        if (expected != actual) {
            result.put("status", "warning");
            result.put("message", String.format("数据不一致：预期%d行，实际入库%d行", expected, actual));
        } else {
            result.put("status", "success");
            result.put("message", "数据完全一致");
        }
        
        return result;
    }

    /**
     * 导出阶段校验 - 仅校验行数
     * @param expectedRows 预期导出行数
     * @param actualRows 实际导出行数
     * @return 校验结果
     */
    public Map<String, Object> validateExport(int expectedRows, int actualRows) {
        Map<String, Object> result = new HashMap<>();
        result.put("expectedRows", expectedRows);
        result.put("actualRows", actualRows);
        result.put("isConsistent", expectedRows == actualRows);
        
        if (expectedRows != actualRows) {
            result.put("status", "warning");
            result.put("message", String.format("导出数据不一致：预期%d行，实际%d行", expectedRows, actualRows));
        } else {
            result.put("status", "success");
            result.put("message", "导出数据一致");
        }
        
        return result;
    }

    /**
     * 计算数据的SHA-256哈希值
     * @param data 数据列表
     * @return 哈希值
     */
    public String calculateDataHash(List<String[]> data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            
            for (String[] row : data) {
                for (String cell : row) {
                    sb.append(cell != null ? cell : "").append("|");
                }
                sb.append("\n");
            }
            
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算哈希失败", e);
        }
    }
}
