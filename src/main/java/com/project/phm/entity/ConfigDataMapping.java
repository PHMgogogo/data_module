package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 构型与CSV数据关联表 — 将上传的csv_xxx数据关联到指定飞机构型和构型项目
 *
 * 诊断/评价/预测信息通过此关联追溯到对应的飞机构型上下文
 */
@TableName("config_data_mapping")
public class ConfigDataMapping {

    @TableId(type = IdType.AUTO)
    private Long mappingId;         // PK, 自增
    private String tailNumber;      // 机号 → AircraftConfig
    private Long itemId;            // 构型项目 → ConfigItem
    private String csvTableName;    // 关联的 csv_xxx 表名
    private String dataType;        // 数据类型: DIAGNOSIS / EVALUATION / PREDICTION / RAW
    private String dataTime;        // 数据时间
    private String createdAt;       // 创建时间

    public Long getMappingId() {
        return mappingId;
    }

    public void setMappingId(Long mappingId) {
        this.mappingId = mappingId;
    }

    public String getTailNumber() {
        return tailNumber;
    }

    public void setTailNumber(String tailNumber) {
        this.tailNumber = tailNumber;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getCsvTableName() {
        return csvTableName;
    }

    public void setCsvTableName(String csvTableName) {
        this.csvTableName = csvTableName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDataTime() {
        return dataTime;
    }

    public void setDataTime(String dataTime) {
        this.dataTime = dataTime;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
