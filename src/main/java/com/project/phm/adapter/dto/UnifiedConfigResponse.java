package com.project.phm.adapter.dto;

import com.project.phm.entity.ConfigItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 统一单机构型查询响应行 — 三个数据源的字段并集。
 * 不做跨源去重合并，每条记录标记来源供前端区分。
 */
@Schema(description = "统一单机构型响应行")
public class UnifiedConfigResponse {

    @Schema(description = "数据来源：hangxin / sansan / local")
    private String source;

    @Schema(description = "节点唯一标识（本地 itemId；633/航新原 GXBS）")
    private String nodeId;

    @Schema(description = "父节点标识（本地递归展开时由父节点 itemId 生成；633/航新原 SJGXBS）")
    private String parentNodeId;

    @Schema(description = "节点名称（本地按 itemType 取 systemName/subSystemName/equipmentName；633/航新原 GXMC）")
    private String nodeName;

    @Schema(description = "节点类型（本地 itemType：SYSTEM/SUBSYSTEM/EQUIPMENT；633/航新固定为 CONFIG）")
    private String nodeType;

    @Schema(description = "GJB章节号（本地 ataChapter）")
    private String chapterCode;

    @Schema(description = "所属飞机号（633/航新原 SSFJH）")
    private String aircraftNo;

    @Schema(description = "设备编号（633/航新原 JJH）")
    private String equipmentNo;

    @Schema(description = "安装位置（633/航新原 AZWZ）")
    private String installPosition;

    @Schema(description = "件号（本地 partNumber）")
    private String partNumber;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(String parentNodeId) { this.parentNodeId = parentNodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getChapterCode() { return chapterCode; }
    public void setChapterCode(String chapterCode) { this.chapterCode = chapterCode; }
    public String getAircraftNo() { return aircraftNo; }
    public void setAircraftNo(String aircraftNo) { this.aircraftNo = aircraftNo; }
    public String getEquipmentNo() { return equipmentNo; }
    public void setEquipmentNo(String equipmentNo) { this.equipmentNo = equipmentNo; }
    public String getInstallPosition() { return installPosition; }
    public void setInstallPosition(String installPosition) { this.installPosition = installPosition; }
    public String getPartNumber() { return partNumber; }
    public void setPartNumber(String partNumber) { this.partNumber = partNumber; }

    // ==================== 工厂方法 ====================

    /** 从本地 ConfigItem 构造统一的响应行 */
    public static UnifiedConfigResponse fromLocal(ConfigItem item) {
        if (item == null) return null;
        UnifiedConfigResponse r = new UnifiedConfigResponse();
        r.source = "local";
        r.nodeId = String.valueOf(item.getItemId());
        r.parentNodeId = item.getParentItemId() != null ? String.valueOf(item.getParentItemId()) : null;
        // 按 itemType 取值
        String type = item.getItemType();
        if ("SYSTEM".equals(type)) {
            r.nodeName = item.getSystemName();
        } else if ("SUBSYSTEM".equals(type)) {
            r.nodeName = item.getSubSystemName();
        } else {
            r.nodeName = item.getEquipmentName();
        }
        r.nodeType = type;
        r.chapterCode = item.getAtaChapter();
        r.partNumber = item.getPartNumber();
        return r;
    }

    /** 从外部（633/航新）返回的 data 条目（Map）构造统一的响应行 */
    public static UnifiedConfigResponse fromExternal(Map<String, Object> row, String source) {
        if (row == null) return null;
        UnifiedConfigResponse r = new UnifiedConfigResponse();
        r.source = source;
        r.nodeId = toString(row.get("GXBS"));
        r.parentNodeId = toString(row.get("SJGXBS"));
        r.nodeName = toString(row.get("GXMC"));
        r.nodeType = "CONFIG";
        r.aircraftNo = toString(row.get("SSFJH"));
        r.equipmentNo = toString(row.get("JJH"));
        r.installPosition = toString(row.get("AZWZ"));
        return r;
    }

    private static String toString(Object val) {
        return val != null ? val.toString() : null;
    }
}
