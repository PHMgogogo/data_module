package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * CSV 数据关联表 — 将上传的 csv_xxx 表绑定到本地架次。
 *
 * <p>一个本地架次只能绑定一张 CSV 表，一张 CSV 表也只能绑定一个本地架次。</p>
 */
@TableName("config_data_mapping")
public class ConfigDataMapping {

    @TableId(type = IdType.AUTO)
    private Long mappingId;         // PK, 自增
    private String aircraftNumber;
    private Long sortieId;          // 本地架次ID → Sortie
    private String csvTableName;    // 关联的 csv_xxx 表名
    private String dataTime;        // 数据时间
    private String createdAt;       // 创建时间

    public Long getMappingId() {
        return mappingId;
    }

    public void setMappingId(Long mappingId) {
        this.mappingId = mappingId;
    }

    public String getAircraftNumber() {
        return aircraftNumber;
    }

    public void setAircraftNumber(String aircraftNumber) {
        this.aircraftNumber = aircraftNumber;
    }

    public Long getSortieId() {
        return sortieId;
    }

    public void setSortieId(Long sortieId) {
        this.sortieId = sortieId;
    }

    public String getCsvTableName() {
        return csvTableName;
    }

    public void setCsvTableName(String csvTableName) {
        this.csvTableName = csvTableName;
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
