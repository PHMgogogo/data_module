package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 健康记录 — 系统/设备的诊断、评价、预测结果
 *
 * 每条记录关联到具体的飞机构型和构型项目，是实现"诊断、评价和预测信息
 * 均与飞机构型进行关联"的核心实体
 */
@TableName("health_record")
public class HealthRecord {

    @TableId(type = IdType.AUTO)
    private Long recordId;          // PK, 自增
    private String tailNumber;      // 机号 → AircraftConfig
    private Long itemId;            // 构型项目 → ConfigItem
    private String recordType;      // 记录类型: DIAGNOSIS / EVALUATION / PREDICTION
    private String indicatorName;   // 指标名称, e.g. "振动值", "温度偏差"
    private String indicatorValue;  // 指标值, e.g. "2.3mm/s"
    private String confidence;      // 置信度/可信度, e.g. "0.95"
    private String recordTime;      // 记录/采集时间
    private String dataSourceTable; // 来源数据表（csv_xxx表名）
    private String createdAt;       // 创建时间

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
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

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getIndicatorName() {
        return indicatorName;
    }

    public void setIndicatorName(String indicatorName) {
        this.indicatorName = indicatorName;
    }

    public String getIndicatorValue() {
        return indicatorValue;
    }

    public void setIndicatorValue(String indicatorValue) {
        this.indicatorValue = indicatorValue;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getRecordTime() {
        return recordTime;
    }

    public void setRecordTime(String recordTime) {
        this.recordTime = recordTime;
    }

    public String getDataSourceTable() {
        return dataSourceTable;
    }

    public void setDataSourceTable(String dataSourceTable) {
        this.dataSourceTable = dataSourceTable;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
