package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 构型项目 — 系统/设备，按ATA章节组织的树形结构
 *
 * 层级示例:
 *   SYSTEM     → 系统级       (ataChapter="72-00", systemName="发动机")
 *   SUBSYSTEM  → 子系统级     (ataChapter="72-50", systemName="发动机", subSystemName="低压压气机")
 *   EQUIPMENT  → 设备/LRU级  (ataChapter="72-50", equipmentName="振动传感器", partNumber="P/N 12345")
 */
@Schema(description = "构型项目实体（GJB章节树形结构）")
@TableName("config_item")
public class ConfigItem {

    @Schema(description = "项目ID（主键，自增）", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    @TableId(type = IdType.AUTO)
    private Long itemId;            // PK, 自增

    @Schema(description = "所属机型代码", example = "B737-800", required = true)
    private String modelCode;       // 所属机型 → AircraftModel

    @Schema(description = "父级项目ID（树形结构，根节点为null）", example = "null")
    private Long parentItemId;      // 父级项目（自关联，树形结构）

    @Schema(description = "GJB章节号", example = "72-00")
    @JsonProperty("gjbChapter")
    private String ataChapter;      // ATA章节号, e.g. "72-00"

    @Schema(description = "系统名称", example = "发动机")
    private String systemName;      // 系统名称

    @Schema(description = "子系统名称", example = "低压压气机")
    private String subSystemName;   // 子系统名称

    @Schema(description = "设备名称", example = "振动传感器")
    private String equipmentName;   // 设备名称

    @Schema(description = "件号", example = "P/N 12345")
    private String partNumber;      // 件号

    @Schema(description = "项目类型：SYSTEM/SUBSYSTEM/EQUIPMENT/LRU", example = "SYSTEM", 
            allowableValues = {"SYSTEM", "SUBSYSTEM", "EQUIPMENT", "LRU"}, required = true)
    private String itemType;        // 类型: SYSTEM / SUBSYSTEM / EQUIPMENT / LRU

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public Long getParentItemId() {
        return parentItemId;
    }

    public void setParentItemId(Long parentItemId) {
        this.parentItemId = parentItemId;
    }

    public String getAtaChapter() {
        return ataChapter;
    }

    public void setAtaChapter(String ataChapter) {
        this.ataChapter = ataChapter;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getSubSystemName() {
        return subSystemName;
    }

    public void setSubSystemName(String subSystemName) {
        this.subSystemName = subSystemName;
    }

    public String getEquipmentName() {
        return equipmentName;
    }

    public void setEquipmentName(String equipmentName) {
        this.equipmentName = equipmentName;
    }

    public String getPartNumber() {
        return partNumber;
    }

    public void setPartNumber(String partNumber) {
        this.partNumber = partNumber;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }
}
