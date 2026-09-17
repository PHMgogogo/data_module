package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 时序数据查询请求体。
 *
 * <p><b>定位一个架次只需要一个 {@code sortieId}</b>：它就是 {@code /aircraft/sorties} 下发的
 * {@code sortieKey} —— 本地行是数字主键的字符串形态，三方行是平台原始 id（UUID 形态）。
 * 后端拿它去内存里的平台路由索引查出该架次归属哪个平台，然后只向那一个平台发请求。</p>
 *
 * <p>因此请求体里<b>没有</b>独立的「架次号」字段：以前靠 {@code aircraftNumber + sortieNumber}
 * 让后端挨个源试的路子，在定向模式下已经不需要了。</p>
 *
 * <p>{@code aircraftNumber} / {@code airplaneType} 保留为可选补充：正常情况下后端用索引里
 * 随架次一起记下来的值构造三方请求，只有在索引缺少该项时才回落到请求体。</p>
 *
 * <p>startTime / endTime 可选：不传时后端查询三方架次接口自行推算，
 * 传了则以传入值为准（两种格式都接受，最终统一转成 ISO 再发给三方时序接口）。</p>
 */
@Schema(description = "时序数据查询请求")
public class UnifiedTimeSeriesRequest {

    @Schema(description = "架次标识（取自 /aircraft/sorties 的 sortieKey；本地为数字主键，三方为平台原始 id）",
            example = "1")
    private String sortieId;

    @Schema(description = "机号（可选，仅当路由索引缺少该架次的机号时作为补充）", example = "0003")
    private String aircraftNumber;

    @Schema(description = "机型（可选，仅当路由索引缺少该架次真实的 airplaneType 时作为补充）",
            example = "K4-WS19")
    private String airplaneType;

    @Schema(description = "开始时间（可选，不传则查三方架次接口推算）",
            example = "2026-07-23 10:30:00")
    private String startTime;

    @Schema(description = "结束时间（可选，不传则查三方架次接口推算）",
            example = "2026-07-23 14:20:00")
    private String endTime;

    @Schema(description = "所查参数列表（本地 → 列名；633 → Paralist；航新 → parameters）",
            example = "[\"ALTITUDE\", \"SPEED\", \"N1\"]")
    private List<String> paralist;

    @Schema(description = "采样率，不传默认 10", example = "10")
    private String samplingRate;

    /** 采样率缺省值 */
    public static final String DEFAULT_SAMPLING_RATE = "10";

    public String getSortieId() { return sortieId; }
    public void setSortieId(String sortieId) { this.sortieId = sortieId; }
    public String getAircraftNumber() { return aircraftNumber; }
    public void setAircraftNumber(String aircraftNumber) { this.aircraftNumber = aircraftNumber; }
    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public List<String> getParalist() { return paralist; }
    public void setParalist(List<String> paralist) { this.paralist = paralist; }
    public String getSamplingRate() { return samplingRate; }
    public void setSamplingRate(String samplingRate) { this.samplingRate = samplingRate; }

    /** 采样率：未配置时取默认值 10 */
    public String samplingRateOrDefault() {
        return (samplingRate == null || samplingRate.isEmpty()) ? DEFAULT_SAMPLING_RATE : samplingRate;
    }

    /** 是否携带了架次标识 —— 这是定向路由的唯一依据，没它就定位不到平台 */
    public boolean hasSortieIdentifier() {
        return notEmpty(sortieId);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
