package com.project.phm.controller;

import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.UnifiedAircraftRequest;
import com.project.phm.adapter.dto.UnifiedConfigRequest;
import com.project.phm.adapter.dto.UnifiedModelRequest;
import com.project.phm.adapter.dto.UnifiedModelResponse;
import com.project.phm.adapter.dto.UnifiedSortieRequest;
import com.project.phm.adapter.dto.UnifiedSortieResponse;
import com.project.phm.service.UnifiedAircraftService;
import com.project.phm.service.UnifiedConfigItemService;
import com.project.phm.service.UnifiedModelService;
import com.project.phm.service.UnifiedSortieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一数据聚合查询控制器。
 *
 * <p>对外暴露聚合查询接口，内部并发查询航新、633 和本地数据源，
 * 合并后返回统一的响应结果，每源附带查询状态和记录数。</p>
 *
 * <p>后续其他业务（如传感器数据、健康评估等）的聚合查询接口也放在此类中，
 * 按统一模式扩展。</p>
 */
@RestController
@RequestMapping("/api/unified")
@Tag(name = "06-统一数据聚合查询")
public class UnifiedSortieController {

    private final UnifiedSortieService unifiedSortieService;
    private final UnifiedModelService unifiedModelService;
    private final UnifiedAircraftService unifiedAircraftService;
    private final UnifiedConfigItemService unifiedConfigItemService;

    public UnifiedSortieController(UnifiedSortieService unifiedSortieService,
                                   UnifiedModelService unifiedModelService,
                                   UnifiedAircraftService unifiedAircraftService,
                                   UnifiedConfigItemService unifiedConfigItemService) {
        this.unifiedSortieService = unifiedSortieService;
        this.unifiedModelService = unifiedModelService;
        this.unifiedAircraftService = unifiedAircraftService;
        this.unifiedConfigItemService = unifiedConfigItemService;
    }

    @Operation(summary = "聚合查询架次元数据",
            description = "并发查询航新、633 和本地三个数据源的架次数据，合并后返回统一的响应列表。\n\n" +
                    "**合并规则**：以 (机号 + 架次号) 为键匹配，同一架次的记录合并所有字段，不同来源的字段互补。\n\n" +
                    "**响应说明**：data 为合并后的架次列表；hangxin/sansan/local 分别记录各源的查询条数和状态。")
    @PostMapping("/sortie/query")
    public ResponseEntity<ApiResult<List<UnifiedSortieResponse>>> querySorties(
            @RequestBody UnifiedSortieRequest request) {
        // 服务层内部已做异常捕获，任一源失败不影响整体返回
        ApiResult<List<UnifiedSortieResponse>> result = unifiedSortieService.querySortiesSafe(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询机型",
            description = "并发查询航新、633 和本地三个数据源的机型数据，合并后返回统一的响应列表。\n\n" +
                    "**keyword**：本地按 modelCode 模糊匹配；航新/633 按 airplaneType 精确匹配。")
    @GetMapping("/model/query")
    public ResponseEntity<ApiResult<List<UnifiedModelResponse>>> queryModels(
            @RequestParam(value = "keyword", required = false) String keyword) {
        UnifiedModelRequest request = new UnifiedModelRequest();
        request.setKeyword(keyword);
        ApiResult<List<UnifiedModelResponse>> result = unifiedModelService.queryModelsSafe(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询单机",
            description = "查询航新、633 和本地三个数据源的单机信息。\n\n" +
                    "**注意**：data 为异构列表 [{本机实体}, {633返回}, {航新返回}]，各源数据独立不合并。")
    @GetMapping("/aircraft/query")
    public ResponseEntity<ApiResult<List<Object>>> queryAircraft(
            @RequestParam(value = "airplaneType", required = false) String airplaneType,
            @RequestParam(value = "airplaneNum", required = false) String airplaneNum) {
        UnifiedAircraftRequest request = new UnifiedAircraftRequest();
        request.setAirplaneType(airplaneType);
        request.setAirplaneNum(airplaneNum);
        ApiResult<List<Object>> result = unifiedAircraftService.queryAircraft(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询单机构型",
            description = "查询航新、633 和本地三个数据源的单机构型信息。\n\n" +
                    "**注意**：data 为异构列表 [{本地配置列表}, {633返回}, {航新返回}]。\n" +
                    "本地仅使用 airplaneType（对应 modelCode）查询构型列表，忽略 page/rows。")
    @GetMapping("/config/query")
    public ResponseEntity<ApiResult<List<Object>>> queryConfigItems(
            @RequestParam(value = "airplaneType", required = false) String airplaneType,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "rows", required = false) Integer rows) {
        UnifiedConfigRequest request = new UnifiedConfigRequest();
        request.setAirplaneType(airplaneType);
        request.setPage(page);
        request.setRows(rows);
        ApiResult<List<Object>> result = unifiedConfigItemService.queryConfigItems(request);
        return ResponseEntity.ok(result);
    }
}
