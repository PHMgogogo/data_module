package com.project.phm.controller;

import com.project.phm.entity.ConfigDataMapping;
import com.project.phm.entity.ValidationResult;
import com.project.phm.service.AircraftConfigService;
import com.project.phm.service.CsvService;
import com.project.phm.utils.CsvColumnAnalyzer;
import com.project.phm.utils.CsvColumnAnalyzer.AnalysisResult;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    private final AircraftConfigService aircraftConfigService;

    public CsvController(CsvService csvService,
                         DataValidationUtils validationUtils,
                         DbUtils dbUtils,
                         CsvColumnAnalyzer columnAnalyzer,
                         AircraftConfigService aircraftConfigService) {
        this.csvService = csvService;
        this.validationUtils = validationUtils;
        this.dbUtils = dbUtils;
        this.columnAnalyzer = columnAnalyzer;
        this.aircraftConfigService = aircraftConfigService;
    }

    /**
     * 分析CSV列属性（上传前调用，返回列类型和构型模板建议）
     */
    @Operation(summary = "分析CSV列属性", 
            description = "上传前调用，自动识别CSV每一列的数据类型（数值/日期/字符串等），并给出构型项目模板建议。\n\n" +
                    "**用途**：辅助前端在上传前确认列类型，并推荐合适的构型项目模板。")
    @Tag(name = "05-CSV数据管理")
    @PostMapping(value = "/analyze-columns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzeColumns(@Parameter(description = "CSV文件（multipart/form-data）", required = true) 
                                             @RequestParam("file") MultipartFile file) {
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
    @Operation(summary = "上传预览校验", 
            description = "上传前对CSV文件进行完整校验，包含：\n" +
                    "- 文件格式校验（必须是CSV）\n" +
                    "- 表头校验（必须有列名）\n" +
                    "- 数据行校验（空行检测、列数一致性）\n" +
                    "- 列类型分析\n" +
                    "- 文件大小警告（>100MB）\n\n" +
                    "**返回**：ValidationResult（含 errors/warnings/sampleData）")
    @Tag(name = "05-CSV数据管理")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewCsv(@Parameter(description = "CSV文件（multipart/form-data）", required = true) 
                                         @RequestParam("file") MultipartFile file) {
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
    @Operation(summary = "上传CSV文件并入库",
            description = "解析CSV文件并保存到达梦数据库的 `csv_xxx` 表，同时：\n" +
                    "- 自动建表（如不存在）\n" +
                    "- 计算原始数据的SHA-256哈希（用于后续导出一致性校验）\n" +
                    "- 保存到元数据表 csv_table_metadata\n" +
                    "- 绑定到架次（通过架次自动关联到对应单机 + 构型项目）\n\n" +
                    "**注意**：表名会自动加 `csv_` 前缀（如传 `engine` 会变成 `csv_engine`）\n" +
                    "**注意**：如果表名已存在，会报错防止重复上传")
    @Tag(name = "05-CSV数据管理")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCsv(
            @Parameter(description = "CSV文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "表名（会自动加csv_前缀）", required = true, example = "engine_vibration") @RequestParam("tableName") String tableName,
            @Parameter(description = "构型项目ID（可选）", example = "3") @RequestParam(value = "parentItemId", required = false) Long parentItemId,
            @Parameter(description = "架次ID（通过架次关联单机）", example = "1") @RequestParam(value = "sortieId", required = false) Long sortieId) {
        try {
            // 确保表名以 csv_ 开头
            String fullTableName = tableName.toLowerCase().startsWith("csv_") ? tableName : "csv_" + tableName;

            Map<String, Object> result = csvService.uploadCsv(
                    file, fullTableName, parentItemId, sortieId);

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
    @Operation(summary = "分页查询设备数据", 
            description = "按机号+设备名分页查询达梦 csv_xxx 表数据。\n\n" +
                    "**示例**：aircraftNumber=B-1234, deviceName=engine_vibration → 查询 csv_engine_vibration")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/query")
    public ResponseEntity<?> queryDeviceData(
            @Parameter(description = "机号", required = true, example = "B-1234") @RequestParam("aircraftNumber") String aircraftNumber,
            @Parameter(description = "设备名（对应csv_后的表名）", required = true, example = "engine_vibration") @RequestParam("deviceName") String deviceName,
            @Parameter(description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "20") @RequestParam(value = "size", defaultValue = "20") int size) {
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
    @Operation(summary = "获取所有已存储设备列表", 
            description = "从达梦元数据表 csv_table_metadata 读取，返回所有已上传CSV的设备列表。\n\n" +
                    "**返回字段**：label（显示名）、aircraftNumber（机号）、deviceName（设备名）")
    @Tag(name = "05-CSV数据管理")
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
                String rawTableName = (String) row.get("table_name");
                // 双重保险：检查表是否实际存在
                if (!dbUtils.tableExists(rawTableName)) {
                    continue;
                }
                Map<String, String> d = new LinkedHashMap<>();
                String tn = (String) row.get("tail_number");
                // 去掉 csv_ 前缀作为设备名
                String deviceName = rawTableName.toLowerCase().startsWith("csv_")
                        ? rawTableName.substring(4) : rawTableName;
                d.put("label", tn + " / " + deviceName);
                d.put("aircraftNumber", tn);
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
    @Operation(summary = "获取设备数据总览",
            description = "获取指定 csv_xxx 表的数据总览，包含行数、列数、列类型等信息。\n\n" +
                    "**传参方式**：\n" +
                    "- 传 `mappingId`：从构型关联自动解析机号 + 表名（推荐）\n" +
                    "- 传 `aircraftNumber` + `deviceName`：直接指定（兼容旧版）")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(
            @Parameter(description = "构型关联ID（与aircraftNumber+deviceName二选一）", example = "1") @RequestParam(value = "mappingId", required = false) Long mappingId,
            @Parameter(description = "机号（与mappingId二选一）", example = "B-1234") @RequestParam(value = "aircraftNumber", required = false) String aircraftNumber,
            @Parameter(description = "设备名（与mappingId二选一）", example = "engine_vibration") @RequestParam(value = "deviceName", required = false) String deviceName) {
        try {
            // mappingId 优先：自动解析表名和机号
            if (mappingId != null) {
                ConfigDataMapping mapping = aircraftConfigService.getMapping(mappingId);
                if (mapping == null) {
                    return ResponseEntity.badRequest().body(errorMap("构型关联不存在: " + mappingId));
                }
                aircraftNumber = mapping.getAircraftNumber();
                deviceName = mapping.getCsvTableName();
                // csvTableName 可能已经带 csv_ 前缀
                if (deviceName != null && deviceName.toLowerCase().startsWith("csv_")) {
                    // 已经是完整表名
                } else {
                    deviceName = "csv_" + deviceName;
                }
            }

            if (aircraftNumber == null || deviceName == null) {
                return ResponseEntity.badRequest().body(errorMap("请提供 mappingId，或 aircraftNumber + deviceName"));
            }

            String fullTableName = deviceName.toLowerCase().startsWith("csv_") ? deviceName : "csv_" + deviceName;
            if (!dbUtils.tableExists(fullTableName.toUpperCase())) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("deviceName", deviceName);
                empty.put("aircraftNumber", aircraftNumber);
                empty.put("totalRows", 0);
                return ResponseEntity.ok(empty);
            }
            Map<String, Object> overview = csvService.getOverview(fullTableName);
            // 确保前端需要的字段存在
            if (!overview.containsKey("aircraftNumber") || overview.get("aircraftNumber") == null) {
                overview.put("aircraftNumber", aircraftNumber);
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
    @Operation(summary = "按数值范围分页查询", 
            description = "在指定列上按数值范围(min ≤ value ≤ max)过滤数据，并分页返回。\n\n" +
                    "**示例**：column=vibration, min=2.0, max=3.0 → 查询振动值在2.0~3.0之间的数据")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/query-range")
    public ResponseEntity<?> queryByValueRange(
            @Parameter(description = "机号", required = true, example = "B-1234") @RequestParam("aircraftNumber") String aircraftNumber,
            @Parameter(description = "设备名", required = true, example = "engine_vibration") @RequestParam("deviceName") String deviceName,
            @Parameter(description = "数值列名", required = true, example = "vibration") @RequestParam("column") String column,
            @Parameter(description = "最小值（可选）", example = "2.0") @RequestParam(value = "min", required = false) Double minVal,
            @Parameter(description = "最大值（可选）", example = "3.0") @RequestParam(value = "max", required = false) Double maxVal,
            @Parameter(description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "20") @RequestParam(value = "size", defaultValue = "20") int size) {
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
    @Operation(summary = "执行自定义SQL查询", 
            description = "接收前端传入的SQL语句，校验语法后执行。\n\n" +
                    "**安全限制**：\n" +
                    "- 仅允许 `SELECT` 查询\n" +
                    "- 通过 EXPLAIN 预校验语法\n" +
                    "- INSERT/UPDATE/DELETE/DROP 等会被拒绝\n\n" +
                    "**返回**：columns（列名列表）+ data（数据行）+ totalRows（总行数）\n\n" +
                    "**示例SQL**：\n" +
                    "```sql\n" +
                    "SELECT * FROM csv_engine_vibration WHERE vibration > 2.3 ORDER BY id\n" +
                    "```")
    @Tag(name = "05-CSV数据管理")
    @PostMapping("/sql")
    public ResponseEntity<?> executeSql(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "SQL查询体",
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"sql\": \"SELECT * FROM csv_engine_vibration ORDER BY id\"}")))
            @RequestBody Map<String, String> body) {
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

    @Operation(summary = "查询表的全部数据", 
            description = "返回指定 csv_xxx 表的所有数据（不分页，慎用！大表会很慢）。\n\n" +
                    "建议优先使用 `/csv/query`（分页）或 `/csv/sql`（自定义查询）。")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/list")
    public ResponseEntity<?> listData(@Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName) {
        try {
            List<Map<String, Object>> data = csvService.listData(tableName);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除单条数据", 
            description = "根据 ID 删除指定 csv_xxx 表中的一条记录。\n\n" +
                    "**⚠️ 警告**：删除后会破坏数据一致性，导出校验将失败。")
    @Tag(name = "05-CSV数据管理")
    @PostMapping("/delete")
    public ResponseEntity<?> deleteData(@Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName,
                                        @Parameter(description = "记录ID", required = true, example = "1") @RequestParam("id") int id) {
        try {
            boolean success = csvService.deleteData(tableName, id);
            return ResponseEntity.ok(successMap("删除" + (success ? "成功" : "失败")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @Operation(summary = "清空表数据", 
            description = "清空指定 csv_xxx 表中所有数据，但保留表结构（不删除元数据）。\n\n" +
                    "**⚠️ 警告**：此操作不可逆！")
    @Tag(name = "05-CSV数据管理")
    @PostMapping("/truncate")
    public ResponseEntity<?> truncateTable(@Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName) {
        try {
            csvService.truncateTable(tableName);
            return ResponseEntity.ok(successMap("清空成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除整个表", 
            description = "删除指定 csv_xxx 表（包括数据和表结构）。\n\n" +
                    "**⚠️ 危险**：此操作不可逆！")
    @Tag(name = "05-CSV数据管理")
    @PostMapping("/drop")
    public ResponseEntity<?> dropTable(@Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName) {
        try {
            csvService.dropTable(tableName);
            return ResponseEntity.ok(successMap("删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("服务器内部错误: " + e.getMessage()));
        }
    }

    @Operation(summary = "导出CSV并校验一致性", 
            description = "导出指定 csv_xxx 表的数据为CSV文件，并通过SHA-256哈希校验数据完整性。\n\n" +
                    "**校验响应头**：\n" +
                    "- `X-Validation-Status`：success/error\n" +
                    "- `X-Validation-Message`：校验消息（URL编码）\n" +
                    "- `X-Is-Consistent`：true/false（数据是否一致）\n" +
                    "- `X-Original-Rows`：原始上传行数\n" +
                    "- `X-Actual-Rows`：实际导出行数\n\n" +
                    "**校验原理**：上传时保存原始数据SHA-256哈希，导出时对比哈希值。任何修改都会被检测出来。")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(@Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName) {
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

            // 正确处理中文文件名编码，使用 Spring 的 ContentDisposition
            String filename = tableName + ".csv";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            // 使用 ContentDisposition 来正确处理 filename 和 filename*
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.attachment()
                            .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                            .build()
            );
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

    // ==================== 列子集导出 ====================

    @Operation(summary = "导出指定列的子集CSV",
            description = "按列名列表导出 csv_xxx 表的指定列子集为 CSV 文件。\n\n" +
                    "**示例**：`/csv/export-columns?tableName=csv_engine_vibration&columns=fan_vibration,egt_actual`\n\n" +
                    "**返回**：`text/csv` 文件流，仅包含请求的列。")
    @Tag(name = "05-CSV数据管理")
    @GetMapping("/export-columns")
    public ResponseEntity<byte[]> exportCsvColumns(
            @Parameter(description = "表名（含csv_前缀）", required = true, example = "csv_engine_vibration") @RequestParam("tableName") String tableName,
            @Parameter(description = "列名列表（逗号分隔）", required = true, example = "fan_vibration,egt_actual") @RequestParam("columns") String columns) {
        try {
            List<String> columnNames = Arrays.asList(columns.split("\\s*,\\s*"));
            if (columnNames.isEmpty()) {
                return ResponseEntity.badRequest().body(null);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            csvService.exportCsvColumns(tableName, columnNames, outputStream);
            byte[] csvBytes = outputStream.toByteArray();

            // 正确处理中文文件名编码，使用 Spring 的 ContentDisposition
            String filename = tableName + "_subset.csv";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            // 使用 ContentDisposition 来正确处理 filename 和 filename*
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.attachment()
                            .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                            .build()
            );
            headers.setContentLength(csvBytes.length);

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
