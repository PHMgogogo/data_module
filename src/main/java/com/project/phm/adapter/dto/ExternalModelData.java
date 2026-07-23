package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 外来平台单条机型数据。
 */
@Schema(description = "外来平台机型数据")
public class ExternalModelData {

    @Schema(description = "机型唯一标识")
    private String id;

    @Schema(description = "机型编码")
    private String airplaneType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
}
