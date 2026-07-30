package com.project.phm.adapter.dto;

import com.project.phm.entity.AircraftModel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一机型查询响应行 — 三个数据源的字段并集。
 * 不做跨源去重合并，每条记录标记来源供前端区分。
 */
@Schema(description = "统一机型响应行")
public class UnifiedModelResponse {

    @Schema(description = "数据来源：hangxin / sansan / local")
    private String source;

    @Schema(description = "型号唯一标识（航新/633 原 id；本地无）")
    private String modelId;

    @Schema(description = "型号编码（航新/633 原 airplaneType；本地为 modelCode）")
    private String modelCode;

    @Schema(description = "生产厂家（本地）")
    private String manufacturer;

    @Schema(description = "型号描述（本地）")
    private String description;

    @Schema(description = "创建时间（本地 createdAt）")
    private String createdTime;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public static UnifiedModelResponse fromExternal(ExternalModelData ext, String source) {
        UnifiedModelResponse r = new UnifiedModelResponse();
        r.source = source;
        r.modelId = ext.getId();
        r.modelCode = ext.getAirplaneType();
        return r;
    }

    public static UnifiedModelResponse fromLocal(AircraftModel model) {
        UnifiedModelResponse r = new UnifiedModelResponse();
        r.source = "local";
        r.modelCode = model.getModelCode();
        r.manufacturer = model.getManufacturer();
        r.description = model.getDescription();
        r.createdTime = model.getCreatedAt();
        return r;
    }
}
