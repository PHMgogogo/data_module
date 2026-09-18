package com.project.phm.service;

import com.project.phm.adapter.dto.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 平台路由树 —— 以平台为根维护「平台 → 机型 → 单机 → 架次 → CSV 表」关系。
 *
 * <p>各业务接口返回数据后调用本类的方法做增量更新，不再由一个接口一次性构建所有层级。</p>
 *
 * <p>写操作串行化；写完后重新构建不可变查询快照，读侧无锁。</p>
 */
@Service
public class PlatformRouteService {

    private static final Logger log = LoggerFactory.getLogger(PlatformRouteService.class);

    /** 同一标识命中多个平台时的固定优先级。 */
    private static final List<DataSource> PRIORITY = Collections.unmodifiableList(
            Arrays.asList(DataSource.LOCAL, DataSource.HANGXIN, DataSource.SAN_SAN));

    private static final int MAX_CONFLICT_KEYS_LOGGED = 10;

    /** 平台机型接口返回的一条机型。 */
    public static final class ModelDefinition {

        private final String modelKey;
        private final String realAirplaneType;

        public ModelDefinition(String modelKey, String realAirplaneType) {
            this.modelKey = normalize(modelKey);
            this.realAirplaneType = normalize(realAirplaneType);
        }

        public String getModelKey() { return modelKey; }
        public String getRealAirplaneType() { return realAirplaneType; }
    }

    /** 单机查询结果，用于查询反查和日志。 */
    public static final class AircraftRef {

        private final DataSource source;
        private final String aircraftNumber;
        private final String modelKey;
        private final String realAirplaneType;

        private AircraftRef(DataSource source, String aircraftNumber,
                            String modelKey, String realAirplaneType) {
            this.source = source;
            this.aircraftNumber = aircraftNumber;
            this.modelKey = modelKey;
            this.realAirplaneType = realAirplaneType;
        }

        public DataSource getSource() { return source; }
        public String getAircraftNumber() { return aircraftNumber; }
        public String getModelKey() { return modelKey; }
        public String getRealAirplaneType() { return realAirplaneType; }
    }

    /** 一条架次在树中的完整引用。 */
    public static final class SortieRef {

        private final DataSource source;
        private final Long localSortieId;
        private final String sortieKey;
        private final String modelKey;
        private final String airplaneType;
        private final String airplaneNum;
        private final String flightNum;
        private final String startTime;
        private final String endTime;
        private final String parameterGroupId;

        private SortieRef(DataSource source, Long localSortieId, String sortieKey,
                          String modelKey, String airplaneType, String airplaneNum,
                          String flightNum, String startTime, String endTime,
                          String parameterGroupId) {
            this.source = source;
            this.localSortieId = localSortieId;
            this.sortieKey = normalize(sortieKey);
            this.modelKey = normalize(modelKey);
            this.airplaneType = normalize(airplaneType);
            this.airplaneNum = normalize(airplaneNum);
            this.flightNum = normalize(flightNum);
            this.startTime = normalize(startTime);
            this.endTime = normalize(endTime);
            this.parameterGroupId = normalize(parameterGroupId);
        }

        public static SortieRef local(Long sortieId, String modelKey, String airplaneNum) {
            return new SortieRef(DataSource.LOCAL, sortieId, localSortieKey(sortieId),
                    normalize(modelKey), null, normalize(airplaneNum),
                    null, null, null, null);
        }

        public static SortieRef external(DataSource source, String sortieKey,
                                         String airplaneType, String airplaneNum, String flightNum,
                                         String startTime, String endTime, String parameterGroupId) {
            return new SortieRef(source, null, sortieKey, null,
                    normalize(airplaneType), normalize(airplaneNum), normalize(flightNum),
                    normalize(startTime), normalize(endTime), normalize(parameterGroupId));
        }

        SortieRef withModelKey(String value) {
            return new SortieRef(source, localSortieId, sortieKey, normalize(value),
                    airplaneType, airplaneNum, flightNum, startTime, endTime, parameterGroupId);
        }

