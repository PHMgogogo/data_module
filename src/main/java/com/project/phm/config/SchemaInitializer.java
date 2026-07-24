package com.project.phm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 数据库表结构初始化器 — 应用启动时自动创建飞机构型相关表
 *
 * 使用 IF NOT EXISTS 保证幂等性，多次启动不会重复建表
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        log.info("=== 开始初始化飞机构型相关表结构 ===");
        createAircraftModelTable();
        createAircraftConfigTable();
        createConfigItemTable();
        createConfigDataMappingTable();
        createSortieTable();
        createHealthRecordTable();
        createExternalPlatformTable();
        log.info("=== 飞机构型相关表结构初始化完成 ===");
    }

    /**
     * 飞机机型表
     */
    private void createAircraftModelTable() {
        String sql = "CREATE TABLE IF NOT EXISTS aircraft_model ("
                + "model_code VARCHAR(50) NOT NULL, "
                + "manufacturer VARCHAR(200), "
                + "description VARCHAR(500), "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (model_code)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 aircraft_model 已就绪");
    }

    /**
     * 飞机构型表（每架飞机实例）
     */
    private void createAircraftConfigTable() {
        String sql = "CREATE TABLE IF NOT EXISTS aircraft_config ("
                + "aircraft_number VARCHAR(20) NOT NULL, "
                + "model_code VARCHAR(50) NOT NULL, "
                + "airline VARCHAR(100), "
                + "config_version VARCHAR(20), "
                + "status VARCHAR(20) DEFAULT 'active', "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (aircraft_number)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 aircraft_config 已就绪");
    }

    /**
     * 构型项目表（系统/设备，树形结构）
     */
    private void createConfigItemTable() {
        String sql = "CREATE TABLE IF NOT EXISTS config_item ("
                + "item_id INT IDENTITY(1,1) NOT NULL, "
                + "model_code VARCHAR(50) NOT NULL, "
                + "parent_item_id INT, "
                + "ata_chapter VARCHAR(10), "
                + "system_name VARCHAR(200), "
                + "sub_system_name VARCHAR(200), "
                + "equipment_name VARCHAR(200), "
                + "part_number VARCHAR(100), "
                + "item_type VARCHAR(20), "
                + "PRIMARY KEY (item_id)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 config_item 已就绪");
    }

    /**
     * 构型与CSV数据关联表
     */
    private void createConfigDataMappingTable() {
        String sql = "CREATE TABLE IF NOT EXISTS config_data_mapping ("
                + "mapping_id INT IDENTITY(1,1) NOT NULL, "
                + "aircraft_number VARCHAR(20), "
                + "item_id INT, "
                + "sortie_id INT, "
                + "csv_table_name VARCHAR(200), "
                + "data_type VARCHAR(50) DEFAULT 'RAW', "
                + "data_time DATETIME, "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (mapping_id)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 config_data_mapping 已就绪");
    }

    /**
     * 架次表（每次飞行任务）
     */
    private void createSortieTable() {
        String sql = "CREATE TABLE IF NOT EXISTS sortie ("
                + "sortie_id INT IDENTITY(1,1) NOT NULL, "
                + "aircraft_number VARCHAR(20) NOT NULL, "
                + "sortie_number VARCHAR(100), "
                + "flight_date VARCHAR(20), "
                + "start_time VARCHAR(20), "
                + "end_time VARCHAR(20), "
                + "PRIMARY KEY (sortie_id)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 sortie 已就绪");
    }

    /**
     * 健康记录表（诊断/评价/预测结果）
     */
    private void createHealthRecordTable() {
        String sql = "CREATE TABLE IF NOT EXISTS health_record ("
                + "record_id INT IDENTITY(1,1) NOT NULL, "
                + "aircraft_number VARCHAR(20), "
                + "item_id INT, "
                + "record_type VARCHAR(20), "
                + "indicator_name VARCHAR(200), "
                + "indicator_value VARCHAR(500), "
                + "confidence VARCHAR(20), "
                + "record_time DATETIME, "
                + "data_source_table VARCHAR(200), "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (record_id)"
                + ")";
        jdbcTemplate.execute(sql);
        log.info("表 health_record 已就绪");
    }

    /**
     * 外来平台配置表
     */
    private void createExternalPlatformTable() {
        String sql = "CREATE TABLE IF NOT EXISTS external_platform ("
                + "id INT IDENTITY(1,1) NOT NULL, "
                + "platform_name VARCHAR(100) NOT NULL, "
                + "platform_ip VARCHAR(100) NOT NULL DEFAULT '', "
                + "port INT, "
                + "PRIMARY KEY (id)"
                + ")";
        jdbcTemplate.execute(sql);
        // 兼容旧表：添加 port 列（忽略已存在），删除 base_url 列（忽略不存在）
        try {
            jdbcTemplate.execute("ALTER TABLE external_platform ADD port INT");
        } catch (Exception ignored) { }
        try {
            jdbcTemplate.execute("ALTER TABLE external_platform DROP COLUMN base_url");
        } catch (Exception ignored) { }
        log.info("表 external_platform 已就绪");
    }
}
