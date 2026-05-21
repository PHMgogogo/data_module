package com.project.phm.controller;

import com.project.phm.entity.ValidationResult;
import com.project.phm.service.CsvService;
import com.project.phm.utils.CsvColumnAnalyzer;
import com.project.phm.utils.CsvColumnAnalyzer.AnalysisResult;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSV控制器 — 数据存储于达梦数据库 csv_xxx 表
 *
 * 元数据（列类型、机号关联、哈希等）存储于 csv_table_metadata
 * 飞机构型关联存储于 config_data_mapping
 */
@RestController
@RequestMapping("/csv")
public class CsvController {

    private static final Logger log = LoggerFactory.getLogger(CsvController.class);

    private final CsvService csvService;
    private final DataValidationUtils validationUtils;
    private final DbUtils dbUtils;
    private final CsvColumnAnalyzer columnAnalyzer;

    public CsvController(CsvService csvService,
                         DataValidationUtils validationUtils,
                         DbUtils dbUtils,
                         CsvColumnAnalyzer columnAnalyzer) {
        this.csvService = csvService;
        this.validationUtils = validationUtils;
        this.dbUtils = dbUtils;
        this.columnAnalyzer = columnAnalyzer;
    }

    /**
     * 分析CSV列属性（上传前调用，返回列类型和构型模板建议）
     */
    @PostMapping("/analyze-columns")
    public ResponseEntity<?> analyzeColumns(@RequestParam("file") MultipartFile file) {
        try {
            AnalysisResult result = columnAnalyzer.analyze(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "列分析失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 上传预览校验（上传前校验，含列分析）
     */
    @PostMapping("/preview")
    public ResponseEntity<?> previewCsv(@RequestParam("file") MultipartFile file) {
        try {
            ValidationResult validationResult = validationUtils.validateUpload(file);
            AnalysisResult analysis = columnAnalyzer.analyze(file);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("validation", validationResult);
            result.put("analysis", analysis);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "预览失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 上传CSV数据 → 达梦数据库 csv_xxx 表 + 绑定飞机构型
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tableName") String tableName,
            @RequestParam("tailNumber") String tailNumber,
            @RequestParam(value = "parentItemId", required = false) Long parentItemId,
            @RequestParam(value = "dataType", required = false) String dataType) {
        try {
            // 确保表名以 csv_ 开头
            String fullTableName = tableName.toLowerCase().startsWith("csv_") ? tableName : "csv_" + tableName;

            Map<String, Object> result = csvService.uploadCsv(
                    file, fullTableName, tailNumber, parentItemId, dataType);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== 达梦数据库数据查询 ====================

    /**
     * 分页查询达梦 csv_xxx 表数据
     */
    @GetMapping("/query")
    public ResponseEntity<?> queryDeviceData(
            @RequestParam("tailNumber") String tailNumber,
            @RequestParam("deviceName") String deviceName,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            String fullTableName = "csv_" + deviceName;
            if (!dbUtils.tableExists(fullTableName.toUpperCase())) {
                return ResponseEntity.ok(pageResult(Collections.emptyList(), 0, page, size));
            }
            Map<String, Object> result = csvService.pageData(fullTableName, page, size);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("查询失败: " + e.getMessage()));
        }
    }

    /**
     * 获取所有已存储的设备列表（含机号）
     * 从达梦元数据表 csv_table_metadata 读取
     */
    @GetMapping("/tables")
    public ResponseEntity<?> getAllTables() {
        try {
            String sql = "SELECT DISTINCT t1.table_name, t1.meta_value AS tail_number " +
                         "FROM csv_table_metadata t1 " +
                         "WHERE t1.meta_key = 'tail_number' " +
                         "ORDER BY t1.table_name";
            List<Map<String, Object>> rows = dbUtils.queryForList(sql);

            List<Map<String, String>> devices = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, String> d = new LinkedHashMap<>();
                String tn = (String) row.get("tail_number");
                String rawTableName = (String) row.get("table_name");
                // 去掉 csv_ 前缀作为设备名
                String deviceName = rawTableName.toLowerCase().startsWith("csv_")
                        ? rawTableName.substring(4) : rawTableName;
                d.put("label", tn + " / " + deviceName);
                d.put("tailNumber", tn);
                d.put("deviceName", deviceName);
                devices.add(d);
            }

            return ResponseEntity.ok(devices);
        } catch (Exception e) {
            log.warn("获取设备列表失败: {}", e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * 获取达梦 csv_xxx 表数据总览
     */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(
            @RequestParam("tailNumber") String tailNumber,
            @RequestParam("deviceName") String deviceName) {
        try {
            String fullTableName = "csv_" + deviceName;
            if (!dbUtils.tableExists(fullTableName.toUpperCase())) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("deviceName", deviceName);
                empty.put("tailNumber", tailNumber);
                empty.put("totalRows", 0);
                return ResponseEntity.ok(empty);
            }
            Map<String, Object> overview = csvService.getOverview(fullTableName);
            // 确保前端需要的字段存在
            if (!overview.containsKey("tailNumber") || overview.get("tailNumber") == null) {
                overview.put("tailNumber", tailNumber);
            }
            return ResponseEntity.ok(overview);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取概览失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 按数值范围分页查询达梦数据
     */
    @GetMapping("/query-range")
    public ResponseEntity<?> queryByValueRange(
            @RequestParam("tailNumber") String tailNumber,
            @RequestParam("deviceName") String deviceName,
            @RequestParam("column") String column,
            @RequestParam(value = "min", required = false) Double minVal,
            @RequestParam(value = "max", required = false) Double maxVal,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            String fullTableName = "csv_" + deviceName;
            Map<String, Object> result = csvService.queryPageWithRange(
                    fullTableName, column, minVal, maxVal, page, size);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("范围查询失败: " + e.getMessage()));
        }
    }

    // ==================== SQL 执行接口 ====================

    /**
     * 执行自定义 SQL 查询
     * <p>
     * 接收前端传入的 SQL 语句，校验语法后执行。
     * 仅允许 SELECT 查询，通过 EXPLAIN 预校验确保语法正确性。
     *
     * @param body { "sql": "SELECT ..." }
     * @return 查询结果（columns + data + totalRows）或错误信息
     */
    @PostMapping("/sql")
    public ResponseEntity<?> executeSql(@RequestBody Map<String, String> body) {
        String sql = body != null ? body.get("sql") : null;
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("SQL语句不能为空"));
        }
        try {
            Map<String, Object> result = csvService.executeSql(sql);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            // 语法错误/安全检查不通过 → 400
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            // 其他运行时错误 → 500
            log.error("SQL执行异常: {}", e.getMessage());
            return ResponseEntity.status(500).body(errorMap("SQL执行失败: " + e.getMessage()));
        }
    }

    // ==================== 原有达梦表操作（保持不变） ====================

    @GetMapping("/list")
    public ResponseEntity<?> listData(@RequestParam("tableName") String tableName) {
        try {
            List<Map<String, Object>> data = csvService.listData(tableName);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deleteData(@RequestParam("tableName") String tableName,
                                        @RequestParam("id") int id) {
        try {
            boolean success = csvService.deleteData(tableName, id);
            return ResponseEntity.ok(successMap("删除" + (success ? "成功" : "失败")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @PostMapping("/truncate")
    public ResponseEntity<?> truncateTable(@RequestParam("tableName") String tableName) {
        try {
            csvService.truncateTable(tableName);
            return ResponseEntity.ok(successMap("清空成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @PostMapping("/drop")
    public ResponseEntity<?> dropTable(@RequestParam("tableName") String tableName) {
        try {
            csvService.dropTable(tableName);
            return ResponseEntity.ok(successMap("删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(@RequestParam("tableName") String tableName) {
        try {
            List<String[]> exportedData = csvService.exportCsv(tableName);
            int actualRows = Math.max(0, exportedData.size() - 1);
            String originalHash = dbUtils.getTableMetadata(tableName, "original_data_hash");
            String originalRowCountStr = dbUtils.getTableMetadata(tableName, "original_row_count");

            Map<String, Object> validation;
            int displayOriginalRows;
            int displayActualRows = actualRows;

            if (originalHash != null && originalRowCountStr != null) {
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
                    validation.put("message", String.format("数据不一致：原始%d行，导出%d行", originalRowCount, actualRows));
                } else if (originalHash.equals(exportedHash)) {
                    validation.put("isConsistent", true);
                    validation.put("status", "success");
                    validation.put("message", "数据完全一致");
                } else {
                    validation.put("isConsistent", false);
                    validation.put("status", "error");
                    validation.put("message", "数据内容不一致");
                }
            } else {
                int expectedRows = dbUtils.getTotalCount(tableName);
                displayOriginalRows = expectedRows;
                validation = validationUtils.validateExport(expectedRows, actualRows);
                validation.put("warning", "未找到原始数据记录，仅行数校验");
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

    // ==================== 辅助 ====================

    private Map<String, Object> successMap(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", message);
        return r;
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("error", message);
        return r;
    }

    private Map<String, Object> pageResult(List<?> data, int total, int page, int size) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("data", data);
        r.put("total", total);
        r.put("page", page);
        r.put("size", size);
        return r;
    }
}
