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
                + "sortie_id INT, "
                + "csv_table_name VARCHAR(200), "
                + "data_type VARCHAR(50) DEFAULT 'RAW', "
                + "data_time DATETIME, "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (mapping_id)"
                + ")";
        jdbcTemplate.execute(sql);
        // 本地架次与 CSV 表严格一对一。已有历史重复数据时索引创建失败，不影响应用启动。
        createUniqueIndexQuietly("uk_cdm_sortie",
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_cdm_sortie "
                        + "ON config_data_mapping(sortie_id)");
        createUniqueIndexQuietly("uk_cdm_table",
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_cdm_table "
                        + "ON config_data_mapping(csv_table_name)");
        log.info("表 config_data_mapping 已就绪");
    }

    private void createUniqueIndexQuietly(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("唯一索引 {} 创建失败，请检查历史重复数据: {}", name, e.getMessage());
        }
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
     * 外来平台配置表
     */
    private void createExternalPlatformTable() {
        String sql = "CREATE TABLE IF NOT EXISTS external_platform ("
                + "id INT IDENTITY(1,1) NOT NULL, "
                + "platform_name VARCHAR(100) NOT NULL, "
                + "platform_config VARCHAR(1024) DEFAULT '{\"ip\":\"127.0.0.1\",\"port\":123}', "
                + "PRIMARY KEY (id)"
                + ")";
        jdbcTemplate.execute(sql);
        // 兼容旧表：添加 platform_config 列（忽略已存在）
        try {
            jdbcTemplate.execute("ALTER TABLE external_platform ADD platform_config VARCHAR(1024) "
                    + "DEFAULT '{\"ip\":\"127.0.0.1\",\"port\":123}'");
        } catch (Exception ignored) { }
        log.info("表 external_platform 已就绪");
    }
}
