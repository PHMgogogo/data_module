package com.project.phm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedConfigRequest;
import com.project.phm.adapter.dto.UnifiedConfigResponse;
import com.project.phm.entity.ConfigItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 统一单机构型查询编排服务。
 *
 * <p>并发查询三个数据源，将各源结果全部放入列表（不做去重合并），每条记录标记来源。</p>
 */
@Service
public class UnifiedConfigItemService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedConfigItemService.class);
    private static final String SUCCESS = "success";

    private final RestTemplate supportRestTemplate;
    private final AircraftConfigService aircraftConfigService;
    private final PlatformConfigService platformConfigService;
    private final ObjectMapper objectMapper;
    private final String configPath;

    public UnifiedConfigItemService(RestTemplate supportRestTemplate,
                                    AircraftConfigService aircraftConfigService,
                                    PlatformConfigService platformConfigService,
                                    ObjectMapper objectMapper,
                                    @Value("${support-system.paths.getDzgxxx}") String configPath) {
        this.supportRestTemplate = supportRestTemplate;
        this.aircraftConfigService = aircraftConfigService;
        this.platformConfigService = platformConfigService;
        this.objectMapper = objectMapper;
        this.configPath = configPath;
    }

    /** 来源名称 → 平台名称映射（兼容统一查询用的 sansan/hangxin 标识） */
    private static String platformName(String source) {
        if ("633".equals(source) || "sansan".equals(source)) return "633服务";
        if ("航新".equals(source) || "hangxin".equals(source)) return "航新服务";
        return source;
    }

    public ApiResult<List<UnifiedConfigResponse>> queryConfigItems(UnifiedConfigRequest request) {
        CompletableFuture<List<UnifiedConfigResponse>> localFuture   = queryLocalSafe(request);
        CompletableFuture<List<UnifiedConfigResponse>> sanSanFuture   = queryExternalSafe("sansan", request);
        CompletableFuture<List<UnifiedConfigResponse>> hangxinFuture  = queryExternalSafe("hangxin", request);

        CompletableFuture.allOf(localFuture, sanSanFuture, hangxinFuture).join();

        List<UnifiedConfigResponse> local   = localFuture.join();
        List<UnifiedConfigResponse> sanSan  = sanSanFuture.join();
        List<UnifiedConfigResponse> hangxin = hangxinFuture.join();

        log.info("三方构型查询完成: 本地={}, 633={}, 航新={}",
                local.size(), sanSan.size(), hangxin.size());

        // 直接拼接，不做去重合并
        List<UnifiedConfigResponse> dataList = new ArrayList<>();
        dataList.addAll(local);
        dataList.addAll(sanSan);
        dataList.addAll(hangxin);

        ApiResult<List<UnifiedConfigResponse>> result = ApiResult.success(dataList);
        result.setLocal(new SourceInfo(local.size(), local.isEmpty() ? "本地无构型数据" : SUCCESS));
        result.setSansan(new SourceInfo(sanSan.size(), sanSan.isEmpty() ? "633未返回数据" : SUCCESS));
        result.setHangxin(new SourceInfo(hangxin.size(), hangxin.isEmpty() ? "航新未返回数据" : SUCCESS));
        return result;
    }

    private CompletableFuture<List<UnifiedConfigResponse>> queryLocalSafe(UnifiedConfigRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String modelCode = request.getModelCode();
                // 未指定机型：默认列出本地全部机型的构型项目，行为与航新/633 一致
                List<ConfigItem> items = (modelCode == null || modelCode.isEmpty())
                        ? aircraftConfigService.listAllItems()
                        : aircraftConfigService.listItems(modelCode);
                return items.stream()
                        .map(UnifiedConfigResponse::fromLocal)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("本地构型查询失败: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    /** 外部构型查询（使用各自平台的 base URL + 公共路径） */
    private CompletableFuture<List<UnifiedConfigResponse>> queryExternalSafe(String sourceName, UnifiedConfigRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String platName = platformName(sourceName);
                String baseUrl = platformConfigService.getFullBaseUrl(platName);
                if (baseUrl == null || baseUrl.isEmpty()) {
                    log.warn("{}未配置 base URL", platName);
                    return Collections.emptyList();
                }
                UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + configPath)
                        .queryParam("page", request.getPageNum() != null ? request.getPageNum() : 1)
                        .queryParam("rows", request.getPageSize() != null ? request.getPageSize() : 10);
                // 指定机型时按 modelCode 过滤外源构型；不传则返回外源全量
                if (request.getModelCode() != null && !request.getModelCode().isEmpty()) {
                    urlBuilder = urlBuilder.queryParam("modelCode", request.getModelCode());
                }
                String fullUrl = urlBuilder.build().toUriString();
                log.info("[外来平台调用] {} 请求方式: GET, URL: {}, 参数: page={}(Integer), rows={}(Integer){}",
                        platName, fullUrl,
                        request.getPageNum() != null ? request.getPageNum() : 1,
                        request.getPageSize() != null ? request.getPageSize() : 10,
                        (request.getModelCode() != null && !request.getModelCode().isEmpty())
                                ? ", modelCode=" + request.getModelCode() + "(String)" : "");

                String json = supportRestTemplate.getForObject(fullUrl, String.class);
                if (json == null || json.isEmpty()) {
                    return Collections.emptyList();
                }
                log.info("[外来平台调用] {} 请求方式: GET, URL: {} 原始返回: {}", platName, fullUrl, json);

                return parseExternalConfig(json, sourceName);
            } catch (Exception e) {
                log.warn("{}构型查询失败: {}", sourceName, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    /** 解析外部构型接口返回的 JSON，提取 data.rows 并转为统一响应 */
    @SuppressWarnings("unchecked")
    private List<UnifiedConfigResponse> parseExternalConfig(String json, String source) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("外部构型接口返回异常状态码: code={}", code);
                return Collections.emptyList();
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return Collections.emptyList();
            }
            JsonNode rowsNode = dataNode.path("rows");
            if (rowsNode.isMissingNode() || !rowsNode.isArray()) {
                return Collections.emptyList();
            }
            List<UnifiedConfigResponse> result = new ArrayList<>();
            for (JsonNode row : rowsNode) {
                Map<String, Object> map = objectMapper.treeToValue(row, Map.class);
                UnifiedConfigResponse r = UnifiedConfigResponse.fromExternal(map, source);
                if (r != null) result.add(r);
            }
            return result;
        } catch (Exception e) {
            log.warn("外部构型JSON解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
