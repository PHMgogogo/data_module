package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ExternalAircraftData;
import com.project.phm.adapter.dto.ExternalConfigPage;
import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.entity.*;
import com.project.phm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 飞机单机管理服务 — 机型/构型/系统设备关联的业务逻辑
 */
@Service
public class AircraftConfigService {

    private static final Logger log = LoggerFactory.getLogger(AircraftConfigService.class);

    /** 第三方平台机型行 manufacturer / description / createdAt 的占位符 */
    private static final String PLACEHOLDER = "-";

    private static final String LABEL_HANGXIN = "航新";
    private static final String LABEL_SANSAN = "633";

    /** 三方构型接口的分页参数：前端未暴露，固定默认值 */
    private static final int DEFAULT_CONFIG_PAGE = 1;
    private static final int DEFAULT_CONFIG_ROWS = 10;
    /** 三方构型翻页取全的页数上限，防三方 total 异常导致无限翻页 */
    private static final int MAX_CONFIG_PAGES = 100;

    /** 三方构型行的 itemType 固定值（GXMC 落在 equipmentName） */
    private static final String EXTERNAL_ITEM_TYPE = "EQUIPMENT";

    private final AircraftModelMapper aircraftModelMapper;
    private final AircraftConfigMapper aircraftConfigMapper;
    private final ConfigItemMapper configItemMapper;
    private final ConfigDataMappingMapper configDataMappingMapper;
    private final SortieMapper sortieMapper;
    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;
    private final String configPath;

    /**
     * 三方构型内存索引：SSFJH → 该实体号下的全部三方构型行，整体替换、不逐条改。
     *
     * <p>存在的理由：三方构型接口不支持按机型过滤，而前端拿到的"机型"可能是三方实体号，
     * 所以只能在内存里备一份全量，按 modelCode 命中后直接切片返回。</p>
     *
     * <p>只在"不传 modelCode"的查询里刷新；只在两个平台都没取到数据时保留旧值。</p>
     */
    private volatile Map<String, List<ConfigItem>> externalConfigIndex = Collections.emptyMap();

    public AircraftConfigService(AircraftModelMapper aircraftModelMapper,
                                  AircraftConfigMapper aircraftConfigMapper,
                                  ConfigItemMapper configItemMapper,
                                  ConfigDataMappingMapper configDataMappingMapper,
                                  SortieMapper sortieMapper,
                                  HangxinSortieClient hangxinClient,
                                  SanSanSortieClient sanSanClient,
                                  @Value("${support-system.paths.getDzgxxx}") String configPath) {
        this.aircraftModelMapper = aircraftModelMapper;
        this.aircraftConfigMapper = aircraftConfigMapper;
        this.configItemMapper = configItemMapper;
        this.configDataMappingMapper = configDataMappingMapper;
        this.sortieMapper = sortieMapper;
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.configPath = configPath;
    }

    // ==================== 机型管理 ====================

    /**
     * 查询全部机型：本地 aircraft_model 行在前，随后依次拼接航新、633 平台的机型，跨源不去重。
     *
     * <p>第三方行的 modelCode 为 {@code airplaneType + ":" + id}，manufacturer / description /
     * createdAt 固定为 {@code "-"}；这些实例仅用于响应，不参与任何持久化。</p>
     *
     * <p>任一第三方平台未配置或不可达时仅跳过该平台并记日志，本地数据照常返回；
     * 本地库异常不吞，直接向上抛出由控制器返回 500。</p>
     */
    public List<AircraftModel> listModels() {
        // 空 Map 表示不携带任何过滤条件：BaseExternalClient.queryModels 仅在
        // params 中 airplaneType 非 null 时才拼接该参数，故等价于查询三方全部机型。
        Map<String, Object> noFilter = Collections.emptyMap();

        // 先派发两个外源请求，使其与下面的本地查询重叠执行
        CompletableFuture<List<AircraftModel>> hangxinFuture =
                CompletableFuture.supplyAsync(() -> queryModelsSafe(hangxinClient, LABEL_HANGXIN, noFilter));
        CompletableFuture<List<AircraftModel>> sanSanFuture =
                CompletableFuture.supplyAsync(() -> queryModelsSafe(sanSanClient, LABEL_SANSAN, noFilter));

        // 本地查询内联执行：异常不吞，由控制器按既有逻辑返回 500
        List<AircraftModel> localModels = listLocalModels();

        int localCount = localModels == null ? 0 : localModels.size();
        List<AircraftModel> merged = new ArrayList<>(localCount + 16);
        if (localModels != null) {
            merged.addAll(localModels);
        }

        List<AircraftModel> hangxinModels = joinSafe(hangxinFuture, LABEL_HANGXIN);
        List<AircraftModel> sanSanModels = joinSafe(sanSanFuture, LABEL_SANSAN);
        merged.addAll(hangxinModels);
        merged.addAll(sanSanModels);

        log.info("/aircraft/models 聚合完成: 本地={}, 航新={}, 633={}, 合计={}",
                localCount, hangxinModels.size(), sanSanModels.size(), merged.size());
        return merged;
    }

