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
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(buildUrl(SORTIE_PATH));
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object value = e.getValue();
                if (value == null) continue;
                if (value instanceof List) {
                    // List 值（如 633 的 ParaList）按同名参数重复拼接
                    for (Object item : (List<?>) value) {
                        if (item != null) builder.queryParam(e.getKey(), item);
                    }
                } else {
                    builder.queryParam(e.getKey(), value);
                }
            }
        }
        String url = builder.build().toUriString();
        printRequest("GET", url, params);
        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        printRawResponse("GET", url, json);
        return parseSortieResponse(json);
    }

    // ==================== 机型查询 ====================

    public List<ExternalModelData> queryModels(Map<String, Object> params) {
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(buildUrl(MODEL_PATH));
        if (params != null) {
            // 缺省视为 null：仅在携带值时拼入 airplaneType，避免向第三方传空串
            Object airplaneType = params.get("airplaneType");
            if (airplaneType != null) {
                urlBuilder.queryParam("airplaneType", airplaneType);
            }
        }
        String url = urlBuilder.build().toUriString();
        printRequest("GET", url, params);

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台机型接口返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        printRawResponse("GET", url, json);
        return parseModelResponse(json);
    }

    // ==================== 通用 POST（原始 data） ====================

    public Object postForRawData(String path, Object body) {
        String fullUrl = buildUrl(path);
        printRequest("POST", fullUrl, body);
        String json = restTemplate.postForObject(fullUrl, body, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] POST 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        printRawResponse("POST", fullUrl, json);
        return parseRawData(json);
    }

    /** POST 请求，返回原始 JSON 字符串（由调用方自行解析） */
    public String postForRawJson(String path, Object body) {
        String fullUrl = buildUrl(path);
        printRequest("POST", fullUrl, body);
        String json = restTemplate.postForObject(fullUrl, body, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] POST 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        printRawResponse("POST", fullUrl, json);
        return json;
    }

    // ==================== 通用 GET（原始 data） ====================

    public Object queryForRawData(String path, Map<String, Object> params) {
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(buildUrl(path));
        if (params != null) {
            // 缺省视为 null：仅在携带值时拼入 airplaneType/airplaneNum，避免向第三方传空串
            Object airplaneType = params.get("airplaneType");
            Object airplaneNum = params.get("airplaneNum");
            if (airplaneType != null) {
                urlBuilder.queryParam("airplaneType", airplaneType);
            }
            if (airplaneNum != null) {
                urlBuilder.queryParam("airplaneNum", airplaneNum);
            }
        }
        String url = urlBuilder.build().toUriString();
        printRequest("GET", url, params);

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
        printRawResponse("GET", url, json);
        return parseRawData(json);
    }

    // ==================== HTTP 调用日志 ====================

    /** 打印请求：输出可直接复制执行的 curl 命令 */
    private void printRequest(String method, String url, Object params) {
        StringBuilder cmd = new StringBuilder("curl -X ")
                .append(method).append(" \"").append(url).append('"');
        // GET/DELETE 无 body；POST/PUT 把请求参数序列化为 JSON body
        if (!"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            cmd.append(" \\\n  -H \"Content-Type: application/json\" \\\n  -d '")
                    .append(toRequestBodyJson(params)).append('\'');
        }
        log.info("[外来平台调用] {} 请求命令:\n{}", getPlatformName(), cmd);
    }

    /** 打印外来平台返回的原始响应 */
    private void printRawResponse(String method, String url, String rawJson) {
        log.info("[外来平台调用] {} 请求方式: {}, URL: {} 原始返回: {}",
                getPlatformName(), method, url, rawJson);
    }

    /** 请求参数序列化为 JSON body，null/序列化失败时回退为空对象或原文 */
    private String toRequestBodyJson(Object params) {
        if (params == null) return "{}";
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return String.valueOf(params);
        }
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
