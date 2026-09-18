package com.project.phm.adapter.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一机型查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一机型查询请求")
public class UnifiedModelRequest {

    @Schema(description = "机型（必填），后端据此路由到唯一平台", example = "A320")
    private String airplaneType;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }

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
