package com.project.phm.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IoTDB 数据库工具类 — 时序数据操作
 *
 * 数据模型:
 *   Database: root.aircraft.{tailNumber}
 *   Device:   {deviceName} (aligned timeseries)
 *   Sensor:   {columnName} (measurement)
 *
 * 例: root.aircraft.B-1234.engine_vibration.fan_vibration
 */
@Component
public class IoTDbUtils {

    private static final Logger log = LoggerFactory.getLogger(IoTDbUtils.class);

    private final JdbcTemplate iotdb;
    private final DataSource dataSource;

    public IoTDbUtils(@Qualifier("iotdbDataSource") DataSource ds) {
        this.dataSource = ds;
        this.iotdb = new JdbcTemplate(ds);
    }

    @PostConstruct
    public void checkConnection() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            log.info("IoTDB 连接成功: {} | driver={}", meta.getURL(), meta.getDriverName());
        } catch (Exception e) {
            log.error("IoTDB 连接失败: {} | type={}", e.getMessage(), e.getClass().getName());
        }
    }

    /**
     * 获取storage group前缀
     */
    public String storageGroup(String tailNumber) {
        return "root.aircraft." + tailNumber;
    }

    /**
     * 获取完整设备路径
     */
    public String devicePath(String tailNumber, String deviceName) {
        return storageGroup(tailNumber) + "." + deviceName;
    }

    /**
     * 确保storage group存在 (IoTDB 1.x 使用 CREATE DATABASE)
     */
    public void ensureDatabase(String tailNumber) {
        String sg = storageGroup(tailNumber);
        try {
            iotdb.execute("CREATE DATABASE " + sg);
        } catch (Exception e) {
            // 已存在则忽略
        }
    }

    /**
     * 创建对齐的 time-series 列表（同一设备的所有传感器共享时间轴）
     *
     * @param tailNumber 机号
     * @param deviceName 设备名
     * @param columns    列名→类型映射, e.g. {"fan_vibration" → "FLOAT", "phase" → "TEXT"}
     */
    public void createAlignedTimeseries(String tailNumber, String deviceName, Map<String, String> columns) {
        ensureDatabase(tailNumber);
        String device = devicePath(tailNumber, deviceName);

        // 检查已存在的列
        Set<String> existing = new HashSet<>(listSensorColumns(tailNumber, deviceName));

        List<String> newCols = new ArrayList<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                newCols.add(entry.getKey() + " " + entry.getValue());
            }
        }

        if (newCols.isEmpty()) return;

        String sql = "CREATE ALIGNED TIMESERIES " + device + " (" + String.join(", ", newCols) + ")";
        try {
            iotdb.execute(sql);
            log.info("IoTDB 创建 timeseries: {}", sql);
        } catch (Exception e) {
            log.warn("IoTDB 创建 timeseries 异常(可能已存在): {} | type={}", e.getMessage(), e.getClass().getName());
        }
    }

    /**
     * 批量插入对齐数据
     *
     * @param tailNumber 机号
     * @param deviceName 设备名
     * @param timestampCol 时间戳列名 (null则用当前时间)
     * @param data        行数据: Map<列名, 值>
     */
    public void insertAlignedRows(String tailNumber, String deviceName,
                                   String timestampCol, List<Map<String, String>> data) {
        if (data.isEmpty()) return;

        String device = devicePath(tailNumber, deviceName);
        String sql = "INSERT INTO " + device + "(timestamp, %s) ALIGNED VALUES %s";

        // 收集所有列（排除时间戳列）
        Set<String> allCols = new LinkedHashSet<>();
        for (Map<String, String> row : data) {
            allCols.addAll(row.keySet());
        }
        allCols.remove(timestampCol);
        if (allCols.isEmpty()) return;

        String colList = String.join(", ", allCols);

        // 构建 values 子句
        List<String> valueClauses = new ArrayList<>();
        for (Map<String, String> row : data) {
            long ts;
            if (timestampCol != null && row.containsKey(timestampCol)) {
                ts = parseTimestamp(row.get(timestampCol));
            } else {
                ts = System.currentTimeMillis();
            }
            List<String> vals = new ArrayList<>();
            vals.add(String.valueOf(ts));
            for (String col : allCols) {
                String raw = row.get(col);
                if (raw == null || raw.trim().isEmpty()) {
                    vals.add("null");
                } else {
                    // 判断是否为数值
                    if (isNumeric(raw)) {
                        vals.add(raw);
                    } else {
                        vals.add("'" + raw.replace("'", "''") + "'");
                    }
                }
            }
            valueClauses.add("(" + String.join(", ", vals) + ")");
        }

        // IoTDB JDBC单次执行可能有限制，分批
        int batchSize = 500;
        for (int i = 0; i < valueClauses.size(); i += batchSize) {
            int end = Math.min(i + batchSize, valueClauses.size());
            List<String> batch = valueClauses.subList(i, end);
            String fullSql = String.format(sql, colList, String.join(", ", batch));
            try {
                log.debug("IoTDB 执行SQL: {}", fullSql);
                iotdb.execute(fullSql);
            } catch (Exception e) {
                log.error("IoTDB 插入失败: SQL=[{}] error={} type={}", fullSql, e.getMessage(), e.getClass().getName(), e);
            }
        }
    }

    /**
     * 查询设备的最近N条数据
     */
    public List<Map<String, Object>> queryLatest(String tailNumber, String deviceName, int limit) {
        String device = devicePath(tailNumber, deviceName);
        String sql = "SELECT * FROM " + device + " ORDER BY TIME DESC LIMIT " + limit;
        try {
            return iotdb.queryForList(sql);
        } catch (Exception e) {
            log.warn("IoTDB 查询失败(设备可能不存在): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询设备某时间范围的数据
     */
    public List<Map<String, Object>> queryRange(String tailNumber, String deviceName,
                                                 long startTime, long endTime) {
        String device = devicePath(tailNumber, deviceName);
        String sql = "SELECT * FROM " + device + " WHERE TIME >= " + startTime + " AND TIME <= " + endTime + " ORDER BY TIME";
        try {
            return iotdb.queryForList(sql);
        } catch (Exception e) {
            log.warn("IoTDB 查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 列出设备的所有传感器列
     */
    public List<String> listSensorColumns(String tailNumber, String deviceName) {
        String device = devicePath(tailNumber, deviceName);
        String sql = "SHOW TIMESERIES " + device + ".*";
        try {
            List<Map<String, Object>> rows = iotdb.queryForList(sql);
            return rows.stream()
                    .map(r -> {
                        String fullPath = (String) r.get("timeseries");
                        if (fullPath == null) fullPath = (String) r.get("column_name");
                        if (fullPath == null) return "";
                        int idx = fullPath.lastIndexOf('.');
                        return idx > 0 ? fullPath.substring(idx + 1) : fullPath;
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 列出某机号下所有设备
     */
    public List<String> listDevices(String tailNumber) {
        String sql = "SHOW DEVICES " + storageGroup(tailNumber) + ".*";
        try {
            List<Map<String, Object>> rows = iotdb.queryForList(sql);
            return rows.stream()
                    .map(r -> {
                        String dev = (String) r.get("devices");
                        if (dev == null) dev = (String) r.get("device");
                        return dev;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ==================== 辅助 ====================

    private long parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        value = value.trim();
        // 纯数字 → 视为毫秒时间戳
        if (value.matches("\\d+")) {
            return Long.parseLong(value);
        }
        // 支持多格式解析
        String normalized = value.replace("T", " ");
        String[] patterns = {
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "MM/dd/yyyy HH:mm:ss.SSS",
            "MM/dd/yyyy HH:mm:ss",
            "yyyy-MM-dd",
            "MM/dd/yyyy"
        };
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat();
        sdf.setLenient(false);
        for (String pattern : patterns) {
            try {
                sdf.applyPattern(pattern);
                return sdf.parse(normalized).getTime();
            } catch (Exception ignored) {}
        }
        return System.currentTimeMillis();
    }

    private boolean isNumeric(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        s = s.trim();
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 获取行数
     */
    public long queryCount(String tailNumber, String deviceName) {
        String device = devicePath(tailNumber, deviceName);
        try {
            List<Map<String, Object>> rows = iotdb.queryForList("SELECT COUNT(*) FROM " + device);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                for (Object val : row.values()) {
                    if (val instanceof Number) return ((Number) val).longValue();
                }
            }
        } catch (Exception e) {
            log.warn("IoTDB 查询行数失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 获取时间范围（首条/末条时间戳）
     */
    public Map<String, Object> getTimeRange(String tailNumber, String deviceName) {
        String device = devicePath(tailNumber, deviceName);
        Map<String, Object> range = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> first = iotdb.queryForList("SELECT * FROM " + device + " ORDER BY TIME ASC LIMIT 1");
            if (!first.isEmpty() && first.get(0).containsKey("Time")) {
                range.put("startTime", first.get(0).get("Time"));
            }
            List<Map<String, Object>> last = iotdb.queryForList("SELECT * FROM " + device + " ORDER BY TIME DESC LIMIT 1");
            if (!last.isEmpty() && last.get(0).containsKey("Time")) {
                range.put("endTime", last.get(0).get("Time"));
            }
        } catch (Exception e) {
            log.warn("IoTDB 查询时间范围失败: {}", e.getMessage());
        }
        return range;
    }

    /**
     * 查询数值列的统计信息（min/max/avg）
     */
    public Map<String, Map<String, Object>> queryColumnStats(String tailNumber, String deviceName, List<String> numericColumns) {
        String device = devicePath(tailNumber, deviceName);
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (String col : numericColumns) {
            try {
                String sql = String.format("SELECT MIN(%s), MAX(%s), AVG(%s), COUNT(%s) FROM %s", col, col, col, col, device);
                List<Map<String, Object>> rows = iotdb.queryForList(sql);
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.get(0);
                    Map<String, Object> colStat = new LinkedHashMap<>();
                    if (row.containsKey("MIN(" + col + ")")) colStat.put("min", row.get("MIN(" + col + ")"));
                    if (row.containsKey("MAX(" + col + ")")) colStat.put("max", row.get("MAX(" + col + ")"));
                    if (row.containsKey("AVG(" + col + ")")) colStat.put("avg", row.get("AVG(" + col + ")"));
                    if (row.containsKey("COUNT(" + col + ")")) colStat.put("count", row.get("COUNT(" + col + ")"));
                    stats.put(col, colStat);
                }
            } catch (Exception e) {
                log.warn("IoTDB 列统计失败(col={}): {}", col, e.getMessage());
            }
        }
        return stats;
    }

    /**
     * 列出设备的所有传感器列及类型
     */
    public Map<String, String> listSensorsWithTypes(String tailNumber, String deviceName) {
        String device = devicePath(tailNumber, deviceName);
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String sql = "SHOW TIMESERIES " + device + ".*";
            List<Map<String, Object>> rows = iotdb.queryForList(sql);
            for (Map<String, Object> row : rows) {
                String fullPath = (String) row.get("timeseries");
                if (fullPath == null) fullPath = (String) row.get("column_name");
                if (fullPath == null) continue;
                int idx = fullPath.lastIndexOf('.');
                String colName = idx > 0 ? fullPath.substring(idx + 1) : fullPath;
                String dataType = (String) row.get("dataType");
                result.put(colName, dataType != null ? dataType : "TEXT");
            }
        } catch (Exception e) {
            log.warn("IoTDB 查询传感器类型失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 按数值范围查询数据
     *
     * @param tailNumber 机号
     * @param deviceName 设备名
     * @param column     筛选列名
     * @param minVal     最小值（null表示不限制）
     * @param maxVal     最大值（null表示不限制）
     * @param limit      返回行数限制
     */
    public List<Map<String, Object>> queryWithValueFilter(String tailNumber, String deviceName,
                                                           String column, Double minVal, Double maxVal,
                                                           int limit) {
        String device = devicePath(tailNumber, deviceName);
        List<String> conditions = new ArrayList<>();
        if (minVal != null) conditions.add(column + " >= " + minVal);
        if (maxVal != null) conditions.add(column + " <= " + maxVal);

        String sql;
        if (conditions.isEmpty()) {
            sql = "SELECT * FROM " + device + " ORDER BY TIME DESC LIMIT " + limit;
        } else {
            sql = "SELECT * FROM " + device + " WHERE " + String.join(" AND ", conditions)
                    + " ORDER BY TIME DESC LIMIT " + limit;
        }
        try {
            return iotdb.queryForList(sql);
        } catch (Exception e) {
            log.warn("IoTDB 值范围查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取IoTDB支持的数据类型（根据样本值推断）
     */
    public static String inferDataType(String sampleValue) {
        if (sampleValue == null || sampleValue.trim().isEmpty()) return "TEXT";
        String s = sampleValue.trim();
        // 尝试整数
        try {
            Long.parseLong(s);
            return "INT64";
        } catch (NumberFormatException ignored) {}
        // 尝试浮点
        try {
            Double.parseDouble(s);
            return "DOUBLE";
        } catch (NumberFormatException ignored) {}
        // 布尔
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return "BOOLEAN";
        }
        return "TEXT";
    }

    /**
     * 检测是否为时间戳列
     */
    public static boolean isTimestampColumn(String colName) {
        if (colName == null) return false;
        String lower = colName.toLowerCase().trim();
        return lower.equals("time") || lower.equals("timestamp")
                || lower.equals("date") || lower.equals("datetime")
                || lower.contains("date") || lower.contains("time");
    }
}
