package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalModelData;
import com.project.phm.adapter.dto.UnifiedModelResponse;
import com.project.phm.entity.AircraftModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 机型统一响应转换器。
 *
 * <p>机型查询按机型路由到唯一平台，一次只有一个来源，因此这里只做单源转换，
 * 不做跨源拼接。</p>
 */
@Component
public class ModelDataMerger {

    /** 航新机型行 → 统一响应 */
    public List<UnifiedModelResponse> fromHangxin(List<ExternalModelData> list) {
        return fromExternal(list, "hangxin");
    }

    /** 633 机型行 → 统一响应 */
    public List<UnifiedModelResponse> fromSanSan(List<ExternalModelData> list) {
        return fromExternal(list, "sansan");
    }

    /** 本地机型行 → 统一响应 */
    public List<UnifiedModelResponse> fromLocal(List<AircraftModel> list) {
        List<UnifiedModelResponse> result = new ArrayList<>();
        if (list != null) {
            for (AircraftModel local : list) {
                result.add(UnifiedModelResponse.fromLocal(local));
            }
        }
        return result;
    }

    private List<UnifiedModelResponse> fromExternal(List<ExternalModelData> list, String source) {
        List<UnifiedModelResponse> result = new ArrayList<>();
        if (list != null) {
            for (ExternalModelData ext : list) {
                result.add(UnifiedModelResponse.fromExternal(ext, source));
            }
        }
        return result;
    }
}
