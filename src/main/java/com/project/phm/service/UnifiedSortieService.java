package com.project.phm.service;

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
import java.util.concurrent.CompletableFuture;

/**
 * 统一架次查询编排服务。
 *
 * <p>并发调用三个数据源（航新、633、本地），合并结果后返回统一的响应。
 * 任一数据源失败不影响其他数据源，失败原因记入对应 SourceInfo.message。</p>
 */
@Service
public class UnifiedSortieService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSortieService.class);
    private static final String SUCCESS = "success";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final SortieMapper sortieMapper;
    private final SortieDataMerger merger;

    public UnifiedSortieService(HangxinSortieClient hangxinClient,
                                SanSanSortieClient sanSanClient,
                                SortieMapper sortieMapper,
                                SortieDataMerger merger) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.sortieMapper = sortieMapper;
        this.merger = merger;
    }

    /**
     * 查询并合并三个数据源的架次数据，返回包含数据和各来源元数据的响应。
     */
    public ApiResult<List<UnifiedSortieResponse>> querySorties(UnifiedSortieRequest request) {
        // 并发调用三个数据源
        CompletableFuture<List<ExternalSortieData>> hangxinFuture = queryHangxinAsync(request);
        CompletableFuture<List<ExternalSortieData>> sanSanFuture  = querySanSanAsync(request);
        CompletableFuture<List<Sortie>> localFuture = queryLocalAsync(request);

        CompletableFuture.allOf(hangxinFuture, sanSanFuture, localFuture).join();

        List<ExternalSortieData> hangxinData = hangxinFuture.join();
        List<ExternalSortieData> sanSanData  = sanSanFuture.join();
        List<Sortie> localData = localFuture.join();

        // 合并数据
        List<UnifiedSortieResponse> merged = merger.merge(hangxinData, sanSanData, localData);

        // 构造响应
        ApiResult<List<UnifiedSortieResponse>> result = ApiResult.success(merged);
        result.setHangxin(new SourceInfo(hangxinData.size(), SUCCESS));
        result.setSansan(new SourceInfo(sanSanData.size(), SUCCESS));
        result.setLocal(new SourceInfo(localData.size(), SUCCESS));
        return result;
    }

    /**
     * 查询并合并三个数据源的架次数据（带异常捕获），
     * 异常时失败源的 data 为空列表，message 记录原因。
     */
    public ApiResult<List<UnifiedSortieResponse>> querySortiesSafe(UnifiedSortieRequest request) {
        // 并发调用，各自捕获异常
        CompletableFuture<SourceResult<ExternalSortieData>> hangxinFuture = queryHangxinSafe(request);
        CompletableFuture<SourceResult<ExternalSortieData>> sanSanFuture  = querySanSanSafe(request);
        CompletableFuture<SourceResult<Sortie>> localFuture = queryLocalSafe(request);

        CompletableFuture.allOf(hangxinFuture, sanSanFuture, localFuture).join();

        SourceResult<ExternalSortieData> hangxin = hangxinFuture.join();
        SourceResult<ExternalSortieData> sanSan  = sanSanFuture.join();
        SourceResult<Sortie> local = localFuture.join();

        log.info("三方数据查询完成: 航新={}({}), 633={}({}), 本地={}({})",
                hangxin.total, hangxin.message,
                sanSan.total,  sanSan.message,
                local.total,   local.message);

        // 合并数据
        List<UnifiedSortieResponse> merged = merger.merge(
                hangxin.data, sanSan.data, local.data);

        // 构造响应
        ApiResult<List<UnifiedSortieResponse>> result = ApiResult.success(merged);
        result.setHangxin(hangxin.toSourceInfo());
        result.setSansan(sanSan.toSourceInfo());
        result.setLocal(local.toSourceInfo());
        return result;
    }

    // ==================== 安全查询（带异常捕获） ====================

    private CompletableFuture<SourceResult<ExternalSortieData>> queryHangxinSafe(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ExternalSortieData> list = hangxinClient.querySorties(request.toHangxinParams());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("航新查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "航新查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceResult<ExternalSortieData>> querySanSanSafe(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ExternalSortieData> list = sanSanClient.querySorties(request.toSanSanParams());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("633查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "633查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceResult<Sortie>> queryLocalSafe(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Sortie> list = sortieMapper.selectList(request.toLocalQuery());
                return new SourceResult<>(list, list.size(), SUCCESS);
            } catch (Exception e) {
                log.warn("本地查询失败: {}", e.getMessage());
                return new SourceResult<>(Collections.emptyList(), 0, "本地查询失败: " + e.getMessage());
            }
        });
    }

    // ==================== 常规查询（无异常捕获、简洁） ====================

    private CompletableFuture<List<ExternalSortieData>> queryHangxinAsync(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() ->
                hangxinClient.querySorties(request.toHangxinParams()));
    }

    private CompletableFuture<List<ExternalSortieData>> querySanSanAsync(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() ->
                sanSanClient.querySorties(request.toSanSanParams()));
    }

    private CompletableFuture<List<Sortie>> queryLocalAsync(UnifiedSortieRequest request) {
        return CompletableFuture.supplyAsync(() ->
                sortieMapper.selectList(request.toLocalQuery()));
    }

    // ==================== 泛型结果容器 ====================

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
