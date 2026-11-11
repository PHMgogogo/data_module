# CSV 数据管理接口文档（/csv）

- **控制器**：`com.project.phm.controller.CsvController`
- **Base URL**：`http://<host>:8080`（无 context-path，端口见 `application.yml`）
- **数据存储**：达梦数据库，CSV 数据存于 `csv_xxx` 动态表
- **元数据**：列类型、机号关联、SHA-256 哈希等存于 `csv_table_metadata`；飞机构型关联存于 `config_data_mapping`
- **Swagger**：`/swagger-ui.html`（Tag：`05-CSV数据管理`）
- **上传限制**：Spring multipart 限制单文件 **10MB**（`spring.servlet.multipart.max-file-size`）

## 接口总览

| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 1 | POST | `/csv/analyze-columns` | 分析 CSV 列属性（上传前） |
| 2 | POST | `/csv/preview` | 上传预览校验（校验 + 列分析） |
| 3 | POST | `/csv/upload` | 上传 CSV 入库并绑定架次 |
| 4 | GET | `/csv/query` | 分页查询设备数据 |
| 5 | GET | `/csv/tables` | 获取所有已存储设备列表 |
| 6 | GET | `/csv/overview` | 获取设备数据总览 |
| 7 | GET | `/csv/query-range` | 按数值范围分页查询 |
| 8 | POST | `/csv/sql` | 执行自定义 SQL（仅 SELECT） |
| 9 | GET | `/csv/list` | 查询表全部数据（不分页） |
| 10 | POST | `/csv/delete` | 删除单条数据 |
| 11 | POST | `/csv/truncate` | 清空表数据（保留表结构） |
| 12 | POST | `/csv/drop` | 删除整个表 |
| 13 | GET | `/csv/export` | 导出 CSV 并校验一致性 |
| 14 | GET | `/csv/export-columns` | 导出指定列子集 CSV |
| 15 | POST | `/csv/query-timeseries` | 时序数据统一查询（按架次路由） |

## 通用约定

- **表名前缀**：接口里传「设备名」（如 `engine_vibration`），后端自动拼成 `csv_engine_vibration`；部分接口直接要求传完整表名（含 `csv_` 前缀），见各接口说明。
- **错误响应**：
  - 400：`{"success": false, "error": "原因"}` 或 `{"error": "原因"}`
  - 500：同上，`error` 带 `"xxx失败: "` 前缀
- **modelCode（机型）参数**：多个查询接口要求必填，用于平台路由校验（CSV 数据只存在于本地）。

---

## 1. 分析 CSV 列属性

`POST /csv/analyze-columns` · `multipart/form-data`

上传前调用，自动识别每列数据类型（数值/日期/字符串等），并给出构型项目模板建议。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `file` | form-data | file | 是 | CSV 文件 |

**响应 200**（`AnalysisResult`）：

```json
{
  "columns": ["timestamp", "vibration", "note"],
  "originalColumns": ["Timestamp", "Vibration", "Note"],
  "timestampColumn": "timestamp",
  "columnTypes": { "timestamp": "datetime", "vibration": "double", "note": "string" },
  "numericColumns": ["vibration"],
  "textColumns": ["note"],
  "totalRows": 1000,
  "sampleData": [ { "...": "前 20 行采样" } ]
}
```

**响应 500**：`{"error": "列分析失败: ..."}`

---

## 2. 上传预览校验

`POST /csv/preview` · `multipart/form-data`

上传前完整校验，返回校验结果 + 列分析两部分。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `file` | form-data | file | 是 | CSV 文件 |

校验项：文件格式（必须是 CSV）、表头（必须有列名）、数据行（空行、列数一致性）、文件大小警告（>100MB）。

**响应 200**：

```json
{
  "validation": {
    "valid": true,
    "totalRows": 1000,
    "validRows": 998,
    "invalidRows": 2,
    "columns": ["timestamp", "vibration"],
    "warnings": ["第 500 行存在空行"],
    "errors": [],
    "sampleData": [["2026-07-23 10:00:00", "2.35"]]
  },
  "analysis": { "...同接口 1 的 AnalysisResult..." }
}
```

