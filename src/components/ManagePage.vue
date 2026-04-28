<template>
  <div class="manage-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>数据管理</span>
        </div>
      </template>

      <div class="table-controls">
        <el-select
          v-model="tableName"
          placeholder="请选择表"
          style="width: 300px; margin-right: 10px"
          filterable
          @focus="loadTables"
          @change="handleTableChange"
        >
          <el-option
            v-for="table in tables"
            :key="table"
            :label="table"
            :value="table"
          />
        </el-select>
        <el-button type="primary" @click="loadData" :loading="loading">
          <el-icon style="margin-right: 5px"><Refresh /></el-icon>
          查询
        </el-button>
        <el-button type="danger" @click="handleDropTable" :disabled="!tableName">删除表</el-button>
        <el-button type="warning" @click="handleTruncateTable" :disabled="!tableName">清空表</el-button>
        <el-button type="success" @click="handleExport" :disabled="!tableName" :loading="exporting">
          导出CSV并校验
        </el-button>
      </div>

      <div v-if="tableData.length > 0" class="table-container">
        <el-table
          :data="tableData"
          style="width: 100%"
          height="400"
          border
        >
          <el-table-column prop="ID" label="ID" width="80" fixed />
          <el-table-column
            v-for="column in columns"
            :key="column"
            :prop="column"
            :label="column"
            v-if="column !== 'ID'"
            :min-width="120"
          />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="scope">
              <el-button
                type="danger"
                size="small"
                @click="handleDeleteRow(scope.row.ID)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-empty v-else-if="!loading && tableName" description="暂无数据，请先上传CSV" style="margin-top: 40px" />
      <el-empty v-else-if="!tableName" description="请先选择表名" style="margin-top: 40px" />

      <!-- 增强型分页 -->
      <div class="pagination-container">
        <div class="pagination-info">
          <span>共 {{ total }} 条记录，第 {{ page }} / {{ totalPages }} 页</span>
        </div>
        <el-pagination
          :current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50, 100, 500, 1000]"
          layout="prev, pager, next, sizes, jumper"
          :total="total"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>

      <!-- 导出校验结果弹窗 -->
      <el-dialog
        v-model="showExportDialog"
        title="导出校验结果"
        width="500px"
        :close-on-click-modal="false"
      >
        <div class="export-result">
          <el-alert
            :title="exportValidation.message"
            :type="exportValidation.status === 'success' ? 'success' : 'warning'"
            :closable="false"
            show-icon
          />
          <div class="export-stats">
            <div class="stat-item">
              <el-icon><i class="el-icon-s-data"></i></el-icon>
              <div class="stat-text">
                <div class="stat-label">数据库总行数</div>
                <div class="stat-value">{{ exportValidation.expectedRows }}</div>
              </div>
            </div>
            <div class="stat-item">
              <el-icon><i class="el-icon-document"></i></el-icon>
              <div class="stat-text">
                <div class="stat-label">导出文件行数</div>
                <div class="stat-value">{{ exportValidation.actualRows }}</div>
              </div>
            </div>
            <div class="stat-item">
              <el-icon><i class="el-icon-check"></i></el-icon>
              <div class="stat-text">
                <div class="stat-label">一致性校验</div>
                <div class="stat-value">{{ exportValidation.isConsistent ? '通过 ✓' : '不通过 ✗' }}</div>
              </div>
            </div>
          </div>
        </div>
        <template #footer>
          <el-button type="primary" @click="showExportDialog = false">确定</el-button>
        </template>
      </el-dialog>

      <el-alert
        v-if="message.show"
        :title="message.content"
        :type="message.type"
        show-icon
        class="message-alert"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'

import axios from 'axios'

const tableName = ref('')
const tables = ref([])
const tableData = ref([])
const columns = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const showExportDialog = ref(false)
const exportValidation = ref({
  status: '',
  expectedRows: 0,
  actualRows: 0,
  isConsistent: true,
  message: ''
})
const message = reactive({
  show: false,
  content: '',
  type: 'success'
})

// 计算总页数
const totalPages = computed(() => {
  return Math.ceil(total.value / size.value)
})

const loadTables = async () => {
  try {
    const response = await axios.get('/api/csv/tables', {
      params: {
        _t: Date.now() // 添加时间戳防止缓存
      },
      headers: {
        'Cache-Control': 'no-cache',
        'Pragma': 'no-cache'
      }
    })
    tables.value = response.data
    console.log('表列表:', response.data)
  } catch (error) {
    console.error('加载表列表失败', error)
  }
}

const handleTableChange = () => {
  page.value = 1
  tableData.value = []
  columns.value = []
  total.value = 0
  if (tableName.value) {
    loadData()
  }
}

