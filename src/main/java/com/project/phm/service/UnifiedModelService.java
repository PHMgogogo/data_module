package com.project.phm.service;

import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.*;
import com.project.phm.adapter.merge.ModelDataMerger;
import com.project.phm.entity.AircraftModel;
import com.project.phm.mapper.AircraftModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一机型查询编排服务。
 *
 * <p>按请求里的机型路由到唯一数据源：命中三方只查那一个平台，未命中路由或平台不可达时回落本地。</p>
 */
@Service
public class UnifiedModelService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedModelService.class);
    private static final String SUCCESS = "success";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final AircraftModelMapper modelMapper;
    private final ModelDataMerger merger;
    private final PlatformRouteService routeService;

    public UnifiedModelService(HangxinSortieClient hangxinClient,
                               SanSanSortieClient sanSanClient,
                               AircraftModelMapper modelMapper,
                               ModelDataMerger merger,
                               PlatformRouteService routeService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.modelMapper = modelMapper;
        this.merger = merger;
        this.routeService = routeService;
    }

    /** 按机型路由查询机型，返回统一响应 */
    public ApiResult<List<UnifiedModelResponse>> queryModelsSafe(UnifiedModelRequest request) {
        String modelKey = requireModel(request.getAirplaneType());
        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地查询机型", modelKey);
            }
            return localResult(request);
        }
        return externalResult(request, source, modelKey);
    }

    private ApiResult<List<UnifiedModelResponse>> localResult(UnifiedModelRequest request) {
        List<AircraftModel> localData;
        try {
            localData = modelMapper.selectList(request.toLocalQuery());
        } catch (Exception e) {
            log.warn("本地机型查询失败: {}", e.getMessage());
            localData = Collections.emptyList();
        }
        List<UnifiedModelResponse> merged = merger.fromLocal(localData);
        ApiResult<List<UnifiedModelResponse>> result = ApiResult.success(merged);
        result.setLocal(new SourceInfo(localData.size(), SUCCESS));
        return result;
    }

    private ApiResult<List<UnifiedModelResponse>> externalResult(UnifiedModelRequest request,
                                                                DataSource source, String modelKey) {
        Map<String, Object> params = paramsFor(modelKey);
        List<ExternalModelData> data;
        try {
            data = clientFor(source).queryModels(params);
        } catch (Exception e) {
            log.warn("{}机型查询失败: {}", source.getLabel(), e.getMessage());
            data = Collections.emptyList();
        }
        boolean hangxin = source == DataSource.HANGXIN;
        List<UnifiedModelResponse> merged = hangxin
                ? merger.fromHangxin(data)
                : merger.fromSanSan(data);
        ApiResult<List<UnifiedModelResponse>> result = ApiResult.success(merged);
        SourceInfo info = new SourceInfo(data.size(), SUCCESS);
        if (hangxin) {
            result.setHangxin(info);
        } else {
            result.setSansan(info);
        }
        return result;
    }

    /** 三方机型接口按原始 airplaneType 过滤，合成码先还原 */
    private Map<String, Object> paramsFor(String modelKey) {
        String realType = routeService.realAirplaneType(modelKey);
        Map<String, Object> params = new HashMap<>();
        if (realType != null) {
            params.put("airplaneType", realType);
        }
        return params;
    }

    private BaseExternalClient clientFor(DataSource source) {
        return source == DataSource.SAN_SAN ? sanSanClient : hangxinClient;
    }

    /** 机型是路由的必要条件 */
    private static String requireModel(String modelCode) {
        String modelKey = modelCode == null ? null : modelCode.trim();
        if (modelKey == null || modelKey.isEmpty()) {
            throw new IllegalArgumentException("机型不能为空");
        }
        return modelKey;
    }
}
