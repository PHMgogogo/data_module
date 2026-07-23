package com.project.phm.adapter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.ExternalSortieData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 外来平台 API 调用的基础封装。
 * 子类只需提供 RestTemplate 和 base URL，公共的 HTTP 调用和 JSON 解析由本类处理。
 */
public abstract class BaseExternalClient {

    private static final Logger log = LoggerFactory.getLogger(BaseExternalClient.class);
    protected static final String SORTIE_PATH = "/processing/data/noPage/list";
    protected static final String MODEL_PATH  = "/configuration/airplane/type/list";

    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;

    protected BaseExternalClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用外来平台的架次查询接口。
     * <p>HTTP/IO 异常直接向上抛出，由编排服务层（UnifiedSortieService）统一捕获并记录原因。</p>
     *
     * @param params 请求参数 map
     * @return 解析后的架次数据列表
     * @throws RuntimeException RestTemplate 调用异常（连接超时、DNS 解析失败等）
     */
    public List<ExternalSortieData> querySorties(Map<String, Object> params) {
        String json = restTemplate.postForObject(SORTIE_PATH, params, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        return parseResponse(json);
    }

    /**
     * 调用外来平台的机型查询接口。
     *
     * @param params 请求参数 map
     * @return 解析后的机型数据列表
     */
    public List<ExternalModelData> queryModels(Map<String, Object> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(MODEL_PATH);
        params.forEach(builder::queryParam);
        String url = builder.build().toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台机型接口返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        return parseModelResponse(json);
    }

    /** 解析机型 JSON 响应 */
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

    /**
     * 通用 GET 查询，返回原始 data 段（用于结构不确定的接口）。
     *
     * @param path   API 路径
     * @param params 查询参数
     * @return 原始 data 的 parsed 对象（Map / List / null）
     */
    public Object queryForRawData(String path, Map<String, Object> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        params.forEach(builder::queryParam);
        String url = builder.build().toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 接口返回空响应: {}", getPlatformName(), path);
            return null;
        }
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

    /**
     * 解析外来平台返回的 JSON。
     * 期望格式: { "code": 200, "message": "success", "data": [...] }
     * data 可以是对象数组，也可以是单个对象（会被包装成单元素列表）。
     */
    private List<ExternalSortieData> parseResponse(String json) {
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
                // data 是单个对象，包装成列表
                ExternalSortieData single = objectMapper.treeToValue(dataNode, ExternalSortieData.class);
                return Collections.singletonList(single);
            }
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("[{}] JSON 解析失败: {}", getPlatformName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** 子类返回平台名称，用于日志标识 */
    protected abstract String getPlatformName();
}