    /** 仅查本地 aircraft_model 表，按机型代码升序 */
    public List<AircraftModel> listLocalModels() {
        return aircraftModelMapper.selectList(
                Wrappers.<AircraftModel>lambdaQuery().orderByAsc(AircraftModel::getModelCode));
    }

    // ==================== 外源机型查询（带异常隔离） ====================

    /** 单平台查询：未配置 / 不可达 / 超时 / HTTP 4xx-5xx / 解析异常均在此吞掉 */
    private List<AircraftModel> queryModelsSafe(BaseExternalClient client, String label,
                                                Map<String, Object> params) {
        try {
            return toModels(client.queryModels(params));
        } catch (Exception e) {
            log.warn("{}机型查询失败，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 第三方行转为响应载体：modelCode = airplaneType + ":" + id，其余三字段为占位符。
     */
    private List<AircraftModel> toModels(List<ExternalModelData> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<AircraftModel> models = new ArrayList<>(raw.size());
        for (ExternalModelData ext : raw) {
            if (ext == null) {
                continue;
            }
            AircraftModel model = new AircraftModel();
            model.setModelCode(ext.getAirplaneType() + ":" + ext.getId());
            model.setManufacturer(PLACEHOLDER);
            model.setDescription(PLACEHOLDER);
            model.setCreatedAt(PLACEHOLDER);
            models.add(model);
        }
        return models;
    }

    /** 兜底 join：正常情况下 future 内的 try/catch 已保证不抛异常 */
    private List<AircraftModel> joinSafe(CompletableFuture<List<AircraftModel>> future, String label) {
        try {
            List<AircraftModel> list = future.join();
            return list == null ? Collections.<AircraftModel>emptyList() : list;
        } catch (Exception e) {
            log.warn("{}机型聚合任务异常，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 外源单机查询（带异常隔离） ====================

    /** 单平台查询：未配置 / 不可达 / 超时 / HTTP 4xx-5xx / 解析异常均在此吞掉 */
    private List<Aircraft> queryPlanesSafe(BaseExternalClient client, String label,
                                           Map<String, Object> params) {
        try {
            return toPlanes(client.queryAircrafts(params));
        } catch (Exception e) {
            log.warn("{}单机查询失败，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 第三方行转为响应载体：aircraftNumber = airplaneNum，modelCode = airplaneType，其余为占位符。
     */
    private List<Aircraft> toPlanes(List<ExternalAircraftData> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Aircraft> planes = new ArrayList<>(raw.size());
        for (ExternalAircraftData ext : raw) {
            if (ext == null) {
                continue;
            }
            Aircraft plane = new Aircraft();
            plane.setAircraftNumber(ext.getAirplaneNum());
            plane.setModelCode(ext.getAirplaneType());
            plane.setAirline(PLACEHOLDER);
            plane.setConfigVersion(PLACEHOLDER);
            plane.setStatus(PLACEHOLDER);
            plane.setCreatedAt(PLACEHOLDER);
            planes.add(plane);
        }
        return planes;
    }

    /** 兜底 join：正常情况下 future 内的 try/catch 已保证不抛异常 */
    private List<Aircraft> joinPlanesSafe(CompletableFuture<List<Aircraft>> future, String label) {
        try {
            List<Aircraft> list = future.join();
            return list == null ? Collections.<Aircraft>emptyList() : list;
        } catch (Exception e) {
            log.warn("{}单机聚合任务异常，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    public AircraftModel getModel(String modelCode) {
        return aircraftModelMapper.selectById(modelCode);
    }

    public void addModel(AircraftModel model) {
        if (aircraftModelMapper.selectCount(
                Wrappers.<AircraftModel>lambdaQuery().eq(AircraftModel::getModelCode, model.getModelCode())) > 0) {
            throw new IllegalArgumentException("机型代码已存在: " + model.getModelCode());
        }
        aircraftModelMapper.insert(model);
    }

    public void removeModel(String modelCode) {
        if (aircraftModelMapper.selectById(modelCode) == null) {
            throw new IllegalArgumentException("机型不存在: " + modelCode);
        }
        Long planeCount = aircraftConfigMapper.selectCount(
                Wrappers.<Aircraft>lambdaQuery().eq(Aircraft::getModelCode, modelCode));
        if (planeCount > 0) {
            throw new IllegalArgumentException("请先删除该机型下的所有单机（共" + planeCount + "架）");
        }
        Long itemCount = configItemMapper.selectCount(
                Wrappers.<ConfigItem>lambdaQuery().eq(ConfigItem::getModelCode, modelCode));
        if (itemCount > 0) {
            throw new IllegalArgumentException("请先删除该机型下的所有构型项目（共" + itemCount + "个）");
        }
        aircraftModelMapper.deleteById(modelCode);
    }

    // ==================== 飞机单机管理 ====================

    /**
     * 查询单机列表：本地 aircraft_config 行在前，随后依次拼接航新、633 平台的单机，跨源不去重。
     *
     * <p>第三方行的 {@code aircraftNumber} 取原 {@code airplaneNum}、{@code modelCode} 取原
     * {@code airplaneType}；airline / configVersion / status / createdAt 固定为 {@code "-"}。
     * 这些实例仅用于响应，不参与任何持久化。</p>
     *
     * <p>三方请求参数：modelCode 非空时透传为 {@code airplaneType} 做过滤；{@code airplaneNum}
     * 新接口未暴露，恒为 null（不拼接）。</p>
     *
     * <p>任一第三方平台未配置或不可达时仅跳过该平台并记日志，本地数据照常返回；
     * 本地库异常不吞，直接向上抛出由控制器返回 500。</p>
     */
    public List<Aircraft> listPlanes(String modelCode) {
        // BaseExternalClient.queryAircrafts 仅在 params 中 airplaneType / airplaneNum 非 null 时才拼接该参数
        Map<String, Object> params = new HashMap<>();
        if (modelCode != null && !modelCode.isEmpty()) {
            params.put("airplaneType", modelCode);
        }

        // 先派发两个外源请求，使其与下面的本地查询重叠执行
        CompletableFuture<List<Aircraft>> hangxinFuture =
                CompletableFuture.supplyAsync(() -> queryPlanesSafe(hangxinClient, LABEL_HANGXIN, params));
        CompletableFuture<List<Aircraft>> sanSanFuture =
                CompletableFuture.supplyAsync(() -> queryPlanesSafe(sanSanClient, LABEL_SANSAN, params));

        // 本地查询内联执行：异常不吞，由控制器按既有逻辑返回 500
        List<Aircraft> localPlanes = listLocalPlanes(modelCode);

        int localCount = localPlanes == null ? 0 : localPlanes.size();
        List<Aircraft> merged = new ArrayList<>(localCount + 16);
        if (localPlanes != null) {
            merged.addAll(localPlanes);
        }

        List<Aircraft> hangxinPlanes = joinPlanesSafe(hangxinFuture, LABEL_HANGXIN);
        List<Aircraft> sanSanPlanes = joinPlanesSafe(sanSanFuture, LABEL_SANSAN);
        merged.addAll(hangxinPlanes);
        merged.addAll(sanSanPlanes);

        log.info("/aircraft/plane 聚合完成: 本地={}, 航新={}, 633={}, 合计={}",
                localCount, hangxinPlanes.size(), sanSanPlanes.size(), merged.size());
        return merged;
    }

    /** 仅查本地 aircraft_config 表，按机号升序；modelCode 非空时按机型过滤 */
    public List<Aircraft> listLocalPlanes(String modelCode) {
        if (modelCode != null && !modelCode.isEmpty()) {
            return aircraftConfigMapper.selectList(
                    Wrappers.<Aircraft>lambdaQuery()
                            .eq(Aircraft::getModelCode, modelCode)
                            .orderByAsc(Aircraft::getAircraftNumber));
        }
        return aircraftConfigMapper.selectList(
                Wrappers.<Aircraft>lambdaQuery().orderByAsc(Aircraft::getAircraftNumber));
    }

    public Aircraft getPlane(String aircraftNumber) {
        return aircraftConfigMapper.selectById(aircraftNumber);
    }

    public void addPlane(Aircraft aircraft) {
        if (aircraft.getAircraftNumber() == null || aircraft.getAircraftNumber().trim().isEmpty()) {
            throw new IllegalArgumentException("机号不能为空");
        }
        if (aircraftModelMapper.selectById(aircraft.getModelCode()) == null) {
            throw new IllegalArgumentException("机型不存在: " + aircraft.getModelCode());
        }
        if (aircraftConfigMapper.selectById(aircraft.getAircraftNumber()) != null) {
            throw new IllegalArgumentException("机号已存在: " + aircraft.getAircraftNumber());
        }
        if (aircraft.getStatus() == null || aircraft.getStatus().trim().isEmpty()) {
            aircraft.setStatus("active");
        }
        aircraftConfigMapper.insert(aircraft);
    }

    public void removePlane(String aircraftNumber) {
        if (aircraftConfigMapper.selectById(aircraftNumber) == null) {
            throw new IllegalArgumentException("单机不存在: " + aircraftNumber);
        }
        Long sortieCount = sortieMapper.selectCount(
                Wrappers.<Sortie>lambdaQuery().eq(Sortie::getAircraftNumber, aircraftNumber));
        if (sortieCount > 0) {
            throw new IllegalArgumentException("请先删除该飞机的所有架次（共" + sortieCount + "个）");
        }
        aircraftConfigMapper.deleteById(aircraftNumber);
    }

    /**
     * 获取某机型下的所有可用机号
     */
    public List<String> listActiveAircraftNumbers(String modelCode) {
        List<Aircraft> configs = aircraftConfigMapper.selectList(
                Wrappers.<Aircraft>lambdaQuery()
                        .eq(Aircraft::getModelCode, modelCode)
                        .eq(Aircraft::getStatus, "active")
                        .orderByAsc(Aircraft::getAircraftNumber));
        return configs.stream().map(Aircraft::getAircraftNumber).collect(Collectors.toList());
    }

    // ==================== 构型项目管理 ====================

    /**
     * 查询构型项目列表：按 modelCode 走两个互斥的分支，一次调用只返回一个来源的数据，不做跨源拼接。
     *
     * <p><b>传了 modelCode</b>：先与内存里的三方 SSFJH 索引比对 —— 命中即认定这是三方实体号，
     * 直接返回该实体号下的全部三方构型行；<b>未命中的才当作本地机型代码</b>查 config_item 表
     * （按 GJB 章节 + itemId 升序，异常不吞、由控制器返回 500）。</p>
     *
     * <p><b>不传 modelCode</b>：全量重拉三方构型（各平台翻页取全），依次拼接航新、633，
     * 跨源不去重，本地行不参与；随后用这批数据<b>整体替换</b> SSFJH 索引，并原样返回。
     * 任一平台未配置或不可达时仅跳过该源并记日志，两个平台都拿不到数据时返回空列表。</p>
     *
     * <p>三方行的字段映射：{@code GXBS → itemId}、{@code SJGXBS → parentItemId}、
     * {@code GXMC → equipmentName}、{@code SSFJH → modelCode}、{@code JJH → partNumber}，
     * {@code itemType} 固定为 {@code EQUIPMENT}；{@code AZWZ} 及三方其余字段一律忽略。
     * 三方 id 为非数字串（如 {@code gx-jx20a-01}）时解析失败置 null，不整条丢弃。
     * 这些实例仅用于响应，不参与任何持久化。</p>
     */
    public List<ConfigItem> listItems(String modelCode) {
        // 传了机型：先按三方实体号（SSFJH）匹配内存索引，命中就返回该实体号下的全部三方构型
        if (modelCode != null && !modelCode.isEmpty()) {
            List<ConfigItem> externalHit = externalConfigIndex.get(modelCode.trim());
            if (externalHit != null) {
                log.info("/aircraft/config-items 命中三方 SSFJH 索引: modelCode={}, 构型数={}",
                        modelCode, externalHit.size());
                return externalHit;
            }
            // 未命中：当作本地机型代码查 config_item
            return listLocalItems(modelCode);
        }

        // 不传机型：全量重拉三方 → 整体替换索引 → 返回这批全量行
        List<ConfigItem> all = queryAllExternalConfigItems();
        refreshExternalConfigIndex(all);
        return all;
    }

    /**
     * 仅查本地 config_item 表，按 GJB 章节 + itemId 升序；modelCode 非空时按机型过滤。
     */
    public List<ConfigItem> listLocalItems(String modelCode) {
        if (modelCode != null && !modelCode.isEmpty()) {
            return configItemMapper.selectList(
                    Wrappers.<ConfigItem>lambdaQuery()
                            .eq(ConfigItem::getModelCode, modelCode)
                            .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
        }
        return listAllItems();
    }

    /**
     * 获取所有机型的构型项目（不过滤机型），按 GJB 章节排序。
     */
    public List<ConfigItem> listAllItems() {
        return configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
    }

    // ==================== 外源构型查询（带异常隔离） ====================

    /**
     * 并发拉取两个平台的三方构型全量，按 航新 → 633 顺序拼接，跨源不去重。
     */
    private List<ConfigItem> queryAllExternalConfigItems() {
        CompletableFuture<List<ConfigItem>> hangxinFuture = CompletableFuture.supplyAsync(
                () -> queryAllConfigItemsSafe(hangxinClient, LABEL_HANGXIN));
        CompletableFuture<List<ConfigItem>> sanSanFuture = CompletableFuture.supplyAsync(
                () -> queryAllConfigItemsSafe(sanSanClient, LABEL_SANSAN));

        List<ConfigItem> hangxinItems = joinConfigSafe(hangxinFuture, LABEL_HANGXIN);
        List<ConfigItem> sanSanItems = joinConfigSafe(sanSanFuture, LABEL_SANSAN);

        List<ConfigItem> merged = new ArrayList<>(hangxinItems.size() + sanSanItems.size());
        merged.addAll(hangxinItems);
        merged.addAll(sanSanItems);

        log.info("/aircraft/config-items 三方全量查询完成: 航新={}, 633={}, 合计={}",
                hangxinItems.size(), sanSanItems.size(), merged.size());
        return merged;
    }

    /**
     * 单平台翻页取全：每页 {@code rows = DEFAULT_CONFIG_ROWS}，收齐 total / 空页 / 不满一页 /
     * 撞上 {@link #MAX_CONFIG_PAGES} 即停。
     *
     * <p>未配置 / 不可达 / 超时 / HTTP 4xx-5xx / 解析异常均在此吞掉，并保留该平台前面已取到的页，
     * 不因某页失败丢掉整源数据。</p>
     */
    private List<ConfigItem> queryAllConfigItemsSafe(BaseExternalClient client, String label) {
        List<ConfigItem> collected = new ArrayList<>();
        List<Map<String, Object>> previousRows = null;

        for (int page = DEFAULT_CONFIG_PAGE; page < DEFAULT_CONFIG_PAGE + MAX_CONFIG_PAGES; page++) {
            // 分页参数由后端给定：BaseExternalClient.queryConfigItems 会跳过 null 值
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("page", page);
            params.put("rows", DEFAULT_CONFIG_ROWS);

            ExternalConfigPage result;
            try {
                result = client.queryConfigItems(configPath, params);
            } catch (Exception e) {
                log.warn("{}构型查询失败（page={}），已跳过该数据源: {}", label, page, e.getMessage());
                break;
            }

            List<Map<String, Object>> rows = result.getRows();
            if (rows.isEmpty()) {
                break;
            }
            // 三方若忽略 page 参数会反复返回同一页，靠"与上一页完全相同"兜底退出，避免重复收行
            if (rows.equals(previousRows)) {
                log.warn("{}构型接口 page={} 返回与上一页完全相同的数据，判定其不翻页，提前结束", label, page);
                break;
            }

            collected.addAll(toConfigItems(rows));
            // 已收齐 total，或拿到不满一页的尾页
            if (result.getTotal() > 0 && collected.size() >= result.getTotal()) {
                break;
            }
            if (rows.size() < DEFAULT_CONFIG_ROWS) {
                break;
            }
            previousRows = rows;
        }
        return collected;
    }

    /**
     * 用最新一次三方全量重建 SSFJH 索引（键即三方行映射后的 {@code modelCode}，做 trim；
     * 没有 SSFJH 的行不建索引，因为 modelCode 永远匹配不到它）。
     *
     * <p>两个平台都没取到数据时<b>不刷新</b>，保留旧索引 —— 避免三方抽风把索引清空，
     * 导致后续所有按 modelCode 的查询全部回落到本地。</p>
     */
    private void refreshExternalConfigIndex(List<ConfigItem> all) {
        if (all == null || all.isEmpty()) {
            log.warn("三方构型本次未取到任何数据，保留旧 SSFJH 索引（共 {} 个实体号）",
                    externalConfigIndex.size());
            return;
        }

        Map<String, List<ConfigItem>> index = new LinkedHashMap<>();
        for (ConfigItem item : all) {
            String key = item.getModelCode();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            index.computeIfAbsent(key.trim(), k -> new ArrayList<>()).add(item);
        }

        // 先在局部变量里拼好再整体替换：读侧只会看到一份完整、不可变的索引
        Map<String, List<ConfigItem>> replacement = new HashMap<>(index.size() * 2);
        for (Map.Entry<String, List<ConfigItem>> entry : index.entrySet()) {
            replacement.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        externalConfigIndex = Collections.unmodifiableMap(replacement);

        log.info("三方 SSFJH 索引已刷新: {} 个实体号, {} 条构型", replacement.size(), all.size());
    }

    /**
     * 三方行转为响应载体，按约定的字段映射逐个赋值；其余字段一律忽略。
     *
     * <p>三方字段名大小写不统一（GXBS / gxbs / SJgxbs …），取值时忽略大小写。</p>
     */
    private List<ConfigItem> toConfigItems(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ConfigItem> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            ConfigItem item = new ConfigItem();
            item.setItemId(parseNullableLong(pick(row, "GXBS")));
            item.setParentItemId(parseNullableLong(pick(row, "SJGXBS")));
            item.setEquipmentName(pick(row, "GXMC"));
            item.setModelCode(pick(row, "SSFJH"));
            item.setPartNumber(pick(row, "JJH"));
            item.setItemType(EXTERNAL_ITEM_TYPE);
            items.add(item);
        }
        return items;
    }

    /** 按目标字段名取值（忽略大小写），仅取首个非空值；无匹配返回 null */
    private static String pick(Map<String, Object> row, String key) {
        Object exact = row.get(key);
        if (exact != null) {
            return exact.toString();
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                return e.getValue().toString();
            }
        }
        return null;
    }

    /** 三方 id 转 Long：纯数字才接受，非数字/空返回 null（不整条丢弃） */
    private static Long parseNullableLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 兜底 join：正常情况下 future 内的 try/catch 已保证不抛异常 */
    private List<ConfigItem> joinConfigSafe(CompletableFuture<List<ConfigItem>> future, String label) {
        try {
            List<ConfigItem> list = future.join();
            return list == null ? Collections.<ConfigItem>emptyList() : list;
        } catch (Exception e) {
            log.warn("{}构型聚合任务异常，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    public ConfigItem getItem(Long itemId) {
        return configItemMapper.selectById(itemId);
    }

    public Long addItem(ConfigItem item) {
        if (item.getParentItemId() != null) {
            ConfigItem parent = configItemMapper.selectById(item.getParentItemId());
            if (parent == null) {
                throw new IllegalArgumentException("父级构型项目不存在");
            }
        }
        if (item.getItemType() == null || item.getItemType().trim().isEmpty()) {
            item.setItemType("EQUIPMENT");
        }
        configItemMapper.insert(item);
        return item.getItemId();
    }

    public void removeItem(Long itemId) {
        if (configItemMapper.selectById(itemId) == null) {
            throw new IllegalArgumentException("构型项目不存在: " + itemId);
        }
        // 检查是否有子节点
        Long childCount = configItemMapper.selectCount(
                Wrappers.<ConfigItem>lambdaQuery().eq(ConfigItem::getParentItemId, itemId));
        if (childCount > 0) {
            throw new IllegalArgumentException("该构型项目下存在子项目，请先删除子项目");
        }
        // 检查是否有CSV数据绑定
        Long mappingCount = configDataMappingMapper.selectCount(
                Wrappers.<ConfigDataMapping>lambdaQuery().eq(ConfigDataMapping::getItemId, itemId));
        if (mappingCount > 0) {
            throw new IllegalArgumentException("该节点绑定了" + mappingCount + "个CSV文件，请先解除关联");
        }
        configItemMapper.deleteById(itemId);
    }

    /**
     * 获取某机型的完整构型树（前端展示用）
     */
    public List<Map<String, Object>> getConfigTree(String modelCode) {
        List<ConfigItem> allItems = configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, modelCode)
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
        List<ConfigItem> topLevel = allItems.stream()
                .filter(item -> item.getParentItemId() == null)
                .collect(Collectors.toList());

        List<Map<String, Object>> tree = new ArrayList<>();
        for (ConfigItem root : topLevel) {
            tree.add(buildTreeNode(root, allItems));
        }
        return tree;
    }

    private Map<String, Object> buildTreeNode(ConfigItem node, List<ConfigItem> allItems) {
        Map<String, Object> nodeMap = new LinkedHashMap<>();
        nodeMap.put("itemId", node.getItemId());
        nodeMap.put("gjbChapter", node.getAtaChapter());
        nodeMap.put("systemName", node.getSystemName());
        nodeMap.put("subSystemName", node.getSubSystemName());
        nodeMap.put("equipmentName", node.getEquipmentName());
        nodeMap.put("partNumber", node.getPartNumber());
        nodeMap.put("itemType", node.getItemType());

        List<ConfigItem> children = allItems.stream()
                .filter(item -> node.getItemId().equals(item.getParentItemId()))
                .collect(Collectors.toList());

        if (!children.isEmpty()) {
            List<Map<String, Object>> childList = new ArrayList<>();
            for (ConfigItem child : children) {
                childList.add(buildTreeNode(child, allItems));
            }
            nodeMap.put("children", childList);
        }

        return nodeMap;
    }

    /**
     * 获取扁平化的构型项目选择列表（前端下拉框用）
     */
    public List<Map<String, Object>> getItemSelectList(String modelCode) {
        List<ConfigItem> items = configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, modelCode)
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConfigItem item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemId", item.getItemId());
            entry.put("label", buildItemLabel(item));
            entry.put("itemType", item.getItemType());
            list.add(entry);
        }
        return list;
    }

    private String buildItemLabel(ConfigItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.getAtaChapter() != null) {
            sb.append("[").append(item.getAtaChapter()).append("] ");
        }
        if (item.getSystemName() != null) {
            sb.append(item.getSystemName());
        }
        if (item.getSubSystemName() != null) {
            sb.append(" > ").append(item.getSubSystemName());
        }
        if (item.getEquipmentName() != null) {
            sb.append(" > ").append(item.getEquipmentName());
        }
        if (item.getPartNumber() != null) {
            sb.append(" (").append(item.getPartNumber()).append(")");
        }
        return sb.toString();
    }

    // ==================== 数据关联 ====================

    public ConfigDataMapping getMapping(Long mappingId) {
        return configDataMappingMapper.selectById(mappingId);
    }

    /**
     * 创建CSV数据到飞机构型的关联记录
     */
    public void createDataMapping(String aircraftNumber, Long itemId, String csvTableName,
                                  String dataTime) {
        ConfigDataMapping mapping = new ConfigDataMapping();
        mapping.setAircraftNumber(aircraftNumber);
        mapping.setItemId(itemId);
        mapping.setCsvTableName(csvTableName);
        mapping.setDataTime(dataTime);
        configDataMappingMapper.insert(mapping);
    }

    public List<ConfigDataMapping> listMappingsByAircraftNumber(String aircraftNumber) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getAircraftNumber, aircraftNumber)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    public List<ConfigDataMapping> listMappingsByItem(Long itemId) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getItemId, itemId)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    /**
     * 删除指定CSV表的所有关联记录
     */
    public void deleteMappingsByCsvTableName(String csvTableName) {
        configDataMappingMapper.delete(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getCsvTableName, csvTableName));
    }

    // ==================== 架次管理 ====================

    /**
     * 查询架次列表：本地 sortie 行在前，随后依次拼接航新、633 平台的架次，跨源不去重。
     *
     * <p>第三方行的 {@code sortieId} 取原 {@code id}、{@code aircraftNumber} 取原
     * {@code airplaneNum}、{@code sortieNumber} 取原 {@code flightNum}；{@code flightDate} /
     * {@code startTime} / {@code endTime} 由三方 {@code startTime} / {@code endTime}
     * （形如 {@code 2026-01-01 10:00:00.00}）拆分而来，字段形态与本地行一致
     * （flightDate 只放日期、startTime/endTime 只放时刻）。这些实例仅用于响应，不参与任何持久化。</p>
     *
     * <p>三方请求参数：aircraftNumber 非空时透传为 {@code airplaneNum} 做过滤，其余参数不带。</p>
     *
     * <p>任一第三方平台未配置或不可达时仅跳过该平台并记日志，本地数据照常返回；
     * 本地库异常不吞，直接向上抛出由控制器返回 500。</p>
     */
    public List<Sortie> listSorties(String aircraftNumber) {
        // BaseExternalClient.querySorties 仅在 params 取值非 null 时才拼接该查询参数
        Map<String, Object> params = new HashMap<>();
        if (aircraftNumber != null && !aircraftNumber.isEmpty()) {
            params.put("airplaneNum", aircraftNumber);
        }

        // 先派发两个外源请求，使其与下面的本地查询重叠执行
        CompletableFuture<List<Sortie>> hangxinFuture =
                CompletableFuture.supplyAsync(() -> querySortiesSafe(hangxinClient, LABEL_HANGXIN, params));
        CompletableFuture<List<Sortie>> sanSanFuture =
                CompletableFuture.supplyAsync(() -> querySortiesSafe(sanSanClient, LABEL_SANSAN, params));

        // 本地查询内联执行：异常不吞，由控制器按既有逻辑返回 500
        List<Sortie> localSorties = listLocalSorties(aircraftNumber);

        int localCount = localSorties == null ? 0 : localSorties.size();
        List<Sortie> merged = new ArrayList<>(localCount + 16);
        if (localSorties != null) {
            merged.addAll(localSorties);
        }

        List<Sortie> hangxinSorties = joinSortiesSafe(hangxinFuture, LABEL_HANGXIN);
        List<Sortie> sanSanSorties = joinSortiesSafe(sanSanFuture, LABEL_SANSAN);
        merged.addAll(hangxinSorties);
        merged.addAll(sanSanSorties);

        log.info("/aircraft/sorties 聚合完成: 本地={}, 航新={}, 633={}, 合计={}",
                localCount, hangxinSorties.size(), sanSanSorties.size(), merged.size());
        return merged;
    }

    /** 仅查本地 sortie 表，按架次ID降序；aircraftNumber 非空时按机号过滤 */
    public List<Sortie> listLocalSorties(String aircraftNumber) {
        if (aircraftNumber != null && !aircraftNumber.isEmpty()) {
            return sortieMapper.selectList(
                    Wrappers.<Sortie>lambdaQuery()
                            .eq(Sortie::getAircraftNumber, aircraftNumber)
                            .orderByDesc(Sortie::getSortieId));
        }
        return sortieMapper.selectList(
                Wrappers.<Sortie>lambdaQuery().orderByDesc(Sortie::getSortieId));
    }

    // ==================== 外源架次查询（带异常隔离） ====================

    /** 单平台查询：未配置 / 不可达 / 超时 / HTTP 4xx-5xx / 解析异常均在此吞掉 */
    private List<Sortie> querySortiesSafe(BaseExternalClient client, String label,
                                          Map<String, Object> params) {
        try {
            return toSorties(client.querySorties(params));
        } catch (Exception e) {
            log.warn("{}架次查询失败，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 第三方行转为响应载体：sortieId = id，aircraftNumber = airplaneNum，sortieNumber = flightNum；
     * flightDate / startTime / endTime 由三方 {@code startTime} / {@code endTime}
     * （形如 {@code 2026-01-01 10:00:00.00}）拆出，与本地行的字段形态保持一致：
     * flightDate 取日期部分、startTime / endTime 只取时刻部分。三方的 flightDate 非空时优先用它。
     */
    private List<Sortie> toSorties(List<ExternalSortieData> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Sortie> sorties = new ArrayList<>(raw.size());
        for (ExternalSortieData ext : raw) {
            if (ext == null) {
                continue;
            }
            Sortie sortie = new Sortie();
            sortie.setSortieId(parseSortieId(ext.getId()));
            sortie.setAircraftNumber(ext.getAirplaneNum());
            sortie.setSortieNumber(ext.getFlightNum());
            sortie.setFlightDate(placeholderIfAbsent(firstNonEmpty(ext.getFlightDate(),
                    datePart(ext.getStartTime()))));
            sortie.setStartTime(placeholderIfAbsent(timePart(ext.getStartTime())));
            sortie.setEndTime(placeholderIfAbsent(timePart(ext.getEndTime())));
            sorties.add(sortie);
        }
        return sorties;
    }

    /**
     * 取三方时间的日期部分："2026-01-01 10:00:00.00" / "2026-01-01T10:00:00" → "2026-01-01"。
     *
     * <p>只有时刻（"10:00:00"）时返回 null。</p>
     */
    static String datePart(String dateTime) {
        String value = normalizeTime(dateTime);
        if (value == null) {
            return null;
        }
        int sep = dateTimeSeparator(value);
        if (sep < 0) {
            return value.indexOf(':') < 0 ? value : null;
        }
        return sep == 0 ? null : value.substring(0, sep);
    }

    /**
     * 取三方时间的时刻部分："2026-01-01 10:00:00.00" → "10:00:00"（丢弃小数秒）。
     *
     * <p>只有日期时返回 null。</p>
     */
    static String timePart(String dateTime) {
        String value = normalizeTime(dateTime);
        if (value == null) {
            return null;
        }
        int sep = dateTimeSeparator(value);
        String clock = sep < 0 ? value : value.substring(sep + 1);
        if (clock.indexOf(':') < 0) {
            return null;
        }
        int dot = clock.indexOf('.');
        return dot > 0 ? clock.substring(0, dot) : clock;
    }

    /** 去空白与末尾的 UTC 后缀（Z），空值归 null */
    private static String normalizeTime(String dateTime) {
        if (dateTime == null) {
            return null;
        }
        String value = dateTime.trim();
        if (value.endsWith("Z") || value.endsWith("z")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** 日期与时刻的分隔符位置（空格或 ISO 的 T），没有则返回 -1 */
    private static int dateTimeSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == 'T' || c == 't') {
                return i;
            }
        }
        return -1;
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    /** 三方该字段缺失时的占位符：本地行是真实值，三方缺则沿用 "-" 占位，前端无需判空 */
    private static String placeholderIfAbsent(String value) {
        return value == null || value.isEmpty() ? PLACEHOLDER : value;
    }

    /** 三方架次ID（字符串）转本地自增主键；非数字时置 null，避免整条记录丢失 */
    private Long parseSortieId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            log.warn("三方架次ID非数字，sortieId 置空: {}", id);
            return null;
        }
    }

    /** 兜底 join：正常情况下 future 内的 try/catch 已保证不抛异常 */
    private List<Sortie> joinSortiesSafe(CompletableFuture<List<Sortie>> future, String label) {
        try {
            List<Sortie> list = future.join();
            return list == null ? Collections.<Sortie>emptyList() : list;
        } catch (Exception e) {
            log.warn("{}架次聚合任务异常，已跳过该数据源: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    public Sortie getSortie(Long sortieId) {
        return sortieMapper.selectById(sortieId);
    }

    public void addSortie(Sortie sortie) {
        if (sortie.getAircraftNumber() == null || sortie.getAircraftNumber().trim().isEmpty()) {
            throw new IllegalArgumentException("机号不能为空");
        }
        if (aircraftConfigMapper.selectById(sortie.getAircraftNumber()) == null) {
            throw new IllegalArgumentException("单机不存在: " + sortie.getAircraftNumber());
        }
        // 去重校验：同机号下架次号不能重复
        if (sortie.getSortieNumber() != null && !sortie.getSortieNumber().trim().isEmpty()) {
            Long count = sortieMapper.selectCount(
                    Wrappers.<Sortie>lambdaQuery()
                            .eq(Sortie::getAircraftNumber, sortie.getAircraftNumber())
                            .eq(Sortie::getSortieNumber, sortie.getSortieNumber().trim()));
            if (count > 0) {
                throw new IllegalArgumentException("该单机下已存在相同架次号: " + sortie.getSortieNumber().trim());
            }
        }
        sortieMapper.insert(sortie);
    }

    @Transactional
    public void removeSortie(Long sortieId) {
        if (sortieMapper.selectById(sortieId) == null) {
            throw new IllegalArgumentException("架次不存在: " + sortieId);
        }
        // 删除该架次下的所有数据关联（ConfigDataMapping）
        configDataMappingMapper.delete(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getSortieId, sortieId));
        // 删除架次
        sortieMapper.deleteById(sortieId);
    }

    public List<ConfigDataMapping> listMappingsBySortie(Long sortieId) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getSortieId, sortieId)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    // ==================== 带 sortieId 的数据关联 ====================

    /**
     * 创建CSV数据到飞机构型的关联记录（含架次）
     */
    public void createDataMapping(String aircraftNumber, Long itemId, Long sortieId,
                                  String csvTableName, String dataTime) {
        ConfigDataMapping mapping = new ConfigDataMapping();
        mapping.setAircraftNumber(aircraftNumber);
        mapping.setItemId(itemId);
        mapping.setSortieId(sortieId);
        mapping.setCsvTableName(csvTableName);
        mapping.setDataTime(dataTime);
        configDataMappingMapper.insert(mapping);
    }

}
