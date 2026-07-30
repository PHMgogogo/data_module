package com.project.phm.adapter.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一机型查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一机型查询请求")
public class UnifiedModelRequest {

    @Schema(description = "机型（本地按 modelCode 模糊匹配；航新/633 按 airplaneType 精确匹配）", example = "A320")
    private String airplaneType;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }

    /** 转为航新/633 的请求参数（两者参数相同） */
    public Map<String, Object> toExternalParams() {
        Map<String, Object> params = new HashMap<>();
        if (airplaneType != null && !airplaneType.isEmpty()) {
            params.put("airplaneType", airplaneType);
        }
        return params;
    }

    /** 转为本地 AircraftModel 表的查询条件 */
    public LambdaQueryWrapper<AircraftModel> toLocalQuery() {
        LambdaQueryWrapper<AircraftModel> wrapper = Wrappers.lambdaQuery();
        if (airplaneType != null && !airplaneType.isEmpty()) {
            wrapper.like(AircraftModel::getModelCode, airplaneType);
        }
        wrapper.orderByAsc(AircraftModel::getModelCode);
        return wrapper;
    }
}
