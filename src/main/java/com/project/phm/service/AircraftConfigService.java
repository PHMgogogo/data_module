package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.ExternalAircraftData;
import com.project.phm.adapter.dto.ExternalConfigPage;
import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.entity.*;
import com.project.phm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 飞机单机管理服务 — 机型/构型/系统设备关联的业务逻辑
 */
@Service
public class AircraftConfigService {

    private static final Logger log = LoggerFactory.getLogger(AircraftConfigService.class);

    /** 第三方平台机型行 manufacturer / description / createdAt 的占位符 */
    private static final String PLACEHOLDER = "-";

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
    private final PlatformRouteService routeService;
    private final String configPath;

    public AircraftConfigService(AircraftModelMapper aircraftModelMapper,
                                  AircraftConfigMapper aircraftConfigMapper,
                                  ConfigItemMapper configItemMapper,
                                  ConfigDataMappingMapper configDataMappingMapper,
                                  SortieMapper sortieMapper,
                                  HangxinSortieClient hangxinClient,
                                  SanSanSortieClient sanSanClient,
                                  PlatformRouteService routeService,
                                  @Value("${support-system.paths.getDzgxxx}") String configPath) {
        this.aircraftModelMapper = aircraftModelMapper;
        this.aircraftConfigMapper = aircraftConfigMapper;
        this.configItemMapper = configItemMapper;
        this.configDataMappingMapper = configDataMappingMapper;
        this.sortieMapper = sortieMapper;
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
        this.routeService = routeService;
        this.configPath = configPath;
    }

    // ==================== 机型管理 ====================

    /**
     * 查询全部机型：本地 aircraft_model 行在前，随后依次拼接航新、633 平台在内存中的机型快照。
     *
     * <p>三方机型不再由本接口拉取 —— 它由 {@link PlatformModelPoller} 每 5 秒轮询维护，
     * 这里只读内存里的机型映射（键为 {@code airplaneType:id}），读不到说明该平台当前不可达。</p>
     *
     * <p>第三方行的 manufacturer / description / createdAt 固定为 {@code "-"}；
     * 这些实例仅用于响应，不参与任何持久化。</p>
     */
    public List<AircraftModel> listModels() {
        List<AircraftModel> localModels = listLocalModels();
        // 本地机型是查询的兜底路由，每次查询前同步一次，保证新建机型立即可路由
        routeService.replaceModels(DataSource.LOCAL, toLocalModelDefinitions(localModels));

        List<AircraftModel> hangxinModels = toModels(routeService.modelsOf(DataSource.HANGXIN));
        List<AircraftModel> sanSanModels = toModels(routeService.modelsOf(DataSource.SAN_SAN));
        int localCount = localModels == null ? 0 : localModels.size();
        List<AircraftModel> merged = new ArrayList<>(localCount + hangxinModels.size() + sanSanModels.size());
        if (localModels != null) {
            merged.addAll(localModels);
        }
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

    // ==================== 路由树机型更新 ====================

    private List<PlatformRouteService.ModelDefinition> toLocalModelDefinitions(
            List<AircraftModel> models) {
        List<PlatformRouteService.ModelDefinition> definitions = new ArrayList<>();
        for (AircraftModel model : orEmpty(models)) {
            if (model != null && model.getModelCode() != null) {
                definitions.add(new PlatformRouteService.ModelDefinition(
                        model.getModelCode(), model.getModelCode()));
            }
        }
        return definitions;
    }

    /**
     * 内存机型定义转为响应载体：modelCode = {@code airplaneType:id}，其余三字段为占位符。
     */
    private List<AircraftModel> toModels(Collection<PlatformRouteService.ModelDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Collections.emptyList();
        }
        List<AircraftModel> models = new ArrayList<>(definitions.size());
        for (PlatformRouteService.ModelDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            AircraftModel model = new AircraftModel();
            model.setModelCode(definition.getModelKey());
            model.setManufacturer(PLACEHOLDER);
            model.setDescription(PLACEHOLDER);
            model.setCreatedAt(PLACEHOLDER);
            models.add(model);
        }
        return models;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    /** 机型是查询路由的必要条件：缺失或空白时直接拒绝，避免误落到全量扫描 */
    private static String requireModel(String modelCode) {
        String modelKey = modelCode == null ? null : modelCode.trim();
        if (modelKey == null || modelKey.isEmpty()) {
            throw new IllegalArgumentException("机型不能为空");
        }
        return modelKey;
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

    public AircraftModel getModel(String modelCode) {
        return aircraftModelMapper.selectById(modelCode);
    }

    public void addModel(AircraftModel model) {
        if (aircraftModelMapper.selectCount(
                Wrappers.<AircraftModel>lambdaQuery().eq(AircraftModel::getModelCode, model.getModelCode())) > 0) {
            throw new IllegalArgumentException("机型代码已存在: " + model.getModelCode());
        }
        aircraftModelMapper.insert(model);
        routeService.addLocalModel(model.getModelCode());
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
        routeService.removeLocalModel(modelCode);
    }

    // ==================== 飞机单机管理 ====================

    /**
     * 查询机型下的单机列表：按机型路由到唯一平台。
     *
     * <p>机型必须提供 —— 查平台路由索引确定归属，只向命中的那一个源发请求；
     * 命中三方则把合成码还原成真实的 {@code airplaneType} 再发出去。未命中索引、
     * 或命中的平台当前不可达（保活失败）时回落本地表，不再向三方发请求。</p>
     *
     * <p>第三方行的 {@code aircraftNumber} 取原 {@code airplaneNum}、{@code modelCode} 取原
     * {@code airplaneType}；airline / configVersion / status / createdAt 固定为 {@code "-"}。
     * 这些实例仅用于响应，不参与任何持久化。</p>
     */
    public List<Aircraft> listPlanes(String modelCode) {
        String modelKey = requireModel(modelCode);
        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地机型查询", modelKey);
            }
            // 本地机型的 modelCode 就是索引入参本身，直接拿来过滤本地表
            return orEmpty(listLocalPlanes(modelKey));
        }
        return listPlanesFromOneSource(source, modelKey);
    }

