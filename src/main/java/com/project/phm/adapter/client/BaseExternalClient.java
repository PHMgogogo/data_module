package com.project.phm.adapter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.project.phm.adapter.dto.ExternalAircraftData;
import com.project.phm.adapter.dto.ExternalConfigPage;
import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.entity.ExternalPlatform;
import com.project.phm.service.PlatformConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
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
    protected static final String AIRCRAFT_PATH = "/configuration/airplane/number/list";
    protected static final String PARAMETERS_PATH = "/coding/parameter/getParametersByVersion";

    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;
    protected final PlatformConfigService configService;

    protected BaseExternalClient(RestTemplate restTemplate, ObjectMapper objectMapper,
                                 PlatformConfigService configService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.configService = configService;
    }

    /**
     * 普通业务请求使用的端口配置 key，默认取通用 key {@code port}。
     *
     * <p>航新服务重写为 {@code port1}。</p>
     */
    protected String getPortKey() {
        return ExternalPlatform.KEY_PORT;
    }

    /**
     * 查询构型（getDzgxxx）使用的端口配置 key，默认与 {@link #getPortKey()} 相同。
     *
     * <p>航新服务重写为 {@code port2}。</p>
     */
    protected String getConfigPortKey() {
        return getPortKey();
    }

    /** 构建完整请求 URL（普通业务端口），无配置时抛异常 */
    private String buildUrl(String path) {
        return buildUrl(path, getPortKey());
    }

    /**
     * 构建完整请求 URL，端口取 config 中指定的 key。
     *
     * <p>不同业务可能走不同端口，如航新服务的构型查询走 {@code port2}，
     * 其余请求走 {@code port1}。</p>
     */
    private String buildUrl(String path, String portKey) {
        String base = configService.getFullBaseUrl(getPlatformName(), portKey);
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

    // ==================== 参数字段名查询 ====================

    /**
     * 按参数组ID查询该组的参数字段名列表。
     *
     * <p>架次接口返回的 {@code parameterGroupId} 是查时序数据的必需参数，
     * 先经此接口拿到字段名，再据此查时序数据。</p>
     *
     * @param parameterGroupId 架次接口返回的参数组ID
     * @return 字段名列表；未配置平台 / 空响应 / 解析失败时返回空列表
     */
    public List<String> queryParameterNames(String parameterGroupId) {
        if (parameterGroupId == null || parameterGroupId.isEmpty()) {
            return Collections.emptyList();
        }
        String url = UriComponentsBuilder.fromHttpUrl(buildUrl(PARAMETERS_PATH))
                .queryParam("parameterGroupId", parameterGroupId)
                .build().toUriString();
        printRequest("GET", url, null);

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台参数接口返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        printRawResponse("GET", url, json);
        return parseParameterNames(json);
    }

    /**
     * 解析参数接口返回的 JSON，取出 {@code data[].parameterName}。
     *
     * <p>只取字段名：{@code parameterGroupId} / {@code system} 对调用方无意义，
     * 下发回去反而让前端多一层解包。</p>
     */
    private List<String> parseParameterNames(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("[{}] 外来平台参数接口返回异常状态码: code={}, msg={}",
                        getPlatformName(), code, root.path("msg").asText());
                return Collections.emptyList();
            }
            JsonNode dataNode = root.path("data");
            if (!dataNode.isArray()) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<>(dataNode.size());
            for (JsonNode row : dataNode) {
                String name = row.path("parameterName").asText(null);
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            log.error("[{}] 参数JSON解析失败: {}", getPlatformName(), e.getMessage(), e);
            return Collections.emptyList();
        }
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

    // ==================== 单机查询 ====================

    public List<ExternalAircraftData> queryAircrafts(Map<String, Object> params) {
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(buildUrl(AIRCRAFT_PATH));
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
            log.warn("[{}] 外来平台单机接口返回空响应", getPlatformName());
            return Collections.emptyList();
        }
        printRawResponse("GET", url, json);
        return parseAircraftResponse(json);
    }

    // ==================== 构型查询 ====================

    /**
     * 构型查询：返回单页的 {@code data.total} + {@code data.rows} 原始行。
     *
     * <p>需要 {@code total} 是因为三方 rows 有上限（现用 10），只取第一页会漏数据，
     * 调用方要据此翻页取全。</p>
     *
     * @param configPath 构型接口路径，取自 {@code support-system.paths.getDzgxxx}
     */
    public ExternalConfigPage queryConfigItems(String configPath, Map<String, Object> params) {
        // 构型查询可能走独立端口（如航新服务走 port2）
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(buildUrl(configPath, getConfigPortKey()));
        if (params != null) {
            // 缺省视为 null：仅在携带值时拼入，避免向第三方传空串
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (e.getValue() != null) {
                    urlBuilder.queryParam(e.getKey(), e.getValue());
                }
            }
        }
        String url = urlBuilder.build().toUriString();
        printRequest("GET", url, params);

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isEmpty()) {
            log.warn("[{}] 外来平台构型接口返回空响应", getPlatformName());
            return ExternalConfigPage.empty();
        }
        printRawResponse("GET", url, json);
        return parseConfigPage(json);
    }

    /**
     * 解析构型接口返回的 JSON，取出数据行。
     *
     * <p>兼容两种返回形态：</p>
     * <ul>
     *   <li>{@code {"code":200,"data":{"total":n,"rows":[...]}}}（分页包装）</li>
     *   <li>{@code {"code":200,"data":[...]}}（data 直接是数组，无 total）</li>
     * </ul>
     *
     * <p>老接口把行放在 {@code data.rows} 并给 total 用于翻页；新接口 data 本身就是数组，
     * 此时 total 取数组长度，调用方靠"不满一页"兜底终止翻页。</p>
     */
    private ExternalConfigPage parseConfigPage(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("[{}] 外来平台构型接口返回异常状态码: code={}, message={}",
                        getPlatformName(), code, root.path("message").asText());
                return ExternalConfigPage.empty();
            }
            JsonNode dataNode = root.path("data");
            // 形态一：data 直接是数组
            if (dataNode.isArray()) {
                return toConfigPage(dataNode, dataNode.size());
            }
            // 形态二：data.rows + data.total
            JsonNode rowsNode = dataNode.path("rows");
            if (!rowsNode.isArray()) {
                return ExternalConfigPage.empty();
            }
            return toConfigPage(rowsNode, dataNode.path("total").asInt(0));
        } catch (Exception e) {
            log.error("[{}] 构型JSON解析失败: {}", getPlatformName(), e.getMessage(), e);
            return ExternalConfigPage.empty();
        }
    }

    /** 把 JSON 数组行转成 ExternalConfigPage */
    private ExternalConfigPage toConfigPage(JsonNode rowsNode, int total) {
        List<Map<String, Object>> rows = new ArrayList<>(rowsNode.size());
        for (JsonNode row : rowsNode) {
            rows.add(objectMapper.convertValue(row, new TypeReference<Map<String, Object>>() {}));
        }
        return new ExternalConfigPage(total, rows);
    }

    // ==================== 通用 POST（原始 data） ====================

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

    /**
     * 打印请求：先输出带参数的调用行，再输出可直接复制执行的 curl 命令。
     *
     * <p>GET 的参数走 query string，URL 里已能看出，这里单独再列一栏是为了让
     * 出站参数一眼可见，不必去 URL 里找。</p>
     */
    private void printRequest(String method, String url, Object params) {
        log.info("[外来平台调用] {} 请求方式: {}, URL: {}, 参数: {}",
                getPlatformName(), method, url, toParamsText(params));

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

    /** 请求参数文本：无参数时显示「无」，否则序列化为 JSON */
    private String toParamsText(Object params) {
        if (params == null) {
            return "无";
        }
        if (params instanceof Map && ((Map<?, ?>) params).isEmpty()) {
            return "无";
        }
        return toRequestBodyJson(params);
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

    private List<ExternalAircraftData> parseAircraftResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return Collections.emptyList();
            }
            CollectionType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ExternalAircraftData.class);
            if (dataNode.isArray()) {
                return objectMapper.treeToValue(dataNode, listType);
            } else if (dataNode.isObject()) {
                ExternalAircraftData single = objectMapper.treeToValue(dataNode, ExternalAircraftData.class);
                return Collections.singletonList(single);
            }
            return Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.error("[{}] 单机JSON解析失败: {}", getPlatformName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    protected abstract String getPlatformName();
}
