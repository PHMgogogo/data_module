package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一单机查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一单机查询请求")
public class UnifiedAircraftRequest {

    @Schema(description = "机型（航新/633）", example = "A320")
    private String airplaneType;

    @Schema(description = "机号（633/本地）", example = "B-1234")
    private String airplaneNum;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getAirplaneNum() { return airplaneNum; }
    public void setAirplaneNum(String airplaneNum) { this.airplaneNum = airplaneNum; }

    public Map<String, Object> toHangxinParams() {
        Map<String, Object> params = new HashMap<>();
        if (airplaneType != null && !airplaneType.isEmpty()) {
            params.put("airplaneType", airplaneType);
        }
        return params;
    }

    public Map<String, Object> toSanSanParams() {
        Map<String, Object> params = new HashMap<>();
        if (airplaneType != null && !airplaneType.isEmpty()) {
            params.put("airplaneType", airplaneType);
        }
        if (airplaneNum != null && !airplaneNum.isEmpty()) {
            params.put("airplaneNum", airplaneNum);
        }
        return params;
    }
}
