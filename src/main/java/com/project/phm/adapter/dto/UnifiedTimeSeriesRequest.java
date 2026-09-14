package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时序数据查询请求体。
 *
 * <p>只携带「定位一个架次」所需的标识 + 查询参数：</p>
 * <ul>
 *   <li>本地：{@code sortieId} → config_data_mapping 查出该架次关联的 csv_xxx 表</li>
 *   <li>航新 / 633：{@code aircraftNumber} → airplaneNum、{@code sortieNumber} → flightNum</li>
 * </ul>
 *
 * <p>startTime / endTime 不由调用方传入，由后端查询三方架次接口后自行推算。</p>
 */
@Schema(description = "时序数据查询请求")
public class UnifiedTimeSeriesRequest {

    @Schema(description = "本地架次ID（定位该架次关联的 csv_xxx 表，可空）", example = "1")
    private Long sortieId;

    @Schema(description = "机号（三方 → airplaneNum）", example = "B-1234")
    private String aircraftNumber;

    @Schema(description = "架次号（三方 → flightNum）", example = "CA1234-20260723")
    private String sortieNumber;

    @Schema(description = "机型（可选，三方 → airplaneType）", example = "B737-800")
    private String airplaneType;

    @Schema(description = "所查参数列表（本地 → 列名；633 → Paralist；航新 → parameters）",
            example = "[\"ALTITUDE\", \"SPEED\", \"N1\"]")
    private List<String> paralist;

    @Schema(description = "采样率，不传默认 10", example = "10")
    private String samplingRate;

    /** 采样率缺省值 */
    public static final String DEFAULT_SAMPLING_RATE = "10";

    public Long getSortieId() { return sortieId; }
    public void setSortieId(Long sortieId) { this.sortieId = sortieId; }
    public String getAircraftNumber() { return aircraftNumber; }
    public void setAircraftNumber(String aircraftNumber) { this.aircraftNumber = aircraftNumber; }
    public String getSortieNumber() { return sortieNumber; }
    public void setSortieNumber(String sortieNumber) { this.sortieNumber = sortieNumber; }
    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public List<String> getParalist() { return paralist; }
    public void setParalist(List<String> paralist) { this.paralist = paralist; }
    public String getSamplingRate() { return samplingRate; }
    public void setSamplingRate(String samplingRate) { this.samplingRate = samplingRate; }

    /** 采样率：未配置时取默认值 10 */
    public String samplingRateOrDefault() {
        return (samplingRate == null || samplingRate.isEmpty()) ? DEFAULT_SAMPLING_RATE : samplingRate;
    }

    /** 是否携带了可用于查询三方平台的机号 / 架次号 */
    public boolean hasExternalIdentifier() {
        return notEmpty(aircraftNumber) || notEmpty(sortieNumber);
    }

    /** 转为 633 API 的请求参数 */
    public Map<String, Object> toSanSanParams() {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneType", airplaneType);
        putIfNotNull(params, "airplaneNum", aircraftNumber);
        putIfNotNull(params, "flightNum", sortieNumber);
        putIfNotNull(params, "Paralist", paralist);
        return params;
    }

    /** 转为航新 API 的请求参数（querySorties 接口） */
    public Map<String, Object> toHangxinParams() {
        Map<String, Object> params = new HashMap<>();
        // 航新 querySorties 使用 sortieId（由架次号映射）
        putIfNotNull(params, "sortieId", sortieNumber);
        // 参数列表（航新字段名为 parameters）
        if (paralist != null && !paralist.isEmpty()) {
            params.put("parameters", paralist);
        }
        putIfNotNull(params, "samplingRate", samplingRateOrDefault());
        return params;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            if (!((String) value).isEmpty()) {
                map.put(key, value);
            }
        } else if (value instanceof List) {
            if (!((List<?>) value).isEmpty()) {
                map.put(key, value);
            }
        } else {
            map.put(key, value);
        }
    }
}
