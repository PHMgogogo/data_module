package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 架次寿命扣减流水。
 */
@Schema(description = "架次寿命扣减流水")
@TableName("sortie_life_usage")
public class SortieLifeUsage {

    @TableId(type = IdType.AUTO)
    private Long usageId;
    private Long sortieId;
    private Long lifeId;
    private BigDecimal deductedHours;
    private String createdAt;

    public Long getUsageId() {
        return usageId;
    }

    public void setUsageId(Long usageId) {
        this.usageId = usageId;
    }

    public Long getSortieId() {
        return sortieId;
    }

    public void setSortieId(Long sortieId) {
        this.sortieId = sortieId;
    }

    public Long getLifeId() {
        return lifeId;
    }

    public void setLifeId(Long lifeId) {
        this.lifeId = lifeId;
    }

    public BigDecimal getDeductedHours() {
        return deductedHours;
    }

    public void setDeductedHours(BigDecimal deductedHours) {
        this.deductedHours = deductedHours;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
