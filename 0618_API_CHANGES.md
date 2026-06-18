# API 变更记录

## 1. 删除 dataType 字段

### 涉及接口

**`POST /csv/upload`**

请求参数已移除 `dataType`（数据类型：RAW/DIAGNOSIS/EVALUATION/PREDICTION）。

**移除前：**
```
file, tableName, aircraftNumber, parentItemId, dataType
```

**移除后：**
```
file, tableName, aircraftNumber, parentItemId
```

### 涉及实体

`ConfigDataMapping` 已删除 `dataType` 字段。

---

## 2. `/csv/overview` 支持 mappingId

### 接口定义

```http
GET /csv/overview?mappingId=5
```

或兼容旧方式：

```http
GET /csv/overview?aircraftNumber=B-1234&deviceName=engine_vibration
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `mappingId` | Long | 否 | 构型关联ID（推荐，与 aircraftNumber+deviceName 二选一） |
| `aircraftNumber` | String | 否 | 机号（兼容旧版，与 mappingId 二选一） |
| `deviceName` | String | 否 | 设备名（兼容旧版，与 mappingId 二选一） |


---

## 3. 新增 `/csv/export-columns`

按列名列表导出 csv_xxx 表的指定列子集为 CSV 文件。

### 接口定义

```http
GET /csv/export-columns?tableName=csv_engine_vibration&columns=fan_vibration,egt_actual
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tableName` | String | 是 | 表名（含 csv_ 前缀） |
| `columns` | String | 是 | 要导出的列名，逗号分隔 |

### 返回

- `Content-Type: text/csv; charset=UTF-8`
- 仅包含请求列的 CSV 文件流
- 文件名: `{tableName}_subset.csv`
