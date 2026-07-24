package com.project.phm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.project.phm.adapter.dto.PlatformType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 外来平台配置 — 只管理航新服务和 633 服务的 IP 和端口。
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

    @Schema(description = "平台IP", example = "192.168.1.100")
    private String platformIp;

    @Schema(description = "端口", example = "58080")
    private Integer port;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }
    public String getPlatformIp() { return platformIp; }
    public void setPlatformIp(String platformIp) { this.platformIp = platformIp; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
}
