package com.project.phm.service;

import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.ExternalModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 三方平台机型轮询器 —— 每 5 秒调用各外源「获取机型」接口，兼作平台保活。
 *
 * <p>轮询结果直接写进 {@link PlatformRouteService}：拉取成功即标记平台可达并刷新其机型映射；
 * 拉取失败（未配置 / 超时 / 异常）即标记不可达并清空该平台机型映射，后续查询不会再路由到它。</p>
 *
 * <p>轮询是「保活 + 机型映射刷新」同一件事，不再有独立的心跳接口。</p>
 */
@Service
public class PlatformModelPoller {

    private static final Logger log = LoggerFactory.getLogger(PlatformModelPoller.class);

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 5000L;

    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final PlatformRouteService routeService;

    public PlatformModelPoller(HangxinSortieClient hangxinClient,
                               SanSanSortieClient sanSanClient,
                               PlatformRouteService routeService) {
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.routeService = routeService;
    }

    /**
     * 每 5 秒轮询一次所有外源平台的机型接口。
     *
     * <p>两个平台并发拉取；单个平台失败只影响它自己，不会中断另一平台的轮询。</p>
     */
    @Scheduled(fixedRate = POLL_INTERVAL_MS)
    public void pollModels() {
        Map<String, Object> noFilter = Collections.emptyMap();

        CompletableFuture<Void> hangxin = pollOne(hangxinClient, DataSource.HANGXIN, noFilter);
        CompletableFuture<Void> sanSan = pollOne(sanSanClient, DataSource.SAN_SAN, noFilter);

        CompletableFuture.allOf(hangxin, sanSan).join();
    }

    /** 轮询单个平台：成功刷新机型映射，失败置为不可达并清空 */
    private CompletableFuture<Void> pollOne(BaseExternalClient client, DataSource source,
                                            Map<String, Object> params) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<ExternalModelData> models = client.queryModels(params);
                routeService.markReachable(source, toModelDefinitions(models));
            } catch (Exception e) {
                log.warn("{}机型轮询失败: {}", source.getLabel(), e.getMessage());
                routeService.markUnreachable(source);
            }
        });
    }

    /** 三方机型行转为路由树的机型定义：机型键为 {@code airplaneType:id}，与下发给前端的 modelCode 同源 */
    private List<PlatformRouteService.ModelDefinition> toModelDefinitions(List<ExternalModelData> models) {
        List<PlatformRouteService.ModelDefinition> definitions = new ArrayList<>();
        if (models == null) {
            return definitions;
        }
        for (ExternalModelData model : models) {
            if (model == null) {
                continue;
            }
            String key = modelKey(model.getAirplaneType(), model.getId());
            if (key != null) {
                definitions.add(new PlatformRouteService.ModelDefinition(key, model.getAirplaneType()));
            }
        }
        return definitions;
    }

    /** 索引键：{@code airplaneType + ":" + id}，任一侧为空则整条丢弃 */
    private static String modelKey(String airplaneType, String id) {
        if (airplaneType == null || airplaneType.trim().isEmpty()) {
            return null;
        }
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return airplaneType + ":" + id;
    }
}
