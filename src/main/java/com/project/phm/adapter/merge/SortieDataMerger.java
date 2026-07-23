package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.adapter.dto.UnifiedSortieResponse;
import com.project.phm.entity.Sortie;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 三方（航新、633、本地）架次数据合并器。
 *
 * <p>以 (airplaneNum + flightNum) 为键去重合并。各源字段名已统一，
 * 相同键的记录会合并字段（后到的补充先到的 null 字段）。</p>
 */
@Component
public class SortieDataMerger {

    /**
     * 将三个数据源的架次数据合并为统一的响应列表。
     */
    public List<UnifiedSortieResponse> merge(
            List<ExternalSortieData> hangxinList,
            List<ExternalSortieData> sanSanList,
            List<Sortie> localList) {

        Map<String, UnifiedSortieResponse> mergedMap = new LinkedHashMap<>();

        // 1. 航新
        if (hangxinList != null) {
            for (ExternalSortieData ext : hangxinList) {
                String key = buildKey(ext.getAirplaneNum(), ext.getFlightNum());
                mergedMap.merge(key, UnifiedSortieResponse.fromExternal(ext),
                        SortieDataMerger::mergeNonNull);
            }
        }

        // 2. 633
        if (sanSanList != null) {
            for (ExternalSortieData ext : sanSanList) {
                String key = buildKey(ext.getAirplaneNum(), ext.getFlightNum());
                mergedMap.merge(key, UnifiedSortieResponse.fromExternal(ext),
                        SortieDataMerger::mergeNonNull);
            }
        }

        // 3. 本地
        if (localList != null) {
            for (Sortie local : localList) {
                String key = buildKey(local.getAircraftNumber(), local.getSortieNumber());
                mergedMap.merge(key, UnifiedSortieResponse.fromLocal(local),
                        SortieDataMerger::mergeNonNull);
            }
        }

        return new ArrayList<>(mergedMap.values());
    }

    /** 合并两个响应行：保留先到的值，后到的仅补充先到的 null 字段 */
    private static UnifiedSortieResponse mergeNonNull(UnifiedSortieResponse existing, UnifiedSortieResponse incoming) {
        if (existing.getId() == null)            existing.setId(incoming.getId());
        if (existing.getAirplaneType() == null)  existing.setAirplaneType(incoming.getAirplaneType());
        if (existing.getAirplaneNum() == null)   existing.setAirplaneNum(incoming.getAirplaneNum());
        if (existing.getFlightNum() == null)     existing.setFlightNum(incoming.getFlightNum());
        if (existing.getStartTime() == null)     existing.setStartTime(incoming.getStartTime());
        if (existing.getEndTime() == null)       existing.setEndTime(incoming.getEndTime());
        if (existing.getParamList() == null)     existing.setParamList(incoming.getParamList());
        return existing;
    }

    private String buildKey(String airplaneNum, String flightNum) {
        return (airplaneNum != null ? airplaneNum : "") + "|" + (flightNum != null ? flightNum : "");
    }
}
