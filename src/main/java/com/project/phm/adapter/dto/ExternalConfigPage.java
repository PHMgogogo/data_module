package com.project.phm.adapter.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 外来平台构型接口的单页结果：{@code data.total} + {@code data.rows}。
 *
 * <p>调用方靠 {@code total} 判断是否还有下一页（三方 rows=10 会把全量截断，
 * 只取第一页会漏数据）。{@code rows} 保留原始 Map，不绑 DTO —— 三方字段名大小写不统一
 * （GXBS / gxbs / SJgxbs …），由调用方按忽略大小写的规则取值。</p>
 */
public class ExternalConfigPage {

    /** 三方声明的总条数；缺失或非法时为 0（调用方需另靠"不满一页"兜底终止翻页） */
    private final int total;

    private final List<Map<String, Object>> rows;

    public ExternalConfigPage(int total, List<Map<String, Object>> rows) {
        this.total = total;
        this.rows = rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
    }

    /** 空页：未配置 / 不可达 / 返回异常码 / 解析失败时使用 */
    public static ExternalConfigPage empty() {
        return new ExternalConfigPage(0, Collections.<Map<String, Object>>emptyList());
    }

    public int getTotal() { return total; }

    public List<Map<String, Object>> getRows() { return rows; }

    public boolean isEmpty() { return rows.isEmpty(); }

    public int getRowCount() { return rows.size(); }
}
