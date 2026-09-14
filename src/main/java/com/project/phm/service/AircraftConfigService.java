package com.project.phm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.adapter.client.BaseExternalClient;
import com.project.phm.adapter.client.HangxinSortieClient;
import com.project.phm.adapter.client.SanSanSortieClient;
import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.entity.*;
import com.project.phm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final AircraftModelMapper aircraftModelMapper;
    private final AircraftConfigMapper aircraftConfigMapper;
    private final ConfigItemMapper configItemMapper;
    private final ConfigDataMappingMapper configDataMappingMapper;
    private final SortieMapper sortieMapper;
    private final HangxinSortieClient hangxinClient;
    private final SanSanSortieClient sanSanClient;

    public AircraftConfigService(AircraftModelMapper aircraftModelMapper,
                                  AircraftConfigMapper aircraftConfigMapper,
                                  ConfigItemMapper configItemMapper,
                                  ConfigDataMappingMapper configDataMappingMapper,
                                  SortieMapper sortieMapper,
                                  HangxinSortieClient hangxinClient,
                                  SanSanSortieClient sanSanClient) {
        this.aircraftModelMapper = aircraftModelMapper;
        this.aircraftConfigMapper = aircraftConfigMapper;
        this.configItemMapper = configItemMapper;
        this.configDataMappingMapper = configDataMappingMapper;
        this.sortieMapper = sortieMapper;
        this.hangxinClient = hangxinClient;
        this.sanSanClient = sanSanClient;
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

    public List<Aircraft> listPlanes(String modelCode) {
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

    public List<ConfigItem> listItems(String modelCode) {
        return configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, modelCode)
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
    }

    /**
     * 获取所有机型的构型项目（不过滤机型），按 GJB 章节排序。
     */
    public List<ConfigItem> listAllItems() {
        return configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
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

    public List<Sortie> listSortiesByAircraft(String aircraftNumber) {
        return sortieMapper.selectList(
                Wrappers.<Sortie>lambdaQuery()
                        .eq(Sortie::getAircraftNumber, aircraftNumber)
                        .orderByDesc(Sortie::getSortieId));
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
