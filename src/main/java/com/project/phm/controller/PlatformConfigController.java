package com.project.phm.controller;

import com.project.phm.adapter.dto.PlatformType;
import com.project.phm.entity.ExternalPlatform;
import com.project.phm.service.PlatformConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外来平台配置管理 — CRUD + 连通性测试。
 */
@RestController
@RequestMapping("/api/external-platforms")
@Tag(name = "07-外来平台配置管理")
public class PlatformConfigController {

    private final PlatformConfigService platformConfigService;

    public PlatformConfigController(PlatformConfigService platformConfigService) {
        this.platformConfigService = platformConfigService;
    }

    @Operation(summary = "获取所有平台配置")
    @GetMapping
    public ResponseEntity<List<ExternalPlatform>> listAll() {
        return ResponseEntity.ok(platformConfigService.listAll());
    }

    @Operation(summary = "按ID获取平台配置")
    @GetMapping("/{id}")
    public ResponseEntity<ExternalPlatform> getById(@PathVariable Long id) {
        ExternalPlatform platform = platformConfigService.getById(id);
        if (platform == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(platform);
    }

    @Operation(summary = "新增平台配置", description = "platform 枚举: HANGXIN(航新服务) / SAN_SAN(633服务)")
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(
            @RequestParam PlatformType platform,
            @RequestParam String ip,
            @RequestParam(required = false) Integer port) {
        try {
            ExternalPlatform p = new ExternalPlatform();
            p.setPlatformName(platform.getDisplayName());
            p.setPlatformIp(ip);
            p.setPort(port);
            platformConfigService.add(p);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "平台配置添加成功");
            result.put("id", p.getId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @Operation(summary = "更新平台配置", description = "platform 枚举: HANGXIN / SAN_SAN")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestParam PlatformType platform,
            @RequestParam String ip,
            @RequestParam(required = false) Integer port) {
        try {
            ExternalPlatform p = new ExternalPlatform();
            p.setId(id);
            p.setPlatformName(platform.getDisplayName());
            p.setPlatformIp(ip);
            p.setPort(port);
            platformConfigService.update(p);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "平台配置更新成功");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @Operation(summary = "删除平台配置")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            platformConfigService.delete(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "删除成功");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @Operation(summary = "测试平台连通性（按ID）")
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testById(@PathVariable Long id) {
        try {
            boolean ok = platformConfigService.testConnectivity(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", ok);
            result.put("message", ok ? "连接成功" : "连接失败");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @Operation(summary = "测试IP+端口连通性（不保存）")
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestParam String ip,
            @RequestParam(required = false) Integer port) {
        boolean ok = platformConfigService.testConnectivity(ip, port);
        Map<String, Object> result = new HashMap<>();
        result.put("success", ok);
        result.put("message", ok ? "连接成功" : "连接失败，请检查IP和端口");
        return ResponseEntity.ok(result);
    }
}
