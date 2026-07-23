package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 架次 — 单次飞行任务（起飞到降落）
 *
 * 每个架次归属于一架单机（Aircraft），是数据采集的时间维度。
 * CSV 传感器数据通过 ConfigDataMapping.sortieId 关联到架次。
 */
@Schema(description = "架次实体（飞行任务）")
@TableName("sortie")
public class Sortie {

    @Schema(description = "架次ID（主键，自增）", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    @TableId(type = IdType.AUTO)
    private Long sortieId;

    @Schema(description = "所属单机机号", example = "B-1234", required = true)
    private String aircraftNumber;

    @Schema(description = "架次号", example = "CA1234-20260723")
    private String sortieNumber;

    @Schema(description = "飞行日期", example = "2026-07-23")
    private String flightDate;

    @Schema(description = "起飞时间", example = "10:30:00")
    private String takeoffTime;

    @Schema(description = "降落时间", example = "14:20:00")
    private String landingTime;

    @Schema(description = "起飞机场", example = "北京首都")
    private String origin;

    @Schema(description = "降落机场", example = "上海浦东")
    private String destination;

    @Schema(description = "飞行员", example = "张三")
    private String pilot;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", example = "2026-07-23 10:00:00", accessMode = Schema.AccessMode.READ_ONLY)
    private String createdAt;

    public Long getSortieId() { return sortieId; }
    public void setSortieId(Long sortieId) { this.sortieId = sortieId; }

    public String getAircraftNumber() { return aircraftNumber; }
    public void setAircraftNumber(String aircraftNumber) { this.aircraftNumber = aircraftNumber; }

    public String getSortieNumber() { return sortieNumber; }
    public void setSortieNumber(String sortieNumber) { this.sortieNumber = sortieNumber; }

    public String getFlightDate() { return flightDate; }
    public void setFlightDate(String flightDate) { this.flightDate = flightDate; }

    public String getTakeoffTime() { return takeoffTime; }
    public void setTakeoffTime(String takeoffTime) { this.takeoffTime = takeoffTime; }

    public String getLandingTime() { return landingTime; }
    public void setLandingTime(String landingTime) { this.landingTime = landingTime; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getPilot() { return pilot; }
    public void setPilot(String pilot) { this.pilot = pilot; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
