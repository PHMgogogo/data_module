package com.project.phm.service;

import com.project.phm.entity.UploadTask;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器，管理CSV上传任务
 */
@Component
public class TaskManager {

    private final Map<String, UploadTask> tasks = new ConcurrentHashMap<>();

    /**
     * 创建新任务
     * @param fileName 文件名
     * @param tableName 表名
     * @return 任务ID
     */
    public String createTask(String fileName, String tableName) {
        String taskId = UUID.randomUUID().toString();
        UploadTask task = new UploadTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setTableName(tableName);
        task.setStatus("PENDING");
        task.setStartTime(System.currentTimeMillis());
        tasks.put(taskId, task);
        return taskId;
    }

    /**
     * 获取任务状态
     * @param taskId 任务ID
     * @return 任务对象
     */
    public UploadTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 更新任务进度
     * @param taskId 任务ID
     * @param processedRows 已处理行数
     * @param successRows 成功行数
     * @param failedRows 失败行数
     */
    public void updateProgress(String taskId, int processedRows, int successRows, int failedRows) {
        UploadTask task = tasks.get(taskId);
        if (task != null) {
            task.setProcessedRows(processedRows);
            task.setSuccessRows(successRows);
            task.setFailedRows(failedRows);
        }
    }

    /**
     * 设置任务总条数
     * @param taskId 任务ID
     * @param totalRows 总条数
     */
    public void setTotalRows(String taskId, int totalRows) {
        UploadTask task = tasks.get(taskId);
        if (task != null) {
            task.setTotalRows(totalRows);
            task.setStatus("PROCESSING");
        }
    }

    /**
     * 标记任务完成
     * @param taskId 任务ID
     * @param message 完成消息
     */
    public void completeTask(String taskId, String message) {
        UploadTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("COMPLETED");
            task.setMessage(message);
            task.setEndTime(System.currentTimeMillis());
        }
    }

    /**
     * 标记任务失败
     * @param taskId 任务ID
     * @param errorMessage 错误消息
     */
    public void failTask(String taskId, String errorMessage) {
        UploadTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("FAILED");
            task.setMessage(errorMessage);
            task.setEndTime(System.currentTimeMillis());
        }
    }

    /**
     * 清理已完成的任务（可选）
     */
    public void cleanupCompletedTasks() {
        long cutoffTime = System.currentTimeMillis() - 3600000; // 1小时前
        tasks.entrySet().removeIf(entry -> 
            "COMPLETED".equals(entry.getValue().getStatus()) && 
            entry.getValue().getEndTime() < cutoffTime
        );
    }
}
