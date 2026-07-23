package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一架次查询响应行 — 三个数据源（航新、633、本地）的字段并集。
 * 所有记录统一使用相同的字段名，不区分来源。
 */
@Schema(description = "统一架次响应行")
public class UnifiedSortieResponse {

    @Schema(description = "架次ID（航新/633 原 data.id；本地为 sortieId）")
    private String id;

    @Schema(description = "机型（航新/633）")
    private String airplaneType;

    @Schema(description = "机号（航新/633 原 data.airplaneNum；本地为 aircraftNumber）")
    private String airplaneNum;

    @Schema(description = "架次号（航新/633 原 data.flightNum；本地为 sortieNumber）")
    private String flightNum;

    @Schema(description = "开始时间（航新/本地）")
    private String startTime;

    @Schema(description = "结束时间（航新/本地）")
    private String endTime;

    @Schema(description = "属性字段列表（航新）")
    private List<String> paramList;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
    public List<String> getParamList() { return paramList; }
    public void setParamList(List<String> paramList) { this.paramList = paramList; }

    // ==================== 工厂方法 ====================

    /** 从外来数据构造统一的响应行 */
    public static UnifiedSortieResponse fromExternal(ExternalSortieData ext) {
        UnifiedSortieResponse r = new UnifiedSortieResponse();
        r.id = ext.getId();
        r.airplaneType = ext.getAirplaneType();
        r.airplaneNum = ext.getAirplaneNum();
        r.flightNum = ext.getFlightNum();
        r.startTime = ext.getStartTime();
        r.endTime = ext.getEndTime();
        r.paramList = ext.getParamList();
        return r;
    }

    /** 从本地 Sortie 构造统一的响应行 */
    public static UnifiedSortieResponse fromLocal(com.project.phm.entity.Sortie sortie) {
        UnifiedSortieResponse r = new UnifiedSortieResponse();
        r.id = String.valueOf(sortie.getSortieId());
        r.airplaneNum = sortie.getAircraftNumber();
        r.flightNum = sortie.getSortieNumber();
        r.startTime = sortie.getStartTime();
        r.endTime = sortie.getEndTime();
        return r;
    }
}
