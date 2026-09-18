package com.project.phm.adapter.dto;

/**
 * 外来平台枚举类型。
 * 前端传入枚举名（HANGXIN / SAN_SAN），后端转为对应的中文平台名存入 DB。
 */
public enum PlatformType {
    HANGXIN("航新服务"),
    SAN_SAN("633服务");

    private final String displayName;

    PlatformType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
