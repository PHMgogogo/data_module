package com.project.phm.adapter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.entity.ExternalPlatform;
import com.project.phm.service.PlatformConfigService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 航新服务客户端。
 *
 * <p>航新配置了 port1 / port2 两个端口：查询构型（getDzgxxx）走 port2，
 * 其余请求走 port1。</p>
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

    /** 非构型请求走 port1 */
    @Override
    protected String getPortKey() {
        return ExternalPlatform.KEY_PORT1;
    }

    /** 构型查询走 port2 */
    @Override
    protected String getConfigPortKey() {
        return ExternalPlatform.KEY_PORT2;
    }
}