        public DataSource getSource() { return source; }
        public Long getLocalSortieId() { return localSortieId; }
        public String getSortieKey() { return sortieKey; }
        public String getModelKey() { return modelKey; }
        public String getAirplaneType() { return airplaneType; }
        public String getAirplaneNum() { return airplaneNum; }
        public String getFlightNum() { return flightNum; }
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public String getParameterGroupId() { return parameterGroupId; }

        @Override
        public String toString() {
            return source + "#" + sortieKey;
        }
    }

    // ==================== 树节点 ====================

    private static final class TableNode {
        private final String tableName;

        private TableNode(String tableName) {
            this.tableName = tableName;
        }
    }

    private static final class SortieNode {
        private final SortieRef ref;
        private final Map<String, TableNode> tables = new LinkedHashMap<>();

        private SortieNode(SortieRef ref) {
            this.ref = ref;
        }
    }

    private static final class AircraftNode {
        private final DataSource source;
        private final String aircraftNumber;
        private final String modelKey;
        private final String realAirplaneType;
        private final Map<String, SortieNode> sorties = new LinkedHashMap<>();

        private AircraftNode(DataSource source, String aircraftNumber,
                             String modelKey, String realAirplaneType) {
            this.source = source;
            this.aircraftNumber = aircraftNumber;
            this.modelKey = modelKey;
            this.realAirplaneType = realAirplaneType;
        }
    }

    private static final class ModelNode {
        private final DataSource source;
        private final String modelKey;
        private final String realAirplaneType;
        private final Map<String, AircraftNode> aircraft = new LinkedHashMap<>();

        private ModelNode(DataSource source, String modelKey, String realAirplaneType) {
            this.source = source;
            this.modelKey = modelKey;
            this.realAirplaneType = realAirplaneType;
        }
    }

    private static final class PlatformNode {
        private final DataSource source;
        private final Map<String, ModelNode> models = new LinkedHashMap<>();

        private PlatformNode(DataSource source) {
            this.source = source;
        }
    }

    private final Map<DataSource, PlatformNode> platforms = new EnumMap<>(DataSource.class);
    private volatile RouteIndex index = RouteIndex.empty();

    /** 不可变查询快照。 */
    private static final class RouteIndex {

        private final Map<String, DataSource> modelToPlatform;
        private final Map<String, String> modelToRealAirplaneType;
        private final Map<String, DataSource> modelAliasToPlatform;
        private final Map<String, Set<String>> modelAliasToKeys;
        private final Map<String, Set<String>> modelToAircraftNumbers;
        private final Map<String, AircraftRef> aircraftToRef;
        private final Map<String, SortieRef> sortieToRef;
        private final Map<String, SortieRef> parameterGroupToRef;
        private final Map<String, Set<String>> sortieToTables;
        private final Map<String, String> tableToSortie;

        private RouteIndex(Map<String, DataSource> modelToPlatform,
                           Map<String, String> modelToRealAirplaneType,
                           Map<String, DataSource> modelAliasToPlatform,
                           Map<String, Set<String>> modelAliasToKeys,
                           Map<String, Set<String>> modelToAircraftNumbers,
                           Map<String, AircraftRef> aircraftToRef,
                           Map<String, SortieRef> sortieToRef,
                           Map<String, SortieRef> parameterGroupToRef,
                           Map<String, Set<String>> sortieToTables,
                           Map<String, String> tableToSortie) {
            this.modelToPlatform = modelToPlatform;
            this.modelToRealAirplaneType = modelToRealAirplaneType;
            this.modelAliasToPlatform = modelAliasToPlatform;
            this.modelAliasToKeys = modelAliasToKeys;
            this.modelToAircraftNumbers = modelToAircraftNumbers;
            this.aircraftToRef = aircraftToRef;
            this.sortieToRef = sortieToRef;
            this.parameterGroupToRef = parameterGroupToRef;
            this.sortieToTables = sortieToTables;
            this.tableToSortie = tableToSortie;
        }

        private static RouteIndex empty() {
            return new RouteIndex(Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap());
        }
    }

    // ==================== 增量更新入口 ====================

