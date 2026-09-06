package com.project.phm.service;

import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedAircraftRequest;
import com.project.phm.adapter.dto.UnifiedAircraftResponse;
import com.project.phm.entity.Aircraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 统一单机查询编排服务。
 *
 * <p>并发调用三个数据源，将各源结果全部放入列表（不做去重合并），每条记录标记来源。</p>
 */
@Service
public class UnifiedAircraftService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAircraftService.class);
    private static final String SUCCESS = "success";
    private static final String AIRCRAFT_PATH = "/configuration/airplane/number/list";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final AircraftConfigService aircraftConfigService;

    public UnifiedAircraftService(HangxinSortieClient hangxinClient,
                                  SanSanSortieClient sanSanClient,
                                  AircraftConfigService aircraftConfigService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.aircraftConfigService = aircraftConfigService;
    }

    public ApiResult<List<UnifiedAircraftResponse>> queryAircraft(UnifiedAircraftRequest request) {
        CompletableFuture<List<UnifiedAircraftResponse>> localFuture   = queryLocalSafe(request);
        CompletableFuture<List<UnifiedAircraftResponse>> sanSanFuture  = querySanSanSafe(request);
        CompletableFuture<List<UnifiedAircraftResponse>> hangxinFuture = queryHangxinSafe(request);

        CompletableFuture.allOf(localFuture, sanSanFuture, hangxinFuture).join();

        List<UnifiedAircraftResponse> local   = localFuture.join();
        List<UnifiedAircraftResponse> sanSan  = sanSanFuture.join();
        List<UnifiedAircraftResponse> hangxin = hangxinFuture.join();

        log.info("三方单机查询完成: 本地={}, 633={}, 航新={}",
                local.size(), sanSan.size(), hangxin.size());

        // 直接拼接，不做去重合并
        List<UnifiedAircraftResponse> dataList = new ArrayList<>();
        dataList.addAll(local);
        dataList.addAll(sanSan);
        dataList.addAll(hangxin);

        ApiResult<List<UnifiedAircraftResponse>> result = ApiResult.success(dataList);
        boolean hasNum = request.getAirplaneNum() != null && !request.getAirplaneNum().isEmpty();
        result.setLocal(new SourceInfo(local.size(),
                local.isEmpty() ? (hasNum ? "本机未找到该单机" : "本机无单机数据") : SUCCESS));
        result.setSansan(new SourceInfo(sanSan.size(), sanSan.isEmpty() ? "633未返回数据" : SUCCESS));
        result.setHangxin(new SourceInfo(hangxin.size(), hangxin.isEmpty() ? "航新未返回数据" : SUCCESS));
        return result;
    }

    private CompletableFuture<List<UnifiedAircraftResponse>> queryLocalSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String aircraftNum = request.getAirplaneNum();
                String airplaneType = request.getAirplaneType();
                if (aircraftNum == null || aircraftNum.isEmpty()) {
                    // 未指定机号：列出本地全部单机（可按机型过滤），行为与航新/633 一致
                    return aircraftConfigService.listPlanes(airplaneType).stream()
                            .map(UnifiedAircraftResponse::fromLocal)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
                Aircraft plane = aircraftConfigService.getPlane(aircraftNum);
                if (plane == null) {
                    return Collections.emptyList();
                }
                // 同时指定机型时校验一致性，不一致视为未命中
                if (airplaneType != null && !airplaneType.isEmpty()
                        && !airplaneType.equals(plane.getModelCode())) {
                    return Collections.emptyList();
                }
                UnifiedAircraftResponse r = UnifiedAircraftResponse.fromLocal(plane);
                return r != null ? Collections.singletonList(r) : Collections.emptyList();
            } catch (Exception e) {
                log.warn("本地单机查询失败: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<UnifiedAircraftResponse>> querySanSanSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object raw = sanSanClient.queryForRawData(AIRCRAFT_PATH, request.toSanSanParams());
                return parseAircraftList(raw, "sansan");
            } catch (Exception e) {
                log.warn("633单机查询失败: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<UnifiedAircraftResponse>> queryHangxinSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object raw = hangxinClient.queryForRawData(AIRCRAFT_PATH, request.toHangxinParams());
                return parseAircraftList(raw, "hangxin");
            } catch (Exception e) {
                log.warn("航新单机查询失败: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
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
}
