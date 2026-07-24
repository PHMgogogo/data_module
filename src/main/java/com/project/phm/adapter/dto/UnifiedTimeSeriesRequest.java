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

    @Schema(description = "架次（633）", example = "CA123")
    private String flightNum;

    @Schema(description = "开始时间（633）", example = "2024-01-01 00:00:00")
    private String startTime;

    @Schema(description = "结束时间（633）", example = "2024-01-31 23:59:59")
    private String endTime;

    @Schema(description = "所查参数（633）")
    private List<String> paralist;

    @Schema(description = "架次ID（航新）", example = "123")
    private String sortieId;

    @Schema(description = "查询选项（航新）", example = "{\"samplingRate\":1, \"parameters\":[\"ALTITUDE\"], \"startTimestamp\":1704067200, \"endTimestamp\":1706745599, \"outputFormat\":\"CSV\"}")
    private Map<String, Object> queryOptions;

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
    public String getSortieId() { return sortieId; }
    public void setSortieId(String sortieId) { this.sortieId = sortieId; }
    public Map<String, Object> getQueryOptions() { return queryOptions; }
    public void setQueryOptions(Map<String, Object> queryOptions) { this.queryOptions = queryOptions; }
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

    /** 转为航新 API 的请求参数 */
    public Map<String, Object> toHangxinParams() {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "sortieId", sortieId);
        putIfNotNull(params, "queryOptions", queryOptions);
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
            } else if (value instanceof Map) {
                if (!((Map<?, ?>) value).isEmpty()) {
                    map.put(key, value);
                }
            } else {
                map.put(key, value);
            }
        }
    }
}