    /**
     * 替换某平台的全部机型。
     *
     * <p>同名机型若仍存在，会保留它下面已经加载的单机、架次和 CSV 表；被删除的机型
     * 连同其整个子树一起移除。</p>
     */
    public synchronized void replaceModels(DataSource source, Collection<ModelDefinition> models) {
        if (source == null) {
            return;
        }
        PlatformNode oldPlatform = platforms.get(source);
        PlatformNode fresh = new PlatformNode(source);
        if (models != null) {
            for (ModelDefinition definition : models) {
                if (definition == null || definition.getModelKey() == null) {
                    continue;
                }
                ModelNode node = new ModelNode(source, definition.getModelKey(),
                        firstNonBlank(definition.getRealAirplaneType(), definition.getModelKey()));
                ModelNode oldModel = oldPlatform == null ? null : oldPlatform.models.get(definition.getModelKey());
                if (oldModel != null) {
                    node.aircraft.putAll(oldModel.aircraft);
                }
                fresh.models.put(node.modelKey, node);
            }
        }
        platforms.put(source, fresh);
        refreshIndex();
        log.info("{}机型映射已增量更新: {}", source.getLabel(), fresh.models.size());
    }

    /** 替换某机型下已经加载的单机集合，架次和 CSV 表在机号未变化时保留。 */
    public synchronized void replaceAircraft(DataSource source, String modelKey,
                                             Collection<String> aircraftNumbers) {
        ModelNode model = findModel(source, modelKey);
        if (model == null) {
            log.warn("{}单机更新失败，机型 {} 尚未登记", sourceLabel(source), modelKey);
            return;
        }
        Map<String, AircraftNode> fresh = new LinkedHashMap<>();
        if (aircraftNumbers != null) {
            for (String number : aircraftNumbers) {
                String normalized = normalize(number);
                if (normalized == null) {
                    continue;
                }
                AircraftNode old = model.aircraft.get(normalized);
                AircraftNode node = old != null ? old : new AircraftNode(source, normalized,
                        model.modelKey, model.realAirplaneType);
                fresh.put(normalized, node);
            }
        }
        model.aircraft.clear();
        model.aircraft.putAll(fresh);
        refreshIndex();
        log.info("{}机型 {} 的单机映射已增量更新: {}", sourceLabel(source),
                model.modelKey, fresh.size());
    }

    /** 增量登记一个单机，保留该机号下已经加载的架次和 CSV 表。 */
    public synchronized void addAircraft(DataSource source, String modelKey, String aircraftNumber) {
        String number = normalize(aircraftNumber);
        ModelNode model = findModel(source, modelKey);
        if (model == null && source == DataSource.LOCAL && normalize(modelKey) != null) {
            PlatformNode platform = platform(DataSource.LOCAL);
            String key = normalize(modelKey);
            model = new ModelNode(DataSource.LOCAL, key, key);
            platform.models.put(key, model);
        }
        if (number == null || model == null) {
            log.warn("{}单机增量登记失败: modelKey={}, aircraftNumber={}",
                    sourceLabel(source), modelKey, aircraftNumber);
            return;
        }
        model.aircraft.putIfAbsent(number, new AircraftNode(source, number,
                model.modelKey, model.realAirplaneType));
        refreshIndex();
    }

    /** 替换某单机下已经加载的架次集合，本地 CSV 表在 sortieKey 未变化时保留。 */
    public synchronized void replaceSorties(DataSource source, String aircraftNumber,
                                            Collection<SortieRef> sorties) {
        AircraftNode aircraft = findAircraft(source, aircraftNumber);
        if (aircraft == null) {
            log.warn("{}架次更新失败，机号 {} 尚未登记", sourceLabel(source), aircraftNumber);
            return;
        }
        Map<String, SortieNode> fresh = new LinkedHashMap<>();
        if (sorties != null) {
            for (SortieRef ref : sorties) {
                if (ref == null || ref.getSortieKey() == null) {
                    continue;
                }
                SortieRef routed = ref.getModelKey() == null
                        ? ref.withModelKey(aircraft.modelKey) : ref;
                SortieNode old = aircraft.sorties.get(routed.getSortieKey());
                SortieNode node = new SortieNode(routed);
                if (old != null) {
                    node.tables.putAll(old.tables);
                }
                fresh.put(routed.getSortieKey(), node);
            }
        }
        aircraft.sorties.clear();
        aircraft.sorties.putAll(fresh);
        refreshIndex();
        log.info("{}机号 {} 的架次映射已增量更新: {}", sourceLabel(source),
                aircraft.aircraftNumber, fresh.size());
    }

