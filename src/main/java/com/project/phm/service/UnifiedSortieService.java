package com.project.phm.service;

import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.*;
import com.project.phm.adapter.merge.SortieDataMerger;
import com.project.phm.entity.Sortie;
import com.project.phm.mapper.SortieMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统一架次查询编排服务。
 *
 * <p>按请求里的机型路由到唯一数据源（本地 / 航新 / 633）：命中三方只查那一个平台，
 * 未命中路由或命中平台不可达（保活失败）时回落本地。不再并发扫描三个源。</p>
 */
@Service
public class UnifiedSortieService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSortieService.class);
    private static final String SUCCESS = "success";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final SortieMapper sortieMapper;
    private final SortieDataMerger merger;
    private final PlatformRouteService routeService;

    public UnifiedSortieService(HangxinSortieClient hangxinClient,
                                SanSanSortieClient sanSanClient,
                                SortieMapper sortieMapper,
                                SortieDataMerger merger,
                                PlatformRouteService routeService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.sortieMapper = sortieMapper;
        this.merger = merger;
        this.routeService = routeService;
    }

    /**
     * 按机型路由查询架次，返回统一响应。
     *
     * <p>机型为路由唯一依据：命中三方则只向该平台发一次请求，否则查本地表。</p>
     */
    public ApiResult<List<UnifiedSortieResponse>> querySortiesSafe(UnifiedSortieRequest request) {
        String modelKey = requireModel(request.getAirplaneType());
        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地查询架次", modelKey);
            }
            return localResult(request);
        }
        return externalResult(request, source);
    }

    /** 本地分支：查本地 sortie 表 */
    private ApiResult<List<UnifiedSortieResponse>> localResult(UnifiedSortieRequest request) {
        List<Sortie> localData;
        try {
            localData = sortieMapper.selectList(request.toLocalQuery());
        } catch (Exception e) {
            log.warn("本地查询失败: {}", e.getMessage());
            localData = Collections.emptyList();
        }
        List<UnifiedSortieResponse> merged = merger.fromLocal(localData);
        ApiResult<List<UnifiedSortieResponse>> result = ApiResult.success(merged);
        result.setLocal(new SourceInfo(localData.size(), SUCCESS));
        return result;
    }

    /** 三方分支：只向命中的那一个平台发请求 */
    private ApiResult<List<UnifiedSortieResponse>> externalResult(UnifiedSortieRequest request,
                                                                 DataSource source) {
        List<ExternalSortieData> data;
        try {
            data = clientFor(source).querySorties(paramsFor(request, source));
        } catch (Exception e) {
            log.warn("{}查询失败: {}", source.getLabel(), e.getMessage());
            data = Collections.emptyList();
        }
        boolean hangxin = source == DataSource.HANGXIN;
        List<UnifiedSortieResponse> merged = hangxin
                ? merger.fromHangxin(data)
                : merger.fromSanSan(data);
        ApiResult<List<UnifiedSortieResponse>> result = ApiResult.success(merged);
        SourceInfo info = new SourceInfo(data.size(), SUCCESS);
        if (hangxin) {
            result.setHangxin(info);
        } else {
            result.setSansan(info);
        }
        return result;
    }

    /** 请求参数还原成目标平台能认的形态：航新用 airplaneType，633 需要裸 airplaneType */
    private Map<String, Object> paramsFor(UnifiedSortieRequest request, DataSource source) {
        String realType = routeService.realAirplaneType(request.getAirplaneType());
        String effective = realType != null ? realType : request.getAirplaneType();
        UnifiedSortieRequest routed = new UnifiedSortieRequest();
        routed.setAirplaneType(effective);
        routed.setAirplaneNum(request.getAirplaneNum());
        routed.setStartTime(request.getStartTime());
        routed.setEndTime(request.getEndTime());
        routed.setFlightNum(request.getFlightNum());
        routed.setParaList(request.getParaList());
        routed.setFileName(request.getFileName());
        routed.setFileType(request.getFileType());
        routed.setSortieId(request.getSortieId());
        return source == DataSource.SAN_SAN ? routed.toSanSanParams() : routed.toHangxinParams();
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
