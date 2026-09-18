package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一单机查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一单机查询请求")
public class UnifiedAircraftRequest {

    @Schema(description = "机型（必填），后端据此路由到唯一平台", example = "A320")
    private String airplaneType;

    @Schema(description = "机号（可选，用于按机号过滤）", example = "B-1234")
    private String airplaneNum;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getAirplaneNum() { return airplaneNum; }
    public void setAirplaneNum(String airplaneNum) { this.airplaneNum = airplaneNum; }
}
