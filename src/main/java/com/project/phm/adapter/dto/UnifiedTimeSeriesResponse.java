package com.project.phm.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一时序数据响应 — 三个数据源的格式统一。
 *
 * <pre>
 * {
 *   "timestamps": [1783296000000, ...],
 *   "parameters": [
 *     {"name": "ALTITUDE", "values": [1000.5, ...]}
 *   ]
 * }
 * </pre>
 */
@Schema(description = "统一时序数据响应")
public class UnifiedTimeSeriesResponse {

    @Schema(description = "时间轴（毫秒时间戳列表）")
    private List<Long> timestamps;

    @Schema(description = "参数集合")
    private List<ParameterEntry> parameters;

    public List<Long> getTimestamps() { return timestamps; }
    public void setTimestamps(List<Long> timestamps) { this.timestamps = timestamps; }
    public List<ParameterEntry> getParameters() { return parameters; }
    public void setParameters(List<ParameterEntry> parameters) { this.parameters = parameters; }

    @Schema(description = "参数条目")
    public static class ParameterEntry {

        @Schema(description = "参数名", example = "ALTITUDE")
        private String name;

        @Schema(description = "参数序列值（与时间轴一一对应）")
        private List<Object> values;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Object> getValues() { return values; }
        public void setValues(List<Object> values) { this.values = values; }

        public static ParameterEntry of(String name, List<Object> values) {
            ParameterEntry e = new ParameterEntry();
            e.name = name;
            e.values = values;
            return e;
        }
    }
}
