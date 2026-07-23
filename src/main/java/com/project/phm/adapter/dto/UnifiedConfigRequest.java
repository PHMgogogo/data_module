package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一单机构型查询请求体 — 航新、633、本地请求参数的并集。
 */
@Schema(description = "统一单机构型查询请求")
public class UnifiedConfigRequest {

    @Schema(description = "机型（本地按 modelCode 查询）", example = "B737-800")
    private String airplaneType;

    @Schema(description = "页码（航新/633）", example = "1")
    private Integer page;

    @Schema(description = "每页数量（航新/633），默认10", example = "10")
    private Integer rows;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Integer getRows() { return rows; }
    public void setRows(Integer rows) { this.rows = rows; }

    public Map<String, Object> toExternalParams() {
        Map<String, Object> params = new HashMap<>();
        if (page != null)  params.put("page", page);
        if (rows != null)  params.put("rows", rows);
        else               params.put("rows", 10);
        return params;
    }
}
