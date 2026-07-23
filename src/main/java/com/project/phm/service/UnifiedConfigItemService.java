package com.project.phm.service;

import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedConfigRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 统一单机构型查询编排服务。
 *
 * <p>data 为异构列表：[本地配置列表, 633返回, 航新返回]，各源结果独立放入。</p>
 */
@Service
public class UnifiedConfigItemService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedConfigItemService.class);
    private static final String SUCCESS = "success";

    private final RestTemplate supportRestTemplate;
    private final AircraftConfigService aircraftConfigService;
    private final String configPath;

    public UnifiedConfigItemService(RestTemplate supportRestTemplate,
                                    AircraftConfigService aircraftConfigService,
                                    @Value("${support-system.paths.getDzgxxx}") String configPath) {
        this.supportRestTemplate = supportRestTemplate;
        this.aircraftConfigService = aircraftConfigService;
        this.configPath = configPath;
    }

    public ApiResult<List<Object>> queryConfigItems(UnifiedConfigRequest request) {
        CompletableFuture<SourceRawResult> localFuture   = queryLocalSafe(request);
        CompletableFuture<SourceRawResult> sanSanFuture   = queryExternalSafe("633", request);
        CompletableFuture<SourceRawResult> hangxinFuture  = queryExternalSafe("航新", request);

        CompletableFuture.allOf(localFuture, sanSanFuture, hangxinFuture).join();

        SourceRawResult local   = localFuture.join();
        SourceRawResult sanSan  = sanSanFuture.join();
        SourceRawResult hangxin = hangxinFuture.join();

        log.info("三方构型查询完成: 本地={}, 633={}, 航新={}",
                local.message, sanSan.message, hangxin.message);

        List<Object> dataList = new ArrayList<>();
        if (local.data instanceof List) {
            dataList.addAll((List<?>) local.data);
        } else if (local.data != null) {
            dataList.add(local.data);
        }
        if (sanSan.data != null)  dataList.add(sanSan.data);
        if (hangxin.data != null) dataList.add(hangxin.data);

        int localTotal = local.data instanceof List ? ((List<?>) local.data).size()
                : (local.data != null ? 1 : 0);

        ApiResult<List<Object>> result = ApiResult.success(dataList);
        result.setLocal(new SourceInfo(localTotal, local.message));
        result.setSansan(new SourceInfo(sanSan.data != null ? 1 : 0, sanSan.message));
        result.setHangxin(new SourceInfo(hangxin.data != null ? 1 : 0, hangxin.message));
        return result;
    }

    private CompletableFuture<SourceRawResult> queryLocalSafe(UnifiedConfigRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (request.getAirplaneType() == null || request.getAirplaneType().isEmpty()) {
                    return new SourceRawResult(null, "未提供机型");
                }
                Object items = aircraftConfigService.listItems(request.getAirplaneType());
                return new SourceRawResult(items, SUCCESS);
            } catch (Exception e) {
                log.warn("本地构型查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "本地查询失败: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<SourceRawResult> queryExternalSafe(String sourceName, UnifiedConfigRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = UriComponentsBuilder.fromPath(configPath)
                        .queryParam("page", request.getPage() != null ? request.getPage() : 1)
                        .queryParam("rows", request.getRows() != null ? request.getRows() : 10)
                        .build().toUriString();
                Object data = supportRestTemplate.getForObject(url, Object.class);
                return new SourceRawResult(data, data != null ? SUCCESS : sourceName + "未返回数据");
            } catch (Exception e) {
                log.warn("{}构型查询失败: {}", sourceName, e.getMessage());
                return new SourceRawResult(null, sourceName + "查询失败: " + e.getMessage());
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
