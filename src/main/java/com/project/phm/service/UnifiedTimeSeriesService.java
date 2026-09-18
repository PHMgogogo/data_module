package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.ExternalSortieData;
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

/**
 * 时序数据查询编排服务。
 *
 * <p>三个数据源的时序数据统一为 {@link UnifiedTimeSeriesResponse} 格式：</p>
 * <ul>
 *   <li>本地：按 {@code sortieId} 从 config_data_mapping 查出该架次关联的 csv_xxx 表</li>
 *   <li>航新 / 633：优先使用 /aircraft/sorties 返回后缓存的架次 ID 与 startTime / endTime
 *       （转成 {@code 2026-07-23T10:30:00.000+08:00} 形式，北京时间），再查三方时序接口。
 *       请求体里传了 {@code startTime} / {@code endTime} 时以传入值为准；缓存缺失才回查架次接口</li>
 * </ul>
 *
 * <p>新接口 {@link #querySingleTimeSeries} 走<b>定向单源</b>：请求里的 {@code sortieId}
 * 就是 {@code /aircraft/sorties} 下发的 {@code sortieKey}，拿它去 {@code PlatformRouteService}
 * 查出该架次归属哪个平台，然后只向那一个平台发请求 —— 不再三个源都打一遍。</p>
 *
 * <p>未命中索引（id 不存在，或对应机号尚未调用 {@code /aircraft/sorties} 加载）时返回空结构并告警。</p>
 */
