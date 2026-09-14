package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 外来平台单条单机数据（/configuration/airplane/number/list 的 data 条目）。
 */
@Schema(description = "外来平台单机数据")
public class ExternalAircraftData {

    @Schema(description = "单机唯一标识")
    private String id;

    @Schema(description = "机型")
    private String airplaneType;

    @Schema(description = "机号")
    private String airplaneNum;

    @Schema(description = "单机编码")
    private String airplaneId;

    @Schema(description = "数据来源/录入方式")
    private Integer inputType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getAirplaneNum() { return airplaneNum; }
    public void setAirplaneNum(String airplaneNum) { this.airplaneNum = airplaneNum; }
    public String getAirplaneId() { return airplaneId; }
    public void setAirplaneId(String airplaneId) { this.airplaneId = airplaneId; }
    public Integer getInputType() { return inputType; }
    public void setInputType(Integer inputType) { this.inputType = inputType; }
}
