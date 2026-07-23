package com.project.phm.adapter.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 外来平台 HTTP 客户端配置。
 * 为航新和 633 提供独立的 RestTemplate 实例，各自配置 base URL 和超时。
 */
@Configuration
public class SortieClientConfig {

    @Bean
    public RestTemplate hangxinRestTemplate(RestTemplateBuilder builder) {
        return builder
                .rootUri("http://hangxin-service")
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public RestTemplate sanSanRestTemplate(RestTemplateBuilder builder) {
        return builder
                .rootUri("http://633-service")
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public RestTemplate supportRestTemplate(RestTemplateBuilder builder) {
        return builder
                .rootUri("http://support-system")
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
