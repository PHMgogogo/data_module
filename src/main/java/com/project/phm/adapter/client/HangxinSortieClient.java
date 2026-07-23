package com.project.phm.adapter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 航新服务 — 架次元数据查询接口客户端。
 *
 * <p>支持按机型、机号、时间范围、文件名、文件类型查询架次元数据。
 * 响应中包含 startTime、endTime、paramList 等航新特有的字段。</p>
 */
@Component
public class HangxinSortieClient extends BaseExternalClient {

    public HangxinSortieClient(RestTemplate hangxinRestTemplate, ObjectMapper objectMapper) {
        super(hangxinRestTemplate, objectMapper);
    }

    @Override
    protected String getPlatformName() {
        return "航新";
    }
}
