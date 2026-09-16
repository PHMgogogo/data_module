package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.project.phm.adapter.dto.PlatformType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 外来平台配置 — 平台连接信息统一存在 platform_config 中。
 *
 * <p>不同平台所需的 key 不同（如航新服务用 ip/port，633 服务用 ip/port/basePath），
 * 由各平台自身约定，业务代码按平台名 + key 从 config 中取值。
 */
@Schema(description = "外来平台配置")
@TableName("external_platform")
public class ExternalPlatform {

    /** 允许的平台名称（来自枚举） */
    public static final String[] VALID_NAMES = {
        PlatformType.HANGXIN.getDisplayName(),
        PlatformType.SAN_SAN.getDisplayName()
    };

    @Schema(description = "主键，自增")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "平台名称，仅允许: 航新服务 / 633服务", example = "航新服务")
    private String platformName;

    @Schema(description = "平台配置 JSON，不同平台的 key 不同",
            example = "{\"ip\":\"127.0.0.1\",\"port\":123}")
    private String platformConfig;

    /** config 中平台 IP 的 key */
    public static final String KEY_IP = "ip";

    /** config 中平台端口的 key（通用，未区分业务时使用） */
    public static final String KEY_PORT = "port";

    /** 航新服务：查询构型（getDzgxxx）使用的端口 key */
    public static final String KEY_PORT2 = "port2";

    /** 航新服务：除构型外其它请求使用的端口 key */
    public static final String KEY_PORT1 = "port1";

    /** 兼容旧表字段（platform_ip / port 列已废弃，不再读写） */
    @Deprecated
    @TableField(exist = false)
    private String platformIp;

    @Deprecated
    @TableField(exist = false)
    private Integer port;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }
    public String getPlatformConfig() { return platformConfig; }
    public void setPlatformConfig(String platformConfig) { this.platformConfig = platformConfig; }
}
