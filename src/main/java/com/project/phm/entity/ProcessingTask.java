package com.project.phm.entity;

import java.util.Map;

/**
 * 处理任务实体
 */
public class ProcessingTask {

    private String taskId;
    private String fileName;
    private String tableName;
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED
    private int progress;
    private int expectedRows;
    private int processedRows;
    private int successRows;
    private String message;
    private long startTime;
    private long endTime;
    private Map<String, Object> storageValidation;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getExpectedRows() {
        return expectedRows;
    }

    public void setExpectedRows(int expectedRows) {
        this.expectedRows = expectedRows;
    }

    public int getProcessedRows() {
        return processedRows;
    }

    public void setProcessedRows(int processedRows) {
        this.processedRows = processedRows;
    }

    public int getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(int successRows) {
        this.successRows = successRows;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public Map<String, Object> getStorageValidation() {
        return storageValidation;
    }

    public void setStorageValidation(Map<String, Object> storageValidation) {
        this.storageValidation = storageValidation;
    }
}