**响应 500**：`{"error": "预览失败: ..."}`

---

## 3. 上传 CSV 文件并入库

`POST /csv/upload` · `multipart/form-data`

解析 CSV 并写入达梦 `csv_xxx` 表，同时完成：

- 自动建表（如不存在）
- 计算原始数据 SHA-256 哈希（用于导出一致性校验）
- 写入元数据表 `csv_table_metadata`
- 绑定到本地架次（`config_data_mapping`）：**一个架次只能绑定一张表，一张表只能绑定一个架次（严格一对一）**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `file` | form-data | file | 是 | CSV 文件 |
| `tableName` | form-data | string | 是 | 表名，自动加 `csv_` 前缀（传 `engine` → `csv_engine`） |
| `sortieId` | form-data | long | 是 | 本地架次 ID |

**响应 200**：`csvService.uploadCsv` 返回的结果 Map（含建表/入库统计信息）。

**错误**：
- 400：参数非法（如表名已存在、架次已绑定其它表）
- 500：`{"error": "上传失败: ..."}`

---

## 4. 分页查询设备数据

`GET /csv/query`

按机型 + 机号 + 设备名分页查询 `csv_xxx` 表。表不存在时返回空分页结构而不是报错。

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `modelCode` | string | 是 | — | 机型代码，如 `B737-800` |
| `aircraftNumber` | string | 是 | — | 机号，如 `B-1234` |
| `deviceName` | string | 是 | — | 设备名（`csv_` 后的部分），如 `engine_vibration` |
| `page` | int | 否 | `1` | 页码 |
| `size` | int | 否 | `20` | 每页条数 |

**响应 200**：

```json
{ "data": [ {} ], "total": 1000, "page": 1, "size": 20 }
```

**响应 500**：`{"success": false, "error": "查询失败: ..."}`

---

## 5. 获取所有已存储设备列表

`GET /csv/tables` · 无参数

从元数据表 `csv_table_metadata` 读取（`meta_key = 'tail_number'`），并双重校验物理表存在；查询失败时返回空数组（不报错）。

**响应 200**：

```json
[
  { "label": "B-1234 / engine_vibration", "aircraftNumber": "B-1234", "deviceName": "engine_vibration" }
]
```

---

## 6. 获取设备数据总览

`GET /csv/overview`

返回指定表的总览（行数、列数、列类型等）。两种定位方式二选一，`mappingId` 优先：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelCode` | string | 是 | 机型代码 |
| `mappingId` | long | 二选一 | 构型关联 ID，自动解析机号 + 表名（推荐） |
| `aircraftNumber` | string | 二选一 | 机号 |
| `deviceName` | string | 二选一 | 设备名（可带可不带 `csv_` 前缀） |

**响应 200**：

```json
{
  "deviceName": "csv_engine_vibration",
  "aircraftNumber": "B-1234",
  "totalRows": 1000,
  "...": "列数、列类型等由 csvService.getOverview 提供"
}
```

表不存在时返回 `{"deviceName": "...", "aircraftNumber": "...", "totalRows": 0}`。

**错误**：400 —— `mappingId` 不存在，或两类参数都没传全；500 —— 其它异常。

---

## 7. 按数值范围分页查询

`GET /csv/query-range`

在指定数值列上按 `min ≤ value ≤ max` 过滤并分页。`min`/`max` 均可省略（只限单边）。

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `modelCode` | string | 是 | — | 机型代码 |
| `aircraftNumber` | string | 是 | — | 机号 |
| `deviceName` | string | 是 | — | 设备名 |
| `column` | string | 是 | — | 数值列名，如 `vibration` |
| `min` | double | 否 | — | 最小值 |
| `max` | double | 否 | — | 最大值 |
| `page` | int | 否 | `1` | 页码 |
| `size` | int | 否 | `20` | 每页条数 |

**响应 200**：分页结构（同接口 4）。
**响应 500**：`{"success": false, "error": "范围查询失败: ..."}`

---

## 8. 执行自定义 SQL 查询

`POST /csv/sql` · `application/json`

安全限制：**仅允许 SELECT**，通过 EXPLAIN 预校验语法；INSERT/UPDATE/DELETE/DROP 等一律拒绝。

**请求体**：

```json
{ "sql": "SELECT * FROM csv_engine_vibration WHERE vibration > 2.3 ORDER BY id" }
```

**响应 200**：

```json
{ "columns": ["id", "timestamp", "vibration"], "data": [ [] ], "totalRows": 42 }
```

**错误**：400 —— SQL 为空、语法错误或安全检查不通过；500 —— 其它运行时错误。

---

## 9. 查询表的全部数据

`GET /csv/list`

返回指定表所有数据，**不分页，大表慎用**。建议优先用 `/csv/query` 或 `/csv/sql`。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelCode` | string | 是 | 机型代码 |
| `tableName` | string | 是 | 完整表名（含 `csv_` 前缀），如 `csv_engine_vibration` |

