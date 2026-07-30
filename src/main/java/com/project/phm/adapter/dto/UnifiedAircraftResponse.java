package com.project.phm.adapter.dto;

import com.project.phm.entity.Aircraft;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 统一单机查询响应行 — 三个数据源的字段并集。
 * 不做跨源去重合并，每条记录标记来源供前端区分。
 */
@Schema(description = "统一单机响应行")
public class UnifiedAircraftResponse {

    @Schema(description = "数据来源：hangxin / sansan / local")
    private String source;

    @Schema(description = "飞机主键ID（航新/633 原 data.id；本地无）")
    private String aircraftId;

    @Schema(description = "飞机编码（航新原 airplaneId）")
    private String aircraftCode;

    @Schema(description = "机号（本地 aircraftNumber；633 原 airplaneNum；航新原 airplaneNum）")
    private String aircraftNumber;

    @Schema(description = "飞机型号（本地 modelCode；航新原 airplaneType）")
    private String modelCode;

    @Schema(description = "所属单位（本地 airline）")
    private String organization;

    @Schema(description = "构型版本（本地 configVersion）")
    private String configVersion;

    @Schema(description = "状态（本地 status）")
    private String status;

    @Schema(description = "数据来源/录入方式（航新原 inputType）")
    private Integer inputType;

    @Schema(description = "创建时间（本地 createdAt）")
    private String createdTime;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getAircraftId() { return aircraftId; }
    public void setAircraftId(String aircraftId) { this.aircraftId = aircraftId; }
    public String getAircraftCode() { return aircraftCode; }
    public void setAircraftCode(String aircraftCode) { this.aircraftCode = aircraftCode; }
    public String getAircraftNumber() { return aircraftNumber; }
    public void setAircraftNumber(String aircraftNumber) { this.aircraftNumber = aircraftNumber; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getConfigVersion() { return configVersion; }
    public void setConfigVersion(String configVersion) { this.configVersion = configVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getInputType() { return inputType; }
    public void setInputType(Integer inputType) { this.inputType = inputType; }
    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    // ==================== 工厂方法 ====================

    /** 从本地 Aircraft 构造统一的响应行 */
    public static UnifiedAircraftResponse fromLocal(Aircraft aircraft) {
        if (aircraft == null) return null;
        UnifiedAircraftResponse r = new UnifiedAircraftResponse();
        r.source = "local";
        r.aircraftNumber = aircraft.getAircraftNumber();
        r.modelCode = aircraft.getModelCode();
        r.organization = aircraft.getAirline();
        r.configVersion = aircraft.getConfigVersion();
        r.status = aircraft.getStatus();
        r.createdTime = aircraft.getCreatedAt();
        return r;
    }

    /** 从 633 返回的 data 条目（Map）构造统一的响应行 */
    public static UnifiedAircraftResponse fromSanSan(Map<String, Object> item) {
        if (item == null) return null;
        UnifiedAircraftResponse r = new UnifiedAircraftResponse();
        r.source = "sansan";
        r.aircraftId = toString(item.get("id"));
        r.aircraftNumber = toString(item.get("airplaneNum"));
        return r;
    }

    /** 从航新返回的 data 条目（Map）构造统一的响应行 */
    public static UnifiedAircraftResponse fromHangxin(Map<String, Object> item) {
        if (item == null) return null;
        UnifiedAircraftResponse r = new UnifiedAircraftResponse();
        r.source = "hangxin";
        r.aircraftId = toString(item.get("id"));
        r.aircraftCode = toString(item.get("airplaneId"));
        r.aircraftNumber = toString(item.get("airplaneNum"));
        r.modelCode = toString(item.get("airplaneType"));
        Object inputTypeVal = item.get("inputType");
        if (inputTypeVal instanceof Number) {
            r.inputType = ((Number) inputTypeVal).intValue();
        }
        return r;
    }

    private static String toString(Object val) {
        return val != null ? val.toString() : null;
    }
}
