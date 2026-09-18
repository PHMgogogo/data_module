package com.project.phm.service;

import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedAircraftRequest;
import com.project.phm.adapter.dto.UnifiedAircraftResponse;
import com.project.phm.entity.Aircraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一单机查询编排服务。
 *
 * <p>按请求里的机型路由到唯一数据源：命中三方只查那一个平台，未命中路由或平台不可达时回落本地。</p>
 */
@Service
public class UnifiedAircraftService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAircraftService.class);
    private static final String SUCCESS = "success";
    private static final String AIRCRAFT_PATH = "/configuration/airplane/number/list";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final AircraftConfigService aircraftConfigService;
    private final PlatformRouteService routeService;

    public UnifiedAircraftService(HangxinSortieClient hangxinClient,
                                  SanSanSortieClient sanSanClient,
                                  AircraftConfigService aircraftConfigService,
                                  PlatformRouteService routeService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.aircraftConfigService = aircraftConfigService;
        this.routeService = routeService;
    }

    /** 按机型路由查询单机，返回统一响应 */
    public ApiResult<List<UnifiedAircraftResponse>> queryAircraft(UnifiedAircraftRequest request) {
        String modelKey = requireModel(request.getAirplaneType());
        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地查询单机", modelKey);
            }
            return localResult(request, modelKey);
        }
        return externalResult(request, source, modelKey);
    }

    private ApiResult<List<UnifiedAircraftResponse>> localResult(UnifiedAircraftRequest request,
                                                                String modelKey) {
        List<UnifiedAircraftResponse> localData;
        try {
            String aircraftNum = request.getAirplaneNum();
            if (aircraftNum == null || aircraftNum.isEmpty()) {
                localData = aircraftConfigService.listLocalPlanes(modelKey).stream()
                        .map(UnifiedAircraftResponse::fromLocal)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } else {
                Aircraft plane = aircraftConfigService.getPlane(aircraftNum);
                // 指定机号时校验机型一致性，不一致视为未命中
                if (plane == null || !modelKey.equals(plane.getModelCode())) {
                    localData = Collections.emptyList();
                } else {
                    UnifiedAircraftResponse r = UnifiedAircraftResponse.fromLocal(plane);
                    localData = r != null ? Collections.singletonList(r) : Collections.emptyList();
                }
            }
        } catch (Exception e) {
            log.warn("本地单机查询失败: {}", e.getMessage());
            localData = Collections.emptyList();
        }
        ApiResult<List<UnifiedAircraftResponse>> result = ApiResult.success(localData);
        result.setLocal(new SourceInfo(localData.size(),
                localData.isEmpty() ? "本机未找到该单机" : SUCCESS));
        return result;
    }

    private ApiResult<List<UnifiedAircraftResponse>> externalResult(UnifiedAircraftRequest request,
                                                                   DataSource source, String modelKey) {
        String realAirplaneType = routeService.realAirplaneType(modelKey);
        Map<String, Object> params = new HashMap<>();
        if (realAirplaneType != null) {
            params.put("airplaneType", realAirplaneType);
        }
        String aircraftNum = request.getAirplaneNum();
        if (aircraftNum != null && !aircraftNum.isEmpty()) {
            params.put("airplaneNum", aircraftNum);
        }
        List<UnifiedAircraftResponse> data;
        try {
            Object raw = clientFor(source).queryForRawData(AIRCRAFT_PATH, params);
            data = parseAircraftList(raw, source == DataSource.SAN_SAN ? "sansan" : "hangxin");
        } catch (Exception e) {
            log.warn("{}单机查询失败: {}", source.getLabel(), e.getMessage());
            data = Collections.emptyList();
        }
        ApiResult<List<UnifiedAircraftResponse>> result = ApiResult.success(data);
        SourceInfo info = new SourceInfo(data.size(), data.isEmpty() ? "未返回数据" : SUCCESS);
        if (source == DataSource.HANGXIN) {
            result.setHangxin(info);
        } else {
            result.setSansan(info);
        }
        return result;
    }

    private BaseExternalClient clientFor(DataSource source) {
        return source == DataSource.SAN_SAN ? sanSanClient : hangxinClient;
    }

    /** 将外部返回的 raw data（预期为 List<Map>）转为统一响应行列表 */
    @SuppressWarnings("unchecked")
    private List<UnifiedAircraftResponse> parseAircraftList(Object raw, String source) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof List) {
            List<Object> list = (List<Object>) raw;
            return list.stream()
                    .map(item -> {
                        if (item instanceof Map) {
                            Map<String, Object> map = (Map<String, Object>) item;
                            return "sansan".equals(source)
                                    ? UnifiedAircraftResponse.fromSanSan(map)
                                    : UnifiedAircraftResponse.fromHangxin(map);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        if (raw instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) raw;
            UnifiedAircraftResponse r = "sansan".equals(source)
                    ? UnifiedAircraftResponse.fromSanSan(map)
                    : UnifiedAircraftResponse.fromHangxin(map);
            return r != null ? Collections.singletonList(r) : Collections.emptyList();
        }
        return Collections.emptyList();
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
