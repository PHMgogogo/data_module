package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.UnifiedModelResponse;
import com.project.phm.entity.AircraftModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 三方（航新、633、本地）机型数据合并器。
 * 以 airplaneType 为键去重合并。
 */
@Component
public class ModelDataMerger {

    public List<UnifiedModelResponse> merge(
            List<ExternalModelData> hangxinList,
            List<ExternalModelData> sanSanList,
            List<AircraftModel> localList) {

        Map<String, UnifiedModelResponse> mergedMap = new LinkedHashMap<>();

        if (hangxinList != null) {
            for (ExternalModelData ext : hangxinList) {
                String key = ext.getAirplaneType();
                if (key != null) {
                    mergedMap.putIfAbsent(key, UnifiedModelResponse.fromExternal(ext));
                }
            }
        }

        if (sanSanList != null) {
            for (ExternalModelData ext : sanSanList) {
                String key = ext.getAirplaneType();
                if (key != null) {
                    mergedMap.putIfAbsent(key, UnifiedModelResponse.fromExternal(ext));
                }
            }
        }

        if (localList != null) {
            for (AircraftModel local : localList) {
                String key = local.getModelCode();
                if (key != null) {
                    mergedMap.putIfAbsent(key, UnifiedModelResponse.fromLocal(local));
                }
            }
        }

        return new ArrayList<>(mergedMap.values());
    }
}
