package com.project.phm.service;

import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedAircraftRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 统一单机查询编排服务。
 *
 * <p>data 为异构列表：[本机实体, 633返回, 航新返回]，各源结果独立放入，不做字段合并。</p>
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

    public ApiResult<List<Object>> queryAircraft(UnifiedAircraftRequest request) {
        // 并发查询三源
        CompletableFuture<SourceRawResult> localFuture   = queryLocalSafe(request);
        CompletableFuture<SourceRawResult> sanSanFuture  = querySanSanSafe(request);
        CompletableFuture<SourceRawResult> hangxinFuture = queryHangxinSafe(request);

        CompletableFuture.allOf(localFuture, sanSanFuture, hangxinFuture).join();

        SourceRawResult local   = localFuture.join();
        SourceRawResult sanSan  = sanSanFuture.join();
        SourceRawResult hangxin = hangxinFuture.join();

        log.info("三方单机查询完成: 本地={}, 633={}, 航新={}",
                local.message, sanSan.message, hangxin.message);

        // 构造异构列表
        List<Object> dataList = new ArrayList<>();
        if (local.data != null)   dataList.add(local.data);
        if (sanSan.data != null)  dataList.add(sanSan.data);
        if (hangxin.data != null) dataList.add(hangxin.data);

        ApiResult<List<Object>> result = ApiResult.success(dataList);
        result.setLocal(new SourceInfo(local.data != null ? 1 : 0, local.message));
        result.setSansan(new SourceInfo(sanSan.data != null ? 1 : 0, sanSan.message));
        result.setHangxin(new SourceInfo(hangxin.data != null ? 1 : 0, hangxin.message));
        return result;
    }

    private CompletableFuture<SourceRawResult> queryLocalSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (request.getAirplaneNum() == null || request.getAirplaneNum().isEmpty()) {
                    return new SourceRawResult(null, "未提供机号");
                }
                Object plane = aircraftConfigService.getPlane(request.getAirplaneNum());
                if (plane == null) {
                    return new SourceRawResult(null, "本机未找到该单机");
                }
                return new SourceRawResult(plane, SUCCESS);
            } catch (Exception e) {
                log.warn("本地单机查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "本地查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceRawResult> querySanSanSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object data = sanSanClient.queryForRawData(AIRCRAFT_PATH, request.toSanSanParams());
                return new SourceRawResult(data, data != null ? SUCCESS : "633未返回数据");
            } catch (Exception e) {
                log.warn("633单机查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "633查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceRawResult> queryHangxinSafe(UnifiedAircraftRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object data = hangxinClient.queryForRawData(AIRCRAFT_PATH, request.toHangxinParams());
                return new SourceRawResult(data, data != null ? SUCCESS : "航新未返回数据");
            } catch (Exception e) {
                log.warn("航新单机查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "航新查询失败: " + e.getMessage());
            }
        });
    }

    private static class SourceRawResult {
        final Object data;
        final String message;

        SourceRawResult(Object data, String message) {
            this.data = data;
            this.message = message;
        }
    }
}
