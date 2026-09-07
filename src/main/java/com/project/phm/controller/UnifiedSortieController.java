package com.project.phm.controller;

import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.UnifiedAircraftRequest;
import com.project.phm.adapter.dto.UnifiedAircraftResponse;
import com.project.phm.adapter.dto.UnifiedConfigRequest;
import com.project.phm.adapter.dto.UnifiedConfigResponse;
import com.project.phm.adapter.dto.UnifiedModelRequest;
import com.project.phm.adapter.dto.UnifiedModelResponse;
import com.project.phm.adapter.dto.UnifiedSortieRequest;
import com.project.phm.adapter.dto.UnifiedSortieResponse;
import com.project.phm.adapter.dto.UnifiedTimeSeriesRequest;
import com.project.phm.service.UnifiedAircraftService;
import com.project.phm.service.UnifiedConfigItemService;
import com.project.phm.service.UnifiedModelService;
import com.project.phm.service.UnifiedSortieService;
import com.project.phm.service.UnifiedTimeSeriesService;
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
@RequestMapping("/unified")
@Tag(name = "06-统一数据聚合查询")
public class UnifiedSortieController {

    private final UnifiedSortieService unifiedSortieService;
    private final UnifiedModelService unifiedModelService;
    private final UnifiedAircraftService unifiedAircraftService;
    private final UnifiedConfigItemService unifiedConfigItemService;
    private final UnifiedTimeSeriesService unifiedTimeSeriesService;

    public UnifiedSortieController(UnifiedSortieService unifiedSortieService,
                                   UnifiedModelService unifiedModelService,
                                   UnifiedAircraftService unifiedAircraftService,
                                   UnifiedConfigItemService unifiedConfigItemService,
                                   UnifiedTimeSeriesService unifiedTimeSeriesService) {
        this.unifiedSortieService = unifiedSortieService;
        this.unifiedModelService = unifiedModelService;
        this.unifiedAircraftService = unifiedAircraftService;
        this.unifiedConfigItemService = unifiedConfigItemService;
        this.unifiedTimeSeriesService = unifiedTimeSeriesService;
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
                    "**airplaneType**：本地按 modelCode 模糊匹配；航新/633 按 airplaneType 精确匹配。")
    @GetMapping("/model/query")
    public ResponseEntity<ApiResult<List<UnifiedModelResponse>>> queryModels(
            @RequestParam(value = "airplaneType", required = false) String airplaneType) {
        UnifiedModelRequest request = new UnifiedModelRequest();
        request.setAirplaneType(airplaneType);
        ApiResult<List<UnifiedModelResponse>> result = unifiedModelService.queryModelsSafe(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询单机",
            description = "查询航新、633 和本地三个数据源的单机信息，合并后返回统一的响应列表。\n\n" +
                    "**筛选规则**：不传参返回各源全部单机（本地无机号时也全量返回）；传 airplaneType 按机型过滤、传 airplaneNum 按机号过滤。\n\n" +
                    "**响应说明**：data 为各源直接拼接，不做跨源去重，同号飞机可能来自多个来源，以 source 字段区分；hangxin/sansan/local 分别记录各源的查询条数和状态。")
    @GetMapping("/aircraft/query")
    public ResponseEntity<ApiResult<List<UnifiedAircraftResponse>>> queryAircraft(
            @RequestParam(value = "airplaneType", required = false) String airplaneType,
            @RequestParam(value = "airplaneNum", required = false) String airplaneNum) {
        UnifiedAircraftRequest request = new UnifiedAircraftRequest();
        request.setAirplaneType(airplaneType);
        request.setAirplaneNum(airplaneNum);
        ApiResult<List<UnifiedAircraftResponse>> result = unifiedAircraftService.queryAircraft(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询单机构型",
            description = "查询航新、633 和本地三个数据源的单机构型信息，合并后返回统一的响应列表。\n\n" +
                    "**筛选规则**：不传 modelCode 返回本地全部机型及外源的全部构型；传 modelCode 仅查该机型的构型。\n\n" +
                    "**响应说明**：data 为各源直接拼接，不做跨源去重，以 source 字段区分来源。")
    @GetMapping("/config/query")
    public ResponseEntity<ApiResult<List<UnifiedConfigResponse>>> queryConfigItems(
            @RequestParam(value = "modelCode", required = false) String modelCode,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        UnifiedConfigRequest request = new UnifiedConfigRequest();
        request.setModelCode(modelCode);
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        ApiResult<List<UnifiedConfigResponse>> result = unifiedConfigItemService.queryConfigItems(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "聚合查询时序数据",
            description = "查询本地、633 和航新三个数据源的时序数据。\n\n" +
                    "**注意**：本地 CSV 文件以 base64 编码放入 data 列表。\n" +
                    "data 为异构列表：[{本地文件(base64)}, {633返回}, {航新返回}]。")
    @PostMapping("/timeseries/query")
    public ResponseEntity<ApiResult<List<Object>>> queryTimeSeries(
            @RequestBody UnifiedTimeSeriesRequest request) {
        ApiResult<List<Object>> result = unifiedTimeSeriesService.queryTimeSeries(request);
        return ResponseEntity.ok(result);
    }
}
