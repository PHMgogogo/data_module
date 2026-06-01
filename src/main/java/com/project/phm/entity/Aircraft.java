package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 飞机构型 — 每架飞机实例的构型定义
 */
@Schema(description = "飞机构型实体（每架飞机一条记录）")
@TableName("aircraft_config")
public class Aircraft {

    @Schema(description = "机号/注册号（主键）", example = "B-1234", required = true)
    @TableId(type = IdType.INPUT)
    private String aircraftNumber;

    @Schema(description = "机型代码", example = "B737-800", required = true)
    private String modelCode;

    @Schema(description = "所属航司", example = "中国国航")
    private String airline;

    @Schema(description = "构型版本", example = "V1.0")
    private String configVersion;

    @Schema(description = "状态：active(在役)/retired(退役)/maintenance(维护)", example = "active", allowableValues = {"active", "retired", "maintenance"})
    private String status;

    @Schema(description = "创建时间", example = "2026-05-23 10:00:00", accessMode = Schema.AccessMode.READ_ONLY)
    private String createdAt;

    public String getAircraftNumber() {
        return aircraftNumber;
    }

    public void setAircraftNumber(String aircraftNumber) {
        this.aircraftNumber = aircraftNumber;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getAirline() {
        return airline;
    }

    public void setAirline(String airline) {
        this.airline = airline;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(String configVersion) {
        this.configVersion = configVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