    /**
     * 定向单个外源查单机。
     *
     * <p>入参既可能是 {@code airplaneType:id} 合成码，也可能是前端直接传的原始
     * {@code airplaneType}。索引里保存了对应的真实值，这里直接取，不在运行期按冒号拆码。</p>
     */
    private List<Aircraft> listPlanesFromOneSource(DataSource source, String modelKey) {
        String label = source.getLabel();
        String realAirplaneType = routeService.realAirplaneType(modelKey);
        if (realAirplaneType == null) {
            log.warn("机型 {} 命中 {}，但索引里没有对应的真实 airplaneType，无法构造三方请求",
                    modelKey, label);
            return Collections.emptyList();
        }
        // BaseExternalClient.queryAircrafts 仅在 params 中 airplaneType / airplaneNum 非 null 时才拼接该参数
        Map<String, Object> params = new HashMap<>();
        params.put("airplaneType", realAirplaneType);
        log.info("/aircraft/plane 机型 {} 定向到 {}，三方 airplaneType={}", modelKey, label, realAirplaneType);
        return orEmpty(queryPlanesSafe(clientFor(source), label, params));
    }

    /** 数据源到客户端的映射，定向查询只剩一个源时用 */
    private BaseExternalClient clientFor(DataSource source) {
        return source == DataSource.SAN_SAN ? sanSanClient : hangxinClient;
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
        routeService.addLocalAircraft(aircraft.getAircraftNumber(), aircraft.getModelCode());
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
        routeService.removeLocalAircraft(aircraftNumber);
    }

