package com.project.phm.service;

import com.project.phm.adapter.dto.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 平台路由表 —— 在内存里回答「某个 id 来自本地 / 航新 / 633 的哪一家」。
 *
 * <p>维护三层索引，键都是各层 id 的字符串形态：</p>
 * <ol>
 *   <li><b>机型</b>：本地机型代码（{@code JX-20A}）或三方合成码（{@code K4-WS19:6}）</li>
 *   <li><b>单机</b>：机号</li>
 *   <li><b>架次</b>：本地 {@code sortieId} 的字符串形态，或三方原始架次 id</li>
 * </ol>
 *
 * <p><b>本类刻意不依赖任何 Mapper / Client</b>：索引原料由 {@code AircraftConfigService}
 * 在刷新时拉好并传进来（见 {@link #publish}）。反过来去注入 {@code AircraftConfigService}
 * 会形成构造循环，Spring 启动直接失败。</p>
 *
 * <p><b>线程安全</b>：读写分离 + 写时复制。{@link #index} 是 volatile 的不可变快照，
 * 读侧无锁且永远看到一份完整数据；写侧的 {@link #publish} / {@code addLocal*} 串行化，
 * 在锁内改完 {@code lastGood} 后整体替换快照。</p>
 *
 * <p><b>与 {@code AircraftConfigService.externalConfigIndex}（构型 SSFJH 索引）并存的注意事项</b>：
 * 两者键空间有重叠 —— 三方构型的 SSFJH 实际就是机号（如 {@code 0003}）。同一个字符串在
 * {@code /aircraft/config-items} 里被解释成三方实体号，在 {@code /aircraft/sorties} 里被解释成机号。
 * 端点不同故不冲突，但改动任一侧时都要意识到这点。</p>
 */
@Service
public class PlatformRouteService {

    private static final Logger log = LoggerFactory.getLogger(PlatformRouteService.class);

    /**
     * 解析优先级：本地 → 航新 → 633。
     *
     * <p>用户约定的前提是「机型 / 单机 / 架次 id 均不会重合」，正常情况下不会有键同时命中多个源。
     * 但开发环境的 mock 是**一个进程扮演两个平台**，机型/单机/架次对两家返回同一份数据，
     * 合成码必然重复。此时按本顺序取第一个，并打冲突告警，避免"到底路由到哪家"变成玄学。</p>
     */
    private static final List<DataSource> PRIORITY = Collections.unmodifiableList(
            Arrays.asList(DataSource.LOCAL, DataSource.HANGXIN, DataSource.SAN_SAN));

    /** 冲突告警最多列出几个键，避免刷屏 */
    private static final int MAX_CONFLICT_KEYS_LOGGED = 10;

    /**
     * 三方单机 / 架次接口若存在服务端默认分页，全量拉取会被截断。这些是常见的整页值，
     * 命中时打告警 —— 索引少一个机号，就是该机号下所有架次的查询静默返回空，极难排查。
     */
    private static final Set<Integer> SUSPICIOUS_PAGE_SIZES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(10, 20, 50, 100, 200, 500, 1000)));

    // ==================== 索引条目 ====================

    /** 一条架次在索引里的引用：定位平台 + 构造该平台请求所需的全部标识 */
    public static final class SortieRef {

        private final DataSource source;
        /** 本地架次主键，仅 LOCAL 有值 */
        private final Long localSortieId;
        /** 三方机型（真实 airplaneType，非合成码），仅三方有值 */
        private final String airplaneType;
        /** 三方机号（航新 / 633 的 airplaneNum），仅三方有值 */
        private final String airplaneNum;
        /** 三方架次号（flightNum），仅三方有值 */
        private final String flightNum;

        private SortieRef(DataSource source, Long localSortieId,
                          String airplaneType, String airplaneNum, String flightNum) {
            this.source = source;
            this.localSortieId = localSortieId;
            this.airplaneType = airplaneType;
            this.airplaneNum = airplaneNum;
            this.flightNum = flightNum;
        }

        /** 本地架次 */
        public static SortieRef local(Long sortieId) {
            return new SortieRef(DataSource.LOCAL, sortieId, null, null, null);
        }

        /**
         * 三方架次。
         *
         * <p>把这几个标识随架次一起记下来，是为了让时序查询不必回头依赖请求体 ——
         * 请求体里传的 {@code airplaneType} 可能是 {@code K4-WS19:6} 这种合成码，
         * 原样发给三方必然查不到；而这里存的是三方自己返回的真实值。</p>
         */
        public static SortieRef external(DataSource source, String airplaneType,
                                         String airplaneNum, String flightNum) {
            return new SortieRef(source, null, airplaneType, airplaneNum, flightNum);
        }

        public DataSource getSource() { return source; }
        public Long getLocalSortieId() { return localSortieId; }
        public String getAirplaneType() { return airplaneType; }
        public String getAirplaneNum() { return airplaneNum; }
        public String getFlightNum() { return flightNum; }

        @Override
        public String toString() {
            return source + (localSortieId != null ? "#" + localSortieId : "(" + airplaneNum + "/" + flightNum + ")");
        }
    }

    /**
     * 单个数据源在**一次刷新**里拉到的全部索引原料。
     *
     * <p>三层各自带一个 available 标记：只有 available 的层才会覆盖上一轮的索引，
     * 这样「某平台本次不可用」不会把它上一轮的好数据抹掉（这是与构型的
     * {@code refreshExternalConfigIndex} 有意不同的地方 —— 那边是按合并后判空的，
     * 航新 0 条 + 633 10 条同样会整体替换，会把航新的旧条目抹掉）。</p>
     *
     * <p>{@code modelAvailable} 还兼作「该机型列表已加载完成」的标记，供
     * {@code listModels} 在本地机型内联查完之后补写。</p>
     */
    public static final class SourceEntries {

        private final DataSource source;
        private boolean modelAvailable;
        private boolean aircraftAvailable;
        private boolean sortieAvailable;

        /** 机型索引键 → 真实 airplaneType；本地机型与（无 id 的）非法键为 null */
        private final Map<String, String> models = new LinkedHashMap<>();
        private final Set<String> aircraftNumbers = new LinkedHashSet<>();
        /** 架次标识 → 引用 */
        private final Map<String, SortieRef> sorties = new LinkedHashMap<>();

        SourceEntries(DataSource source) {
            this.source = source;
        }

        public DataSource getSource() { return source; }

        public boolean isModelAvailable() { return modelAvailable; }
        public boolean isAircraftAvailable() { return aircraftAvailable; }
        public boolean isSortieAvailable() { return sortieAvailable; }

        public void markModelAvailable() { this.modelAvailable = true; }
        public void markAircraftAvailable() { this.aircraftAvailable = true; }
        public void markSortieAvailable() { this.sortieAvailable = true; }

        /**
         * 登记一个机型。
         *
         * @param key              索引键：本地机型代码 或 三方合成码（不能为空/空白）
         * @param realAirplaneType 三方真实机型编码；本地机型传 null
         */
        public void addModel(String key, String realAirplaneType) {
            String normalized = normalize(key);
            if (normalized == null) {
                // toModels 会产出 "K4-WS19:null" 这类「看着合法其实查不到」的键，必须拦在这里
                warnBlankKey("机型", key);
                return;
            }
            models.put(normalized, normalize(realAirplaneType));
        }

        /** 登记一个机号 */
        public void addAircraft(String aircraftNumber) {
            String normalized = normalize(aircraftNumber);
            if (normalized == null) {
                warnBlankKey("机号", aircraftNumber);
                return;
            }
            aircraftNumbers.add(normalized);
        }

        /**
         * 登记一个架次。
         *
         * @param key 索引键：本地 sortieId 的字符串形态，或三方原始架次 id
         */
        public void addSortie(String key, SortieRef ref) {
            String normalized = normalize(key);
            if (normalized == null) {
                warnBlankKey("架次", key);
                return;
            }
            if (ref == null) {
                return;
            }
            sorties.put(normalized, ref);
        }

        private void warnBlankKey(String layer, String key) {
            log.warn("{}索引键为空，已跳过该条: source={}, key={}", layer, source.getLabel(), key);
        }
    }

    /** 不可变快照 —— 读侧只碰这个对象 */
    private static final class PlatformIndex {

        private final Map<String, DataSource> modelToPlatform;
        private final Map<String, String> modelToRealAirplaneType;
        private final Map<String, DataSource> aircraftToPlatform;
        private final Map<String, SortieRef> sortieToRef;

        PlatformIndex(Map<String, DataSource> modelToPlatform,
                      Map<String, String> modelToRealAirplaneType,
                      Map<String, DataSource> aircraftToPlatform,
                      Map<String, SortieRef> sortieToRef) {
            this.modelToPlatform = modelToPlatform;
            this.modelToRealAirplaneType = modelToRealAirplaneType;
            this.aircraftToPlatform = aircraftToPlatform;
            this.sortieToRef = sortieToRef;
        }

        static PlatformIndex empty() {
            return new PlatformIndex(Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap());
        }
    }

    // ==================== 状态 ====================

    /** 各源最近一次成功加载的条目；只在锁内访问 */
    private final Map<DataSource, SourceEntries> lastGood = new EnumMap<>(DataSource.class);

    /** 读侧快照 */
    private volatile PlatformIndex index = PlatformIndex.empty();

    // ==================== 建条目 / 发布 ====================

    public SourceEntries newEntries(DataSource source) {
        return new SourceEntries(source);
    }

    /** 本地架的索引键：与 {@link #resolveSortie} 的入参必须一致 */
    public static String localSortieKey(Long sortieId) {
        return sortieId == null ? null : String.valueOf(sortieId);
    }

    /**
     * 发布一批刷新结果，逐源逐层更新索引。
     *
     * <p>某个源某一层本次不可用时（平台没配 / 请求失败 / 解析失败），该层沿用上一轮的数据，
     * 避免「平台抽风一次 → 索引被清空 → 后续所有查询静默返回空」。</p>
     */
    public synchronized void publish(Collection<SourceEntries> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (SourceEntries fresh : batch) {
            if (fresh == null) {
                continue;
            }
            SourceEntries previous = lastGood.get(fresh.source);
            SourceEntries effective = new SourceEntries(fresh.source);

            boolean carryModel = adoptLayer(fresh.source, "机型", fresh.modelAvailable, previous, effective::markModelAvailable);
            boolean carryAircraft = adoptLayer(fresh.source, "机号", fresh.aircraftAvailable, previous, effective::markAircraftAvailable);
            boolean carrySortie = adoptLayer(fresh.source, "架次", fresh.sortieAvailable, previous, effective::markSortieAvailable);

            if (fresh.modelAvailable) {
                effective.models.putAll(fresh.models);
            } else if (carryModel) {
                effective.models.putAll(previous.models);
            }

            if (fresh.aircraftAvailable) {
                effective.aircraftNumbers.addAll(fresh.aircraftNumbers);
            } else if (carryAircraft) {
                effective.aircraftNumbers.addAll(previous.aircraftNumbers);
            }

            if (fresh.sortieAvailable) {
                effective.sorties.putAll(fresh.sorties);
            } else if (carrySortie) {
                effective.sorties.putAll(previous.sorties);
            }

            lastGood.put(fresh.source, effective);

            warnIfSuspiciousSize(fresh.source, "单机", fresh.aircraftAvailable, fresh.aircraftNumbers.size());
            warnIfSuspiciousSize(fresh.source, "架次", fresh.sortieAvailable, fresh.sorties.size());
        }

        index = buildIndex();

        log.info("平台路由索引已刷新: {}", describe());
    }

    /**
     * 决定该层本次是否沿用上一轮的数据。
     *
     * @param markOnEffective 在 effective 上标记该层已就绪（本层采用新数据或成功沿用旧数据都要标）
     * @return true 表示采用上一轮的数据（本次不可用且上一轮有数据）
     */
    private boolean adoptLayer(DataSource source, String layer, boolean available,
                               SourceEntries previous, Runnable markOnEffective) {
        if (available) {
            markOnEffective.run();
            return false;
        }
        if (previous != null && isLayerReady(previous, layer)) {
            log.warn("{}的{}本次未取到数据（平台未配置或不可用），沿用上一轮索引", source.getLabel(), layer);
            markOnEffective.run();
            return true;
        }
        return false;
    }

    private static boolean isLayerReady(SourceEntries entries, String layer) {
        switch (layer) {
            case "机型":
                return entries.modelAvailable;
            case "机号":
                return entries.aircraftAvailable;
            default:
                return entries.sortieAvailable;
        }
    }

    private void warnIfSuspiciousSize(DataSource source, String layer, boolean available, int size) {
        if (!available || !SUSPICIOUS_PAGE_SIZES.contains(size)) {
            return;
        }
        log.warn("{}的{}全量返回 {} 条，恰好是一个常见的整页值 —— 若三方接口存在默认分页，"
                        + "索引会缺失其余条目（表现为这些 id 的查询静默返回空），请核实",
                source.getLabel(), layer, size);
    }

    private PlatformIndex buildIndex() {
        Map<String, DataSource> modelToPlatform = new HashMap<>();
        Map<String, String> modelToRealType = new HashMap<>();
        Map<String, DataSource> aircraftToPlatform = new HashMap<>();
        Map<String, SortieRef> sortieToRef = new HashMap<>();

        List<String> modelConflicts = new ArrayList<>();
        List<String> aircraftConflicts = new ArrayList<>();
        List<String> sortieConflicts = new ArrayList<>();

        // 按优先级从高到低放置，先到先得；后到的同键记一条冲突
        for (DataSource source : PRIORITY) {
            SourceEntries entries = lastGood.get(source);
            if (entries == null) {
                continue;
            }
            for (Map.Entry<String, String> model : entries.models.entrySet()) {
                if (modelToPlatform.putIfAbsent(model.getKey(), source) != null) {
                    modelConflicts.add(model.getKey());
                } else if (model.getValue() != null) {
                    modelToRealType.put(model.getKey(), model.getValue());
                }
            }
            for (String number : entries.aircraftNumbers) {
                if (aircraftToPlatform.putIfAbsent(number, source) != null) {
                    aircraftConflicts.add(number);
                }
            }
            for (Map.Entry<String, SortieRef> sortie : entries.sorties.entrySet()) {
                if (sortieToRef.putIfAbsent(sortie.getKey(), sortie.getValue()) != null) {
                    sortieConflicts.add(sortie.getKey());
                }
            }
        }

        warnConflicts("机型 id", modelConflicts);
        warnConflicts("机号", aircraftConflicts);
        warnConflicts("架次标识", sortieConflicts);

        return new PlatformIndex(
                Collections.unmodifiableMap(modelToPlatform),
                Collections.unmodifiableMap(modelToRealType),
                Collections.unmodifiableMap(aircraftToPlatform),
                Collections.unmodifiableMap(sortieToRef));
    }

    private void warnConflicts(String layer, List<String> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        int shown = Math.min(conflicts.size(), MAX_CONFLICT_KEYS_LOGGED);
        List<String> sample = conflicts.subList(0, shown);
        log.warn("{}在多个数据源中重复（共 {} 个），已按 {} 的优先级取第一个: {}{}",
                layer, conflicts.size(), PRIORITY, sample,
                conflicts.size() > shown ? " …（其余略）" : "");
    }

    // ==================== 解析 ====================

    /** 机型 id → 平台；未命中返回 null（调用方应返回空并告警，不要回落其它源） */
    public DataSource resolveModel(String modelCode) {
        String key = normalize(modelCode);
        return key == null ? null : index.modelToPlatform.get(key);
    }

    /** 三方合成码 → 真实 airplaneType；本地机型或未命中返回 null */
    public String realAirplaneType(String modelCode) {
        String key = normalize(modelCode);
        return key == null ? null : index.modelToRealAirplaneType.get(key);
    }

    /** 机号 → 平台；未命中返回 null */
    public DataSource resolveAircraft(String aircraftNumber) {
        String key = normalize(aircraftNumber);
        return key == null ? null : index.aircraftToPlatform.get(key);
    }

    /** 架次标识 → 引用；未命中返回 null */
    public SortieRef resolveSortie(String sortieKey) {
        String key = normalize(sortieKey);
        return key == null ? null : index.sortieToRef.get(key);
    }

    /** 索引是否已建立（供调用方区分「未命中」与「还没建过索引」） */
    public boolean isReady() {
        PlatformIndex snapshot = index;
        return !snapshot.modelToPlatform.isEmpty();
    }

    /** 索引规模摘要，供日志使用 */
    public String describe() {
        PlatformIndex snapshot = index;
        StringBuilder detail = new StringBuilder();
        for (DataSource source : PRIORITY) {
            SourceEntries entries = lastGood.get(source);
            if (entries == null) {
                detail.append(source.getLabel()).append("=0/0/0 ");
                continue;
            }
            detail.append(source.getLabel())
                    .append('=').append(entries.models.size())
                    .append('/').append(entries.aircraftNumbers.size())
                    .append('/').append(entries.sorties.size())
                    .append(' ');
        }
        return String.format("机型=%d, 机号=%d, 架次=%d [%s]",
                snapshot.modelToPlatform.size(), snapshot.aircraftToPlatform.size(),
                snapshot.sortieToRef.size(), detail.toString().trim());
    }

    // ==================== 本地增删的增量同步 ====================
    // 只改本模块会写的那几张本地表；平台配置的改动不需要动索引 ——
    // 索引存的是「id → 平台」，不含 URL，平台地址每次请求都是实时读 DB 的。

    public synchronized void addLocalModel(String modelCode) {
        mutateLocal(e -> e.addModel(modelCode, null), entries -> entries.markModelAvailable());
    }

    public synchronized void removeLocalModel(String modelCode) {
        String key = normalize(modelCode);
        mutateLocal(e -> e.models.remove(key), entries -> entries.markModelAvailable());
    }

    public synchronized void addLocalAircraft(String aircraftNumber) {
        mutateLocal(e -> e.addAircraft(aircraftNumber), entries -> entries.markAircraftAvailable());
    }

    public synchronized void removeLocalAircraft(String aircraftNumber) {
        String key = normalize(aircraftNumber);
        mutateLocal(e -> e.aircraftNumbers.remove(key), entries -> entries.markAircraftAvailable());
    }

    public synchronized void addLocalSortie(Long sortieId) {
        String key = localSortieKey(sortieId);
        mutateLocal(e -> e.addSortie(key, SortieRef.local(sortieId)), entries -> entries.markSortieAvailable());
    }

    public synchronized void removeLocalSortie(Long sortieId) {
        String key = localSortieKey(sortieId);
        mutateLocal(e -> e.sorties.remove(key), entries -> entries.markSortieAvailable());
    }

    private void mutateLocal(java.util.function.Consumer<SourceEntries> mutation,
                             java.util.function.Consumer<SourceEntries> marker) {
        SourceEntries entries = lastGood.get(DataSource.LOCAL);
        if (entries == null) {
            // 本地条目还不存在（应用刚起、还没人调过 /aircraft/models）。此时若凭空造一个只含
            // 这一条的本地条目，索引里就只剩这个机型 / 机号 —— 比「索引为空」更糟：
            // 那些本地本来就查得到的 id 会从「未命中返回空」变成真正的数据丢失。
            // 所以干脆不动，等下一次 /aircraft/models 全量重建。
            log.debug("本地索引尚未建立，跳过增量同步，等待下次 /aircraft/models 全量重建");
            return;
        }
        mutation.accept(entries);
        marker.accept(entries);
        index = buildIndex();
    }

    // ==================== 工具 ====================

    /** trim 后为空统一归 null —— 现有代码里 isEmpty() / trim().isEmpty() 混用，这里统一 */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
