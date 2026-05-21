<template>
  <div class="manage-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>数据管理{{ selectedDevice ? ' — ' + selectedDevice : ' — 查看已上传的数据' }}</span>
        </div>
      </template>

      <!-- 设备选择 -->
      <div class="filter-bar">
        <el-form :inline="true" size="small">
          <el-form-item label="数据设备">
            <el-select v-model="selectedDevice" placeholder="选择机号/设备" style="width: 400px" clearable filterable @change="handleDeviceChange">
              <el-option v-for="d in deviceList" :key="d.label" :label="d.label" :value="d.label" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="refreshDevices">
              刷新列表
            </el-button>
          </el-form-item>
          <el-form-item v-if="overview && overview.tailNumber">
            <el-tag type="success" size="medium">
              机号: {{ overview.tailNumber }}
            </el-tag>
          </el-form-item>
        </el-form>
      </div>

      <!-- SQL 查询 -->
      <div class="sql-section">
        <div class="section-title">SQL 查询</div>
        <el-input
          v-model="sqlInput"
          type="textarea"
          :rows="3"
          placeholder="输入 SELECT 语句，例如：SELECT * FROM csv_xxx WHERE ..."
          class="sql-input"
        />
        <div class="sql-actions">
          <el-button type="primary" @click="executeSql" :loading="sqlLoading">
            执行
          </el-button>
          <el-button @click="clearSql">清空</el-button>
          <span v-if="sqlTotalRows !== null" class="sql-row-count">
            共 {{ sqlTotalRows }} 条记录
          </span>
        </div>
        <el-alert
          v-if="sqlError"
          :title="sqlError"
          type="error"
          show-icon
          closable
          @close="sqlError = ''"
          class="sql-error-alert"
        />
        <div v-if="sqlResult.length > 0" class="sql-result-table">
          <el-table :data="sqlResult" border stripe style="width: 100%" max-height="500" size="small">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column
              v-for="col in sqlColumns"
              :key="col"
              :prop="col"
              :label="col"
              min-width="130"
            />
          </el-table>
        </div>
      </div>

      <!-- 数据总览 -->
      <div v-if="overview" class="overview-section">
        <div class="section-title">
          数据总览
        </div>

        <!-- 概览统计卡片 -->
        <el-row :gutter="16" class="overview-cards">
          <el-col :span="6">
            <div class="overview-card rows">
              <div class="overview-value">{{ overview.totalRows ?? '-' }}</div>
              <div class="overview-label">总行数</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="overview-card columns-card">
              <div class="overview-value">{{ sensorCount }}</div>
              <div class="overview-label">传感器列</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="overview-card numeric">
              <div class="overview-value">{{ overview.numericColumns ? overview.numericColumns.length : 0 }}</div>
              <div class="overview-label">数值列</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="overview-card time-card">
              <div class="overview-value" style="font-size: 14px">{{ overview.totalRows > 0 ? (overview.startTimeFormatted || '-') : '-' }}</div>
              <div class="overview-label">开始时间</div>
            </div>
          </el-col>
        </el-row>
        <el-row :gutter="16" style="margin-top: 8px">
          <el-col :span="6">
            <div class="overview-card time-range">
              <div class="overview-value" style="font-size: 14px">{{ overview.totalRows > 0 ? (overview.endTimeFormatted || '-') : '-' }}</div>
              <div class="overview-label">结束时间</div>
            </div>
          </el-col>
          <el-col :span="18">
            <div class="overview-card col-types">
              <div class="overview-label" style="text-align: left; margin-bottom: 6px">传感器列信息</div>
              <div class="col-tags">
                <el-tag v-for="col in sensorColumns" :key="col.name" size="small"
                  :type="col.role === 'measurement' ? 'success' : 'info'"
                  style="margin: 2px 4px 2px 0">
                  {{ col.name }}
                  <span style="opacity: 0.7; margin-left: 4px">
                    ({{ col.type }})
                  </span>
                </el-tag>
              </div>
            </div>
          </el-col>
        </el-row>

        <!-- 数值列统计 -->
        <div v-if="overview.columnStats && Object.keys(overview.columnStats).length > 0" class="stats-table-wrap">
          <div class="stats-label">数值列统计 (最小值 / 最大值 / 平均值)</div>
          <el-table :data="statsTableData" border stripe size="small" style="width: 100%">
            <el-table-column prop="column" label="列名" width="180" />
            <el-table-column prop="min" label="最小值" width="160">
              <template #default="scope">
                <span v-if="scope.row.min !== null && scope.row.min !== undefined">{{ formatNum(scope.row.min) }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="max" label="最大值" width="160">
              <template #default="scope">
                <span v-if="scope.row.max !== null && scope.row.max !== undefined">{{ formatNum(scope.row.max) }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="avg" label="平均值" width="160">
              <template #default="scope">
                <span v-if="scope.row.avg !== null && scope.row.avg !== undefined">{{ formatNum(scope.row.avg) }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="count" label="非空值数" />
          </el-table>
        </div>
      </div>

      <!-- 值范围筛选 -->
      <div v-if="overview && overview.numericColumns && overview.numericColumns.length > 0" class="range-filter">
        <div class="section-title">
          值范围筛选
        </div>
        <el-form :inline="true" size="small">
          <el-form-item label="筛选列">
            <el-select v-model="rangeFilter.column" placeholder="选择数值列" style="width: 180px" clearable>
              <el-option v-for="col in overview.numericColumns" :key="col" :label="col" :value="col" />
            </el-select>
          </el-form-item>
          <el-form-item label="最小值">
            <el-input-number v-model="rangeFilter.minVal" :step="0.1" :controls="false" style="width: 140px" placeholder="不限制" />
          </el-form-item>
          <el-form-item label="最大值">
            <el-input-number v-model="rangeFilter.maxVal" :step="0.1" :controls="false" style="width: 140px" placeholder="不限制" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="applyRangeFilter" :loading="loading">
              筛选
            </el-button>
          </el-form-item>
          <el-form-item>
            <el-button @click="resetRangeFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 数据表格 -->
      <div v-if="tableData.length > 0" class="table-container">
        <div class="table-title-bar">
          <span class="table-title">
            {{ rangeFilter.column ? `筛选结果 (${rangeFilter.column}: ${rangeFilter.minVal ?? '-∞'} ~ ${rangeFilter.maxVal ?? '+∞'})` : '全部数据' }}
          </span>
        </div>
        <el-table :data="tableData" border stripe style="width: 100%" max-height="500" size="small">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column
            v-for="col in columns"
            :key="col"
            :prop="col"
            :label="col"
            :min-width="col === 'Time' ? 180 : 130"
          />
        </el-table>
        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="totalRecords"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="handlePageChange"
            @size-change="handleSizeChange"
          />
        </div>
      </div>

      <!-- 空状态提示 -->
      <el-empty v-if="selectedDevice && !loading && tableData.length === 0 && !overviewLoading" description="暂无数据" style="margin-top: 40px" />
      <el-empty v-else-if="!selectedDevice" description="请选择数据设备" style="margin-top: 40px" />

      <el-alert v-if="message.show" :title="message.content" :type="message.type" show-icon class="message-alert" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import axios from 'axios'

// 设备列表
const deviceList = ref([])
const selectedDevice = ref(null)

// 数据
const overview = ref(null)
const tableData = ref([])
const columns = ref([])
const loading = ref(false)
const overviewLoading = ref(false)

// 分页
const currentPage = ref(1)
const pageSize = ref(20)
const totalRecords = ref(0)

// 值范围筛选
const rangeFilter = reactive({
  column: '',
  minVal: null,
  maxVal: null
})

// SQL 查询
const sqlInput = ref('')
const sqlLoading = ref(false)
const sqlError = ref('')
const sqlResult = ref([])
const sqlColumns = ref([])
const sqlTotalRows = ref(null)

const executeSql = async () => {
  if (!sqlInput.value.trim()) {
    sqlError.value = '请输入 SQL 语句'
    return
  }
  sqlLoading.value = true
  sqlError.value = ''
  sqlResult.value = []
  sqlColumns.value = []
  sqlTotalRows.value = null
  try {
    const res = await axios.post('/api/csv/sql', { sql: sqlInput.value }, { timeout: 30000 })
    const data = res.data
    if (data.success) {
      sqlResult.value = data.data || []
      sqlColumns.value = data.columns || []
      sqlTotalRows.value = data.totalRows || 0
    } else {
      sqlError.value = data.error || '查询返回错误'
    }
  } catch (e) {
    if (e.code === 'ECONNABORTED') {
      sqlError.value = 'SQL查询超时，请检查语句是否正确或简化查询'
    } else {
      sqlError.value = e.response?.data?.error || 'SQL执行失败: ' + (e.message || '未知错误')
    }
  } finally {
    sqlLoading.value = false
  }
}

const clearSql = () => {
  sqlInput.value = ''
  sqlError.value = ''
  sqlResult.value = []
  sqlColumns.value = []
  sqlTotalRows.value = null
}

const message = reactive({ show: false, content: '', type: 'success' })

const showMsg = (content, type = 'success') => {
  message.show = true; message.content = content; message.type = type
  setTimeout(() => { message.message = ''; message.show = false }, 3000)
}

// 传感器列列表（从overview的columns数组提取）
const sensorColumns = computed(() => {
  if (!overview.value || !overview.value.columns) return []
  return overview.value.columns.filter(c => c.role !== 'time')
})

// 传感器数量
const sensorCount = computed(() => {
  return sensorColumns.value.length
})

// 统计表格数据
const statsTableData = computed(() => {
  if (!overview.value || !overview.value.columnStats) return []
  const stats = overview.value.columnStats
  return Object.keys(stats).map(col => ({
    column: col,
    min: stats[col].min,
    max: stats[col].max,
    avg: stats[col].avg,
    count: stats[col].count
  }))
})

const formatNum = (val) => {
  if (val === null || val === undefined) return '-'
  const n = Number(val)
  if (Number.isNaN(n)) return val
  if (Number.isInteger(n)) return n.toLocaleString()
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}

const loadDevices = async () => {
  try {
    const res = await axios.get('/api/csv/tables')
    deviceList.value = res.data || []
  } catch (e) { console.error('加载设备列表失败', e) }
}

const refreshDevices = () => {
  loadDevices()
}

const handleDeviceChange = async (label) => {
  overview.value = null
  tableData.value = []
  columns.value = []
  rangeFilter.column = ''
  rangeFilter.minVal = null
  rangeFilter.maxVal = null
  currentPage.value = 1
  totalRecords.value = 0
  if (!label) return
  const device = deviceList.value.find(d => d.label === label)
  if (!device) return
  await loadOverview(device)
  await loadDeviceData(device)
}

// 加载数据总览
const loadOverview = async (device) => {
  overviewLoading.value = true
  try {
    const res = await axios.get('/api/csv/overview', {
      params: { tailNumber: device.tailNumber, deviceName: device.deviceName }
    })
    overview.value = res.data
  } catch (e) {
    console.error('加载概览失败', e)
    showMsg('加载数据总览失败', 'error')
  } finally {
    overviewLoading.value = false
  }
}

// 加载设备数据
const loadDeviceData = async (device, filterParams) => {
  loading.value = true
  try {
    let res
    if (filterParams) {
      res = await axios.get('/api/csv/query-range', {
        params: {
          tailNumber: device.tailNumber,
          deviceName: device.deviceName,
          column: filterParams.column,
          min: filterParams.minVal,
          max: filterParams.maxVal,
          page: currentPage.value,
          size: pageSize.value
        }
      })
    } else {
      res = await axios.get('/api/csv/query', {
        params: {
          tailNumber: device.tailNumber,
          deviceName: device.deviceName,
          page: currentPage.value,
          size: pageSize.value
        }
      })
    }
    const result = res.data
    tableData.value = result.data || []
    totalRecords.value = result.total || 0
    columns.value = tableData.value.length > 0 ? Object.keys(tableData.value[0]) : []
  } catch (e) {
    console.error('查询数据失败', e)
    showMsg('查询失败', 'error')
  } finally {
    loading.value = false
  }
}

// 值范围筛选
const applyRangeFilter = async () => {
  if (!rangeFilter.column) {
    showMsg('请选择筛选列', 'warning')
    return
  }
  if (rangeFilter.minVal === null && rangeFilter.maxVal === null) {
    showMsg('请至少输入最小值或最大值', 'warning')
    return
  }
  currentPage.value = 1
  const device = deviceList.value.find(d => d.label === selectedDevice.value)
  if (!device) return
  await loadDeviceData(device, {
    column: rangeFilter.column,
    minVal: rangeFilter.minVal,
    maxVal: rangeFilter.maxVal
  })
}

const resetRangeFilter = () => {
  rangeFilter.column = ''
  rangeFilter.minVal = null
  rangeFilter.maxVal = null
  currentPage.value = 1
  const device = deviceList.value.find(d => d.label === selectedDevice.value)
  if (device) loadDeviceData(device)
}

// 分页
const handlePageChange = (page) => {
  currentPage.value = page
  const device = deviceList.value.find(d => d.label === selectedDevice.value)
  if (!device) return
  const filterParams = rangeFilter.column ? { column: rangeFilter.column, minVal: rangeFilter.minVal, maxVal: rangeFilter.maxVal } : null
  loadDeviceData(device, filterParams)
}

const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  const device = deviceList.value.find(d => d.label === selectedDevice.value)
  if (!device) return
  const filterParams = rangeFilter.column ? { column: rangeFilter.column, minVal: rangeFilter.minVal, maxVal: rangeFilter.maxVal } : null
  loadDeviceData(device, filterParams)
}

onMounted(() => { loadDevices() })
</script>

<style scoped>
.manage-page { max-width: 1400px; margin: 0 auto; }
.card-header { font-size: 18px; font-weight: bold; }
.filter-bar { margin-top: 15px; padding: 15px; background-color: #f5f7fa; border-radius: 8px; }

/* 总览区域 */
.overview-section { margin-top: 20px; padding: 20px; background-color: #f5f7fa; border-radius: 8px; }
.section-title { font-size: 16px; font-weight: bold; margin-bottom: 16px; color: #303133; }
.overview-cards { margin-bottom: 0; }
.overview-card { padding: 16px 20px; border-radius: 8px; text-align: center; color: white; height: 100%; }
.overview-card.rows { background: linear-gradient(135deg, #11998e, #38ef7d); }
.overview-card.columns-card { background: linear-gradient(135deg, #f093fb, #f5576c); }
.overview-card.numeric { background: linear-gradient(135deg, #667eea, #764ba2); }
.overview-card.time-card { background: linear-gradient(135deg, #4facfe, #00f2fe); }
.overview-card.time-range { background: linear-gradient(135deg, #fa709a, #fee140); }
.overview-card.col-types {
  background: white;
  color: #303133;
  border: 1px solid #e4e7ed;
  text-align: left;
  padding: 12px 16px;
}
.overview-value { font-size: 24px; font-weight: bold; }
.overview-label { font-size: 13px; opacity: 0.9; margin-top: 4px; }
.col-tags { display: flex; flex-wrap: wrap; }

/* 统计表格 */
.stats-table-wrap { margin-top: 16px; padding: 16px; background: white; border-radius: 8px; border: 1px solid #e4e7ed; }
.stats-label { font-size: 14px; font-weight: bold; margin-bottom: 10px; color: #606266; }

/* 值范围筛选 */
.range-filter { margin-top: 20px; padding: 20px; background-color: #f5f7fa; border-radius: 8px; }

/* 数据表格 */
.table-container { margin-top: 20px; }
.table-title-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.table-title { font-size: 15px; font-weight: bold; color: #303133; }

.message-alert { margin-top: 20px; }

.pagination-wrapper { margin-top: 16px; display: flex; justify-content: flex-end; }

/* SQL 查询区域 */
.sql-section { margin-top: 20px; padding: 20px; background-color: #f5f7fa; border-radius: 8px; }
.sql-input { font-family: monospace; }
.sql-actions { margin-top: 12px; display: flex; align-items: center; gap: 10px; }
.sql-row-count { font-size: 13px; color: #909399; margin-left: 10px; }
.sql-error-alert { margin-top: 12px; }
.sql-result-table { margin-top: 16px; }
</style>
