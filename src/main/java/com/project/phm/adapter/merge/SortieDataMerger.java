package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.adapter.dto.UnifiedSortieResponse;
import com.project.phm.entity.Sortie;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 架次统一响应转换器。
 *
 * <p>架次查询按机型路由到唯一平台，一次只有一个来源，因此这里只做单源转换，
 * 不做跨源拼接。</p>
 */
@Component
public class SortieDataMerger {

    /** 航新架次行 → 统一响应 */
    public List<UnifiedSortieResponse> fromHangxin(List<ExternalSortieData> list) {
        return fromExternal(list, "hangxin");
    }

    /** 633 架次行 → 统一响应 */
    public List<UnifiedSortieResponse> fromSanSan(List<ExternalSortieData> list) {
        return fromExternal(list, "sansan");
    }

    /** 本地架次行 → 统一响应 */
    public List<UnifiedSortieResponse> fromLocal(List<Sortie> list) {
        List<UnifiedSortieResponse> result = new ArrayList<>();
        if (list != null) {
            for (Sortie local : list) {
                result.add(UnifiedSortieResponse.fromLocal(local));
            }
        }
        return result;
    }

    private List<UnifiedSortieResponse> fromExternal(List<ExternalSortieData> list, String source) {
        List<UnifiedSortieResponse> result = new ArrayList<>();
        if (list != null) {
            for (ExternalSortieData ext : list) {
                result.add(UnifiedSortieResponse.fromExternal(ext, source));
            }
        }
        return result;
    }
}
