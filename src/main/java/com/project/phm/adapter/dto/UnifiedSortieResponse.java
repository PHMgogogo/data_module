package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一架次查询响应行 — 三个数据源（航新、633、本地）的字段并集。
 * 不做跨源去重合并，每条记录标记来源供前端区分。
 */
@Schema(description = "统一架次响应行")
public class UnifiedSortieResponse {

    @Schema(description = "数据来源：hangxin / sansan / local")
    private String source;

    @Schema(description = "架次唯一标识（航新/633 原 data.id；本地为 sortieId）")
    private String flightId;

    @Schema(description = "机型（航新/633 原 airplaneType；本地无）")
    private String aircraftType;

    @Schema(description = "机号（航新/633 原 airplaneNum；本地为 aircraftNumber）")
    private String aircraftNo;

    @Schema(description = "架次号（航新/633 原 flightNum；本地为 sortieNumber）")
    private String flightNum;

    @Schema(description = "飞行日期（本地 flightDate；633 从 startTime 提取）")
    private String flightDate;

    @Schema(description = "起飞时间（本地 startTime；航新/633 的 startTime）")
    private String startTime;

    @Schema(description = "降落时间（本地 endTime；航新/633 的 endTime）")
    private String endTime;

    @Schema(description = "参数列表（633 的 paralist）")
    private List<String> parameterList;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getAircraftType() { return aircraftType; }
    public void setAircraftType(String aircraftType) { this.aircraftType = aircraftType; }
    public String getAircraftNo() { return aircraftNo; }
    public void setAircraftNo(String aircraftNo) { this.aircraftNo = aircraftNo; }
    public String getFlightNum() { return flightNum; }
    public void setFlightNum(String flightNum) { this.flightNum = flightNum; }
    public String getFlightDate() { return flightDate; }
    public void setFlightDate(String flightDate) { this.flightDate = flightDate; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public List<String> getParameterList() { return parameterList; }
    public void setParameterList(List<String> parameterList) { this.parameterList = parameterList; }

    // ==================== 工厂方法 ====================

    /** 从外来数据构造统一的响应行 */
    public static UnifiedSortieResponse fromExternal(ExternalSortieData ext, String source) {
        UnifiedSortieResponse r = new UnifiedSortieResponse();
        r.source = source;
        r.flightId = ext.getId();
        r.aircraftType = ext.getAirplaneType();
        r.aircraftNo = ext.getAirplaneNum();
        r.flightNum = ext.getFlightNum();
        r.flightDate = ext.getFlightDate() != null ? ext.getFlightDate()
                : extractDate(ext.getStartTime());
        r.startTime = ext.getStartTime();
        r.endTime = ext.getEndTime();
        r.parameterList = ext.getParamList();
        return r;
    }

    /** 从 "2026-01-01 10:00:00.00" 格式的时间戳提取日期 "2026-01-01" */
    private static String extractDate(String dateTime) {
        if (dateTime == null || dateTime.isEmpty()) return null;
        int idx = dateTime.indexOf(' ');
        return idx > 0 ? dateTime.substring(0, idx) : dateTime;
    }

    /** 从本地 Sortie 构造统一的响应行 */
    public static UnifiedSortieResponse fromLocal(com.project.phm.entity.Sortie sortie) {
        UnifiedSortieResponse r = new UnifiedSortieResponse();
        r.source = "local";
        r.flightId = String.valueOf(sortie.getSortieId());
        r.aircraftNo = sortie.getAircraftNumber();
        r.flightNum = sortie.getSortieNumber();
        r.flightDate = sortie.getFlightDate();
        r.startTime = sortie.getStartTime();
        r.endTime = sortie.getEndTime();
        return r;
    }
}
