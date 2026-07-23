package com.project.phm.adapter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 633 服务 — 架次元数据查询接口客户端。
 *
 * <p>支持按机型、机号、时间范围、架次号、参数列表查询架次元数据。
 * 返回字段比航新少（无 startTime/endTime/paramList）。</p>
 */
@Component
public class SanSanSortieClient extends BaseExternalClient {

    public SanSanSortieClient(RestTemplate sanSanRestTemplate, ObjectMapper objectMapper) {
        super(sanSanRestTemplate, objectMapper);
    }

    @Override
    protected String getPlatformName() {
        return "633";
    }
}