@Service
public class UnifiedTimeSeriesService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedTimeSeriesService.class);
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
    private final PlatformRouteService routeService;

    public UnifiedTimeSeriesService(HangxinSortieClient hangxinClient,
                                    SanSanSortieClient sanSanClient,
                                    CsvService csvService,
                                    ObjectMapper objectMapper,
                                    ConfigDataMappingMapper configDataMappingMapper,
                                    PlatformRouteService routeService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.csvService = csvService;
        this.objectMapper = objectMapper;
        this.configDataMappingMapper = configDataMappingMapper;
        this.routeService = routeService;
    }

    // ==================== 新接口：只返回有数据的那个源 ====================

    /**
     * 查询时序数据：按请求里的机型路由到唯一归属平台，只向那一个平台发请求。
     *
     * <p>路由依据是请求里的 {@code airplaneType}（机型）—— 在 {@code PlatformRouteService}
     * 的机型 → 平台映射里解析归属。未命中路由、或命中的平台当前不可达（保活失败）时回落本地：
     * 用请求里的 {@code sortieId} 兜底按本地架次查。</p>
     *
     * <p>命中三方时，架次的定位信息（sortieKey / 机号 / 起止时间）优先取路由索引，
     * 索引缺失的项才回落到请求体。</p>
     */
    public UnifiedTimeSeriesResponse querySingleTimeSeries(UnifiedTimeSeriesRequest request) {
        String modelKey = request.getAirplaneType() == null ? null : request.getAirplaneType().trim();
        if (modelKey == null || modelKey.isEmpty()) {
            throw new IllegalArgumentException("机型不能为空");
        }
        String sortieKey = request.resolveSortieKey();
        String paralistDesc = request.getParalist() == null ? "-" : String.join(",", request.getParalist());
        log.info("时序查询: 机型={}, sortieId={}, paralist={}, samplingRate={}",
                modelKey, sortieKey, paralistDesc, request.samplingRateOrDefault());

        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，时序查询回落本地", modelKey);
            }
            Long localSortieId = parseLocalSortieId(sortieKey);
            return orEmpty(queryLocalBySortie(localSortieId, request.getParalist()));
        }

        if (sortieKey == null || sortieKey.isEmpty()) {
            log.warn("时序查询未提供 sortieKey，无法定位三方架次，返回空");
            return UnifiedTimeSeriesResponse.empty();
        }
        PlatformRouteService.SortieRef ref = routeService.resolveSortie(sortieKey);
        if (ref == null || ref.getSource() != source) {
            log.warn("架次 {} 未命中 {} 的架次路由，返回空", sortieKey, source.getLabel());
            return UnifiedTimeSeriesResponse.empty();
        }

        UnifiedTimeSeriesResponse resp = source == DataSource.HANGXIN
                ? queryHangxinSingle(request, ref)
                : querySanSanSingle(request, ref);
        log.info("时序查询结果: 平台={}, {}", source.getLabel(), desc(resp));
        return resp == null ? UnifiedTimeSeriesResponse.empty() : resp;
    }

    /** 查询结果为空时统一返回空结构 */
    private static UnifiedTimeSeriesResponse orEmpty(UnifiedTimeSeriesResponse resp) {
        return resp == null ? UnifiedTimeSeriesResponse.empty() : resp;
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
    private UnifiedTimeSeriesResponse queryLocalBySortie(Long sortieId, List<String> paralist) {
        try {
            if (sortieId == null) {
                log.warn("本地架次标识为空，跳过失时序查询");
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
            return csvService.queryTimeSeries(tableName, paralist);
        } catch (Exception e) {
            log.warn("本地时序数据查询失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 航新 ====================

    /**
     * 航新时序查询：优先使用 /aircraft/sorties 返回后缓存的架次 ID 与起止时间。
     *
     * <p>架次接口未命中时无法定位架次，直接跳过（不发起时序查询）。</p>
     */
    private UnifiedTimeSeriesResponse queryHangxinSingle(UnifiedTimeSeriesRequest request,
                                                         PlatformRouteService.SortieRef ref) {
        try {
            boolean needSortie = ref.getSortieKey() == null
                    || (request.getStartTime() == null && ref.getStartTime() == null)
                    || (request.getEndTime() == null && ref.getEndTime() == null);
            ExternalSortieData sortie = findSortieIfNeeded(hangxinClient, ref, "航新", needSortie);
            String sortieId = firstNonEmpty(ref.getSortieKey(),
                    sortie == null ? null : sortie.getId());
            if (sortieId == null) {
                log.info("航新未查到该架次，跳过失时序查询");
                return null;
            }

            Map<String, Object> params = new HashMap<>();
            putIfNotNull(params, "sortieId", sortieId);
            putIfNotNull(params, "startTimestamp", resolveTime(request.getStartTime(),
                    firstNonEmpty(ref.getStartTime(), sortie == null ? null : sortie.getStartTime())));
            putIfNotNull(params, "endTimestamp", resolveTime(request.getEndTime(),
                    firstNonEmpty(ref.getEndTime(), sortie == null ? null : sortie.getEndTime())));
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
     * 633 时序查询：startTime / endTime 优先取 /aircraft/sorties 缓存的架次时间。
     *
     * <p>633 不接受 samplingRate，也不使用 sortieId（用 airplaneNum + flightNum 定位架次）。</p>
     */
    private UnifiedTimeSeriesResponse querySanSanSingle(UnifiedTimeSeriesRequest request,
                                                        PlatformRouteService.SortieRef ref) {
        try {
            boolean needSortie = (request.getStartTime() == null && ref.getStartTime() == null)
                    || (request.getEndTime() == null && ref.getEndTime() == null);
            ExternalSortieData sortie = findSortieIfNeeded(sanSanClient, ref, "633", needSortie);

            Map<String, Object> params = new HashMap<>();
            // airplaneType 优先用索引里记下的真实值 —— 请求体里那个可能是 K4-WS19:6 合成码，
            // 发出去 633 必然查不到，还会让排查方向跑偏
            putIfNotNull(params, "airplaneType",
                    firstNonEmpty(ref.getAirplaneType(), resolveExternalAirplaneType(request.getAirplaneType())));
            putIfNotNull(params, "airplaneNum", firstNonEmpty(ref.getAirplaneNum(), request.getAircraftNumber()));
            putIfNotNull(params, "flightNum", ref.getFlightNum());
            if (request.getParalist() != null && !request.getParalist().isEmpty()) {
                params.put("Paralist", request.getParalist());
            }
            String startTime = resolveTime(request.getStartTime(),
                    firstNonEmpty(ref.getStartTime(), sortie == null ? null : sortie.getStartTime()));
            String endTime = resolveTime(request.getEndTime(),
                    firstNonEmpty(ref.getEndTime(), sortie == null ? null : sortie.getEndTime()));
            putIfNotNull(params, "startTime", startTime);
            putIfNotNull(params, "endTime", endTime);

            String json = sanSanClient.postForRawJson(TS_PATH, params);
            return json == null ? null : UnifiedTimeSeriesResponse.fromSanSanJson(json, objectMapper);
        } catch (Exception e) {
            log.warn("633时序查询失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 索引里已有完整架次信息时直接复用，缺字段时才回查三方架次接口。
     */
    private ExternalSortieData findSortieIfNeeded(BaseExternalClient client,
                                                  PlatformRouteService.SortieRef ref,
                                                  String label, boolean needed) {
        if (!needed) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneNum", ref.getAirplaneNum());
        putIfNotNull(params, "flightNum", ref.getFlightNum());
        if (params.isEmpty()) {
            log.warn("{}架次 {} 的索引里没有机号与架次号，跳过时序查询", label, ref);
            return null;
        }

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

    /** 本地路线下把统一标识转回主键；三方 id（UUID 形态）转不动时返回 null */
    private static Long parseLocalSortieId(String sortieKey) {
        if (sortieKey == null || sortieKey.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(sortieKey.trim());
        } catch (NumberFormatException e) {
            log.warn("架次标识 {} 不是本地数字主键，按本地查询必然落空", sortieKey);
            return null;
        }
    }

    /**
     * 把机型还原成三方能认的裸 {@code airplaneType}。
     *
     * <p>索引里有值时根本走不到这里；兜底时也只认「不含冒号」的值 ——
     * 合成码形如 {@code K4-WS19:6}，发出去三方必然查不到，宁可整个参数不发。</p>
     */
    private String resolveExternalAirplaneType(String fromRequest) {
        if (fromRequest == null || fromRequest.trim().isEmpty()) {
            return null;
        }
        String value = fromRequest.trim();
        String real = routeService.realAirplaneType(value);
        if (real != null) {
            return real;
        }
        if (value.indexOf(':') >= 0) {
            log.warn("机型 {} 是未登记的合成码，无法还原真实 airplaneType，本次不发送该参数", value);
            return null;
        }
        return value;
    }

    /** 取第一个非空白值；都为空时返回 null */
    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return (second != null && !second.trim().isEmpty()) ? second : null;
    }
}
