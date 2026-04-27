package com.project.phm.controller;

import com.opencsv.exceptions.CsvException;
import com.project.phm.service.AsyncCsvService;
import com.project.phm.service.CsvService;
import com.project.phm.service.TaskManager;
import com.project.phm.utils.CsvUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private final TaskManager taskManager;

    public CsvController(CsvService csvService, AsyncCsvService asyncCsvService, TaskManager taskManager) {
        this.csvService = csvService;
        this.asyncCsvService = asyncCsvService;
        this.taskManager = taskManager;
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 异步上传CSV文件（大文件推荐）
     * @param file CSV文件
     * @param tableName 表名
     * @return 任务ID
     */
    @PostMapping("/upload-async")
    public ResponseEntity<?> uploadCsvAsync(@RequestParam("file") MultipartFile file, @RequestParam("tableName") String tableName) {
        try {
            // 验证表名
            if (!CsvUtils.isValidTableName(tableName)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "表名必须以csv_开头");
                return ResponseEntity.badRequest().body(error);
            }

            // 关键：在请求线程中先把文件读入内存，避免临时文件被Spring清理
            byte[] fileBytes = file.getBytes();
            String fileName = file.getOriginalFilename();

            // 创建任务
            String taskId = taskManager.createTask(fileName, tableName);

            // 异步处理（传递字节数组而不是MultipartFile）
            asyncCsvService.processCsvAsync(fileBytes, fileName, tableName, taskId);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("message", "文件已上传，正在后台处理中...");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 同步上传CSV文件（小文件使用）
     * @param file CSV文件
     * @param tableName 表名
     * @return 处理结果
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file, @RequestParam("tableName") String tableName) {
        try {
            Map<String, Object> result = csvService.uploadCsv(file, tableName);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (IOException | CsvException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "文件解析失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
    public ResponseEntity<?> pageData(@RequestParam("tableName") String tableName, @RequestParam("page") int page, @RequestParam("size") int size) {
        try {
            Map<String, Object> data = csvService.pageData(tableName, page, size);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务器内部错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 删除单条数据
     * @param tableName 表名
     * @param id ID
     * @return 是否成功
     */
    @PostMapping("/delete")
    public ResponseEntity<?> deleteData(@RequestParam("tableName") String tableName, @RequestParam("id") int id) {
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * 导出表数据为CSV
     * @param tableName 表名
     * @return CSV文件
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(@RequestParam("tableName") String tableName) {
        try {
            List<String[]> data = csvService.exportCsv(tableName);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CsvUtils.generateCsv(data, outputStream);
            byte[] csvBytes = outputStream.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            headers.setContentDispositionFormData("attachment", tableName + ".csv");
            headers.setContentLength(csvBytes.length);

            return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(null);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}
