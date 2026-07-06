package com.project.phm.controller;

import com.project.phm.entity.*;
import com.project.phm.service.AircraftConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞机构型控制器 — 机型/单机/构型项目管理 + 健康记录查询
 */
@RestController
@RequestMapping("/aircraft")
public class AircraftController {

    private final AircraftConfigService configService;

    public AircraftController(AircraftConfigService configService) {
        this.configService = configService;
    }

    // ==================== 机型管理 ====================

    @Operation(summary = "获取所有机型列表",
            description = "返回数据库中所有机型（aircraft_model表）的列表。")
    @Tag(name = "01-机型管理")
    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        try {
            return ResponseEntity.ok(configService.listModels());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取机型列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "添加机型",
            description = "新增一个机型记录。\n\n" +
                    "**请求示例**：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"modelCode\": \"B737-800\",\n" +
                    "  \"manufacturer\": \"Boeing\",\n" +
                    "  \"description\": \"波音737-800\"\n" +
                    "}\n" +
                    "```")
    @Tag(name = "01-机型管理")
    @PostMapping("/models")
    public ResponseEntity<?> addModel(@org.springframework.web.bind.annotation.RequestBody AircraftModel model) {
        try {
            configService.addModel(model);
            return ResponseEntity.ok(successMap("机型添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加机型失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除机型",
            description = "根据机型代码删除指定机型。\n\n**注意**：删除机型前请先删除所有关联的构型。")
    @Tag(name = "01-机型管理")
    @DeleteMapping("/models/{modelCode}")
    public ResponseEntity<?> deleteModel(@Parameter(description = "机型代码", required = true, example = "B737-800")
                                          @PathVariable("modelCode") String modelCode) {
        try {
            configService.removeModel(modelCode);
            return ResponseEntity.ok(successMap("机型删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除机型失败: " + e.getMessage()));
        }
    }

    // ==================== 飞机单机管理 ====================

    @Operation(summary = "获取单机列表",
            description = "获取所有飞机单机，可选按机型过滤。\n\n" +
                    "**不传modelCode**：返回所有单机\n" +
                    "**传modelCode**：返回指定机型下的所有单机")
    @Tag(name = "02-飞机单机管理")
    @GetMapping("/plane")
    public ResponseEntity<?> listPlanes(@Parameter(description = "机型代码（可选）", example = "B737-800")
                                          @RequestParam(value = "modelCode", required = false) String modelCode) {
        try {
            return ResponseEntity.ok(configService.listPlanes(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取单机列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "添加飞机单机",
            description = "为某架飞机（按机号）创建记录。\n\n" +
                    "**请求示例**：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"aircraftNumber\": \"B-1234\",\n" +
                    "  \"modelCode\": \"B737-800\",\n" +
                    "  \"airline\": \"中国国航\",\n" +
                    "  \"configVersion\": \"V1.0\",\n" +
                    "  \"status\": \"active\"\n" +
                    "}\n" +
                    "```\n" +
                    "**status**：active（在役）/ retired（退役）/ maintenance（维护中）")
    @Tag(name = "02-飞机单机管理")
    @PostMapping("/plane")
    public ResponseEntity<?> addPlane(@org.springframework.web.bind.annotation.RequestBody Aircraft aircraft) {
        try {
            configService.addPlane(aircraft);
            return ResponseEntity.ok(successMap("单机添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加单机失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除飞机单机",
            description = "根据机号删除指定的飞机单机。")
    @Tag(name = "02-飞机单机管理")
    @DeleteMapping("/plane/{aircraftNumber}")
    public ResponseEntity<?> deletePlane(@Parameter(description = "机号", required = true, example = "B-1234")
                                           @PathVariable("aircraftNumber") String aircraftNumber) {
        try {
            configService.removePlane(aircraftNumber);
            return ResponseEntity.ok(successMap("单机删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除单机失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取某机型下的可用机号列表",
            description = "获取指定机型下所有 status=active 的机号列表，供前端下拉框使用。")
    @Tag(name = "02-飞机单机管理")
    @GetMapping("/aircraft-numbers")
    public ResponseEntity<?> listAircraftNumbers(@Parameter(description = "机型代码", required = true, example = "B737-800")
                                                  @RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.listActiveAircraftNumbers(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取机号列表失败: " + e.getMessage()));
        }
    }

    // ==================== 构型项目管理 ====================

    @Operation(summary = "获取构型项目列表",
            description = "获取指定机型下的所有构型项目（扁平列表）。\n\n" +
                    "构型项目按GJB章节组织：SYSTEM（系统）→ SUBSYSTEM（子系统）→ EQUIPMENT/LRU（设备）")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/config-items")
    public ResponseEntity<?> listConfigItems(@Parameter(description = "机型代码", required = true, example = "B737-800")
                                              @RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.listItems(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型项目列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取构型项目树",
            description = "获取指定机型的构型项目树形结构（按parentItemId组织），前端树形组件用。")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/config-items/tree")
    public ResponseEntity<?> getConfigTree(@Parameter(description = "机型代码", required = true, example = "B737-800")
                                            @RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.getConfigTree(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型树失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取构型项目选择列表",
            description = "获取指定机型的构型项目选择列表，前端下拉框用（带层级缩进）。")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/config-items/select-list")
    public ResponseEntity<?> getItemSelectList(@Parameter(description = "机型代码", required = true, example = "B737-800")
                                                @RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.getItemSelectList(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型选择列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "添加构型项目",
            description = "添加构型项目（系统/子系统/设备）。\n\n" +
                    "**SYSTEM 示例**（系统级，无父节点）：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"modelCode\": \"B737-800\",\n" +
                    "  \"parentItemId\": null,\n" +
                    "  \"gjbChapter\": \"72-00\",\n" +
                    "  \"systemName\": \"发动机\",\n" +
                    "  \"itemType\": \"SYSTEM\"\n" +
                    "}\n" +
                    "```\n\n" +
                    "**SUBSYSTEM 示例**（子系统，parentItemId指向SYSTEM）：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"modelCode\": \"B737-800\",\n" +
                    "  \"parentItemId\": 1,\n" +
                    "  \"gjbChapter\": \"72-50\",\n" +
                    "  \"systemName\": \"发动机\",\n" +
                    "  \"subSystemName\": \"低压压气机\",\n" +
                    "  \"itemType\": \"SUBSYSTEM\"\n" +
                    "}\n" +
                    "```\n\n" +
                    "**EQUIPMENT 示例**（设备，parentItemId指向SUBSYSTEM）：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"modelCode\": \"B737-800\",\n" +
                    "  \"parentItemId\": 2,\n" +
                    "  \"gjbChapter\": \"72-50\",\n" +
                    "  \"equipmentName\": \"振动传感器\",\n" +
                    "  \"partNumber\": \"P/N 12345\",\n" +
                    "  \"itemType\": \"EQUIPMENT\"\n" +
                    "}\n" +
                    "```")
    @Tag(name = "03-构型项目管理")
    @PostMapping("/config-items")
    public ResponseEntity<?> addConfigItem(@org.springframework.web.bind.annotation.RequestBody ConfigItem item) {
        try {
            Long itemId = configService.addItem(item);
            Map<String, Object> result = successMap("构型项目添加成功");
            result.put("itemId", itemId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加构型项目失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除构型项目",
            description = "根据项目ID删除指定构型项目。\n\n**⚠️ 注意**：如果项目有子节点，会先删除所有子节点。")
    @Tag(name = "03-构型项目管理")
    @DeleteMapping("/config-items/{itemId}")
    public ResponseEntity<?> deleteConfigItem(@Parameter(description = "构型项目ID", required = true, example = "1")
                                               @PathVariable("itemId") Long itemId) {
        try {
            configService.removeItem(itemId);
            return ResponseEntity.ok(successMap("构型项目删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除构型项目失败: " + e.getMessage()));
        }
    }

    // ==================== 数据关联查询 ====================

    @Operation(summary = "查询CSV数据与构型的关联",
            description = "查询 csv_xxx 数据表与飞机构型的关联关系（config_data_mapping表）。\n\n" +
                    "**二选一参数**：\n" +
                    "- 传 `aircraftNumber`：查询某机号关联的所有数据表\n" +
                    "- 传 `itemId`：查询某构型项目关联的所有数据表")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/mappings")
    public ResponseEntity<?> listMappings(
            @Parameter(description = "机号（与itemId二选一）", example = "B-1234") @RequestParam(value = "aircraftNumber", required = false) String aircraftNumber,
            @Parameter(description = "构型项目ID（与aircraftNumber二选一）", example = "3") @RequestParam(value = "itemId", required = false) Long itemId) {
        try {
            if (itemId != null) {
                return ResponseEntity.ok(configService.listMappingsByItem(itemId));
            }
            if (aircraftNumber != null) {
                return ResponseEntity.ok(configService.listMappingsByAircraftNumber(aircraftNumber));
            }
            return ResponseEntity.badRequest().body(errorMap("请提供 aircraftNumber 或 itemId 参数"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("查询关联数据失败: " + e.getMessage()));
        }
    }

    // ==================== 通用辅助 ====================

    private Map<String, Object> successMap(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
