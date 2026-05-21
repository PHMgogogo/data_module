package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 构型项目 — 系统/设备，按ATA章节组织的树形结构
 *
 * 层级示例:
 *   SYSTEM     → 系统级       (ataChapter="72-00", systemName="发动机")
 *   SUBSYSTEM  → 子系统级     (ataChapter="72-50", systemName="发动机", subSystemName="低压压气机")
 *   EQUIPMENT  → 设备/LRU级  (ataChapter="72-50", equipmentName="振动传感器", partNumber="P/N 12345")
 */
@TableName("config_item")
public class ConfigItem {

    @TableId(type = IdType.AUTO)
    private Long itemId;            // PK, 自增
    private String modelCode;       // 所属机型 → AircraftModel
    private Long parentItemId;      // 父级项目（自关联，树形结构）
    private String ataChapter;      // ATA章节号, e.g. "72-00"
    private String systemName;      // 系统名称
    private String subSystemName;   // 子系统名称
    private String equipmentName;   // 设备名称
    private String partNumber;      // 件号
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
