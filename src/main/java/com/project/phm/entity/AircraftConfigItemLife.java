package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 单机构型寿命实例：每架单机的每个 EQUIPMENT 各一条。
 */
@Schema(description = "单机构型寿命实例")
@TableName("aircraft_config_item_life")
public class AircraftConfigItemLife {

    @TableId(type = IdType.AUTO)
    private Long lifeId;
    private String aircraftNumber;
    private Long itemId;
    private String configVersion;
    private BigDecimal initialLifeHours;
    private BigDecimal usedLifeHours;
    private String boundAt;
    private String updatedAt;

    public Long getLifeId() {
        return lifeId;
    }

    public void setLifeId(Long lifeId) {
        this.lifeId = lifeId;
    }

    public String getAircraftNumber() {
        return aircraftNumber;
    }

    public void setAircraftNumber(String aircraftNumber) {
        this.aircraftNumber = aircraftNumber;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(String configVersion) {
        this.configVersion = configVersion;
    }

    public BigDecimal getInitialLifeHours() {
        return initialLifeHours;
    }

    public void setInitialLifeHours(BigDecimal initialLifeHours) {
        this.initialLifeHours = initialLifeHours;
    }

    public BigDecimal getUsedLifeHours() {
        return usedLifeHours;
    }

    public void setUsedLifeHours(BigDecimal usedLifeHours) {
        this.usedLifeHours = usedLifeHours;
    }

    public String getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(String boundAt) {
        this.boundAt = boundAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public BigDecimal getRemainingLifeHours() {
        BigDecimal initial = initialLifeHours == null ? BigDecimal.ZERO : initialLifeHours;
        BigDecimal used = usedLifeHours == null ? BigDecimal.ZERO : usedLifeHours;
        return initial.subtract(used);
    }
}
