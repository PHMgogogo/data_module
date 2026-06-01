package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 飞机机型
 */
@Schema(description = "飞机机型实体")
@TableName("aircraft_model")
public class AircraftModel {

    @Schema(description = "机型代码（主键）", example = "B737-800", required = true)
    @TableId(type = IdType.INPUT)
    private String modelCode;       // 机型代码, e.g. "B737-800"

    @Schema(description = "制造商", example = "Boeing")
    private String manufacturer;    // 制造商

    @Schema(description = "描述", example = "波音737-800窄体客机")
    private String description;     // 描述

    @Schema(description = "创建时间", example = "2026-05-23 10:00:00", accessMode = Schema.AccessMode.READ_ONLY)
    private String createdAt;       // 创建时间

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
