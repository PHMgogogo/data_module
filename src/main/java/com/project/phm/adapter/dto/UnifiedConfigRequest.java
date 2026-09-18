package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一单机构型查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一单机构型查询请求")
public class UnifiedConfigRequest {

    @Schema(description = "飞机ID（633 查询参数）", example = "10")
    private String aircraftId;

    @Schema(description = "机型（必填），后端据此路由到唯一平台", example = "B737-800")
    private String modelCode;

    @Schema(description = "页码（航新/633）", example = "1")
    private Integer pageNum;

    @Schema(description = "每页数量（航新/633），默认10", example = "10")
    private Integer pageSize;

    public String getAircraftId() { return aircraftId; }
    public void setAircraftId(String aircraftId) { this.aircraftId = aircraftId; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
}
