package com.project.phm.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * IoTDB 数据源配置
 *
 * 默认端口 6667，认证用户 root/root
 *
 * 注意：不在此处创建 JdbcTemplate bean，以免覆盖 Spring Boot 自动配置的达梦 JdbcTemplate。
 *       IoTDbUtils 内部通过 iotdbDataSource 自行创建 JdbcTemplate 实例。
 */
@Configuration
public class IoTDBConfig {

    @Bean
    public DataSource iotdbDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.apache.iotdb.jdbc.IoTDBDriver");
        ds.setUrl("jdbc:iotdb://127.0.0.1:6667/");
        ds.setUsername("root");
        ds.setPassword("root");
        return ds;
    }
}
