package com.project.phm.utils;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库工具类，用于处理数据库操作
 */
@Component
public class DbUtils {

    private final JdbcTemplate jdbcTemplate;

    public DbUtils(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 检查表是否存在
     * @param tableName 表名
     * @return 是否存在
     */
    public boolean tableExists(String tableName) {
        String sql = "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName.toUpperCase());
        return count != null && count > 0;
    }

    /**
     * 获取所有以csv_开头的表
     * @return 表名列表（小写）
     */
    public List<String> getAllCsvTables() {
        String sql = "SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME LIKE 'CSV_%' ORDER BY TABLE_NAME";
        List<String> tables = jdbcTemplate.queryForList(sql, String.class);
        return tables.stream().map(String::toLowerCase).collect(Collectors.toList());
    }

    /**
     * 创建表
     * @param tableName 表名
     * @param columns 列名列表
     */
    public void createTable(String tableName, List<String> columns) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS " + tableName + " (");
        sql.append("ID INT IDENTITY(1,1) PRIMARY KEY,");

        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            sql.append(column).append(" VARCHAR(2000)");
            if (i < columns.size() - 1) {
                sql.append(",");
            }
        }
        sql.append(")");

        jdbcTemplate.execute(sql.toString());
    }

    /**
     * 批量插入数据（使用JDBC Batch优化）
     * @param tableName 表名
     * @param columns 列名列表
     * @param data 数据列表
     * @param batchSize 批量大小
     * @return 成功插入的条数
     */
    public int batchInsert(String tableName, List<String> columns, List<String[]> data, int batchSize) {
        if (data.isEmpty()) {
            return 0;
        }

        // 构建INSERT SQL
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO " + tableName + " (");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(columns.get(i));
            if (i < columns.size() - 1) {
                sql.append(",");
            }
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            sql.append("?");
            if (i < columns.size() - 1) {
                sql.append(",");
            }
        }
        sql.append(")");

        int successCount = 0;
        int totalSize = data.size();

        // 分批插入
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<String[]> batch = data.subList(i, end);

            try {
                int[] result = jdbcTemplate.batchUpdate(sql.toString(), new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int j) throws SQLException {
                        String[] row = batch.get(j);
                        for (int k = 0; k < columns.size(); k++) {
                            ps.setString(k + 1, row[k]);
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return batch.size();
                    }
                });

                successCount += result.length;
            } catch (Exception e) {
                e.printStackTrace();
                // 如果批量插入失败，尝试逐条插入
                for (String[] row : batch) {
                    try {
                        jdbcTemplate.update(sql.toString(), (Object[]) row);
                        successCount++;
                    } catch (Exception ex) {
                        // 记录失败的行
                    }
                }
            }
        }

        return successCount;
    }

    /**
     * 批量插入数据（默认批量大小1000）
     */
    public int batchInsert(String tableName, List<String> columns, List<String[]> data) {
        return batchInsert(tableName, columns, data, 1000);
    }

    /**
     * 查询表数据列表
     * @param tableName 表名
     * @return 数据列表
     */
    public List<Map<String, Object>> queryList(String tableName) {
        String sql = "SELECT * FROM " + tableName;
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 分页查询表数据（达梦数据库语法）
     * @param tableName 表名
     * @param page 页码
     * @param size 每页大小
     * @return 分页数据
     */
    public Map<String, Object> queryPage(String tableName, int page, int size) {
        int start = (page - 1) * size + 1;
        int end = page * size;
        
        // 达梦数据库分页语法：SELECT TOP 结束位置 * FROM (SELECT *, ROW_NUMBER() OVER() AS ROWNUM FROM 表名) WHERE ROWNUM >= 开始位置
        String sql = "SELECT * FROM (SELECT *, ROW_NUMBER() OVER(ORDER BY ID) AS ROWNUM_ FROM " + tableName + ") WHERE ROWNUM_ BETWEEN ? AND ?";
        List<Map<String, Object>> data = jdbcTemplate.queryForList(sql, start, end);
        
        // 移除临时的ROWNUM_列
        for (Map<String, Object> row : data) {
            row.remove("ROWNUM_");
        }
        
        int total = getTotalCount(tableName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 删除单条数据
     * @param tableName 表名
     * @param id ID
     * @return 是否成功
     */
    public boolean deleteById(String tableName, int id) {
        String sql = "DELETE FROM " + tableName + " WHERE ID = ?";
        int rows = jdbcTemplate.update(sql, id);
        return rows > 0;
    }

    /**
     * 清空表数据
     * @param tableName 表名
     */
    public void truncateTable(String tableName) {
        String sql = "TRUNCATE TABLE " + tableName;
        jdbcTemplate.execute(sql);
    }

    /**
     * 删除表
     * @param tableName 表名
     */
    public void dropTable(String tableName) {
        String sql = "DROP TABLE IF EXISTS " + tableName;
        jdbcTemplate.execute(sql);
    }

    /**
     * 获取表的列名列表
     * @param tableName 表名
     * @return 列名列表
     */
    public List<String> getColumnNames(String tableName) {
        String sql = "SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME = ? ORDER BY COLUMN_ID";
        return jdbcTemplate.queryForList(sql, String.class, tableName.toUpperCase());
    }

    /**
     * 获取表的总记录数
     * @param tableName 表名
     * @return 总记录数
     */
    public int getTotalCount(String tableName) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }
}