    /** 将一张本地 CSV 表绑定到本地架次；表和架次都只能各绑定一个对象。 */
    public synchronized void bindTable(String sortieKey, String tableName) {
        SortieNode sortie = findSortieNode(sortieKey);
        String normalizedTable = normalize(tableName);
        if (sortie == null || normalizedTable == null) {
            throw new IllegalArgumentException("架次或CSV表不存在");
        }
        if (sortie.ref.getSource() != DataSource.LOCAL) {
            throw new IllegalArgumentException("CSV表只能绑定本地架次");
        }
        if (!sortie.tables.isEmpty() && !sortie.tables.containsKey(normalizedTable)) {
            throw new IllegalArgumentException("该架次已绑定CSV表: "
                    + sortie.tables.keySet().iterator().next());
        }
        String existingSortie = index.tableToSortie.get(normalizedTable);
        if (existingSortie != null && !existingSortie.equals(sortie.ref.getSortieKey())) {
            throw new IllegalArgumentException("该CSV表已绑定架次: " + existingSortie);
        }
        sortie.tables.put(normalizedTable, new TableNode(normalizedTable));
        refreshIndex();
    }

    public synchronized void unbindTable(String tableName) {
        String normalizedTable = normalize(tableName);
        if (normalizedTable == null) {
            return;
        }
        String sortieKey = index.tableToSortie.get(normalizedTable);
        SortieNode sortie = findSortieNode(sortieKey);
        if (sortie != null) {
            sortie.tables.remove(normalizedTable);
            refreshIndex();
        }
    }

    /** 某架次绑定的 CSV 表，最多一张。 */
    public Set<String> tablesForSortie(String sortieKey) {
        String key = normalize(sortieKey);
        if (key == null) {
            return Collections.emptySet();
        }
        Set<String> tables = index.sortieToTables.get(key);
        return tables == null ? Collections.emptySet() : tables;
    }

    // ==================== 本地增删同步 ====================

    public synchronized void addLocalModel(String modelCode) {
        String key = normalize(modelCode);
        if (key == null) {
            return;
        }
        PlatformNode platform = platform(DataSource.LOCAL);
        platform.models.putIfAbsent(key, new ModelNode(DataSource.LOCAL, key, key));
        refreshIndex();
    }

    public synchronized void removeLocalModel(String modelCode) {
        String key = normalize(modelCode);
        PlatformNode platform = platforms.get(DataSource.LOCAL);
        if (key != null && platform != null && platform.models.remove(key) != null) {
            refreshIndex();
        }
    }

    public synchronized void addLocalAircraft(String aircraftNumber, String modelCode) {
        String number = normalize(aircraftNumber);
        ModelNode model = findModel(DataSource.LOCAL, modelCode);
        if (number == null || model == null) {
            log.warn("本地单机增量同步失败: aircraftNumber={}, modelCode={}", aircraftNumber, modelCode);
            return;
        }
        model.aircraft.putIfAbsent(number, new AircraftNode(DataSource.LOCAL, number,
                model.modelKey, model.realAirplaneType));
        refreshIndex();
    }

    public synchronized void removeLocalAircraft(String aircraftNumber) {
        String number = normalize(aircraftNumber);
        PlatformNode platform = platforms.get(DataSource.LOCAL);
        if (number == null || platform == null) {
            return;
        }
        for (ModelNode model : platform.models.values()) {
            if (model.aircraft.remove(number) != null) {
                refreshIndex();
                return;
            }
        }
    }

