package com.project.phm.controller;

import com.project.phm.entity.ValidationResult;
import com.project.phm.service.AsyncCsvService;
import com.project.phm.service.CsvService;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV控制器，处理CSV相关的HTTP请求
 */
@RestController
@RequestMapping("/csv")
public class CsvController {

    private final CsvService csvService;
    private final AsyncCsvService asyncCsvService;
    private final DataValidationUtils validationUtils;
    private final DbUtils dbUtils;

    public CsvController(CsvService csvService, AsyncCsvService asyncCsvService, 
                        DataValidationUtils validationUtils, DbUtils dbUtils) {
        this.csvService = csvService;
        this.asyncCsvService = asyncCsvService;
        this.validationUtils = validationUtils;
        this.dbUtils = dbUtils;
    }

    /**
     * 获取所有CSV表
     * @return 表名列表
     */
    @GetMapping("/tables")
    public ResponseEntity<?> getAllTables() {
        try {
            List<String> tables = csvService.getAllTables();
            return ResponseEntity.ok(tables);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取表列表失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 上传预览校验（上传前校验）
     * @param file CSV文件
     * @return 校验结果
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewCsv(@RequestParam("file") MultipartFile file) {
        try {
            ValidationResult result = validationUtils.validateUpload(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "预览失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 异步上传CSV文件（大文件推荐）
     * @param file CSV文件
     * @param tableName 表名
     * @return 任务ID
     */
    @PostMapping("/upload-async")
    public ResponseEntity<?> uploadCsvAsync(@RequestParam("file") MultipartFile file, 
                                            @RequestParam("tableName") String tableName) {
        try {
            // 先校验
            ValidationResult validationResult = validationUtils.validateUpload(file);
            if (!validationResult.isValid()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "文件校验失败");
                error.put("details", validationResult.getErrors());
                return ResponseEntity.badRequest().body(error);
            }

            // 创建任务
            String taskId = asyncCsvService.createProcessingTask(
                file, tableName, validationResult.getValidRows()
            );

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("message", "文件已上传，正在后台处理中...");
            result.put("validation", validationResult);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 同步上传CSV文件（小文件使用）
     * @param file CSV文件
     * @param tableName 表名
     * @return 处理结果
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file, 
                                        @RequestParam("tableName") String tableName) {
        try {
            Map<String, Object> result = csvService.uploadCsv(file, tableName);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 查询任务状态
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/task-status")
    public ResponseEntity<?> getTaskStatus(@RequestParam("taskId") String taskId) {
        try {
            Map<String, Object> status = asyncCsvService.getTaskStatus(taskId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "查询任务状态失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 查询表数据列表
     * @param tableName 表名
     * @return 数据列表
     */
    @GetMapping("/list")
    public ResponseEntity<?> listData(@RequestParam("tableName") String tableName) {
        try {
            List<Map<String, Object>> data = csvService.listData(tableName);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 分页查询表数据
     * @param tableName 表名
     * @param page 页码
     * @param size 每页大小
     * @return 分页数据
     */
    @GetMapping("/page")
    public ResponseEntity<?> pageData(@RequestParam("tableName") String tableName, 
                                      @RequestParam("page") int page, 
                                      @RequestParam("size") int size) {
        try {
            Map<String, Object> data = csvService.pageData(tableName, page, size);
            
            // 添加响应头，禁用缓存
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
            headers.add("Pragma", "no-cache");
            headers.add("Expires", "0");
            
            return ResponseEntity.ok().headers(headers).body(data);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 删除单条数据
     * @param tableName 表名
     * @param id ID
     * @return 是否成功
     */
    @PostMapping("/delete")
    public ResponseEntity<?> deleteData(@RequestParam("tableName") String tableName, 
                                        @RequestParam("id") int id) {
        try {
            boolean success = csvService.deleteData(tableName, id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 清空表数据
     * @param tableName 表名
     * @return 操作结果
     */
    @PostMapping("/truncate")
    public ResponseEntity<?> truncateTable(@RequestParam("tableName") String tableName) {
        try {
            csvService.truncateTable(tableName);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 删除表
     * @param tableName 表名
     * @return 操作结果
     */
    @PostMapping("/drop")
    public ResponseEntity<?> dropTable(@RequestParam("tableName") String tableName) {
        try {
            csvService.dropTable(tableName);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 导出表数据为CSV（包含完整数据一致性校验）
     * @param tableName 表名
     * @return CSV文件
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(@RequestParam("tableName") String tableName) {
        try {
            List<String[]> exportedData = csvService.exportCsv(tableName);
            int actualRows = Math.max(0, exportedData.size() - 1);

            // 获取原始数据的哈希和行数（上传时保存的）
            String originalHash = dbUtils.getTableMetadata(tableName, "original_data_hash");
            String originalRowCountStr = dbUtils.getTableMetadata(tableName, "original_row_count");

            Map<String, Object> validation;

            int displayOriginalRows;
            int displayActualRows = actualRows;

            if (originalHash != null && originalRowCountStr != null) {
                // 有原始数据记录，进行完整的一致性校验
                int originalRowCount = Integer.parseInt(originalRowCountStr);
                displayOriginalRows = originalRowCount;
                String exportedHash = validationUtils.calculateDataHash(exportedData);

                validation = new HashMap<>();
                validation.put("originalRows", originalRowCount);
                validation.put("actualRows", actualRows);
                validation.put("originalHash", originalHash);
                validation.put("exportedHash", exportedHash);

                if (originalRowCount != actualRows) {
                    validation.put("isConsistent", false);
                    validation.put("status", "error");
                    validation.put("message", String.format("数据不一致：原始%d行，导出%d行（数据可能被修改过）", originalRowCount, actualRows));
                } else if (originalHash.equals(exportedHash)) {
                    validation.put("isConsistent", true);
                    validation.put("status", "success");
                    validation.put("message", "数据完全一致");
                } else {
                    validation.put("isConsistent", false);
                    validation.put("status", "error");
                    validation.put("message", "数据内容不一致（数据可能被修改过）");
                }
            } else {
                // 没有原始数据记录，仅做行数校验
                int expectedRows = dbUtils.getTotalCount(tableName);
                displayOriginalRows = expectedRows;
                validation = validationUtils.validateExport(expectedRows, actualRows);
                validation.put("warning", "未找到原始数据记录，仅进行行数校验");
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            csvService.writeCsvToStream(exportedData, outputStream);
            byte[] csvBytes = outputStream.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            headers.setContentDispositionFormData("attachment", tableName + ".csv");
            headers.setContentLength(csvBytes.length);
            headers.set("X-Validation-Status", (String) validation.get("status"));
            headers.set("X-Validation-Message", java.net.URLEncoder.encode((String) validation.get("message"), "UTF-8"));
            headers.set("X-Is-Consistent", String.valueOf(validation.get("isConsistent")));
            headers.set("X-Original-Rows", String.valueOf(displayOriginalRows));
            headers.set("X-Actual-Rows", String.valueOf(displayActualRows));

            return ResponseEntity.ok().headers(headers).body(csvBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(null);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}
