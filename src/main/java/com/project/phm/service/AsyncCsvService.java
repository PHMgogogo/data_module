package com.project.phm.service;

import com.opencsv.CSVReader;
import com.project.phm.utils.CsvUtils;
import com.project.phm.utils.DbUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步CSV处理服务
 */
@Service
public class AsyncCsvService {

    private final DbUtils dbUtils;
    private final TaskManager taskManager;

    public AsyncCsvService(DbUtils dbUtils, TaskManager taskManager) {
        this.dbUtils = dbUtils;
        this.taskManager = taskManager;
    }

    /**
     * 异步处理CSV上传
     * @param fileBytes 文件字节数组（提前读取，避免临时文件被清理）
     * @param fileName 文件名
     * @param tableName 表名
     * @param taskId 任务ID
     */
    @Async
    public void processCsvAsync(byte[] fileBytes, String fileName, String tableName, String taskId) {
        try {
            // 先统计总行数
            int totalRows = countRows(fileBytes);
            taskManager.setTotalRows(taskId, totalRows);

            // 流式解析并处理
            processCsvStream(fileBytes, tableName, taskId);

        } catch (Exception e) {
            taskManager.failTask(taskId, "处理失败: " + e.getMessage());
        }
    }

    /**
     * 统计CSV文件总行数
     */
    private int countRows(byte[] fileBytes) throws Exception {
        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(fileBytes), "UTF-8"))) {
            int count = 0;
            while (reader.readNext() != null) {
                count++;
            }
            return Math.max(0, count - 1); // 减去表头
        }
    }

    /**
     * 流式处理CSV
     */
    private void processCsvStream(byte[] fileBytes, String tableName, String taskId) throws Exception {
        List<String> columns = new ArrayList<>();
        List<String[]> batch = new ArrayList<>();
        int batchSize = 1000;
        int processedRows = 0;
        int successRows = 0;
        int failedRows = 0;
        boolean firstRow = true;

        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(fileBytes), "UTF-8"))) {

            String[] row;
            while ((row = reader.readNext()) != null) {
                // 处理表头
                if (firstRow) {
                    for (String columnName : row) {
                        columns.add(CsvUtils.processColumnName(columnName));
                    }
                    // 创建表
                    dbUtils.createTable(tableName, columns);
                    firstRow = false;
                    continue;
                }

                // 收集数据行
                batch.add(row);
                processedRows++;

                // 达到批量大小，执行插入
                if (batch.size() >= batchSize) {
                    int inserted = dbUtils.batchInsert(tableName, columns, batch);
                    successRows += inserted;
                    failedRows += (batch.size() - inserted);
                    batch.clear();

                    // 更新进度
                    taskManager.updateProgress(taskId, processedRows, successRows, failedRows);
                }
            }

            // 处理剩余的数据
            if (!batch.isEmpty()) {
                int inserted = dbUtils.batchInsert(tableName, columns, batch);
                successRows += inserted;
                failedRows += (batch.size() - inserted);
                taskManager.updateProgress(taskId, processedRows, successRows, failedRows);
            }

            // 任务完成
            taskManager.completeTask(taskId, 
                String.format("上传成功！总条数：%d，成功：%d，失败：%d", 
                    processedRows, successRows, failedRows));

        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 获取任务状态
     * @param taskId 任务ID
     * @return 任务状态
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> result = new HashMap<>();
        com.project.phm.entity.UploadTask task = taskManager.getTask(taskId);
        if (task == null) {
            result.put("error", "任务不存在");
            return result;
        }
        result.put("taskId", task.getTaskId());
        result.put("fileName", task.getFileName());
        result.put("tableName", task.getTableName());
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("totalRows", task.getTotalRows());
        result.put("processedRows", task.getProcessedRows());
        result.put("successRows", task.getSuccessRows());
        result.put("failedRows", task.getFailedRows());
        result.put("message", task.getMessage());
        return result;
    }
}