    public synchronized void addLocalSortie(Long sortieId, String aircraftNumber, String modelCode) {
        SortieRef ref = SortieRef.local(sortieId, modelCode, aircraftNumber);
        AircraftNode aircraft = findAircraft(DataSource.LOCAL, aircraftNumber);
        if (ref.getSortieKey() == null || aircraft == null) {
            log.warn("本地架次增量同步失败: sortieId={}, aircraftNumber={}",
                    sortieId, aircraftNumber);
            return;
        }
        SortieNode old = aircraft.sorties.get(ref.getSortieKey());
        SortieNode node = new SortieNode(ref.withModelKey(aircraft.modelKey));
        if (old != null) {
            node.tables.putAll(old.tables);
        }
        aircraft.sorties.put(ref.getSortieKey(), node);
        refreshIndex();
    }

    public synchronized void removeLocalSortie(Long sortieId) {
        SortieNode sortie = findSortieNode(localSortieKey(sortieId));
        if (sortie == null || sortie.ref.getSource() != DataSource.LOCAL) {
            return;
        }
        AircraftNode aircraft = findAircraft(DataSource.LOCAL, sortie.ref.getAirplaneNum());
        if (aircraft != null && aircraft.sorties.remove(sortie.ref.getSortieKey()) != null) {
            refreshIndex();
        }
    }

    // ==================== 查询 ====================

    public DataSource resolveModel(String modelCode) {
        String key = normalize(modelCode);
        if (key == null) {
            return null;
        }
        DataSource exact = index.modelToPlatform.get(key);
        return exact != null ? exact : index.modelAliasToPlatform.get(key);
    }

    /** 将合成码或原始 airplaneType 展开为实际机型键集合。 */
    public Set<String> modelKeys(String modelCode) {
        String key = normalize(modelCode);
        if (key == null) {
            return Collections.emptySet();
        }
        if (index.modelToPlatform.containsKey(key)) {
            return Collections.singleton(key);
        }
        Set<String> aliases = index.modelAliasToKeys.get(key);
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
    }

    /** 某平台内真实 airplaneType 对应的机型键集合。 */
    public Set<String> modelKeysByAirplaneType(DataSource source, String airplaneType) {
        PlatformNode platform = source == null ? null : platforms.get(source);
        String type = normalize(airplaneType);
        if (platform == null || type == null) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (ModelNode model : platform.models.values()) {
            if (type.equals(model.realAirplaneType) || type.equals(model.modelKey)) {
                result.add(model.modelKey);
            }
        }
        return result;
    }

