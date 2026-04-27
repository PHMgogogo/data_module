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
        >
          <el-option
            v-for="table in tables"
            :key="table"
            :label="table"
            :value="table"
          />
        </el-select>
        <el-button type="primary" @click="loadData" :loading="loading">查询</el-button>
        <el-button type="danger" @click="handleDropTable" :disabled="!tableName">删除表</el-button>
        <el-button type="warning" @click="handleTruncateTable" :disabled="!tableName">清空表</el-button>
        <el-button type="success" @click="handleExport" :disabled="!tableName">导出CSV</el-button>
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
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="scope">
              <el-button type="danger" size="small" @click="handleDelete(scope.row.ID)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      
      <el-empty v-else-if="!loading && tableName" description="暂无数据，请先上传CSV" style="margin-top: 40px" />
      <el-empty v-else-if="!tableName" description="请先选择表名" style="margin-top: 40px" />
      
      <!-- 增强型分页 - 始终显示调试信息 -->
      <div class="pagination-container" style="margin-top: 20px; padding: 15px 20px; background-color: #f5f7fa; border-radius: 4px; display: flex; align-items: center; justify-content: space-between;">
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
import axios from 'axios'

const tableName = ref('')
const tables = ref([])
const tableData = ref([])
const columns = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
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
    const response = await axios.get('/api/csv/tables')
    tables.value = response.data
    console.log('表列表:', response.data)
  } catch (error) {
    console.error('加载表列表失败', error)
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
    console.log('查询参数:', tableName.value, page.value, size.value)
    const response = await axios.get('/api/csv/page', {
      params: {
        tableName: tableName.value,
        page: page.value,
        size: size.value
      }
    })

    console.log('查询结果:', response.data)
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

const handleDelete = async (id) => {
  try {
    await axios.post('/api/csv/delete', null, {
      params: {
        tableName: tableName.value,
        id: id
      }
    })
    
    message.show = true
    message.content = '删除成功'
    message.type = 'success'
    loadData() // 重新加载数据
  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '删除失败，请重试'
    message.type = 'error'
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
    page.value = 1 // 重置到第一页
    loadData() // 重新加载数据
  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '清空表失败，请重试'
    message.type = 'error'
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
    loadTables() // 重新加载表列表
  } catch (error) {
    message.show = true
    message.content = error.response?.data?.error || '删除表失败，请重试'
    message.type = 'error'
  }
}

const handleExport = async () => {
  try {
    const response = await axios.get('/api/csv/export', {
      params: {
        tableName: tableName.value
      },
      responseType: 'blob'
    })
    
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
  }
}

const handleSizeChange = (newSize) => {
  size.value = newSize
  page.value = 1 // 切换每页条数时重置到第一页
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

.pagination-info {
  color: #606266;
  font-size: 14px;
}

.message-alert {
  margin-top: 20px;
}
</style>
