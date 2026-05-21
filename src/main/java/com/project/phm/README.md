# PHM 预测与健康管理系统 — API 文档

## 项目说明

基于 Spring Boot 的飞机预测与健康管理（PHM）系统，提供飞机构型管理、CSV 数据导入/查询/导出、健康记录管理等功能。

- 数据库：达梦数据库（主库）+ IoTDB（时序库）
- ORM：MyBatis-Plus
- 基础路径：`/aircraft`（构型相关）、`/csv`（数据相关）

---

## 一、飞机构型管理 `/aircraft`

### 1.1 机型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aircraft/models` | 获取所有机型列表 |
| POST | `/aircraft/models` | 添加机型 |
| DELETE | `/aircraft/models/{modelCode}` | 删除指定机型 |

**POST /aircraft/models 请求体：**
```json
{
  "modelCode": "B737-800",
  "manufacturer": "Boeing",
  "description": "波音737-800"
}
```

### 1.2 飞机构型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aircraft/configs?modelCode=` | 获取构型列表（可选按机型过滤） |
| POST | `/aircraft/configs` | 添加构型 |
| DELETE | `/aircraft/configs/{tailNumber}` | 删除指定机号构型 |
| GET | `/aircraft/tail-numbers?modelCode=` | 获取某机型下的可用机号列表（前端下拉框用） |

**POST /aircraft/configs 请求体：**
```json
{
  "tailNumber": "B-1234",
  "modelCode": "B737-800",
  "airline": "中国国航",
  "configVersion": "V1.0",
  "status": "active"
}
```

### 1.3 构型项目管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aircraft/config-items?modelCode=` | 获取构型项目列表 |
| GET | `/aircraft/config-items/tree?modelCode=` | 获取构型项目树（树形展示） |
| GET | `/aircraft/config-items/select-list?modelCode=` | 获取构型项目选择列表（下拉框用） |
| POST | `/aircraft/config-items` | 添加构型项目 |
| DELETE | `/aircraft/config-items/{itemId}` | 删除指定构型项目 |

构型项目按 ATA 章节组织为树形结构：**SYSTEM → SUBSYSTEM → EQUIPMENT/LRU**。

**POST /aircraft/config-items 请求体：**
```json
{
  "modelCode": "B737-800",
  "parentItemId": null,
  "ataChapter": "72-00",
  "systemName": "发动机",
  "subSystemName": null,
  "equipmentName": null,
  "partNumber": null,
  "itemType": "SYSTEM"
}
```

### 1.4 数据关联查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aircraft/mappings?tailNumber=&itemId=` | 查询 CSV 数据与飞机构型的关联（二选一参数） |

### 1.5 健康记录（弃用）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aircraft/health-records?tailNumber=&recordType=` | 查询健康记录（可按机号和记录类型过滤） |
| POST | `/aircraft/health-records` | 添加健康记录 |

记录类型：`DIAGNOSIS`（诊断）/ `EVALUATION`（评价）/ `PREDICTION`（预测）

---

## 二、CSV 数据管理 `/csv`

### 2.1 列分析与预览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/csv/analyze-columns` | 上传前分析 CSV 列属性（列类型 + 构型模板建议） |
| POST | `/csv/preview` | 上传前预览校验（含列分析 + 数据校验） |

请求格式：`multipart/form-data`，字段名 `file`

### 2.2 CSV 上传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/csv/upload` | 上传 CSV 数据 `csv_xxx` 表并绑定飞机构型 |ss

请求格式：`multipart/form-data`

| 参数 | 必填 | 说明 |
|------|------|------|
| file | 是 | CSV 文件 |
| tableName | 是 | 表名（自动补 `csv_` 前缀） |
| tailNumber | 是 | 关联机号 |
| parentItemId | 否 | 关联的构型项目 ID |
| dataType | 否 | 数据类型（DIAGNOSIS/EVALUATION/PREDICTION/RAW） |

### 2.3 数据查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/csv/query` | 分页查询设备数据 |
| GET | `/csv/tables` | 获取所有已存储设备列表（含机号） |
| GET | `/csv/overview` | 获取设备数据总览（行数、列数等） |
| GET | `/csv/query-range` | 按数值范围分页查询 |

**GET /csv/query 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| tailNumber | 是 | 机号 |
| deviceName | 是 | 设备名（对应 csv_ 后的表名） |
| page | 否 | 页码，默认 1 |
| size | 否 | 每页条数，默认 20 |

**GET /csv/query-range 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| tailNumber | 是 | 机号 |
| deviceName | 是 | 设备名 |
| column | 是 | 数值列名 |
| min | 否 | 最小值 |
| max | 否 | 最大值 |
| page | 否 | 页码 |
| size | 否 | 每页条数 |

### 2.4 SQL 查询接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/csv/sql` | 执行自定义 SQL 查询（仅 SELECT） |

**请求体：**
```json
{
  "sql": "SELECT * FROM csv_xxx WHERE rownum <= 10"
}
```

> 仅允许 SELECT 语句，通过 EXPLAIN 预校验语法。

### 2.5 表操作

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/csv/list?tableName=` | 查询指定表所有数据 |
| POST | `/csv/delete?tableName=&id=` | 删除指定表内某行数据 |
| POST | `/csv/truncate?tableName=` | 清空指定表 |
| POST | `/csv/drop?tableName=` | 删除指定表 |
| GET | `/csv/export?tableName=` | 导出指定表为 CSV 文件（含一致性校验头） |

---

## 三、数据模型

### 3.1 核心实体关系

```
AircraftModel (机型)
    ↳ AircraftConfig (飞机构型，按机号)
        ↳ ConfigItem (构型项目，树形：系统→子系统→设备)
            ↳ ConfigDataMapping (关联 csv_xxx 数据表)
        ↳ HealthRecord (健康记录：诊断/评价/预测)
```

### 3.2 数据库表

| 表名 | 说明 |
|------|------|
| `aircraft_model` | 机型定义 |
| `aircraft_config` | 飞机构型（每架飞机实例） |
| `config_item` | 构型项目（ATA 章节树） |
| `config_data_mapping` | CSV 数据与构型关联表 |
| `health_record` | 健康记录 |
| `csv_xxx` | CSV 上传数据表（动态创建） |
| `csv_table_metadata` | CSV 表元数据 |

---

## 四、通用响应格式

### 成功响应
```json
{ "success": true, "message": "操作成功" }
```

### 错误响应
```json
{ "success": false, "error": "错误描述" }
```

### 分页查询响应
```json
{ "data": [...], "total": 100, "page": 1, "size": 20 }
```