    public Set<String> aircraftNumbersForModel(String modelCode) {
        String key = normalize(modelCode);
        if (key == null) {
            return Collections.emptySet();
        }
        Set<String> exact = index.modelToAircraftNumbers.get(key);
        if (exact != null) {
            return exact;
        }
        Set<String> modelKeys = index.modelAliasToKeys.get(key);
        if (modelKeys == null || modelKeys.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String modelKey : modelKeys) {
            Set<String> numbers = index.modelToAircraftNumbers.get(modelKey);
            if (numbers != null) {
                result.addAll(numbers);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public String realAirplaneType(String modelCode) {
        String key = normalize(modelCode);
        if (key == null) {
            return null;
        }
        String real = index.modelToRealAirplaneType.get(key);
        if (real != null) {
            return real;
        }
        return index.modelAliasToPlatform.containsKey(key) ? key : null;
    }

    public DataSource resolveAircraft(String aircraftNumber) {
        AircraftRef ref = resolveAircraftRef(aircraftNumber);
        return ref == null ? null : ref.getSource();
    }

    public AircraftRef resolveAircraftRef(String aircraftNumber) {
        String key = normalize(aircraftNumber);
        return key == null ? null : index.aircraftToRef.get(key);
    }

    public SortieRef resolveSortie(String sortieKey) {
        String key = normalize(sortieKey);
        return key == null ? null : index.sortieToRef.get(key);
    }

    public SortieRef resolveParameterGroup(String parameterGroupId) {
        String key = normalize(parameterGroupId);
        return key == null ? null : index.parameterGroupToRef.get(key);
    }

    public boolean isReady() {
        return !index.modelToPlatform.isEmpty();
    }

    public String describe() {
        StringBuilder detail = new StringBuilder();
        int modelCount = 0;
        int aircraftCount = 0;
        int sortieCount = 0;
        int tableCount = 0;
        for (DataSource source : PRIORITY) {
            PlatformNode platform = platforms.get(source);
            int models = platform == null ? 0 : platform.models.size();
            int aircraft = 0;
            int sorties = 0;
            int tables = 0;
            if (platform != null) {
                for (ModelNode model : platform.models.values()) {
                    aircraft += model.aircraft.size();
                    for (AircraftNode plane : model.aircraft.values()) {
                        sorties += plane.sorties.size();
                        for (SortieNode sortie : plane.sorties.values()) {
                            tables += sortie.tables.size();
                        }
                    }
                }
            }
            modelCount += models;
            aircraftCount += aircraft;
            sortieCount += sorties;
            tableCount += tables;
            detail.append(source.getLabel()).append('=')
                    .append(models).append('/').append(aircraft).append('/')
                    .append(sorties).append('/').append(tables).append(' ');
        }
        return String.format("平台=%d, 机型=%d, 单机=%d, 架次=%d, 表=%d [%s]",
                platforms.size(), modelCount, aircraftCount, sortieCount, tableCount,
                detail.toString().trim());
    }

    // ==================== 快照构建 ====================

    private void refreshIndex() {
        Map<String, DataSource> modelToPlatform = new HashMap<>();
        Map<String, String> modelToRealType = new HashMap<>();
        Map<String, DataSource> modelAliasToPlatform = new HashMap<>();
        Map<String, Set<String>> modelAliasToKeys = new HashMap<>();
        Map<String, Set<String>> modelToAircraftNumbers = new HashMap<>();
        Map<String, AircraftRef> aircraftToRef = new HashMap<>();
        Map<String, SortieRef> sortieToRef = new HashMap<>();
        Map<String, SortieRef> parameterGroupToRef = new HashMap<>();
        Map<String, Set<String>> sortieToTables = new HashMap<>();
        Map<String, String> tableToSortie = new HashMap<>();

        List<String> modelConflicts = new ArrayList<>();
        List<String> modelAliasConflicts = new ArrayList<>();
        List<String> aircraftConflicts = new ArrayList<>();
        List<String> sortieConflicts = new ArrayList<>();
        List<String> tableConflicts = new ArrayList<>();

        for (DataSource source : PRIORITY) {
            PlatformNode platform = platforms.get(source);
            if (platform == null) {
                continue;
            }
            for (ModelNode model : platform.models.values()) {
                if (modelToPlatform.putIfAbsent(model.modelKey, source) != null) {
                    modelConflicts.add(model.modelKey);
                    continue;
                }
                modelToRealType.put(model.modelKey, model.realAirplaneType);
                DataSource aliasOwner = modelAliasToPlatform.putIfAbsent(
                        model.realAirplaneType, source);
                if (aliasOwner == null || aliasOwner == source) {
                    modelAliasToKeys
                            .computeIfAbsent(model.realAirplaneType, key -> new LinkedHashSet<>())
                            .add(model.modelKey);
                } else {
                    modelAliasConflicts.add(model.realAirplaneType);
                }

                for (AircraftNode aircraft : model.aircraft.values()) {
                    if (aircraftToRef.putIfAbsent(aircraft.aircraftNumber,
                            new AircraftRef(source, aircraft.aircraftNumber,
                                    model.modelKey, model.realAirplaneType)) != null) {
                        aircraftConflicts.add(aircraft.aircraftNumber);
                        continue;
                    }
                    modelToAircraftNumbers
                            .computeIfAbsent(model.modelKey, key -> new LinkedHashSet<>())
                            .add(aircraft.aircraftNumber);

                    for (SortieNode sortieNode : aircraft.sorties.values()) {
                        SortieRef routed = sortieNode.ref.withModelKey(model.modelKey);
                        if (sortieToRef.putIfAbsent(routed.getSortieKey(), routed) != null) {
                            sortieConflicts.add(routed.getSortieKey());
                            continue;
                        }
                        if (routed.getParameterGroupId() != null) {
                            parameterGroupToRef.putIfAbsent(routed.getParameterGroupId(), routed);
                        }
                        if (!sortieNode.tables.isEmpty()) {
                            Set<String> tables = new LinkedHashSet<>(sortieNode.tables.keySet());
                            sortieToTables.put(routed.getSortieKey(),
                                    Collections.unmodifiableSet(tables));
                            for (String table : tables) {
                                if (tableToSortie.putIfAbsent(table, routed.getSortieKey()) != null) {
                                    tableConflicts.add(table);
                                }
                            }
                        }
                    }
                }
            }
        }

        warnConflicts("机型 id", modelConflicts);
        warnConflicts("原始机型编码", modelAliasConflicts);
        warnConflicts("机号", aircraftConflicts);
        warnConflicts("架次标识", sortieConflicts);
        warnConflicts("CSV表", tableConflicts);

        index = new RouteIndex(
                Collections.unmodifiableMap(modelToPlatform),
                Collections.unmodifiableMap(modelToRealType),
                Collections.unmodifiableMap(modelAliasToPlatform),
                immutableSetMap(modelAliasToKeys),
                immutableSetMap(modelToAircraftNumbers),
                Collections.unmodifiableMap(aircraftToRef),
                Collections.unmodifiableMap(sortieToRef),
                Collections.unmodifiableMap(parameterGroupToRef),
                Collections.unmodifiableMap(sortieToTables),
                Collections.unmodifiableMap(tableToSortie));
    }

    private PlatformNode platform(DataSource source) {
        PlatformNode platform = platforms.get(source);
        if (platform == null) {
            platform = new PlatformNode(source);
            platforms.put(source, platform);
        }
        return platform;
    }

    private ModelNode findModel(DataSource source, String modelKey) {
        PlatformNode platform = source == null ? null : platforms.get(source);
        String key = normalize(modelKey);
        return platform == null || key == null ? null : platform.models.get(key);
    }

    private AircraftNode findAircraft(DataSource source, String aircraftNumber) {
        PlatformNode platform = source == null ? null : platforms.get(source);
        String number = normalize(aircraftNumber);
        if (platform == null || number == null) {
            return null;
        }
        AircraftRef current = index.aircraftToRef.get(number);
        if (current != null && current.getSource() == source) {
            ModelNode model = platform.models.get(current.getModelKey());
            return model == null ? null : model.aircraft.get(number);
        }
        for (ModelNode model : platform.models.values()) {
            AircraftNode aircraft = model.aircraft.get(number);
            if (aircraft != null) {
                return aircraft;
            }
        }
        return null;
    }

    private SortieNode findSortieNode(String sortieKey) {
        String key = normalize(sortieKey);
        if (key == null) {
            return null;
        }
        SortieRef ref = index.sortieToRef.get(key);
        if (ref == null) {
            return null;
        }
        PlatformNode platform = platforms.get(ref.getSource());
        if (platform == null) {
            return null;
        }
        for (ModelNode model : platform.models.values()) {
            for (AircraftNode aircraft : model.aircraft.values()) {
                SortieNode sortie = aircraft.sorties.get(key);
                if (sortie != null) {
                    return sortie;
                }
            }
        }
        return null;
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new HashMap<>(source.size() * 2);
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private void warnConflicts(String layer, List<String> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        int shown = Math.min(conflicts.size(), MAX_CONFLICT_KEYS_LOGGED);
        List<String> sample = conflicts.subList(0, shown);
        log.warn("{}在多个平台中重复（共 {} 个），已按 {} 的优先级取第一个: {}{}",
                layer, conflicts.size(), PRIORITY, sample,
                conflicts.size() > shown ? " …（其余略）" : "");
    }

    public static String localSortieKey(Long sortieId) {
        return sortieId == null ? null : String.valueOf(sortieId);
    }

    private static String sourceLabel(DataSource source) {
        return source == null ? "未知平台" : source.getLabel();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : normalize(second);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
