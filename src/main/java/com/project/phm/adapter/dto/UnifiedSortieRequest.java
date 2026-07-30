package com.project.phm.adapter.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.project.phm.entity.Sortie;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一架次查询请求体 — 三个数据源（航新、633、本地）请求参数的并集。
 * 前端传入此结构，内部转换为各源所需的参数格式。
 */
@Schema(description = "统一架次查询请求")
public class UnifiedSortieRequest {

    @Schema(description = "机型，精确匹配（航新/633）", example = "A320")
    private String airplaneType;

    @Schema(description = "机号，精确匹配（航新/633/本地）", example = "B1234")
    private String airplaneNum;

    @Schema(description = "起始时间（航新/633）", example = "2024-01-01 00:00:00")
    private String startTime;

    @Schema(description = "结束时间（航新/633）", example = "2024-01-31 23:59:59")
    private String endTime;

    @Schema(description = "源文件名，模糊匹配（航新 only）", example = "flight_data")
    private String fileName;

    @Schema(description = "源文件类型，精确匹配（航新 only）", example = "DAT")
    private String fileType;

    @Schema(description = "架次ID（本地按 sortieId 精确匹配）", example = "1")
    private Long sortieId;

    @Schema(description = "架次号（633 查询参数）", example = "20260706-1")
    private String flightNum;

    @Schema(description = "参数名/参数ID集合（633 only）")
    private List<String> paraList;

    public String getAirplaneType() { return airplaneType; }
    public void setAirplaneType(String airplaneType) { this.airplaneType = airplaneType; }
    public String getAirplaneNum() { return airplaneNum; }
    public void setAirplaneNum(String airplaneNum) { this.airplaneNum = airplaneNum; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getFlightNum() { return flightNum; }
    public void setFlightNum(String flightNum) { this.flightNum = flightNum; }
    public Long getSortieId() { return sortieId; }
    public void setSortieId(Long sortieId) { this.sortieId = sortieId; }
    public List<String> getParaList() { return paraList; }
    public void setParaList(List<String> paraList) { this.paraList = paraList; }

    /** 转为航新 API 的请求参数 */
    public Map<String, Object> toHangxinParams() {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneType", airplaneType);
        putIfNotNull(params, "airplaneNum", airplaneNum);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "fileName", fileName);
        putIfNotNull(params, "fileType", fileType);
        return params;
    }

    /** 转为 633 API 的请求参数 */
    public Map<String, Object> toSanSanParams() {
        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "airplaneType", airplaneType);
        putIfNotNull(params, "airplaneNum", airplaneNum);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "flightNum", flightNum);
        if (paraList != null && !paraList.isEmpty()) {
            params.put("ParaList", paraList);
        }
        return params;
    }

    /** 转为本地 Sortie 表的查询条件。
     *  sortieId 优先（精确匹配主键），其次是 flightNum（按 sortieNumber 匹配），
     *  最后降级用 airplaneNum 过滤。 */
    public LambdaQueryWrapper<Sortie> toLocalQuery() {
        LambdaQueryWrapper<Sortie> wrapper = Wrappers.lambdaQuery();
        if (sortieId != null) {
            wrapper.eq(Sortie::getSortieId, sortieId);
            return wrapper;
        }
        if (flightNum != null && !flightNum.isEmpty()) {
            wrapper.eq(Sortie::getSortieNumber, flightNum);
            return wrapper;
        }
        // 未传 sortieId / flightNum 时降级用 airplaneNum 过滤
        if (airplaneNum != null && !airplaneNum.isEmpty()) {
            wrapper.eq(Sortie::getAircraftNumber, airplaneNum);
        }
        wrapper.orderByDesc(Sortie::getSortieId);
        return wrapper;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            String s = value.toString();
            if (!s.isEmpty()) {
                map.put(key, value);
            }
        }
    }
}
