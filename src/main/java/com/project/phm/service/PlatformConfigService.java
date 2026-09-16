package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.entity.ExternalPlatform;
import com.project.phm.mapper.ExternalPlatformMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 外来平台配置管理服务 — CRUD + 连通性测试 + 配置项解析。
 */
@Service
public class PlatformConfigService {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfigService.class);

    private final ExternalPlatformMapper mapper;
    private final RestTemplate testRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** 获取平台配置记录，无配置返回 null */
    public ExternalPlatform getByPlatformName(String platformName) {
        return mapper.selectOne(
                Wrappers.<ExternalPlatform>lambdaQuery()
                        .eq(ExternalPlatform::getPlatformName, platformName));
    }

    /**
     * 按平台名 + key 从 platform_config 中取配置值。
     * 不同平台需要的 key 不同，由调用方（各平台适配器）自行指定。
     *
     * @return 配置值，平台未配置 / config 为空 / key 不存在时返回 null
     */
    public String getConfigValue(String platformName, String key) {
        ExternalPlatform p = getByPlatformName(platformName);
        if (p == null) return null;
        return extractConfigValue(p.getPlatformConfig(), key);
    }

    /** 按平台名 + key 取配置值，缺省返回 defaultValue */
    public String getConfigValue(String platformName, String key, String defaultValue) {
        String value = getConfigValue(platformName, key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /**
     * 从 config JSON 中取指定 key 的字符串值（兼容数字/布尔值）。
     *
     * @return 配置值，config 非法 / 为空 / key 不存在时返回 null
     */
    public String extractConfigValue(String config, String key) {
        if (config == null || config.trim().isEmpty() || key == null) return null;
        try {
            JsonNode node = objectMapper.readTree(config).get(key);
            if (node == null || node.isNull()) return null;
            return node.asText().trim();
        } catch (Exception e) {
            log.warn("平台配置解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 获取完整 base URL：http://ip[:port]，无配置时返回 null */
    public String getFullBaseUrl(String platformName) {
        return buildBaseUrl(getByPlatformName(platformName), platformName);
    }

    /**
     * 按平台名 + 端口配置 key 拼出 base URL：http://ip[:port]。
     *
     * <p>不同平台、不同业务用不同的端口 key，如航新服务非构型请求用 {@code port1}、
     * 构型请求用 {@code port2}。</p>
     *
     * @param portKey  端口配置 key，为 null 时按 {@link ExternalPlatform#KEY_PORT} 取
     * @return base URL，平台未配置 / 无 ip 时返回 null
     */
    public String getFullBaseUrl(String platformName, String portKey) {
        if (portKey == null) return getFullBaseUrl(platformName);
        ExternalPlatform p = getByPlatformName(platformName);
        if (p == null) return null;
        return buildBaseUrl(p.getPlatformConfig(), platformName, portKey);
    }

    /** 校验：平台名必须是枚举值，config 必须是合法 JSON 且含 ip，端口范围正确 */
    private void validate(ExternalPlatform platform) {
        if (platform.getPlatformName() == null || platform.getPlatformName().trim().isEmpty()) {
            throw new IllegalArgumentException("平台名称不能为空");
        }
        boolean valid = Arrays.stream(ExternalPlatform.VALID_NAMES)
                .anyMatch(n -> n.equals(platform.getPlatformName()));
        if (!valid) {
            throw new IllegalArgumentException("平台名称只能为: " + Arrays.toString(ExternalPlatform.VALID_NAMES));
        }
        String config = platform.getPlatformConfig();
        if (config == null || config.trim().isEmpty()) {
            throw new IllegalArgumentException("平台配置不能为空");
        }
        try {
            objectMapper.readTree(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("平台配置必须是合法的 JSON");
        }
        if (extractConfigValue(config, ExternalPlatform.KEY_IP) == null) {
            throw new IllegalArgumentException("平台配置中缺少 " + ExternalPlatform.KEY_IP + " 配置项");
        }
        // 任意以 port 开头的 key（port / port1 / port2 ...）都按端口校验
        for (String key : portKeys(config)) {
            String port = extractConfigValue(config, key);
            if (port == null || port.isEmpty()) continue;
            try {
                int v = Integer.parseInt(port);
                if (v < 1 || v > 65535) throw new IllegalArgumentException(key + " 必须在 1-65535 之间");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " 必须是数字");
            }
        }
    }

    /** 取出 config 中所有以 port 开头的 key */
    private List<String> portKeys(String config) {
        try {
            List<String> keys = new ArrayList<>();
            objectMapper.readTree(config).fieldNames()
                    .forEachRemaining(k -> { if (k.startsWith("port")) keys.add(k); });
            return keys;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 测试连接 */
    public boolean testConnectivity(Long id) {
        ExternalPlatform p = mapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("平台不存在");
        return doTest(buildBaseUrl(p.getPlatformConfig(), p.getPlatformName(), testPortKey(p.getPlatformConfig())));
    }

    /** 测试连接（不落库），config 为 JSON 字符串 */
    public boolean testConnectivity(String config) {
        return doTest(buildBaseUrl(config, null, testPortKey(config)));
    }

    /**
     * 连通性测试用的端口 key：优先 {@code port1}，没有则退回通用的 {@code port}。
     *
     * <p>航新配置了 port1 / port2，非构型请求走 port1，测试按 port1 探测。</p>
     */
    private String testPortKey(String config) {
        String port1 = extractConfigValue(config, ExternalPlatform.KEY_PORT1);
        return (port1 != null && !port1.isEmpty()) ? ExternalPlatform.KEY_PORT1 : ExternalPlatform.KEY_PORT;
    }

    /** 由 config JSON 拼出 base URL：http://ip[:port]，ip 缺失返回 null */
    private String buildBaseUrl(String config, String platformName) {
        return buildBaseUrl(config, platformName, ExternalPlatform.KEY_PORT);
    }

    /** 由 config JSON 拼出 base URL：http://ip[:portKey]，ip 缺失返回 null */
    private String buildBaseUrl(String config, String platformName, String portKey) {
        String ip = extractConfigValue(config, ExternalPlatform.KEY_IP);
        if (ip == null || ip.isEmpty()) {
            if (platformName != null) log.warn("[{}] platform_config 中未配置 {}", platformName, ExternalPlatform.KEY_IP);
            return null;
        }
        String url = "http://" + ip;
        String port = portKey == null ? null : extractConfigValue(config, portKey);
        if (port == null || port.isEmpty()) {
            // 指定 key 缺失时，退回通用 key（未指定 key 时 key 本身就是通用 key，不再重复取）
            if (portKey != null && !ExternalPlatform.KEY_PORT.equals(portKey)) {
                port = extractConfigValue(config, ExternalPlatform.KEY_PORT);
            }
            if (port != null && !port.isEmpty() && portKey != null && platformName != null) {
                log.warn("[{}] platform_config 中未配置 {}，已退回 {}", platformName, portKey, ExternalPlatform.KEY_PORT);
            }
        }
        if (port != null && !port.isEmpty()) url += ":" + port;
        return url;
    }

    /** 由平台记录拼出 base URL */
    private String buildBaseUrl(ExternalPlatform platform, String platformName) {
        if (platform == null) return null;
        return buildBaseUrl(platform.getPlatformConfig(), platformName);
    }

    private boolean doTest(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return false;
        String url = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        try {
            testRestTemplate.getForEntity(url, String.class);
            return true;
        } catch (Exception e) {
            log.warn("连接测试失败 [{}]: {}", url, e.getMessage());
            return false;
        }
    }
}
