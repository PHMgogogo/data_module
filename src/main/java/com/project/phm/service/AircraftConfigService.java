package com.project.phm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.entity.*;
import com.project.phm.mapper.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 飞机构型管理服务 — 机型/构型/系统设备关联的业务逻辑
 */
@Service
public class AircraftConfigService {

    private final AircraftModelMapper aircraftModelMapper;
    private final AircraftConfigMapper aircraftConfigMapper;
    private final ConfigItemMapper configItemMapper;
    private final ConfigDataMappingMapper configDataMappingMapper;
    private final HealthRecordMapper healthRecordMapper;

    public AircraftConfigService(AircraftModelMapper aircraftModelMapper,
                                  AircraftConfigMapper aircraftConfigMapper,
                                  ConfigItemMapper configItemMapper,
                                  ConfigDataMappingMapper configDataMappingMapper,
                                  HealthRecordMapper healthRecordMapper) {
        this.aircraftModelMapper = aircraftModelMapper;
        this.aircraftConfigMapper = aircraftConfigMapper;
        this.configItemMapper = configItemMapper;
        this.configDataMappingMapper = configDataMappingMapper;
        this.healthRecordMapper = healthRecordMapper;
    }

    // ==================== 机型管理 ====================

    public List<AircraftModel> listModels() {
        return aircraftModelMapper.selectList(
                Wrappers.<AircraftModel>lambdaQuery().orderByAsc(AircraftModel::getModelCode));
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
        aircraftModelMapper.deleteById(modelCode);
    }

    // ==================== 飞机构型管理 ====================

    public List<AircraftConfig> listConfigs(String modelCode) {
        if (modelCode != null && !modelCode.isEmpty()) {
            return aircraftConfigMapper.selectList(
                    Wrappers.<AircraftConfig>lambdaQuery()
                            .eq(AircraftConfig::getModelCode, modelCode)
                            .orderByAsc(AircraftConfig::getTailNumber));
        }
        return aircraftConfigMapper.selectList(
                Wrappers.<AircraftConfig>lambdaQuery().orderByAsc(AircraftConfig::getTailNumber));
    }

    public AircraftConfig getConfig(String tailNumber) {
        return aircraftConfigMapper.selectById(tailNumber);
    }

    public void addConfig(AircraftConfig config) {
        if (config.getTailNumber() == null || config.getTailNumber().trim().isEmpty()) {
            throw new IllegalArgumentException("机号不能为空");
        }
        if (aircraftModelMapper.selectById(config.getModelCode()) == null) {
            throw new IllegalArgumentException("机型不存在: " + config.getModelCode());
        }
        if (aircraftConfigMapper.selectById(config.getTailNumber()) != null) {
            throw new IllegalArgumentException("机号已存在: " + config.getTailNumber());
        }
        if (config.getStatus() == null || config.getStatus().trim().isEmpty()) {
            config.setStatus("active");
        }
        aircraftConfigMapper.insert(config);
    }

    public void removeConfig(String tailNumber) {
        if (aircraftConfigMapper.selectById(tailNumber) == null) {
            throw new IllegalArgumentException("构型不存在: " + tailNumber);
        }
        aircraftConfigMapper.deleteById(tailNumber);
    }

    /**
     * 获取某机型下的所有可用机号
     */
    public List<String> listActiveTailNumbers(String modelCode) {
        List<AircraftConfig> configs = aircraftConfigMapper.selectList(
                Wrappers.<AircraftConfig>lambdaQuery()
                        .eq(AircraftConfig::getModelCode, modelCode)
                        .eq(AircraftConfig::getStatus, "active")
                        .orderByAsc(AircraftConfig::getTailNumber));
        return configs.stream().map(AircraftConfig::getTailNumber).collect(Collectors.toList());
    }

    // ==================== 构型项目管理 ====================

    public List<ConfigItem> listItems(String modelCode) {
        return configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, modelCode)
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
        // 检查是否有子节点，有则不允许删除
        List<ConfigItem> children = configItemMapper.selectList(
                Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getParentItemId, itemId)
                        .orderByAsc(ConfigItem::getAtaChapter, ConfigItem::getItemId));
        if (!children.isEmpty()) {
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
        nodeMap.put("ataChapter", node.getAtaChapter());
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

    /**
     * 创建CSV数据到飞机构型的关联记录
     */
    public void createDataMapping(String tailNumber, Long itemId, String csvTableName,
                                  String dataType, String dataTime) {
        ConfigDataMapping mapping = new ConfigDataMapping();
        mapping.setTailNumber(tailNumber);
        mapping.setItemId(itemId);
        mapping.setCsvTableName(csvTableName);
        mapping.setDataType(dataType != null ? dataType : "RAW");
        mapping.setDataTime(dataTime);
        configDataMappingMapper.insert(mapping);
    }

    public List<ConfigDataMapping> listMappingsByTailNumber(String tailNumber) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getTailNumber, tailNumber)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    public List<ConfigDataMapping> listMappingsByItem(Long itemId) {
        return configDataMappingMapper.selectList(
                Wrappers.<ConfigDataMapping>lambdaQuery()
                        .eq(ConfigDataMapping::getItemId, itemId)
                        .orderByDesc(ConfigDataMapping::getCreatedAt));
    }

    // ==================== 健康记录 ====================

    public void addHealthRecord(HealthRecord record) {
        if (record.getRecordType() == null) {
            throw new IllegalArgumentException("记录类型不能为空(DIAGNOSIS/EVALUATION/PREDICTION)");
        }
        healthRecordMapper.insert(record);
    }

    public List<HealthRecord> listHealthRecords(String tailNumber, String recordType) {
        LambdaQueryWrapper<HealthRecord> wrapper = Wrappers.lambdaQuery();
        boolean hasTail = tailNumber != null && !tailNumber.isEmpty();
        boolean hasType = recordType != null && !recordType.isEmpty();

        if (hasTail) {
            wrapper.eq(HealthRecord::getTailNumber, tailNumber);
        }
        if (hasType) {
            wrapper.eq(HealthRecord::getRecordType, recordType);
        }
        wrapper.orderByDesc(HealthRecord::getRecordTime);

        return healthRecordMapper.selectList(wrapper);
    }
}
