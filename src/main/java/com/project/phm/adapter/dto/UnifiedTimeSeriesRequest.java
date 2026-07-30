package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一时序数据查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一时序数据查询请求")
public class UnifiedTimeSeriesRequest {

    @Schema(description = "机型（633）", example = "A320")
    private String airplaneType;

    @Schema(description = "机号（633）", example = "B1234")
    private String airplaneNum;

    @Schema(description = "架次号（633 → flightNum；航新 → sortieId）", example = "CA123")
    private String flightNum;

    @Schema(description = "开始时间（633 → startTime；航新 → startTimestamp）", example = "2024-01-01 00:00:00")
    private String startTime;

    @Schema(description = "结束时间（633 → endTime；航新 → endTimestamp）", example = "2024-01-31 23:59:59")
    private String endTime;

    @Schema(description = "所查参数列表（633 → Paralist；航新 → parameters；本地 → columns）",
            example = "[\"ALTITUDE\", \"SPEED\", \"N1\"]")
    private List<String> paralist;

    @Schema(description = "采样率（航新 → samplingRate）", example = "10")
    private String samplingRate;

    @Schema(description = "表名（本地，含 csv_ 前缀）", example = "csv_engine_vibration")
    private String tableName;

    @Schema(description = "列名列表（本地，逗号分隔的 String）", example = "fan_vibration,egt_actual")
    private String columns;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getAirplaneNum() { return airplaneNum; }
    public void setAirplaneNum(String airplaneNum) { this.airplaneNum = airplaneNum; }
    public String getFlightNum() { return flightNum; }
    public void setFlightNum(String flightNum) { this.flightNum = flightNum; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public List<String> getParalist() { return paralist; }
    public void setParalist(List<String> paralist) { this.paralist = paralist; }
    public String getSamplingRate() { return samplingRate; }
    public void setSamplingRate(String samplingRate) { this.samplingRate = samplingRate; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumns() { return columns; }
    public void setColumns(String columns) { this.columns = columns; }

    /** 转为 633 API 的请求参数 */
    public Map<String, Object> toSanSanParams() {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneType", airplaneType);
        putIfNotNull(params, "airplaneNum", airplaneNum);
        putIfNotNull(params, "flightNum", flightNum);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "Paralist", paralist);
        return params;
    }

    /** 转为航新 API 的请求参数（querySorties 接口） */
    public Map<String, Object> toHangxinParams() {
        Map<String, Object> params = new HashMap<>();
        // 航新 querySorties 使用 sortieId（从 flightNum 映射）
        putIfNotNull(params, "sortieId", flightNum);
        // 采样率
        if (samplingRate != null && !samplingRate.isEmpty()) {
            params.put("samplingRate", samplingRate);
        }
        // 参数列表（航新字段名为 parameters）
        if (paralist != null && !paralist.isEmpty()) {
            params.put("parameters", paralist);
        }
        // 时间戳（航新使用毫秒时间戳）
        // startTimestamp / endTimestamp 由调用端负责转换，此处原样传入
        putIfNotNull(params, "startTimestamp", startTime);
        putIfNotNull(params, "endTimestamp", endTime);
        return params;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
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
}