**响应 200**：`List<Map<String, Object>>` 数据行数组。
**错误**：400 —— 参数非法；500 —— 其它异常。

---

## 10. 删除单条数据

`POST /csv/delete`

按 ID 删除一条记录。⚠️ 删除后破坏数据一致性，导出哈希校验将失败。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `tableName` | query/body | string | 是 | 完整表名（含 `csv_` 前缀） |
| `id` | query/body | int | 是 | 记录 ID |

**响应 200**：`{"success": true, "message": "删除成功"}`

---

## 11. 清空表数据

`POST /csv/truncate`

清空指定表所有数据，保留表结构（不删元数据）。**不可逆**。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tableName` | string | 是 | 完整表名（含 `csv_` 前缀） |

**响应 200**：`{"success": true, "message": "清空成功"}`

---

## 12. 删除整个表

`POST /csv/drop`

删除指定表（数据 + 表结构）。**不可逆，危险操作**。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tableName` | string | 是 | 完整表名（含 `csv_` 前缀） |

**响应 200**：`{"success": true, "message": "删除成功"}`

---

## 13. 导出 CSV 并校验一致性

`GET /csv/export`

导出整表数据为 CSV，并用上传时保存的 SHA-256 哈希 + 行数做一致性校验。校验结果通过响应头带回：

