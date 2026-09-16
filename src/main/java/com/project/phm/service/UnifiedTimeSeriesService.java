package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ApiResult;
import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.adapter.dto.SourceInfo;
import com.project.phm.adapter.dto.UnifiedTimeSeriesRequest;
import com.project.phm.adapter.dto.UnifiedTimeSeriesResponse;
import com.project.phm.entity.ConfigDataMapping;
import com.project.phm.mapper.ConfigDataMappingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 时序数据查询编排服务。
 *
 * <p>三个数据源的时序数据统一为 {@link UnifiedTimeSeriesResponse} 格式：</p>
 * <ul>
 *   <li>本地：按 {@code sortieId} 从 config_data_mapping 查出该架次关联的 csv_xxx 表</li>
 *   <li>航新 / 633：按机号 + 架次号先查三方架次接口拿到 startTime / endTime
 *       （转成 {@code 2026-07-23T10:30:00.000+08:00} 形式，北京时间），再查三方时序接口。
 *       请求体里传了 {@code startTime} / {@code endTime} 时以传入值为准，不再用架次接口的值</li>
 * </ul>
 *
 * <p>按机号 / 架次过滤后，实际只有归属平台会返回数据，因此新接口
 * {@link #querySingleTimeSeries} 只挑第一个有数据的源返回，不做拼接。</p>
 */
@Service
public class UnifiedTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedTimeSeriesService.class);
    private static final String SUCCESS = "success";
    private static final String TS_PATH = "/processing/data/querySorties";

    /** 三方时序接口要求的时间格式（北京时间，东八区） */
    private static final DateTimeFormatter ISO_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** 北京时区偏移（东八区） */
    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);

    /** 三方架次接口返回的无时区时间格式（按北京时间解释） */
    private static final DateTimeFormatter[] LOCAL_TIME_IN = {
            // 空格分隔、秒后小数位数不定：2026-01-01 10:00:00 / 10:00:00.00 / 10:00:00.123456789
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter(),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final CsvService csvService;
    private final ObjectMapper objectMapper;
    private final ConfigDataMappingMapper configDataMappingMapper;

    public UnifiedTimeSeriesService(HangxinSortieClient hangxinClient,
                                    SanSanSortieClient sanSanClient,
                                    CsvService csvService,
                                    ObjectMapper objectMapper,
                                    ConfigDataMappingMapper configDataMappingMapper) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.csvService = csvService;
        this.objectMapper = objectMapper;
        this.configDataMappingMapper = configDataMappingMapper;
    }

    // ==================== 新接口：只返回有数据的那个源 ====================

    /**
     * 查询时序数据，返回单个平台的结果。
     *
     * <p>并发查本地、航新、633：本地有数据优先，其次是航新、633。
     * 三个源都没有数据时返回空结构（timestamps / parameters 均为空列表）。</p>
     */
    public UnifiedTimeSeriesResponse querySingleTimeSeries(UnifiedTimeSeriesRequest request) {
        String paralistDesc = request.getParalist() == null ? "-" : String.join(",", request.getParalist());
        log.info("时序查询: sortieId={}, aircraftNumber={}, sortieNumber={}, paralist={}, samplingRate={}",
                request.getSortieId(), request.getAircraftNumber(), request.getSortieNumber(),
                paralistDesc, request.samplingRateOrDefault());

        CompletableFuture<UnifiedTimeSeriesResponse> localFuture =
                CompletableFuture.supplyAsync(() -> queryLocalBySortie(request));
        CompletableFuture<UnifiedTimeSeriesResponse> hangxinFuture =
                CompletableFuture.supplyAsync(() -> queryHangxinSingle(request));
        CompletableFuture<UnifiedTimeSeriesResponse> sanSanFuture =
                CompletableFuture.supplyAsync(() -> querySanSanSingle(request));

        CompletableFuture.allOf(localFuture, hangxinFuture, sanSanFuture).join();

        UnifiedTimeSeriesResponse local = localFuture.join();
        UnifiedTimeSeriesResponse hangxin = hangxinFuture.join();
        UnifiedTimeSeriesResponse sanSan = sanSanFuture.join();

        log.info("时序查询结果: 本地={}, 航新={}, 633={}",
                desc(local), desc(hangxin), desc(sanSan));

        if (local != null && local.hasData()) return local;
        if (hangxin != null && hangxin.hasData()) return hangxin;
        if (sanSan != null && sanSan.hasData()) return sanSan;
        return UnifiedTimeSeriesResponse.empty();
    }

    private static String desc(UnifiedTimeSeriesResponse resp) {
        return resp == null ? "无数据" : (resp.hasData() ? resp.getParameters().size() + "个参数" : "无数据");
    }

    // ==================== 本地 ====================

    /**
     * 本地查询：按架次ID查 config_data_mapping 得到该架次关联的 csv_xxx 表名。
     *
     * <p>一个架次关联多张表时取最新关联的一张并记日志。</p>
     */
    private UnifiedTimeSeriesResponse queryLocalBySortie(UnifiedTimeSeriesRequest request) {
        try {
            Long sortieId = request.getSortieId();
            if (sortieId == null) {
                return null;
            }
            List<ConfigDataMapping> mappings = configDataMappingMapper.selectList(
                    Wrappers.<ConfigDataMapping>lambdaQuery()
                            .eq(ConfigDataMapping::getSortieId, sortieId)
                            .orderByDesc(ConfigDataMapping::getCreatedAt));
            if (mappings == null || mappings.isEmpty()) {
                log.warn("本地架次 {} 没有关联任何 csv 数据表", sortieId);
                return null;
            }
            if (mappings.size() > 1) {
                log.warn("本地架次 {} 关联了 {} 张 csv 表，取最新的一张", sortieId, mappings.size());
            }
            // config_data_mapping.csv_table_name 存的是不带 csv_ 前缀的 deviceName
            String tableName = "csv_" + mappings.get(0).getCsvTableName();
            return csvService.queryTimeSeries(tableName, request.getParalist());
        } catch (Exception e) {
            log.warn("本地时序数据查询失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 航新 ====================

    /**
     * 航新时序查询：先用机号 / 架次号查架次接口拿 sortieId 与起止时间，再查时序接口。
     *
     * <p>架次接口未命中时无法定位架次，直接跳过（不发起时序查询）。</p>
     */
    private UnifiedTimeSeriesResponse queryHangxinSingle(UnifiedTimeSeriesRequest request) {
        try {
            ExternalSortieData sortie = findSortie(hangxinClient, request, "航新");
            if (sortie == null) {
                log.info("航新未查到该架次，跳过失时序查询");
                return null;
            }

            Map<String, Object> params = new HashMap<>();
            putIfNotNull(params, "sortieId", sortie.getId());
            putIfNotNull(params, "startTimestamp", resolveTime(request.getStartTime(), sortie.getStartTime()));
            putIfNotNull(params, "endTimestamp", resolveTime(request.getEndTime(), sortie.getEndTime()));
            params.put("samplingRate", request.samplingRateOrDefault());
            if (request.getParalist() != null && !request.getParalist().isEmpty()) {
                params.put("parameters", request.getParalist());
            }

            String json = hangxinClient.postForRawJson(TS_PATH, params);
            return json == null ? null : UnifiedTimeSeriesResponse.fromHangxinJson(json, objectMapper);
        } catch (Exception e) {
            log.warn("航新时序查询失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 633 ====================

    /**
     * 633 时序查询：startTime / endTime 同样取自三方架次接口（命中时），其余参数直接透传。
     *
     * <p>633 不接受 samplingRate，也不使用 sortieId（用 airplaneNum + flightNum 定位架次）。</p>
     */
    private UnifiedTimeSeriesResponse querySanSanSingle(UnifiedTimeSeriesRequest request) {
        try {
            ExternalSortieData sortie = findSortie(sanSanClient, request, "633");

            Map<String, Object> params = new HashMap<>();
            putIfNotNull(params, "airplaneType", request.getAirplaneType());
            putIfNotNull(params, "airplaneNum", request.getAircraftNumber());
            putIfNotNull(params, "flightNum", request.getSortieNumber());
            if (request.getParalist() != null && !request.getParalist().isEmpty()) {
                params.put("Paralist", request.getParalist());
            }
            String startTime = resolveTime(request.getStartTime(),
                    sortie == null ? null : sortie.getStartTime());
            String endTime = resolveTime(request.getEndTime(),
                    sortie == null ? null : sortie.getEndTime());
            putIfNotNull(params, "startTime", startTime);
            putIfNotNull(params, "endTime", endTime);

            String json = sanSanClient.postForRawJson(TS_PATH, params);
            return json == null ? null : UnifiedTimeSeriesResponse.fromSanSanJson(json, objectMapper);
        } catch (Exception e) {
            log.warn("633时序查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** 按机号 + 架次号查三方架次接口，取第一条命中记录（失败时返回 null） */
    private ExternalSortieData findSortie(BaseExternalClient client,
                                          UnifiedTimeSeriesRequest request, String label) {
        if (!request.hasExternalIdentifier()) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneNum", request.getAircraftNumber());
        putIfNotNull(params, "flightNum", request.getSortieNumber());

        List<ExternalSortieData> sorties = client.querySorties(params);
        if (sorties == null || sorties.isEmpty()) {
            return null;
        }
        for (ExternalSortieData s : sorties) {
            if (s != null) {
                log.info("{}架次命中: id={}, startTime={}, endTime={}",
                        label, s.getId(), s.getStartTime(), s.getEndTime());
                return s;
            }
        }
        return null;
    }

    /**
     * 取生效时间：调用方传了就用调用方传的，否则用三方架次接口返回的，两者都统一转成北京时间 ISO。
     *
     * <p>调用方传的格式比三方宽松（空格或 ISO 分隔都行），转换失败时原样透传并告警。</p>
     */
    static String resolveTime(String fromRequest, String fromSortie) {
        if (fromRequest != null && !fromRequest.trim().isEmpty()) {
            return toChinaIso(fromRequest);
        }
        return toChinaIso(fromSortie);
    }

    /**
     * 三方时间转为 {@code 2026-07-23T10:30:00.000+08:00} 形式（北京时间）。
     *
     * <p>无时区输入（三方架次返回的 {@code 2026-07-23 10:30:00}）按北京时间解释，原值即北京时间；
     * 带时区/UTC 后缀的输入先换算到北京时间再格式化；无法识别的格式原样返回。</p>
     */
    static String toChinaIso(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter fmt : LOCAL_TIME_IN) {
            try {
                return LocalDateTime.parse(value, fmt)
                        .atOffset(CHINA_OFFSET)
                        .format(ISO_OUT);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式
            }
        }
        try {
            Instant instant = OffsetDateTime.parse(value).toInstant();
            return instant.atOffset(CHINA_OFFSET).format(ISO_OUT);
        } catch (DateTimeParseException ignored) {
            // 换下一种格式
        }
        try {
            Instant instant = Instant.parse(value);
            return instant.atOffset(CHINA_OFFSET).format(ISO_OUT);
        } catch (DateTimeParseException ignored) {
            log.warn("时间格式无法识别，原样透传给三方: {}", raw);
            return value;
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            if (!((String) value).isEmpty()) {
                map.put(key, value);
            }
        } else {
            map.put(key, value);
        }
    }

    // ==================== 旧接口（/unified/timeseries/query，已废弃） ====================

    /**
     * 查询三个数据源的时序数据。
     *
     * <p>data 列表结构：[本地, 633, 航新]，各源无数据时不出现在列表中。</p>
     *
     * @deprecated 改用 {@link #querySingleTimeSeries}（对外接口 {@code POST /csv/query-timeseries}），
     *             只返回实际有数据的那个源。
     */
    @Deprecated
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

    /** 本地分支：按 sortieId 定位关联的 csv 表 */
    private CompletableFuture<SourceRawResult> queryLocalSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            if (request.getSortieId() == null) {
                return new SourceRawResult(null, "未提供 sortieId");
            }
            UnifiedTimeSeriesResponse resp = queryLocalBySortie(request);
            return resp == null
                    ? new SourceRawResult(null, "本地未找到该架次关联的数据表")
                    : new SourceRawResult(resp, SUCCESS);
        });
    }

    private CompletableFuture<SourceRawResult> querySanSanSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            UnifiedTimeSeriesResponse resp = querySanSanSingle(request);
            return new SourceRawResult(resp, resp != null ? SUCCESS : "633未返回数据");
        });
    }

    private CompletableFuture<SourceRawResult> queryHangxinSafe(UnifiedTimeSeriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            UnifiedTimeSeriesResponse resp = queryHangxinSingle(request);
            return new SourceRawResult(resp, resp != null ? SUCCESS : "航新未返回数据");
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
