package com.project.phm.adapter.merge;

import com.project.phm.adapter.dto.ExternalSortieData;
import com.project.phm.adapter.dto.UnifiedSortieResponse;
import com.project.phm.entity.Sortie;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 三方（航新、633、本地）架次数据拼接器。
 *
 * <p>不做跨源去重合并，直接将各源数据全部放入结果列表，每条记录标记来源。</p>
 */
@Component
public class SortieDataMerger {

    /**
     * 将三个数据源的架次数据拼接为统一的响应列表（不做去重合并）。
     */
    public List<UnifiedSortieResponse> merge(
            List<ExternalSortieData> hangxinList,
            List<ExternalSortieData> sanSanList,
            List<Sortie> localList) {

        List<UnifiedSortieResponse> result = new ArrayList<>();

        if (hangxinList != null) {
            for (ExternalSortieData ext : hangxinList) {
                result.add(UnifiedSortieResponse.fromExternal(ext, "hangxin"));
            }
        }

        if (sanSanList != null) {
            for (ExternalSortieData ext : sanSanList) {
                result.add(UnifiedSortieResponse.fromExternal(ext, "sansan"));
            }
        }

        if (localList != null) {
            for (Sortie local : localList) {
                result.add(UnifiedSortieResponse.fromLocal(local));
            }
        }

        return result;
    }
}
