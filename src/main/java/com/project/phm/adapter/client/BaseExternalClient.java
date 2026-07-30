package com.project.phm.adapter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.service.PlatformConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 外来平台 API 调用的基础封装。
 *
 * <p>base URL 运行时从 DB 动态解析，无配置时抛 IllegalStateException
 * 由上层编排服务捕获并记入 SourceInfo.message。</p>
 */
public abstract class BaseExternalClient {

    private static final Logger log = LoggerFactory.getLogger(BaseExternalClient.class);
    protected static final String SORTIE_PATH = "/processing/data/noPage/list";
    protected static final String MODEL_PATH  = "/configuration/airplane/type/list";

    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;
    protected final PlatformConfigService configService;

    protected BaseExternalClient(RestTemplate restTemplate, ObjectMapper objectMapper,
                                 PlatformConfigService configService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.configService = configService;
    }

    /** 构建完整请求 URL，无配置时抛异常 */
    private String buildUrl(String path) {
        String base = configService.getFullBaseUrl(getPlatformName());
        if (base == null || base.isEmpty()) {
            throw new IllegalStateException("[" + getPlatformName() + "] 未配置 base URL，请先录入平台配置");
        }
        return base + path;
    }

    // ==================== 架次查询 ====================

    public List<ExternalSortieData> querySorties(Map<String, Object> params) {
        String json = restTemplate.postForObject(buildUrl(SORTIE_PATH), params, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        return parseSortieResponse(json);
    }

    // ==================== 机型查询 ====================

    public List<ExternalModelData> queryModels(Map<String, Object> params) {
        String url = UriComponentsBuilder.fromHttpUrl(buildUrl(MODEL_PATH))
                .queryParam("airplaneType", params.getOrDefault("airplaneType", ""))
                .build().toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台机型接口返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        return parseModelResponse(json);
    }

    // ==================== 通用 POST（原始 data） ====================

    public Object postForRawData(String path, Object body) {
        String fullUrl = buildUrl(path);
        String json = restTemplate.postForObject(fullUrl, body, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] POST 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        return parseRawData(json);
    }

    /** POST 请求，返回原始 JSON 字符串（由调用方自行解析） */
    public String postForRawJson(String path, Object body) {
        String fullUrl = buildUrl(path);
        String json = restTemplate.postForObject(fullUrl, body, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] POST 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        return json;
    }

    // ==================== 通用 GET（原始 data） ====================

    public Object queryForRawData(String path, Map<String, Object> params) {
        String url = UriComponentsBuilder.fromHttpUrl(buildUrl(path))
                .queryParam("airplaneType", params.getOrDefault("airplaneType", ""))
                .queryParam("airplaneNum", params.getOrDefault("airplaneNum", ""))
                .build().toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        return parseRawData(json);
    }

    // ==================== JSON 解析 ====================

    private Object parseRawData(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(dataNode, Object.class);
        } catch (JsonProcessingException e) {
            log.error("[{}] JSON解析失败: {}", getPlatformName(), e.getMessage(), e);
            return null;
        }
    }

    private List<ExternalSortieData> parseSortieResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("[{}] 外来平台返回异常状态码: code={}, message={}",
                        getPlatformName(), code, root.path("message").asText());
                return Collections.emptyList();
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return Collections.emptyList();
            }
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ExternalSortieData.class);
            if (dataNode.isArray()) {
                return objectMapper.treeToValue(dataNode, listType);
            } else if (dataNode.isObject()) {
                ExternalSortieData single = objectMapper.treeToValue(dataNode, ExternalSortieData.class);
                return Collections.singletonList(single);
            }
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("[{}] JSON 解析失败: {}", getPlatformName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<ExternalModelData> parseModelResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return Collections.emptyList();
            }
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ExternalModelData.class);
            if (dataNode.isArray()) {
                return objectMapper.treeToValue(dataNode, listType);
            } else if (dataNode.isObject()) {
                ExternalModelData single = objectMapper.treeToValue(dataNode, ExternalModelData.class);
                return Collections.singletonList(single);
            }
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("[{}] 机型JSON解析失败: {}", getPlatformName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    protected abstract String getPlatformName();
}
