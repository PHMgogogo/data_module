package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.UnifiedModelResponse;
import com.project.phm.entity.AircraftModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 三方（航新、633、本地）机型数据拼接器。
 *
 * <p>不做跨源去重合并，直接将各源数据全部放入结果列表，每条记录标记来源。</p>
 */
@Component
public class ModelDataMerger {

    public List<UnifiedModelResponse> merge(
            List<ExternalModelData> hangxinList,
            List<ExternalModelData> sanSanList,
            List<AircraftModel> localList) {

        List<UnifiedModelResponse> result = new ArrayList<>();

        if (hangxinList != null) {
            for (ExternalModelData ext : hangxinList) {
                result.add(UnifiedModelResponse.fromExternal(ext, "hangxin"));
            }
        }

        if (sanSanList != null) {
            for (ExternalModelData ext : sanSanList) {
                result.add(UnifiedModelResponse.fromExternal(ext, "sansan"));
            }
        }

        if (localList != null) {
            for (AircraftModel local : localList) {
                result.add(UnifiedModelResponse.fromLocal(local));
            }
        }

        return result;
    }
}
