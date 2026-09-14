package com.project.phm.adapter.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 统一时序数据响应 — 三个数据源的格式统一。
 *
 * <pre>
 * {
 *   "timestamps": [1783296000000, ...],
 *   "parameters": [
 *     {"name": "ALTITUDE", "values": [1000.5, ...]}
 *   ]
 * }
 * </pre>
 */
@Schema(description = "统一时序数据响应")
public class UnifiedTimeSeriesResponse {

    @Schema(description = "时间轴（毫秒时间戳列表）")
    private List<Long> timestamps;

    @Schema(description = "参数集合")
    private List<ParameterEntry> parameters;

    private static final Logger log = LoggerFactory.getLogger(UnifiedTimeSeriesResponse.class);

    /** 空响应：三个源都没数据时的兜底返回 */
    public static UnifiedTimeSeriesResponse empty() {
        UnifiedTimeSeriesResponse resp = new UnifiedTimeSeriesResponse();
        resp.timestamps = new ArrayList<>();
        resp.parameters = new ArrayList<>();
        return resp;
    }

    /** 是否真的带回了数据（参数非空即视为有数据） */
    public boolean hasData() {
        return parameters != null && !parameters.isEmpty();
    }

    public List<Long> getTimestamps() { return timestamps; }
    public void setTimestamps(List<Long> timestamps) { this.timestamps = timestamps; }
    public List<ParameterEntry> getParameters() { return parameters; }
    public void setParameters(List<ParameterEntry> parameters) { this.parameters = parameters; }

    // ==================== 外来平台响应解析 ====================

    /**
     * 解析 633 时序响应。
     *
     * <p>633 结构：data[].datalist 为扁平 Map，其中 "时间戳" 键对应时间轴，其他键为参数名。</p>
     */
    public static UnifiedTimeSeriesResponse fromSanSanJson(String json, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt(0) != 200) {
                log.warn("633时序查询返回异常状态码: code={}", root.path("code").asInt(0));
                return null;
            }
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) return null;

            JsonNode datalist = dataArray.get(0).path("datalist");
            if (datalist.isMissingNode() || !datalist.isObject()) return null;

            // 提取时间戳列（键名为 "时间戳" 或 "timestamp"，不区分大小写）
            String timeKey = null;
            List<String> paramKeys = new ArrayList<>();
            Iterator<String> fieldNames = datalist.fieldNames();
            while (fieldNames.hasNext()) {
                String fn = fieldNames.next();
                if ("时间戳".equals(fn) || "timestamp".equalsIgnoreCase(fn)) {
                    timeKey = fn;
                } else {
                    paramKeys.add(fn);
                }
            }
            if (timeKey == null) return null;

            JsonNode timeArray = datalist.get(timeKey);
            List<Long> timestamps = new ArrayList<>();
            if (timeArray.isArray()) {
                for (JsonNode t : timeArray) {
                    timestamps.add(t.asLong());
                }
            } else {
                timestamps.add(timeArray.asLong());
            }

            List<ParameterEntry> parameters = new ArrayList<>();
            for (String pk : paramKeys) {
                JsonNode valArray = datalist.get(pk);
                List<Object> values = new ArrayList<>();
                if (valArray.isArray()) {
                    for (JsonNode v : valArray) {
                        values.add(valueToObject(v));
                    }
                } else {
                    values.add(valueToObject(valArray));
                }
                parameters.add(ParameterEntry.of(pk, values));
            }

            UnifiedTimeSeriesResponse resp = new UnifiedTimeSeriesResponse();
            resp.timestamps = timestamps;
            resp.parameters = parameters;
            return resp;
        } catch (Exception e) {
            log.warn("633时序响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析航新时序响应。
     *
     * <p>航新结构：data.timestamp[] + data.parameters[{name, dataType, data[]}]，
     * 时间戳为纳秒，解析时统一转为毫秒。</p>
     */
    public static UnifiedTimeSeriesResponse fromHangxinJson(String json, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt(0) != 200) {
                log.warn("航新时序查询返回异常状态码: code={}", root.path("code").asInt(0));
                return null;
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) return null;

            JsonNode tsArray = dataNode.path("timestamp");
            List<Long> timestamps = new ArrayList<>();
            if (tsArray.isArray()) {
                for (JsonNode t : tsArray) {
                    timestamps.add(t.asLong() / 1_000_000L); // 纳秒 → 毫秒
                }
            }

            JsonNode paramsArray = dataNode.path("parameters");
            List<ParameterEntry> parameters = new ArrayList<>();
            if (paramsArray.isArray()) {
                for (JsonNode p : paramsArray) {
                    List<Object> values = new ArrayList<>();
                    JsonNode data = p.path("data");
                    if (data.isArray()) {
                        for (JsonNode v : data) {
                            values.add(valueToObject(v));
                        }
                    }
                    parameters.add(ParameterEntry.of(p.path("name").asText(), values));
                }
            }

            UnifiedTimeSeriesResponse resp = new UnifiedTimeSeriesResponse();
            resp.timestamps = timestamps;
            resp.parameters = parameters;
            return resp;
        } catch (Exception e) {
            log.warn("航新时序响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** JsonNode 转 Java 对象 */
    private static Object valueToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    @Schema(description = "参数条目")
    public static class ParameterEntry {

        @Schema(description = "参数名", example = "ALTITUDE")
        private String name;

        @Schema(description = "参数序列值（与时间轴一一对应）")
        private List<Object> values;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Object> getValues() { return values; }
        public void setValues(List<Object> values) { this.values = values; }

        public static ParameterEntry of(String name, List<Object> values) {
            ParameterEntry e = new ParameterEntry();
            e.name = name;
            e.values = values;
            return e;
        }
    }
}
