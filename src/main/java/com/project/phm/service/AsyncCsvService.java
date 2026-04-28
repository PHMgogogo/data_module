package com.project.phm.service;

import com.opencsv.CSVReader;
import com.project.phm.entity.ProcessingTask;
import com.project.phm.utils.CsvUtils;
import com.project.phm.utils.DataValidationUtils;
import com.project.phm.utils.DbUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步CSV处理服务
 */
@Service
public class AsyncCsvService {

    private final DbUtils dbUtils;
    private final DataValidationUtils validationUtils;
    private final ConcurrentHashMap<String, ProcessingTask> taskMap = new ConcurrentHashMap<>();

    public AsyncCsvService(DbUtils dbUtils, DataValidationUtils validationUtils) {
        this.dbUtils = dbUtils;
        this.validationUtils = validationUtils;
    }

    /**
     * 创建处理任务
     */
    public String createProcessingTask(MultipartFile file, String tableName, int expectedRows) throws Exception {
        String taskId = UUID.randomUUID().toString();
        
        ProcessingTask task = new ProcessingTask();
        task.setTaskId(taskId);
        task.setFileName(file.getOriginalFilename());
        task.setTableName(tableName);
        task.setExpectedRows(expectedRows);
        task.setStatus("PENDING");
        taskMap.put(taskId, task);

        // 立即开始处理
        processCsvAsync(file, tableName, taskId);

        return taskId;
    }

    /**
     * 异步处理CSV上传
     */
    @Async
    public void processCsvAsync(MultipartFile file, String tableName, String taskId) {
        ProcessingTask task = taskMap.get(taskId);
        if (task == null) {
            return;
        }

        try {
            task.setStatus("PROCESSING");
            task.setStartTime(System.currentTimeMillis());

            // 流式解析CSV
            List<String> columns = new ArrayList<>();
            List<String[]> dataRows = new ArrayList<>();
            List<String[]> fullData = new ArrayList<>();
            boolean firstRow = true;

            try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
                String[] row;
                while ((row = reader.readNext()) != null) {
                    if (firstRow) {
                        for (String columnName : row) {
                            columns.add(CsvUtils.processColumnName(columnName));
                        }
                        fullData.add(columns.toArray(new String[0]));
                        firstRow = false;
                    } else {
                        dataRows.add(row);
                        fullData.add(row);
                    }
                }
            }

            // 计算原始数据哈希并保存（用于后续导出校验）
            String originalDataHash = validationUtils.calculateDataHash(fullData);
            dbUtils.saveTableMetadata(tableName, "original_data_hash", originalDataHash);
            dbUtils.saveTableMetadata(tableName, "original_row_count", String.valueOf(dataRows.size()));

            // 检查是否需要建表
            if (!dbUtils.tableExists(tableName)) {
                dbUtils.createTable(tableName, columns);
            } else {
                // 表已存在，先清空表数据（避免重复）
                dbUtils.truncateTable(tableName);
            }

            // 批量入库（分批处理，每批更新进度）
            int batchSize = 1000;
            int processedRows = 0;
            int successRows = 0;

            for (int i = 0; i < dataRows.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataRows.size());
                List<String[]> batch = dataRows.subList(i, end);
                
                int inserted = dbUtils.batchInsert(tableName, columns, batch);
                successRows += inserted;
                processedRows = end;
                
                // 更新进度
                task.setProcessedRows(processedRows);
                task.setSuccessRows(successRows);
                task.setProgress((int) (processedRows * 100.0 / task.getExpectedRows()));
            }

            // 存储阶段校验
            Map<String, Object> storageValidation = validationUtils.validateStorage(
                task.getExpectedRows(), successRows
            );
            
            task.setStorageValidation(storageValidation);
            task.setEndTime(System.currentTimeMillis());
            task.setStatus("COMPLETED");
            task.setMessage("处理完成");

        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setMessage("处理失败: " + e.getMessage());
            task.setEndTime(System.currentTimeMillis());
        }
    }

    /**
     * 获取任务状态
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        ProcessingTask task = taskMap.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("fileName", task.getFileName());
        result.put("tableName", task.getTableName());
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("expectedRows", task.getExpectedRows());
        result.put("processedRows", task.getProcessedRows());
        result.put("successRows", task.getSuccessRows());
        result.put("storageValidation", task.getStorageValidation());
        result.put("message", task.getMessage());
        
        // 计算处理时间
        if (task.getEndTime() > 0 && task.getStartTime() > 0) {
            result.put("processingTime", task.getEndTime() - task.getStartTime());
        }
        
        return result;
    }
}
