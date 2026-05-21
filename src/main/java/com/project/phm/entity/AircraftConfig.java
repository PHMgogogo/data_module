package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 飞机构型 — 每架飞机实例的构型定义
 */
@TableName("aircraft_config")
public class AircraftConfig {

    @TableId(type = IdType.INPUT)
    private String tailNumber;      // 机号/注册号, PK, e.g. "B-1234"
    private String modelCode;       // 机型代码 → AircraftModel
    private String airline;         // 所属航司
    private String configVersion;   // 构型版本
    private String status;          // 状态: active/retired/maintenance
    private String createdAt;       // 创建时间

    public String getTailNumber() {
        return tailNumber;
    }

    public void setTailNumber(String tailNumber) {
        this.tailNumber = tailNumber;
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
