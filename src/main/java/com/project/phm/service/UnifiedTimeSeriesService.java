package com.project.phm.service;

import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedTimeSeriesRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 统一时序数据查询编排服务。
 *
 * <p>data 为异构列表：[{本地CSV文件(base64)}, {633返回}, {航新返回}]。</p>
 */
@Service
public class UnifiedTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedTimeSeriesService.class);
    private static final String SUCCESS = "success";
    private static final String TS_PATH = "/processing/data/querySorties";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final CsvService csvService;

    public UnifiedTimeSeriesService(HangxinSortieClient hangxinClient,
                                    SanSanSortieClient sanSanClient,
                                    CsvService csvService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.csvService = csvService;
    }

    public ApiResult<List<Object>> queryTimeSeries(UnifiedTimeSeriesRequest request) {
        CompletableFuture<SourceRawResult> localFuture    = queryLocalSafe(request);
        CompletableFuture<SourceRawResult> sanSanFuture   = querySanSanSafe(request);
        CompletableFuture<SourceRawResult> hangxinFuture  = queryHangxinSafe(request);

        CompletableFuture.allOf(localFuture, sanSanFuture, hangxinFuture).join();

        SourceRawResult local   = localFuture.join();
        SourceRawResult sanSan  = sanSanFuture.join();
        SourceRawResult hangxin = hangxinFuture.join();

        log.info("时序查询完成: 本地={}, 633={}, 航新={}",
                local.message, sanSan.message, hangxin.message);

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

    /** 本地查询：调用 CsvService 导出 CSV → base64 编码 */
    private CompletableFuture<SourceRawResult> queryLocalSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (request.getTableName() == null || request.getTableName().isEmpty()) {
                    return new SourceRawResult(null, "未提供 tableName");
                }
                if (request.getColumns() == null || request.getColumns().isEmpty()) {
                    return new SourceRawResult(null, "未提供 columns");
                }

                List<String> columnNames = Arrays.asList(request.getColumns().split("\\s*,\\s*"));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                csvService.exportCsvColumns(request.getTableName(), columnNames, bos);
                byte[] csvBytes = bos.toByteArray();

                String base64Content = Base64.getEncoder().encodeToString(csvBytes);

                Map<String, Object> localResult = new LinkedHashMap<>();
                localResult.put("tableName", request.getTableName());
                localResult.put("columns", request.getColumns());
                localResult.put("fileBase64", base64Content);
                localResult.put("fileLength", csvBytes.length);

                return new SourceRawResult(localResult, SUCCESS);
            } catch (Exception e) {
                log.warn("本地时序数据查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "本地查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceRawResult> querySanSanSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object data = sanSanClient.postForRawData(TS_PATH, request.toSanSanParams());
                return new SourceRawResult(data, data != null ? SUCCESS : "633未返回数据");
            } catch (Exception e) {
                log.warn("633时序查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "633查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceRawResult> queryHangxinSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object data = hangxinClient.postForRawData(TS_PATH, request.toHangxinParams());
                return new SourceRawResult(data, data != null ? SUCCESS : "航新未返回数据");
            } catch (Exception e) {
                log.warn("航新时序查询失败: {}", e.getMessage());
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