const loadData = async () => {
  if (!tableName.value) {
    message.show = true
    message.content = '请选择表'
    message.type = 'warning'
    return
  }

  loading.value = true
  try {
    const response = await axios.get('/api/csv/page', {
      params: {
        tableName: tableName.value,
        page: page.value,
        size: size.value,
        _t: Date.now() // 添加时间戳防止缓存
      },
      headers: {
        'Cache-Control': 'no-cache',
        'Pragma': 'no-cache'
      }
    })

    const data = response.data
    tableData.value = data.data
    total.value = data.total

    // 提取列名
    if (tableData.value.length > 0) {
      columns.value = Object.keys(tableData.value[0])
      console.log('列名:', columns.value)
    } else {
      columns.value = []
    }
  } catch (error) {
    console.error('查询失败:', error)
    message.show = true
    message.content = error.response?.data?.error || '查询失败，请重试'
    message.type = 'error'
  } finally {
    loading.value = false
  }
}

const handleTruncateTable = async () => {
  try {
    await axios.post('/api/csv/truncate', null, {
      params: {
        tableName: tableName.value
      }
    })

    message.show = true
    message.content = '清空表成功'
    message.type = 'success'
    page.value = 1
    loadData()
  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '清空表失败，请重试'
    message.type = 'error'
  }
}

const handleDeleteRow = async (id) => {
  try {
    await ElMessageBox.confirm(
      '确定要删除这条数据吗？删除后数据将不一致，导出校验会失败！',
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    await axios.post('/api/csv/delete', null, {
      params: {
        tableName: tableName.value,
        id: id
      }
    })

    message.show = true
    message.content = '删除成功'
    message.type = 'success'
    loadData()
  } catch (error) {
    if (error !== 'cancel') {
      message.show = true
      message.content = error.response?.data?.error || '删除失败，请重试'
      message.type = 'error'
    }
  }
}

const handleDropTable = async () => {
  try {
    await axios.post('/api/csv/drop', null, {
      params: {
        tableName: tableName.value
      }
    })

    message.show = true
    message.content = '删除表成功'
    message.type = 'success'
    tableData.value = []
    columns.value = []
    total.value = 0
    page.value = 1
    loadTables()
  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '删除表失败，请重试'
    message.type = 'error'
  }
}

const handleExport = async () => {
  exporting.value = true
  try {
    const response = await axios.get('/api/csv/export', {
      params: {
        tableName: tableName.value
      },
      responseType: 'blob'
    })

    // 从响应头获取完整校验结果
    const status = response.headers['x-validation-status'] || 'success'
    const message = decodeURIComponent(response.headers['x-validation-message'] || '')
    const isConsistent = response.headers['x-is-consistent'] === 'true'
    const originalRows = parseInt(response.headers['x-original-rows'] || '0')
    const actualRows = parseInt(response.headers['x-actual-rows'] || '0')

    // 显示校验结果
    exportValidation.value = {
      status,
      expectedRows: originalRows || actualRows,
      actualRows,
      isConsistent,
      message: message || (isConsistent ? '数据一致性校验通过！' : '数据不一致')
    }
    showExportDialog.value = true

    // 下载文件
    const blob = new Blob([response.data], { type: 'text/csv; charset=UTF-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${tableName.value}.csv`
    link.click()
    URL.revokeObjectURL(url)

  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '导出失败，请重试'
    message.type = 'error'
  } finally {
    exporting.value = false
  }
}

const handleSizeChange = (newSize) => {
  size.value = newSize
  page.value = 1
  loadData()
}

const handleCurrentChange = (newPage) => {
  page.value = newPage
  loadData()
}

onMounted(() => {
  loadTables()
})
</script>

<style scoped>
.manage-page {
  max-width: 1400px;
  margin: 0 auto;
}

.card-header {
  font-size: 18px;
  font-weight: bold;
}

.table-controls {
  margin-top: 20px;
  display: flex;
  align-items: center;
}

.table-container {
  margin-top: 20px;
  overflow-x: auto;
}

.pagination-container {
  margin-top: 20px;
  padding: 15px 20px;
  background-color: #f5f7fa;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pagination-info {
  color: #606266;
  font-size: 14px;
}

.export-result {
  padding: 10px 0;
}

.export-stats {
  margin-top: 20px;
}

.stat-item {
  display: flex;
  align-items: center;
  padding: 15px;
  background-color: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 10px;
}

.stat-item .el-icon {
  font-size: 30px;
  color: #409eff;
  margin-right: 15px;
}

.stat-text {
  flex: 1;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 5px;
}

.stat-value {
  font-size: 20px;
  font-weight: bold;
  color: #303133;
}

.message-alert {
  margin-top: 20px;
}
</style>
