package com.project.phm.config;

import com.project.phm.entity.*;
import com.project.phm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;

/**
 * 演示数据初始化器 — 首次启动时自动填充飞机构型演示数据
 *
 * 仅在 aircraft_model 表为空时执行，保证幂等
 */
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AircraftModelMapper aircraftModelMapper;
    private final AircraftConfigMapper aircraftConfigMapper;
    private final ConfigItemMapper configItemMapper;
    private final HealthRecordMapper healthRecordMapper;
    private final ConfigDataMappingMapper configDataMappingMapper;

    public DataSeeder(AircraftModelMapper aircraftModelMapper,
                      AircraftConfigMapper aircraftConfigMapper,
                      ConfigItemMapper configItemMapper,
                      HealthRecordMapper healthRecordMapper,
                      ConfigDataMappingMapper configDataMappingMapper) {
        this.aircraftModelMapper = aircraftModelMapper;
        this.aircraftConfigMapper = aircraftConfigMapper;
        this.configItemMapper = configItemMapper;
        this.healthRecordMapper = healthRecordMapper;
        this.configDataMappingMapper = configDataMappingMapper;
    }

    @PostConstruct
    @Transactional
    public void seed() {
        if (aircraftModelMapper.selectCount(null) > 0) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }

        log.info("=== 开始填充飞机构型演示数据 ===");

        // 1. 机型
        insertModel("B737-800", "Boeing", "波音737-800型客机，CFM56-7B发动机");
        insertModel("A320-200", "Airbus", "空客A320-200型客机，CFM56-5B发动机");
        insertModel("B787-9", "Boeing", "波音787-9梦想飞机，GEnx发动机");

        // 2. 飞机构型
        insertConfig("B-1234", "B737-800", "中国国际航空", "V1.2", "active");
        insertConfig("B-5678", "B737-800", "中国南方航空", "V1.1", "active");
        insertConfig("B-9012", "A320-200", "中国东方航空", "V2.0", "active");
        insertConfig("B-3456", "B787-9", "厦门航空", "V1.0", "active");

        // 3. 构型项目 — B737-800
        long b738_engine = insertItem("B737-800", null, "72-00", "推进系统", null, null, null, "SYSTEM");
        long b738_landing = insertItem("B737-800", null, "32-00", "起落架", null, null, null, "SYSTEM");
        long b738_nav = insertItem("B737-800", null, "34-00", "导航系统", null, null, null, "SYSTEM");
        long b738_power = insertItem("B737-800", null, "24-00", "电源系统", null, null, null, "SYSTEM");
        long b738_apu = insertItem("B737-800", null, "49-00", "APU辅助动力装置", null, null, null, "SYSTEM");

        long b738_engine_body = insertItem("B737-800", b738_engine, "72-00", null, "发动机本体", null, null, "SUBSYSTEM");
        long b738_engine_ind = insertItem("B737-800", b738_engine, "72-50", null, "发动机指示系统", null, null, "SUBSYSTEM");
        long b738_fuel = insertItem("B737-800", b738_engine, "73-00", null, "发动机燃油和控制", null, null, "SUBSYSTEM");

        insertItem("B737-800", b738_engine_body, null, null, null, "高压压气机叶片", "CFM56-7B-001", "EQUIPMENT");
        insertItem("B737-800", b738_engine_body, null, null, null, "低压涡轮叶片", "CFM56-7B-002", "EQUIPMENT");
        insertItem("B737-800", b738_engine_body, null, null, null, "燃烧室", "CFM56-7B-003", "EQUIPMENT");
        insertItem("B737-800", b738_engine_body, null, null, null, "风扇叶片", "CFM56-7B-004", "EQUIPMENT");

        insertItem("B737-800", b738_engine_ind, null, null, null, "排气温度EGT传感器", "EGT-2000B", "EQUIPMENT");
        insertItem("B737-800", b738_engine_ind, null, null, null, "振动传感器", "VIB-3000B", "EQUIPMENT");
        insertItem("B737-800", b738_engine_ind, null, null, null, "N1转速传感器", "N1-100B", "EQUIPMENT");

        insertItem("B737-800", b738_fuel, null, null, null, "燃油计量活门", "FMV-400B", "EQUIPMENT");
        insertItem("B737-800", b738_fuel, null, null, null, "燃油泵", "FP-500B", "EQUIPMENT");

        long b738_nose_gear = insertItem("B737-800", b738_landing, null, null, "前起落架", null, null, "SUBSYSTEM");
        long b738_main_gear = insertItem("B737-800", b738_landing, null, null, "主起落架", null, null, "SUBSYSTEM");

        insertItem("B737-800", b738_nose_gear, null, null, null, "前轮转弯作动筒", "ACT-1000B", "EQUIPMENT");
        insertItem("B737-800", b738_nose_gear, null, null, null, "前起减震支柱", "STR-2000B", "EQUIPMENT");
        insertItem("B737-800", b738_main_gear, null, null, null, "主轮刹车组件", "BRK-3000B", "EQUIPMENT");
        insertItem("B737-800", b738_main_gear, null, null, null, "主起减震支柱", "STR-4000B", "EQUIPMENT");

        insertItem("B737-800", b738_nav, null, null, null, "ADIRU大气数据惯性基准单元", "ADR-100B", "EQUIPMENT");
        insertItem("B737-800", b738_nav, null, null, null, "GPS接收机", "GPS-200B", "EQUIPMENT");
        insertItem("B737-800", b738_nav, null, null, null, "无线电高度表", "RA-300B", "EQUIPMENT");
        insertItem("B737-800", b738_nav, null, null, null, "ILS仪表着陆系统", "ILS-400B", "EQUIPMENT");

        insertItem("B737-800", b738_power, null, null, null, "IDG整体驱动发电机", "IDG-100B", "EQUIPMENT");
        insertItem("B737-800", b738_power, null, null, null, "APU发电机", "APG-200B", "EQUIPMENT");
        insertItem("B737-800", b738_power, null, null, null, "静变流机", "INV-300B", "EQUIPMENT");

        insertItem("B737-800", b738_apu, null, null, null, "APU本体", "APU-131-9B", "EQUIPMENT");
        insertItem("B737-800", b738_apu, null, null, null, "APU启动机", "APS-200B", "EQUIPMENT");

        // 4. 构型项目 — A320-200
        long a320_engine = insertItem("A320-200", null, "72-00", "推进系统", null, null, null, "SYSTEM");
        long a320_landing = insertItem("A320-200", null, "32-00", "起落架", null, null, null, "SYSTEM");
        long a320_vent = insertItem("A320-200", null, "21-00", "空调与增压", null, null, null, "SYSTEM");

        long a320_engine_body = insertItem("A320-200", a320_engine, "72-00", null, "发动机本体", null, null, "SUBSYSTEM");
        insertItem("A320-200", a320_engine_body, null, null, null, "风扇叶片", "CFM56-5B-001", "EQUIPMENT");
        insertItem("A320-200", a320_engine_body, null, null, null, "高压涡轮叶片", "CFM56-5B-002", "EQUIPMENT");
        insertItem("A320-200", a320_engine_body, null, null, null, "燃烧室", "CFM56-5B-003", "EQUIPMENT");

        long a320_engine_ind = insertItem("A320-200", a320_engine, "72-50", null, "发动机指示系统", null, null, "SUBSYSTEM");
        insertItem("A320-200", a320_engine_ind, null, null, null, "振动监测器", "VM-3000A", "EQUIPMENT");
        insertItem("A320-200", a320_engine_ind, null, null, null, "EGT热电偶", "EGT-2000A", "EQUIPMENT");

        // 5. 构型项目 — B787-9
        long b789_engine = insertItem("B787-9", null, "72-00", "推进系统", null, null, null, "SYSTEM");
        long b789_elec = insertItem("B787-9", null, "24-00", "电源系统", null, null, null, "SYSTEM");

        long b789_engine_body = insertItem("B787-9", b789_engine, "72-00", null, "发动机本体", null, null, "SUBSYSTEM");
        insertItem("B787-9", b789_engine_body, null, null, null, "风扇叶片", "GENx-001", "EQUIPMENT");
        insertItem("B787-9", b789_engine_body, null, null, null, "高压压气机", "GENx-002", "EQUIPMENT");
        insertItem("B787-9", b789_engine_body, null, null, null, "低压涡轮", "GENx-003", "EQUIPMENT");

        // 6. 健康记录
        seedHealthData();

        log.info("=== 飞机构型演示数据填充完成 ===");
        log.info("  机型: 3 种 (B737-800, A320-200, B787-9)");
        log.info("  构型: 4 架 (B-1234, B-5678, B-9012, B-3456)");
        log.info("  构型项目: 约 45 项 (ATA章节组织)");
        log.info("  健康记录: 12 条 (含诊断/评价/预测)");
    }

    private void insertModel(String modelCode, String manufacturer, String description) {
        AircraftModel model = new AircraftModel();
        model.setModelCode(modelCode);
        model.setManufacturer(manufacturer);
        model.setDescription(description);
        aircraftModelMapper.insert(model);
    }

    private void insertConfig(String tailNumber, String modelCode, String airline,
                              String configVersion, String status) {
        Aircraft aircraft = new Aircraft();
        aircraft.setAircraftNumber(tailNumber);
        aircraft.setModelCode(modelCode);
        aircraft.setAirline(airline);
        aircraft.setConfigVersion(configVersion);
        aircraft.setStatus(status);
        aircraftConfigMapper.insert(aircraft);
    }

    private long insertItem(String modelCode, Long parentId, String ata, String sys,
                            String subSys, String equip, String pn, String type) {
        ConfigItem item = new ConfigItem();
        item.setModelCode(modelCode);
        item.setParentItemId(parentId);
        item.setAtaChapter(ata);
        item.setSystemName(sys);
        item.setSubSystemName(subSys);
        item.setEquipmentName(equip);
        item.setPartNumber(pn);
        item.setItemType(type);
        configItemMapper.insert(item);
        return item.getItemId();
    }

    private void seedHealthData() {
        ConfigItem vibSensor = configItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, "B737-800")
                        .eq(ConfigItem::getEquipmentName, "振动传感器")
        ).stream().findFirst().orElse(null);
        ConfigItem egtSensor = configItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, "B737-800")
                        .eq(ConfigItem::getEquipmentName, "排气温度EGT传感器")
        ).stream().findFirst().orElse(null);
        ConfigItem n1Sensor = configItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, "B737-800")
                        .eq(ConfigItem::getEquipmentName, "N1转速传感器")
        ).stream().findFirst().orElse(null);

        Long vibSensorId = vibSensor != null ? vibSensor.getItemId() : null;
        Long egtSensorId = egtSensor != null ? egtSensor.getItemId() : null;
        Long n1SensorId = n1Sensor != null ? n1Sensor.getItemId() : null;

        if (vibSensorId != null) {
            insertHealth("B-1234", vibSensorId, "DIAGNOSIS", "风扇端振动值", "2.3 mm/s", "0.92", "2026-04-15 10:30:00");
            insertHealth("B-1234", vibSensorId, "DIAGNOSIS", "涡轮端振动值", "1.8 mm/s", "0.92", "2026-04-15 10:30:00");
            insertHealth("B-1234", vibSensorId, "EVALUATION", "振动趋势评估", "正常范围（阈值<4.0mm/s）", "0.88", "2026-04-16 08:00:00");
            insertHealth("B-1234", vibSensorId, "PREDICTION", "未来50飞行循环振动值预测", "2.5 mm/s", "0.76", "2026-04-16 08:00:00");
        }
        if (egtSensorId != null) {
            insertHealth("B-1234", egtSensorId, "DIAGNOSIS", "起飞EGT裕度", "38.5 °C", "0.95", "2026-04-15 10:30:00");
            insertHealth("B-1234", egtSensorId, "EVALUATION", "EGT裕度趋势评估", "正常（裕度>25°C）", "0.90", "2026-04-16 08:00:00");
            insertHealth("B-1234", egtSensorId, "PREDICTION", "未来200循环EGT裕度预测", "32.1 °C", "0.72", "2026-04-16 08:00:00");
        }
        if (n1SensorId != null) {
            insertHealth("B-5678", n1SensorId, "DIAGNOSIS", "N1转速偏差", "0.8 %", "0.91", "2026-04-14 14:20:00");
            insertHealth("B-5678", n1SensorId, "EVALUATION", "N1振动综合评估", "需关注（趋势上升）", "0.82", "2026-04-15 09:00:00");
            insertHealth("B-5678", n1SensorId, "PREDICTION", "未来100循环N1偏差预测", "1.2 %", "0.65", "2026-04-15 09:00:00");
        }

        ConfigItem vibMonitor = configItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConfigItem>lambdaQuery()
                        .eq(ConfigItem::getModelCode, "A320-200")
                        .eq(ConfigItem::getEquipmentName, "振动监测器")
        ).stream().findFirst().orElse(null);
        Long vibMonitorId = vibMonitor != null ? vibMonitor.getItemId() : null;

        if (vibMonitorId != null) {
            insertHealth("B-9012", vibMonitorId, "DIAGNOSIS", "发动机振动值", "3.1 mm/s", "0.90", "2026-04-13 16:00:00");
            insertHealth("B-9012", vibMonitorId, "PREDICTION", "未来50循环振动值预测", "3.5 mm/s", "0.70", "2026-04-14 08:00:00");
        }

        // 数据关联映射
        if (vibSensorId != null) {
            insertMapping("B-1234", vibSensorId, "csv_engine_vibration", "RAW", "2026-04-15 10:30:00");
        }
        if (egtSensorId != null) {
            insertMapping("B-1234", egtSensorId, "csv_egt_data", "RAW", "2026-04-15 10:30:00");
        }
        if (n1SensorId != null) {
            insertMapping("B-1234", n1SensorId, "csv_apu_data", "RAW", "2026-04-15 10:30:00");
            insertMapping("B-5678", n1SensorId, "csv_engine_vibration", "RAW", "2026-04-14 14:20:00");
        }
        if (vibMonitorId != null) {
            insertMapping("B-9012", vibMonitorId, "csv_engine_vibration", "RAW", "2026-04-13 16:00:00");
        }

        log.info("  健康记录+数据关联已填充");
    }

    private void insertHealth(String tailNo, Long itemId, String type, String name, String value, String conf, String time) {
        HealthRecord record = new HealthRecord();
        record.setAircraftNumber(tailNo);
        record.setItemId(itemId);
        record.setRecordType(type);
        record.setIndicatorName(name);
        record.setIndicatorValue(value);
        record.setConfidence(conf);
        record.setRecordTime(time);
        healthRecordMapper.insert(record);
    }

    private void insertMapping(String tailNo, Long itemId, String tableName, String dataType, String time) {
        ConfigDataMapping mapping = new ConfigDataMapping();
        mapping.setAircraftNumber(tailNo);
        mapping.setItemId(itemId);
        mapping.setCsvTableName(tableName);
        mapping.setDataType(dataType);
        mapping.setDataTime(time);
        configDataMappingMapper.insert(mapping);
    }
}
