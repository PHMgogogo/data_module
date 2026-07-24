package com.project.phm.adapter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.service.PlatformConfigService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 航新服务客户端。
 */
@Component
public class HangxinSortieClient extends BaseExternalClient {

    public HangxinSortieClient(RestTemplate hangxinRestTemplate,
                               ObjectMapper objectMapper,
                               PlatformConfigService configService) {
        super(hangxinRestTemplate, objectMapper, configService);
    }

    @Override
    protected String getPlatformName() {
        return "航新服务";
    }
}