| 响应头 | 说明 |
|--------|------|
| `X-Validation-Status` | `success` / `error` |
| `X-Validation-Message` | 校验消息（URL 编码） |
| `X-Is-Consistent` | `true` / `false` |
| `X-Original-Rows` | 原始上传行数 |
| `X-Actual-Rows` | 实际导出行数 |

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tableName` | string | 是 | 完整表名（含 `csv_` 前缀） |

**响应 200**：`text/csv; charset=UTF-8` 文件流（附件下载，UTF-8 文件名）。元数据缺失时仅做行数校验，并在校验信息里附 warning。

---

## 14. 导出指定列子集 CSV

`GET /csv/export-columns`

按列名列表导出表的列子集，不做哈希校验。

| 参数 | 类型 | 必填 | 示例 |
|------|------|------|------|
| `tableName` | string | 是 | `csv_engine_vibration` |
| `columns` | string（逗号分隔） | 是 | `fan_vibration,egt_actual` |

**响应 200**：`text/csv; charset=UTF-8` 文件流（`<表名>_subset.csv`）。
**错误**：400 / 500，body 为空。

---

## 15. 时序数据统一查询（按架次路由）

`POST /csv/query-timeseries` · `application/json`

按架次把查询**定向**到本地 / 航新 / 633 中的**一个**平台：后端在内存平台路由索引里查该架次归属，只向该平台发请求。本地走 `config_data_mapping` 关联的 `csv_xxx` 表；三方先查架次接口拿起止时间（转 ISO）再查时序接口。索引未命中时返回空结构并记 warn 日志，**不回落其它源**。

**请求体**（`UnifiedTimeSeriesRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sortieId` | string | 是 | 架次标识，取 `/aircraft/sorties` 下发的 `sortieKey`（接受 `sortieKey` 作为 JSON 别名） |
| `airplaneType` | string | 是 | 机型（路由唯一依据），如 `K4-WS19`；带冒号的合成机型码（`K4-WS19:6`）不会转发给三方 |
| `aircraftNumber` | string | 否 | 机号，仅当路由索引缺该架次机号时作为补充 |
| `startTime` | string | 否 | 开始时间；支持 `2026-07-23 10:30:00`（含小数秒）与 ISO 两种格式 |
| `endTime` | string | 否 | 结束时间；格式同上 |
| `paralist` | string[] | 否 | 参数列表（本地 → 列名；633 → Paralist；航新 → parameters） |
| `samplingRate` | string | 否 | 采样率，默认 `10` |

时间缺省逻辑：不传时优先用 `/aircraft/sorties` 刷新时缓存的架次起止时间，缓存缺失才回查三方架次接口；最终统一转成 `2026-07-23T10:30:00.000+08:00` 形式发给三方。

**请求示例**：

```json
{
  "airplaneType": "K4-WS19:6",
  "sortieId": "1",
  "startTime": "2026-07-23 10:30:00",
  "endTime": "2026-07-23 14:20:00",
  "paralist": ["ALTITUDE", "SPEED"],
  "samplingRate": "10"
}
```

**响应 200**（`ApiResult<UnifiedTimeSeriesResponse>`）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "timestamps": [1783296000000, 1783296001000],
    "parameters": [
      { "name": "ALTITUDE", "values": [1000.5, 1001.2] },
      { "name": "SPEED", "values": [300.0, 301.0] }
    ]
  },
  "hangxin": null,
  "sansan": null,
  "local": null
}
```

- `timestamps`：毫秒时间戳数组（航新源已由纳秒转毫秒）
- `parameters[].values`：与时间轴一一对应
- `hangxin` / `sansan` / `local`：数据源查询元信息（`SourceInfo`），非空时才返回
- 三个源都没数据时返回空结构（`timestamps: []`, `parameters: []`）

**错误**：400 —— 缺 `sortieKey`/`sortieId` 或参数非法；500 —— 查询异常。

示例：
curl --url ^"http://152.136.119.117:8888/api/csv/query-timeseries^" ^
  -H ^"Accept: */*^" ^
  -H ^"Accept-Language: zh-CN,zh;q=0.9,en;q=0.8^" ^
  -H ^"Connection: keep-alive^" ^
  -H ^"Content-Type: application/json^" ^
  -H ^"Origin: http://152.136.119.117:8888^" ^
  -H ^"Referer: http://152.136.119.117:8888/phm/aircraft/B-1234^" ^
  -H ^"User-Agent: Mozilla/5.0 ^(Windows NT 10.0; Win64; x64^) AppleWebKit/537.36 ^(KHTML, like Gecko^) Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0^" ^
  --data-raw ^"^{^\^"airplaneType^\^":^\^"B737-800^\^",^\^"sortieId^\^":^\^"3^\^",^\^"aircraftNumber^\^":^\^"B-1234^\^",^\^"paralist^\^":^[^\^"fan_vibration^\^",^\^"egt_actual^\^",^\^"n1_percent^\^"^]^}^" ^
  --insecure

响应：
{"code":200,"message":"success","data":{"timestamps":[1784773800000,1784773810000,1784773820000,1784773830000,1784773840000,1784773850000,1784773860000,1784773870000,1784773880000,1784773890000,1784773900000,1784773910000],"parameters":[{"name":"fan_vibration","values":["2.35","2.28","2.10","1.85","1.80","1.82","1.79","1.90","2.05","2.18","2.22","1.60"]},{"name":"egt_actual","values":["650.5","641.0","598.2","560.8","555.1","557.3","554.6","572.4","590.7","605.9","612.4","480.0"]},{"name":"n1_percent","values":["88.2","86.5","82.1","78.4","77.9","78.0","77.8","79.6","81.3","83.0","84.2","60.5"]}]}}