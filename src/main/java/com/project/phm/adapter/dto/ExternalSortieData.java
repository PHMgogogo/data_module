package com.project.phm.adapter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 外来平台单条架次元数据。
 * 航新和 633 都返回此结构，缺的字段为 null。
 */
@Schema(description = "外来平台架次元数据")
public class ExternalSortieData {

    @Schema(description = "外来平台架次ID")
    private String id;

    @Schema(description = "机型")
    private String airplaneType;

    @Schema(description = "机号")
    private String airplaneNum;

    @Schema(description = "架次号")
    private String flightNum;

    @Schema(description = "架次开始时间（航新返回）")
    private String startTime;

    @Schema(description = "架次结束时间（航新返回）")
    private String endTime;

    @Schema(description = "属性字段列表（航新返回）")
    @JsonProperty("paralist")
    private List<String> paramList;

    @Schema(description = "飞行日期（633 从 startTime 提取）")
    private String flightDate;

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
    public String getFlightDate() { return flightDate; }
    public void setFlightDate(String flightDate) { this.flightDate = flightDate; }
}
