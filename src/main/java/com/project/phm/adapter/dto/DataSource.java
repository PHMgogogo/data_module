package com.project.phm.adapter.dto;

/**
 * 数据来源 — 一条「机型 / 单机 / 架次」记录到底来自哪个源。
 *
 * <p>与 {@link PlatformType} 的区别：{@code PlatformType} 描述的是**平台配置**
 * （中文平台名，只含两个外源，且 {@code ExternalPlatform.VALID_NAMES} 的校验依赖它），
 * 而本枚举描述的是**运行时数据的归属**，因此多出一个 {@code LOCAL}。</p>
 *
 * <p>平台路由索引（{@code PlatformRouteService}）的键解析结果就是本枚举。</p>
 */
public enum DataSource {

    /** 本地达梦库 */
    LOCAL("本地"),

    /** 航新服务 */
    HANGXIN("航新"),

    /** 633 服务 */
    SAN_SAN("633");

    private final String label;

    DataSource(String label) {
        this.label = label;
    }

    /** 简短标签，仅用于日志，不用于查平台配置 */
    public String getLabel() {
        return label;
    }

    /** 是否为外来（三方）平台 */
    public boolean isExternal() {
        return this != LOCAL;
    }
}