    /**
     * 获取某机型下的所有可用机号。
     *
     * <p>本地机型保持原来的 status=active 语义；三方机型按 /aircraft/models 建立的
     * 机型 → 机号内存关系返回，不再单独扫描其它平台。</p>
     */
    public List<String> listActiveAircraftNumbers(String modelCode) {
        String modelKey = requireModel(modelCode);

        DataSource source = routeService.resolveModel(modelKey);
        if (source == null) {
            log.warn("机型 {} 未命中平台路由，按本地机型查询机号", modelKey);
            source = DataSource.LOCAL;
        }

        List<String> numbers;
        List<String> routeNumbers;
        if (source == DataSource.LOCAL) {
            List<Aircraft> configs = aircraftConfigMapper.selectList(
                    Wrappers.<Aircraft>lambdaQuery()
                            .eq(Aircraft::getModelCode, modelKey)
                            .orderByAsc(Aircraft::getAircraftNumber));
            routeNumbers = configs.stream()
                    .map(Aircraft::getAircraftNumber)
                    .collect(Collectors.toList());
            numbers = configs.stream()
                    .filter(plane -> "active".equals(plane.getStatus()))
                    .map(Aircraft::getAircraftNumber)
                    .collect(Collectors.toList());
        } else {
            String realAirplaneType = routeService.realAirplaneType(modelKey);
            if (realAirplaneType == null) {
                log.warn("机型 {} 命中 {}，但没有可用于查询的真实 airplaneType",
                        modelKey, source.getLabel());
                return Collections.emptyList();
            }
            Map<String, Object> params = new HashMap<>();
            params.put("airplaneType", realAirplaneType);
            try {
                List<ExternalAircraftData> raw = clientFor(source).queryAircrafts(params);
                numbers = orEmpty(raw).stream()
                        .filter(Objects::nonNull)
                        .map(ExternalAircraftData::getAirplaneNum)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                routeNumbers = numbers;
            } catch (Exception e) {
                log.warn("{}单机查询失败，保留已有映射: {}", source.getLabel(), e.getMessage());
                return Collections.emptyList();
            }
        }

        Collections.sort(numbers);
        Collections.sort(routeNumbers);
        for (String actualModelKey : routeService.modelKeys(modelKey)) {
            routeService.replaceAircraft(source, actualModelKey, routeNumbers);
        }
        log.info("/aircraft/aircraft-numbers 机型 {} 定向到 {}，增量更新 {} 个机号",
                modelKey, source.getLabel(), numbers.size());
        return numbers;
    }

    // ==================== 构型项目管理 ====================

