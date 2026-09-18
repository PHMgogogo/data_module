package com.project.phm.service;

import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedConfigRequest;
import com.project.phm.adapter.dto.UnifiedConfigResponse;
import com.project.phm.entity.ConfigItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一单机构型查询编排服务。
 *
 * <p>按请求里的机型路由到唯一数据源：命中三方只查那一个平台（并用该机型关联机号过滤 SSFJH），
 * 未命中路由或平台不可达时回落本地构型。</p>
 */
@Service
public class UnifiedConfigItemService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedConfigItemService.class);
    private static final String SUCCESS = "success";

    private final AircraftConfigService aircraftConfigService;
    private final PlatformRouteService routeService;

    public UnifiedConfigItemService(AircraftConfigService aircraftConfigService,
                                    PlatformRouteService routeService) {
        this.aircraftConfigService = aircraftConfigService;
        this.routeService = routeService;
    }

    /** 按机型路由查询构型，返回统一响应 */
    public ApiResult<List<UnifiedConfigResponse>> queryConfigItems(UnifiedConfigRequest request) {
        String modelKey = requireModel(request.getModelCode());
        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地查询构型", modelKey);
            }
            return localResult(modelKey);
        }
        List<ConfigItem> items = aircraftConfigService.listItems(modelKey);
        List<UnifiedConfigResponse> data = items.stream()
                .map(UnifiedConfigResponse::fromLocal)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        ApiResult<List<UnifiedConfigResponse>> result = ApiResult.success(data);
        SourceInfo info = new SourceInfo(data.size(), data.isEmpty() ? "未返回数据" : SUCCESS);
        if (source == DataSource.HANGXIN) {
            result.setHangxin(info);
        } else {
            result.setSansan(info);
        }
        return result;
    }

    private ApiResult<List<UnifiedConfigResponse>> localResult(String modelKey) {
        List<UnifiedConfigResponse> data;
        try {
            data = aircraftConfigService.listLocalItems(modelKey).stream()
                    .map(UnifiedConfigResponse::fromLocal)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("本地构型查询失败: {}", e.getMessage());
            data = Collections.emptyList();
        }
        ApiResult<List<UnifiedConfigResponse>> result = ApiResult.success(data);
        result.setLocal(new SourceInfo(data.size(), data.isEmpty() ? "本地无构型数据" : SUCCESS));
        return result;
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
