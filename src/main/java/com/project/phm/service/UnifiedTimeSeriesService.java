package com.project.phm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedTimeSeriesRequest;
import com.project.phm.adapter.dto.UnifiedTimeSeriesResponse;
import com.project.phm.adapter.dto.UnifiedTimeSeriesResponse.ParameterEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 统一时序数据查询编排服务。
 *
 * <p>将三个数据源的时序数据统一为 {@link UnifiedTimeSeriesResponse} 格式。
 * 本地 CSV 数据作为 base64 编码的 Map 额外返回。</p>
 */
@Service
public class UnifiedTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedTimeSeriesService.class);
    private static final String SUCCESS = "success";
    private static final String TS_PATH = "/processing/data/querySorties";

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final CsvService csvService;
    private final ObjectMapper objectMapper;

    public UnifiedTimeSeriesService(HangxinSortieClient hangxinClient,
                                    SanSanSortieClient sanSanClient,
                                    CsvService csvService,
                                    ObjectMapper objectMapper) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.csvService = csvService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询时序数据。
     *
     * <p>data 列表结构：[{本地CSV(base64)}, {633-统一时序}, {航新-统一时序}]。</p>
     */
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

    /** 本地查询：调用 CsvService.queryTimeSeries → 统一格式 */
    private CompletableFuture<SourceRawResult> queryLocalSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (request.getTableName() == null || request.getTableName().isEmpty()) {
                    return new SourceRawResult(null, "未提供 tableName");
                }

                // 优先使用 paralist（List），向后兼容 columns（逗号分隔 String）
                List<String> paralist = request.getParalist();
                if (paralist == null || paralist.isEmpty()) {
                    String columnsStr = request.getColumns();
                    if (columnsStr == null || columnsStr.isEmpty()) {
                        return new SourceRawResult(null, "未提供 paralist 或 columns");
                    }
                    paralist = Arrays.asList(columnsStr.split("\\s*,\\s*"));
                }

                UnifiedTimeSeriesResponse resp = csvService.queryTimeSeries(
                        request.getTableName(), paralist);
                return new SourceRawResult(resp, SUCCESS);
            } catch (Exception e) {
                log.warn("本地时序数据查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "本地查询失败: " + e.getMessage());
            }
        });
    }

    /** 633 时序查询：解析响应为统一格式 */
    private CompletableFuture<SourceRawResult> querySanSanSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = sanSanClient.postForRawJson(TS_PATH, request.toSanSanParams());
                if (json == null) {
                    return new SourceRawResult(null, "633未返回数据");
                }
                UnifiedTimeSeriesResponse resp = parseSanSanResponse(json);
                return new SourceRawResult(resp, resp != null ? SUCCESS : "633解析失败");
            } catch (Exception e) {
                log.warn("633时序查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "633查询失败: " + e.getMessage());
            }
        });
    }

    /** 航新时序查询：解析响应为统一格式 */
    private CompletableFuture<SourceRawResult> queryHangxinSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = hangxinClient.postForRawJson(TS_PATH, request.toHangxinParams());
                if (json == null) {
                    return new SourceRawResult(null, "航新未返回数据");
                }
                UnifiedTimeSeriesResponse resp = parseHangxinResponse(json);
                return new SourceRawResult(resp, resp != null ? SUCCESS : "航新解析失败");
            } catch (Exception e) {
                log.warn("航新时序查询失败: {}", e.getMessage());
                return new SourceRawResult(null, "航新查询失败: " + e.getMessage());
            }
        });
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 633 时序响应。
     *
     * <p>633 结构：data[].datalist 为扁平 Map，
     * 其中 "时间戳" 键对应时间轴，其他键为参数名。</p>
     */
    private UnifiedTimeSeriesResponse parseSanSanResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("633时序查询返回异常状态码: code={}", code);
                return null;
            }
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) return null;

            // 取第一条记录的 datalist
            JsonNode datalist = dataArray.get(0).path("datalist");
            if (datalist.isMissingNode() || !datalist.isObject()) return null;

            // 提取时间戳列（键名为 "时间戳" 或 "timestamp"，不区分）
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

            // 读取时间轴
            JsonNode timeArray = datalist.get(timeKey);
            List<Long> timestamps = new ArrayList<>();
            if (timeArray.isArray()) {
                for (JsonNode t : timeArray) {
                    timestamps.add(t.asLong());
                }
            } else {
                // 单值
                timestamps.add(timeArray.asLong());
            }

            // 读取各参数值序列
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
            resp.setTimestamps(timestamps);
            resp.setParameters(parameters);
            return resp;
        } catch (Exception e) {
            log.warn("633时序响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析航新时序响应。
     *
     * <p>航新结构：data.timestamp[] + data.parameters[{name, dataType, data[]}]</p>
     */
    private UnifiedTimeSeriesResponse parseHangxinResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(0);
            if (code != 200) {
                log.warn("航新时序查询返回异常状态码: code={}", code);
                return null;
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) return null;

            // 时间轴（航新使用纳秒级时间戳，统一转为毫秒）
            JsonNode tsArray = dataNode.path("timestamp");
            List<Long> timestamps = new ArrayList<>();
            if (tsArray.isArray()) {
                for (JsonNode t : tsArray) {
                    long nanos = t.asLong();
                    timestamps.add(nanos / 1_000_000L); // 纳秒 → 毫秒
                }
            }

            // 参数列表
            JsonNode paramsArray = dataNode.path("parameters");
            List<ParameterEntry> parameters = new ArrayList<>();
            if (paramsArray.isArray()) {
                for (JsonNode p : paramsArray) {
                    String name = p.path("name").asText();
                    JsonNode data = p.path("data");
                    List<Object> values = new ArrayList<>();
                    if (data.isArray()) {
                        for (JsonNode v : data) {
                            values.add(valueToObject(v));
                        }
                    }
                    parameters.add(ParameterEntry.of(name, values));
                }
            }

            UnifiedTimeSeriesResponse resp = new UnifiedTimeSeriesResponse();
            resp.setTimestamps(timestamps);
            resp.setParameters(parameters);
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

    private static class SourceRawResult {
        final Object data;
        final String message;

        SourceRawResult(Object data, String message) {
            this.data = data;
            this.message = message;
        }
    }
}
