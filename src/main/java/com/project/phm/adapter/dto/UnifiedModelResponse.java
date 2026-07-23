package com.project.phm.adapter.dto;

import com.project.phm.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一机型查询响应行 — 三个数据源的字段并集。
 */
@Schema(description = "统一机型响应行")
public class UnifiedModelResponse {

    @Schema(description = "机型唯一标识（航新/633 原 id；本地为 modelCode）")
    private String id;

    @Schema(description = "机型编码（航新/633 原 airplaneType；本地为 modelCode）")
    private String airplaneType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }

    public static UnifiedModelResponse fromExternal(ExternalModelData ext) {
        UnifiedModelResponse r = new UnifiedModelResponse();
        r.id = ext.getId();
        r.airplaneType = ext.getAirplaneType();
        return r;
    }

    public static UnifiedModelResponse fromLocal(AircraftModel model) {
        UnifiedModelResponse r = new UnifiedModelResponse();
        r.id = model.getModelCode();
        r.airplaneType = model.getModelCode();
        return r;
    }
}
