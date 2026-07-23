package com.project.phm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * OpenAPI (Swagger 3) 配置类
 * 访问地址: http://localhost:8080/swagger-ui.html
 * API JSON: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PHM 预测与健康管理系统 API")
                        .version("1.0.0")
                        .description("基于 Spring Boot 的飞机预测与健康管理（PHM）系统，提供飞机单机管理、" +
                                "CSV 数据导入/查询/导出、健康记录管理等功能。\n\n" +
                                "**数据库**：达梦数据库（主库）+ IoTDB（时序库）\n\n" +
                                "**ORM**：MyBatis-Plus\n\n" +
                                "**基础路径**：`/aircraft`（构型相关）、`/csv`（数据相关）")
                        .contact(new Contact()
                                .name("PHM Team")
                                .email("admin@phm.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(Arrays.asList(
                        new Server().url("/").description("当前服务器地址（支持反向代理/frp）")))
                .tags(buildTags());
    }

    /**
     * 构建分组Tag列表（按README.md的模块划分）
     */
    private List<Tag> buildTags() {
        return Arrays.asList(
                // 飞机构型管理模块
                new Tag().name("01-机型管理").description("飞机机型的增删查操作 /aircraft/models"),
                new Tag().name("02-飞机单机管理").description("飞机单机（按机号）的增删查操作 /aircraft/plane"),
                new Tag().name("03-构型项目管理").description("GJB章节树形结构：系统→子系统→设备 /aircraft/config-items，及数据关联查询 /aircraft/mappings"),
                new Tag().name("04-架次管理").description("飞机架次（飞行任务）的增删查操作 /aircraft/sorties"),
                new Tag().name("05-CSV数据管理").description("CSV上传、查询、分析、导出等所有CSV相关操作 /csv/*")
        );
    }
}
