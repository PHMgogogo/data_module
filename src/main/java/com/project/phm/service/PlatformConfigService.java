package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.entity.ExternalPlatform;
import com.project.phm.mapper.ExternalPlatformMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 外来平台配置管理服务 — CRUD + 连通性测试 + URL 解析。
 */
@Service
public class PlatformConfigService {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfigService.class);

    private final ExternalPlatformMapper mapper;
    private final RestTemplate testRestTemplate;

    public PlatformConfigService(ExternalPlatformMapper mapper) {
        this.mapper = mapper;
        this.testRestTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 获取所有平台配置 */
    public List<ExternalPlatform> listAll() {
        return mapper.selectList(Wrappers.emptyWrapper());
    }

    /** 按 ID 获取 */
    public ExternalPlatform getById(Long id) {
        return mapper.selectById(id);
    }

    /** 新增（含校验 + 平台名唯一） */
    public void add(ExternalPlatform platform) {
        validate(platform);
        if (mapper.selectCount(Wrappers.<ExternalPlatform>lambdaQuery()
                .eq(ExternalPlatform::getPlatformName, platform.getPlatformName())) > 0) {
            throw new IllegalArgumentException("平台名称已存在: " + platform.getPlatformName());
        }
        mapper.insert(platform);
    }

    /** 更新（含校验 + 平台名唯一排除自身） */
    public void update(ExternalPlatform platform) {
        if (platform.getId() == null) throw new IllegalArgumentException("更新时必须提供 ID");
        if (mapper.selectById(platform.getId()) == null) throw new IllegalArgumentException("平台不存在");
        validate(platform);
        if (mapper.selectCount(Wrappers.<ExternalPlatform>lambdaQuery()
                .eq(ExternalPlatform::getPlatformName, platform.getPlatformName())
                .ne(ExternalPlatform::getId, platform.getId())) > 0) {
            throw new IllegalArgumentException("平台名称已被其他记录使用: " + platform.getPlatformName());
        }
        mapper.updateById(platform);
    }

    /** 删除 */
    public void delete(Long id) {
        if (mapper.selectById(id) == null) throw new IllegalArgumentException("平台不存在");
        mapper.deleteById(id);
    }

    /** 获取完整 base URL：http://ip:port，无配置时返回 null */
    public String getFullBaseUrl(String platformName) {
        ExternalPlatform p = mapper.selectOne(
                Wrappers.<ExternalPlatform>lambdaQuery()
                        .eq(ExternalPlatform::getPlatformName, platformName));
        if (p == null) return null;
        String ip = p.getPlatformIp();
        if (ip == null || ip.isEmpty()) return null;
        String url = "http://" + ip;
        if (p.getPort() != null) url += ":" + p.getPort();
        return url;
    }

    /** 校验：平台名必须是枚举值，IP 不能为空，端口范围正确 */
    private void validate(ExternalPlatform platform) {
        if (platform.getPlatformName() == null || platform.getPlatformName().trim().isEmpty()) {
            throw new IllegalArgumentException("平台名称不能为空");
        }
        boolean valid = Arrays.stream(ExternalPlatform.VALID_NAMES)
                .anyMatch(n -> n.equals(platform.getPlatformName()));
        if (!valid) {
            throw new IllegalArgumentException("平台名称只能为: " + Arrays.toString(ExternalPlatform.VALID_NAMES));
        }
        if (platform.getPlatformIp() == null || platform.getPlatformIp().trim().isEmpty()) {
            throw new IllegalArgumentException("平台IP不能为空");
        }
        if (platform.getPort() != null && (platform.getPort() < 1 || platform.getPort() > 65535)) {
            throw new IllegalArgumentException("端口号必须在 1-65535 之间");
        }
    }

    /** 测试连接 */
    public boolean testConnectivity(Long id) {
        ExternalPlatform p = mapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("平台不存在");
        return doTest(p);
    }

    public boolean testConnectivity(String ip, Integer port) {
        ExternalPlatform tmp = new ExternalPlatform();
        tmp.setPlatformIp(ip);
        tmp.setPort(port);
        return doTest(tmp);
    }

    private boolean doTest(ExternalPlatform platform) {
        String ip = platform.getPlatformIp();
        if (ip == null || ip.isEmpty()) return false;
        String url = "http://" + ip;
        if (platform.getPort() != null) url += ":" + platform.getPort();
        if (!url.endsWith("/")) url += "/";
        try {
            testRestTemplate.getForEntity(url, String.class);
            return true;
        } catch (Exception e) {
            log.warn("连接测试失败 [{}]: {}", url, e.getMessage());
            return false;
        }
    }
}
