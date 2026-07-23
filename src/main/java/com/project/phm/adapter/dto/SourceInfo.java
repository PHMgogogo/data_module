package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个数据源的查询结果元数据。
 */
@Schema(description = "数据源查询元数据")
public class SourceInfo {

    @Schema(description = "该数据源返回的记录数")
    private int total;

    @Schema(description = "查询结果说明，失败时描述原因", example = "success")
    private String message;

    public SourceInfo() {}

    public SourceInfo(int total, String message) {
        this.total = total;
        this.message = message;
    }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
