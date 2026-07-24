package com.project.phm.adapter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.service.PlatformConfigService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 633 服务客户端。
 */
@Component
public class SanSanSortieClient extends BaseExternalClient {

    public SanSanSortieClient(RestTemplate sanSanRestTemplate,
                              ObjectMapper objectMapper,
                              PlatformConfigService configService) {
        super(sanSanRestTemplate, objectMapper, configService);
    }

    @Override
    protected String getPlatformName() {
        return "633服务";
    }
}