    /**
     * 查询构型项目列表：按模型机型路由到唯一平台，不做跨源拼接。
     *
     * <p>机型必须提供：查平台路由索引确定所属平台。本地机型直接查 config_item；
     * 三方机型只请求所属平台构型接口，并用该机型关联的机号集合过滤 {@code SSFJH}。
     * 未命中索引、或命中的平台当前不可达（保活失败）时回落本地构型。</p>
     */
    public List<ConfigItem> listItems(String modelCode) {
        String modelKey = requireModel(modelCode);

        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地机型查询构型", modelKey);
            }
            return listLocalItems(modelKey);
        }

        Set<String> aircraftNumbers = routeService.aircraftNumbersForModel(modelKey);
        if (aircraftNumbers.isEmpty()) {
            log.warn("机型 {} 属于 {}，但内存中没有关联机号，无法过滤 SSFJH，返回空",
                    modelKey, source.getLabel());
            return Collections.emptyList();
        }

        List<ConfigItem> platformItems = queryAllConfigItemsSafe(clientFor(source), source.getLabel());
        List<ConfigItem> filtered = filterConfigItemsByAircraftNumbers(platformItems, aircraftNumbers);
        log.info("/aircraft/config-items 机型 {} 定向到 {}，SSFJH 匹配 {}/{} 条",
                modelKey, source.getLabel(), filtered.size(), platformItems.size());
        return filtered;
    }

    static List<ConfigItem> filterConfigItemsByAircraftNumbers(
            List<ConfigItem> items, Set<String> aircraftNumbers) {
        if (items == null || items.isEmpty() || aircraftNumbers == null || aircraftNumbers.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new HashSet<>();
        for (String value : aircraftNumbers) {
            if (value != null && !value.trim().isEmpty()) {
                normalized.add(value.trim());
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        List<ConfigItem> result = new ArrayList<>();
        for (ConfigItem item : items) {
            if (item == null || item.getModelCode() == null) {
                continue;
            }
            if (normalized.contains(item.getModelCode().trim())) {
                result.add(item);
            }
        }
        return result;
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

    public List<ConfigDataMapping> listMappingsByAircraftNumber(String aircraftNumber) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getAircraftNumber, aircraftNumber)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    public ConfigDataMapping getMappingBySortie(Long sortieId) {
        if (sortieId == null) {
            return null;
        }
        List<ConfigDataMapping> mappings = configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getSortieId, sortieId)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
        return mappings == null || mappings.isEmpty() ? null : mappings.get(0);
    }

    public ConfigDataMapping getMappingByTable(String csvTableName) {
        if (csvTableName == null || csvTableName.trim().isEmpty()) {
            return null;
        }
        String table = csvTableName.startsWith("csv_")
                ? csvTableName.substring(4) : csvTableName;
        List<ConfigDataMapping> mappings = configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getCsvTableName, table)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
        return mappings == null || mappings.isEmpty() ? null : mappings.get(0);
    }

    /**
     * 删除指定CSV表的所有关联记录
     */
    public void deleteMappingsByCsvTableName(String csvTableName) {
        String table = csvTableName == null ? null : csvTableName.trim();
        if (table != null && table.startsWith("csv_")) {
            table = table.substring(4);
        }
        configDataMappingMapper.delete(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getCsvTableName, table));
        routeService.unbindTable(table);
    }

    // ==================== 架次管理 ====================

    /**
     * 查询架次列表：按机型路由，机号用于过滤。
     *
     * <p>机型必须提供 —— 先按机型解析归属平台，再限定到该机号。命中本地（或机型未命中路由、
     * 或命中平台当前不可达）时只查本地 sortie 表；命中三方则只向该平台发一次架次请求。</p>
     *
     * <p>每行都带 {@code sortieKey} —— 跨源的架次统一标识（本地 = {@code sortieId} 的字符串，
     * 三方 = 平台原始 id），下游时序查询按它定向。</p>
     *
     * <p>第三方行的 {@code aircraftNumber} 取原 {@code airplaneNum}、{@code sortieNumber} 取原
     * {@code flightNum}；{@code flightDate} / {@code startTime} / {@code endTime} 由三方
     * {@code startTime} / {@code endTime}（形如 {@code 2026-01-01 10:00:00.00}）拆分而来，
     * 字段形态与本地行一致（flightDate 只放日期、startTime/endTime 只放时刻）。
     * 这些实例仅用于响应，不参与任何持久化。</p>
     */
    public List<Sortie> listSorties(String modelCode, String aircraftNumber) {
        String modelKey = requireModel(modelCode);
        String number = aircraftNumber == null ? null : aircraftNumber.trim();

        DataSource source = routeService.resolveModel(modelKey);
        if (source == null || source == DataSource.LOCAL) {
            if (source == null) {
                log.warn("机型 {} 未命中平台路由，按本地机型查询架次", modelKey);
            }
            return listLocalSortiesWithRoute(number, modelKey);
        }

        PlatformRouteService.AircraftRef ref = ensureAircraftRoute(source, modelKey, number);
        if (ref == null) {
            log.warn("机号 {} 在 {} 下没有登记，架次查询返回空", number, source.getLabel());
            return Collections.emptyList();
        }
        // BaseExternalClient.querySorties 仅在 params 取值非 null 时才拼接该查询参数
        Map<String, Object> params = new HashMap<>();
        params.put("airplaneType", ref.getRealAirplaneType());
        params.put("airplaneNum", number);
        String label = source.getLabel();
        log.info("/aircraft/sorties 机号 {}（机型 {}）定向到 {}", number, ref.getModelKey(), label);
        try {
            List<ExternalSortieData> raw = clientFor(source).querySorties(params);
            routeService.replaceSorties(source, number, toSortieRefs(source, raw));
            return toSorties(raw);
        } catch (Exception e) {
            log.warn("{}架次查询失败，保留已有映射: {}", label, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 本地分支：查本地 sortie 表并把架次与 CSV 表绑定关系同步进内存树 */
    private List<Sortie> listLocalSortiesWithRoute(String aircraftNumber, String modelKey) {
        List<Sortie> sorties = orEmpty(listLocalSorties(aircraftNumber));
        if (aircraftNumber != null && !aircraftNumber.isEmpty()) {
            List<PlatformRouteService.SortieRef> refs = new ArrayList<>(sorties.size());
            for (Sortie sortie : sorties) {
                if (sortie != null) {
                    refs.add(PlatformRouteService.SortieRef.local(
                            sortie.getSortieId(), modelKey, aircraftNumber));
                }
            }
            routeService.replaceSorties(DataSource.LOCAL, aircraftNumber, refs);
        }
        for (Sortie sortie : sorties) {
            ConfigDataMapping mapping = getMappingBySortie(sortie.getSortieId());
            if (mapping != null && mapping.getCsvTableName() != null) {
                try {
                    routeService.bindTable(
                            PlatformRouteService.localSortieKey(sortie.getSortieId()),
                            mapping.getCsvTableName());
                } catch (IllegalArgumentException e) {
                    log.warn("恢复本地CSV表映射失败: sortieId={}, table={}, reason={}",
                            sortie.getSortieId(), mapping.getCsvTableName(), e.getMessage());
                }
            }
        }
        return sorties;
    }

    /**
     * 架次查询前确保「机号 → 机型/平台」已进入内存树。
     *
     * <p>机型已知，这里只在该平台内按机号补一次懒加载，不扫描其它平台。</p>
     */
    private PlatformRouteService.AircraftRef ensureAircraftRoute(DataSource source, String modelKey,
                                                                 String aircraftNumber) {
        if (aircraftNumber == null || aircraftNumber.isEmpty()) {
            return null;
        }
        PlatformRouteService.AircraftRef existing = routeService.resolveAircraftRef(aircraftNumber);
        if (existing != null && existing.getSource() == source) {
            return existing;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("airplaneNum", aircraftNumber);
        String realAirplaneType = routeService.realAirplaneType(modelKey);
        if (realAirplaneType != null) {
            params.put("airplaneType", realAirplaneType);
        }
        try {
            List<ExternalAircraftData> raw = clientFor(source).queryAircrafts(params);
            for (ExternalAircraftData aircraft : orEmpty(raw)) {
                if (aircraft == null || !aircraftNumber.equals(aircraft.getAirplaneNum())) {
                    continue;
                }
                for (String actualModelKey : routeService.modelKeys(modelKey)) {
                    routeService.addAircraft(source, actualModelKey, aircraftNumber);
                }
                log.info("架次查询前已补全机号 {} 的平台路由: {}", aircraftNumber, source.getLabel());
                return routeService.resolveAircraftRef(aircraftNumber);
            }
        } catch (Exception e) {
            log.warn("{}按机号 {} 补全单机路由失败: {}", source.getLabel(), aircraftNumber, e.getMessage());
        }
        return null;
    }

    /** 仅查本地 sortie 表，按架次ID降序；aircraftNumber 非空时按机号过滤 */
    public List<Sortie> listLocalSorties(String aircraftNumber) {
        List<Sortie> sorties;
        if (aircraftNumber != null && !aircraftNumber.isEmpty()) {
            sorties = sortieMapper.selectList(
                    Wrappers.<Sortie>lambdaQuery()
                            .eq(Sortie::getAircraftNumber, aircraftNumber)
                            .orderByDesc(Sortie::getSortieId));
        } else {
            sorties = sortieMapper.selectList(
                    Wrappers.<Sortie>lambdaQuery().orderByDesc(Sortie::getSortieId));
        }
        // 本地行的统一标识就是主键的字符串形态；三方行在 toSorties 里用平台原始 id 填
        for (Sortie sortie : orEmpty(sorties)) {
            if (sortie != null) {
                sortie.setSortieKey(PlatformRouteService.localSortieKey(sortie.getSortieId()));
            }
        }
        return sorties;
    }

    // ==================== 外源架次查询（带异常隔离） ====================

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
            // 三方 id 是 UUID 形态，塞不进 Long 形的 sortieId，统一标识单独用 String 字段下发
            sortie.setSortieKey(ext.getId());
            sortie.setAircraftNumber(ext.getAirplaneNum());
            sortie.setSortieNumber(ext.getFlightNum());
            sortie.setFlightDate(placeholderIfAbsent(firstNonEmpty(ext.getFlightDate(),
                    datePart(ext.getStartTime()))));
            sortie.setStartTime(placeholderIfAbsent(timePart(ext.getStartTime())));
            sortie.setEndTime(placeholderIfAbsent(timePart(ext.getEndTime())));
            sortie.setParameterGroupId(ext.getParameterGroupId());
            sorties.add(sortie);
        }
        return sorties;
    }

    private List<PlatformRouteService.SortieRef> toSortieRefs(
            DataSource source, List<ExternalSortieData> raw) {
        List<PlatformRouteService.SortieRef> refs = new ArrayList<>();
        for (ExternalSortieData ext : orEmpty(raw)) {
            if (ext == null) {
                continue;
            }
            refs.add(PlatformRouteService.SortieRef.external(
                    source, ext.getId(), ext.getAirplaneType(), ext.getAirplaneNum(),
                    ext.getFlightNum(), ext.getStartTime(), ext.getEndTime(),
                    ext.getParameterGroupId()));
        }
        return refs;
    }

    /**
     * 按参数组ID查参数字段名列表（该架次有哪些列）。
     *
     * <p>parameterGroupId 来自 {@link #listSorties}。架次接口返回后会增量登记
     * parameterGroupId → 架次/平台映射，本接口据此只调用该平台接口。</p>
     *
     * <p>平台未配置 / 不可达 / 解析失败时返回空列表（记日志），不向上抛。</p>
     */
    public List<String> listParameterNames(String parameterGroupId) {
        if (parameterGroupId == null || parameterGroupId.isEmpty()) {
            return Collections.emptyList();
        }
        PlatformRouteService.SortieRef ref = routeService.resolveParameterGroup(parameterGroupId);
        if (ref == null || !ref.getSource().isExternal()) {
            log.warn("参数组 {} 未命中三方架次路由索引，返回空（不扫描其它平台）", parameterGroupId);
            return Collections.emptyList();
        }
        try {
            DataSource source = ref.getSource();
            List<String> names = clientFor(source).queryParameterNames(parameterGroupId);
            log.info("参数组 {} 定向到 {}，字段名查询完成: {} 个",
                    parameterGroupId, source.getLabel(), names.size());
            return names;
        } catch (Exception e) {
            log.warn("参数组 {} 字段名查询失败: {}", parameterGroupId, e.getMessage());
            return Collections.emptyList();
        }
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
            // 三方架次ID通常是UUID，sortieId 仅兼容数字形态的旧数据。
            return null;
        }
    }

    public Sortie getSortie(Long sortieId) {
        return sortieMapper.selectById(sortieId);
    }

    public void addSortie(Sortie sortie) {
        if (sortie.getAircraftNumber() == null || sortie.getAircraftNumber().trim().isEmpty()) {
            throw new IllegalArgumentException("机号不能为空");
        }
        Aircraft aircraft = aircraftConfigMapper.selectById(sortie.getAircraftNumber());
        if (aircraft == null) {
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
        // insert 后主键才回填，所以同步必须在 insert 之后
        routeService.addLocalSortie(sortie.getSortieId(), sortie.getAircraftNumber(),
                aircraft.getModelCode());
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
        routeService.removeLocalSortie(sortieId);
    }

    public List<ConfigDataMapping> listMappingsBySortie(Long sortieId) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getSortieId, sortieId)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    // ==================== CSV 表与本地架次一对一绑定 ====================

    /**
     * 将 CSV 表绑定到一个本地架次。
     *
     * <p>数据库和应用内存树都强制一对一：一个架次最多一张表，一张表最多一个架次。</p>
     */
    public synchronized void createDataMapping(Long sortieId, String csvTableName, String dataTime) {
        if (sortieId == null) {
            throw new IllegalArgumentException("绑定时必须提供 sortieId");
        }
        String table = csvTableName == null ? null : csvTableName.trim();
        if (table == null || table.isEmpty()) {
            throw new IllegalArgumentException("CSV表名不能为空");
        }
        if (table.startsWith("csv_")) {
            table = table.substring(4);
        }
        Sortie sortie = getSortie(sortieId);
        if (sortie == null) {
            throw new IllegalArgumentException("架次不存在: " + sortieId);
        }
        Aircraft aircraft = aircraftConfigMapper.selectById(sortie.getAircraftNumber());
        if (aircraft == null) {
            throw new IllegalArgumentException("架次所属单机不存在: " + sortie.getAircraftNumber());
        }
        if (getMappingBySortie(sortieId) != null) {
            throw new IllegalArgumentException("该架次已绑定CSV表，不能重复绑定");
        }
        if (getMappingByTable(table) != null) {
            throw new IllegalArgumentException("该CSV表已绑定其它架次: " + table);
        }

        ConfigDataMapping mapping = new ConfigDataMapping();
        mapping.setAircraftNumber(sortie.getAircraftNumber());
        mapping.setSortieId(sortieId);
        mapping.setCsvTableName(table);
        mapping.setDataTime(dataTime);
        try {
            configDataMappingMapper.insert(mapping);

            // 数据库写入成功后同步内存树，确保树和绑定表保持一致。
            routeService.addLocalModel(aircraft.getModelCode());
            routeService.addLocalAircraft(sortie.getAircraftNumber(), aircraft.getModelCode());
            routeService.addLocalSortie(sortieId, sortie.getAircraftNumber(), aircraft.getModelCode());
            routeService.bindTable(PlatformRouteService.localSortieKey(sortieId), table);
        } catch (RuntimeException e) {
            if (mapping.getMappingId() != null) {
                configDataMappingMapper.deleteById(mapping.getMappingId());
            }
            throw e;
        }
    }

}
