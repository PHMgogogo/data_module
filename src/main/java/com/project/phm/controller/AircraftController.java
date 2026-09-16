package com.project.phm.controller;

import com.project.phm.entity.*;
import com.project.phm.service.AircraftConfigService;
import com.project.phm.service.CsvService;
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
    private final CsvService csvService;

    public AircraftController(AircraftConfigService configService, CsvService csvService) {
        this.configService = configService;
        this.csvService = csvService;
    }

    // ==================== 机型管理 ====================

    @Operation(summary = "获取所有机型列表",
            description = "返回机型列表：先取本地 aircraft_model 表全部机型，再依次拼接航新、633 平台的机型。\n\n" +
                    "**合并规则**：跨源不去重，顺序为 本地 → 航新 → 633。\n" +
                    "**第三方行字段**：`modelCode` 为 `airplaneType:id`；`manufacturer` / `description` / `createdAt` 固定为 `\"-\"`。\n" +
                    "**容错**：任一第三方平台未配置或不可达时仅跳过该平台，本地数据仍以 HTTP 200 返回。")
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
            description = "返回单机列表：先取本地 aircraft_config 表，再依次拼接航新、633 平台的单机。\n\n" +
                    "**合并规则**：跨源不去重，顺序为 本地 → 航新 → 633。" +
                    "`modelCode` 同时作用于本地（按机型过滤）和两个第三方平台（透传为 `airplaneType` 过滤）；" +
                    "`airplaneNum` 新接口未暴露，向三方传 null。\n" +
                    "**第三方行字段**：`aircraftNumber` 取原 `airplaneNum`，`modelCode` 取原 `airplaneType`；" +
                    "`airline` / `configVersion` / `status` / `createdAt` 固定为 `\"-\"`。\n" +
                    "**容错**：任一第三方平台未配置或不可达时仅跳过该平台，本地数据仍以 HTTP 200 返回。")
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

    @Operation(summary = "按机号查询单机详情",
            description = "根据机号获取指定单机的详细信息。")
    @Tag(name = "02-飞机单机管理")
    @GetMapping("/plane/{aircraftNumber}")
    public ResponseEntity<?> getPlane(@Parameter(description = "机号", required = true, example = "B-1234")
                                        @PathVariable("aircraftNumber") String aircraftNumber) {
        try {
            com.project.phm.entity.Aircraft plane = configService.getPlane(aircraftNumber);
            if (plane == null) {
                return ResponseEntity.badRequest().body(errorMap("单机不存在: " + aircraftNumber));
            }
            return ResponseEntity.ok(plane);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取单机详情失败: " + e.getMessage()));
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
            description = "按 modelCode 走两个互斥分支，**一次调用只返回一个来源的数据**，不做跨源拼接。\n\n" +
                    "构型项目按GJB章节组织：SYSTEM（系统）→ SUBSYSTEM（子系统）→ EQUIPMENT/LRU（设备）\n\n" +
                    "**传 modelCode**：先与内存里的三方 SSFJH 索引比对，**命中即认定它是三方实体号**，" +
                    "返回该实体号下的全部三方构型行；**未命中的才当作本地机型代码**查本地 config_item。\n\n" +
                    "**不传 modelCode**：全量重拉三方构型（各平台翻页取全，每页 rows=10，由后端给定；" +
                    "三方接口不支持按机型过滤），依次拼接航新、633，本地行不参与；" +
                    "这批数据同时被用于**整体替换**内存索引，并由本接口原样返回。\n\n" +
                    "**三方行映射**：GXBS→itemId、SJGXBS→parentItemId、GXMC→equipmentName、SSFJH→modelCode、" +
                    "JJH→partNumber，itemType 固定为 EQUIPMENT；AZWZ 及三方其余字段忽略。" +
                    "三方 id 为非数字串（如 gx-jx20a-01）时 itemId/parentItemId 为 null。\n\n" +
                    "**说明**：响应无 source 字段，两条分支靠 id 形态区分（本地 itemId 非空、三方为 null）；" +
                    "索引只在「不传 modelCode」时刷新，应用刚启动、还没查过全量就直接传 modelCode 会因索引为空而回落本地；" +
                    "三方全量本次一条都没取到时保留旧索引；任一平台未配置或不可达仅跳过该源，" +
                    "两个平台都拿不到数据时返回空列表。")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/config-items")
    public ResponseEntity<?> listConfigItems(@Parameter(description = "机型代码或三方实体号（SSFJH）：命中三方索引则返回该实体号下的三方构型，否则查本地该机型", example = "B737-800")
                                              @RequestParam(value = "modelCode", required = false) String modelCode) {
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
                    "**筛选参数（二选一）**：\n" +
                    "- 传 `sortieId`：查询某架次关联的所有数据表\n" +
                    "- 传 `itemId`：查询某构型项目关联的所有数据表")
    @Tag(name = "03-构型项目管理")
    @GetMapping("/mappings")
    public ResponseEntity<?> listMappings(
            @Parameter(description = "架次ID", example = "1") @RequestParam(value = "sortieId", required = false) Long sortieId,
            @Parameter(description = "构型项目ID", example = "3") @RequestParam(value = "itemId", required = false) Long itemId) {
        try {
            if (sortieId != null) {
                return ResponseEntity.ok(configService.listMappingsBySortie(sortieId));
            }
            if (itemId != null) {
                return ResponseEntity.ok(configService.listMappingsByItem(itemId));
            }
            return ResponseEntity.badRequest().body(errorMap("请提供 sortieId 或 itemId 参数"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("查询关联数据失败: " + e.getMessage()));
        }
    }

    // ==================== 架次管理 ====================

    @Operation(summary = "获取架次列表",
            description = "返回架次列表：先取本地 sortie 表，再依次拼接航新、633 平台的架次（跨源不去重）。\n\n" +
                    "**第三方行字段映射**：`sortieId` = 原 `id`、`aircraftNumber` = 原 `airplaneNum`、" +
                    "`sortieNumber` = 原 `flightNum`；`flightDate` / `startTime` / `endTime` 由三方 " +
                    "`startTime` / `endTime`（形如 `2026-01-01 10:00:00.00`）拆分而来 —— " +
                    "`flightDate` 取日期部分（`2026-01-01`）、`startTime` / `endTime` 只取时刻部分（`10:00:00`），" +
                    "与本地行形态一致（本地存的就是日期与时刻分开）。三方未返回时间的行仍为 `\"-\"`。")
    @Tag(name = "04-架次管理")
    @GetMapping("/sorties")
    public ResponseEntity<?> listSorties(@Parameter(description = "机号（可选，不传返回各源全部架次）", example = "B-1234")
                                          @RequestParam(value = "aircraftNumber", required = false) String aircraftNumber) {
        try {
            return ResponseEntity.ok(configService.listSorties(aircraftNumber));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取架次列表失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取架次的参数字段名",
            description = "按航新架次接口下发的 `parameterGroupId` 查询该架次的参数字段名列表。\n\n" +
                    "**用途**：字段名是查时序数据的必需参数 —— 先由 `GET /aircraft/sorties` 拿到航新行的 " +
                    "`parameterGroupId`，再调本接口取到字段名，最后按名查时序数据。\n\n" +
                    "**返回**：`data` 为字段名裸数组，如 `[\"A8b信号和值故障_JDK_GME_FDR2_A\", ...]`。" +
                    "仅航新有该能力；平台未配置或不可达时返回空数组。")
    @Tag(name = "04-架次管理")
    @GetMapping("/sortie/parameters")
    public ResponseEntity<?> listSortieParameters(
            @Parameter(description = "参数组ID（取自 /aircraft/sorties 航新行的 parameterGroupId）",
                    required = true, example = "2a26c3bf-67b3-4103-a8f0-3ac6d5973786")
            @RequestParam("parameterGroupId") String parameterGroupId) {
        try {
            return ResponseEntity.ok(configService.listParameterNames(parameterGroupId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取参数字段名失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取架次详情",
            description = "根据架次ID获取详细信息。")
    @Tag(name = "04-架次管理")
    @GetMapping("/sorties/{sortieId}")
    public ResponseEntity<?> getSortie(@Parameter(description = "架次ID", required = true, example = "1")
                                        @PathVariable("sortieId") Long sortieId) {
        try {
            Sortie sortie = configService.getSortie(sortieId);
            if (sortie == null) {
                return ResponseEntity.badRequest().body(errorMap("架次不存在: " + sortieId));
            }
            return ResponseEntity.ok(sortie);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取架次详情失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "添加架次",
            description = "为指定单机添加一条架次（飞行任务）记录。\n\n" +
                    "**请求示例**：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"aircraftNumber\": \"B-1234\",\n" +
                    "  \"sortieNumber\": \"CA1234-20260723\",\n" +
                    "  \"flightDate\": \"2026-07-23\",\n" +
                    "  \"startTime\": \"10:30:00\",\n" +
                    "  \"endTime\": \"14:20:00\",\n" +
                    "}\n" +
                    "```")
    @Tag(name = "04-架次管理")
    @PostMapping("/sorties")
    public ResponseEntity<?> addSortie(@RequestBody Sortie sortie) {
        try {
            configService.addSortie(sortie);
            return ResponseEntity.ok(successMap("架次添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加架次失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "删除架次",
            description = "删除指定架次，同时级联删除该架次关联的所有CSV数据表（csv_xxx）和数据关联记录。")
    @Tag(name = "04-架次管理")
    @DeleteMapping("/sorties/{sortieId}")
    public ResponseEntity<?> deleteSortie(@Parameter(description = "架次ID", required = true, example = "1")
                                           @PathVariable("sortieId") Long sortieId) {
        try {
            // 1. 查出该架次关联的所有 csv_xxx 表，逐个删除
            java.util.List<ConfigDataMapping> mappings = configService.listMappingsBySortie(sortieId);
            for (ConfigDataMapping m : mappings) {
                String tableName = "csv_" + m.getCsvTableName();
                // 表可能已被手动删除，容错处理
                try {
                    csvService.dropTable(tableName);
                } catch (IllegalArgumentException e) {
                    // 表不存在则跳过
                }
            }
            // 2. 删除架次及剩余关联记录
            configService.removeSortie(sortieId);
            return ResponseEntity.ok(successMap("架次删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除架次失败: " + e.getMessage()));
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
