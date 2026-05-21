package com.project.phm.controller;

import com.project.phm.entity.*;
import com.project.phm.service.AircraftConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞机构型控制器 — 机型/构型/系统设备管理 + 健康记录查询
 */
@RestController
@RequestMapping("/aircraft")
public class AircraftController {

    private final AircraftConfigService configService;

    public AircraftController(AircraftConfigService configService) {
        this.configService = configService;
    }

    // ==================== 机型管理 ====================

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        try {
            return ResponseEntity.ok(configService.listModels());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取机型列表失败: " + e.getMessage()));
        }
    }

    @PostMapping("/models")
    public ResponseEntity<?> addModel(@RequestBody AircraftModel model) {
        try {
            configService.addModel(model);
            return ResponseEntity.ok(successMap("机型添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加机型失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/models/{modelCode}")
    public ResponseEntity<?> deleteModel(@PathVariable("modelCode") String modelCode) {
        try {
            configService.removeModel(modelCode);
            return ResponseEntity.ok(successMap("机型删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除机型失败: " + e.getMessage()));
        }
    }

    // ==================== 飞机构型管理 ====================

    @GetMapping("/configs")
    public ResponseEntity<?> listConfigs(@RequestParam(value = "modelCode", required = false) String modelCode) {
        try {
            return ResponseEntity.ok(configService.listConfigs(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型列表失败: " + e.getMessage()));
        }
    }

    @PostMapping("/configs")
    public ResponseEntity<?> addConfig(@RequestBody AircraftConfig config) {
        try {
            configService.addConfig(config);
            return ResponseEntity.ok(successMap("构型添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加构型失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/configs/{tailNumber}")
    public ResponseEntity<?> deleteConfig(@PathVariable("tailNumber") String tailNumber) {
        try {
            configService.removeConfig(tailNumber);
            return ResponseEntity.ok(successMap("构型删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("删除构型失败: " + e.getMessage()));
        }
    }

    /**
     * 获取某机型下的所有可用机号（供前端下拉框使用）
     */
    @GetMapping("/tail-numbers")
    public ResponseEntity<?> listTailNumbers(@RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.listActiveTailNumbers(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取机号列表失败: " + e.getMessage()));
        }
    }

    // ==================== 构型项目管理 ====================

    @GetMapping("/config-items")
    public ResponseEntity<?> listConfigItems(@RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.listItems(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型项目列表失败: " + e.getMessage()));
        }
    }

    /**
     * 获取构型项目树（前端树形展示用）
     */
    @GetMapping("/config-items/tree")
    public ResponseEntity<?> getConfigTree(@RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.getConfigTree(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型树失败: " + e.getMessage()));
        }
    }

    /**
     * 获取构型项目选择列表（前端下拉框用）
     */
    @GetMapping("/config-items/select-list")
    public ResponseEntity<?> getItemSelectList(@RequestParam("modelCode") String modelCode) {
        try {
            return ResponseEntity.ok(configService.getItemSelectList(modelCode));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("获取构型选择列表失败: " + e.getMessage()));
        }
    }

    @PostMapping("/config-items")
    public ResponseEntity<?> addConfigItem(@RequestBody ConfigItem item) {
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

    @DeleteMapping("/config-items/{itemId}")
    public ResponseEntity<?> deleteConfigItem(@PathVariable("itemId") Long itemId) {
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

    @GetMapping("/mappings")
    public ResponseEntity<?> listMappings(
            @RequestParam(value = "tailNumber", required = false) String tailNumber,
            @RequestParam(value = "itemId", required = false) Long itemId) {
        try {
            if (itemId != null) {
                return ResponseEntity.ok(configService.listMappingsByItem(itemId));
            }
            if (tailNumber != null) {
                return ResponseEntity.ok(configService.listMappingsByTailNumber(tailNumber));
            }
            return ResponseEntity.badRequest().body(errorMap("请提供 tailNumber 或 itemId 参数"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("查询关联数据失败: " + e.getMessage()));
        }
    }

    // ==================== 健康记录 ====================

    @GetMapping("/health-records")
    public ResponseEntity<?> listHealthRecords(
            @RequestParam(value = "tailNumber", required = false) String tailNumber,
            @RequestParam(value = "recordType", required = false) String recordType) {
        try {
            return ResponseEntity.ok(configService.listHealthRecords(tailNumber, recordType));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("查询健康记录失败: " + e.getMessage()));
        }
    }

    @PostMapping("/health-records")
    public ResponseEntity<?> addHealthRecord(@RequestBody HealthRecord record) {
        try {
            configService.addHealthRecord(record);
            return ResponseEntity.ok(successMap("健康记录添加成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(errorMap("添加健康记录失败: " + e.getMessage()));
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
