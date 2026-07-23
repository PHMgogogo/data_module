package com.project.phm.service;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 统一机型查询编排服务。
 */
@Service
public class UnifiedModelService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedModelService.class);
    private static final String SUCCESS = "success";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final AircraftModelMapper modelMapper;
    private final ModelDataMerger merger;

    public UnifiedModelService(HangxinSortieClient hangxinClient,
                               SanSanSortieClient sanSanClient,
                               AircraftModelMapper modelMapper,
                               ModelDataMerger merger) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.modelMapper = modelMapper;
        this.merger = merger;
    }

    public ApiResult<List<UnifiedModelResponse>> queryModels(UnifiedModelRequest request) {
        CompletableFuture<List<ExternalModelData>> hangxinFuture = queryHangxin(request);
        CompletableFuture<List<ExternalModelData>> sanSanFuture  = querySanSan(request);
        CompletableFuture<List<AircraftModel>> localFuture = queryLocal(request);

        CompletableFuture.allOf(hangxinFuture, sanSanFuture, localFuture).join();

        List<ExternalModelData> hangxinData = safeJoin(hangxinFuture);
        List<ExternalModelData> sanSanData  = safeJoin(sanSanFuture);
        List<AircraftModel> localData = safeJoin(localFuture);

        List<UnifiedModelResponse> merged = merger.merge(hangxinData, sanSanData, localData);

        ApiResult<List<UnifiedModelResponse>> result = ApiResult.success(merged);
        result.setHangxin(new SourceInfo(hangxinData.size(), SUCCESS));
        result.setSansan(new SourceInfo(sanSanData.size(), SUCCESS));
        result.setLocal(new SourceInfo(localData.size(), SUCCESS));
        return result;
    }

    public ApiResult<List<UnifiedModelResponse>> queryModelsSafe(UnifiedModelRequest request) {
        CompletableFuture<SourceResult<ExternalModelData>> hangxinFuture = queryHangxinSafe(request);
        CompletableFuture<SourceResult<ExternalModelData>> sanSanFuture  = querySanSanSafe(request);
        CompletableFuture<SourceResult<AircraftModel>> localFuture = queryLocalSafe(request);

        CompletableFuture.allOf(hangxinFuture, sanSanFuture, localFuture).join();

        SourceResult<ExternalModelData> hangxin = hangxinFuture.join();
        SourceResult<ExternalModelData> sanSan  = sanSanFuture.join();
        SourceResult<AircraftModel> local = localFuture.join();

        log.info("三方机型查询完成: 航新={}({}), 633={}({}), 本地={}({})",
                hangxin.total, hangxin.message,
                sanSan.total,  sanSan.message,
                local.total,   local.message);

        List<UnifiedModelResponse> merged = merger.merge(hangxin.data, sanSan.data, local.data);

        ApiResult<List<UnifiedModelResponse>> result = ApiResult.success(merged);
        result.setHangxin(hangxin.toSourceInfo());
        result.setSansan(sanSan.toSourceInfo());
        result.setLocal(local.toSourceInfo());
        return result;
    }

    // ==================== 安全查询（带异常捕获） ====================

    private CompletableFuture<SourceResult<ExternalModelData>> queryHangxinSafe(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ExternalModelData> list = hangxinClient.queryModels(request.toExternalParams());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("航新机型查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "航新机型查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceResult<ExternalModelData>> querySanSanSafe(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ExternalModelData> list = sanSanClient.queryModels(request.toExternalParams());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("633机型查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "633机型查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceResult<AircraftModel>> queryLocalSafe(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<AircraftModel> list = modelMapper.selectList(request.toLocalQuery());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("本地机型查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "本地机型查询失败: " + e.getMessage());
            }
        });
    }

    // ==================== 常规查询 ====================

    private CompletableFuture<List<ExternalModelData>> queryHangxin(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() ->
                hangxinClient.queryModels(request.toExternalParams()));
    }

    private CompletableFuture<List<ExternalModelData>> querySanSan(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() ->
                sanSanClient.queryModels(request.toExternalParams()));
    }

    private CompletableFuture<List<AircraftModel>> queryLocal(UnifiedModelRequest request) {
        return CompletableFuture.supplyAsync(() ->
                modelMapper.selectList(request.toLocalQuery()));
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private <T> List<T> safeJoin(CompletableFuture<? extends List<?>> future) {
        try {
            return (List<T>) future.get();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static class SourceResult<T> {
        final List<T> data;
        final int total;
        final String message;

        SourceResult(List<T> data, int total, String message) {
            this.data = data;
            this.total = total;
            this.message = message;
        }

        SourceInfo toSourceInfo() {
            return new SourceInfo(total, message);
        }
    }
}
