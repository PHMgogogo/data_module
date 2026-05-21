<template>
  <div class="health-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>健康看板 — 诊断 / 评价 / 预测</span>
        </div>
      </template>

      <!-- 筛选条件 -->
      <el-form :inline="true" class="filter-bar">
        <el-form-item label="机型">
          <el-select v-model="filterModel" clearable placeholder="选择机型" style="width: 180px" @change="handleModelChange">
            <el-option v-for="m in models" :key="m.modelCode" :label="m.modelCode" :value="m.modelCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="机号">
          <el-select v-model="filterTailNumber" clearable placeholder="选择机号" style="width: 180px" :disabled="!filterModel">
            <el-option v-for="tn in tailNumbers" :key="tn" :label="tn" :value="tn" />
          </el-select>
        </el-form-item>
        <el-form-item label="记录类型">
          <el-select v-model="filterType" clearable placeholder="全部类型" style="width: 160px">
            <el-option label="诊断 DIAGNOSIS" value="DIAGNOSIS" />
            <el-option label="评价 EVALUATION" value="EVALUATION" />
            <el-option label="预测 PREDICTION" value="PREDICTION" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadRecords" :loading="loading">
            <el-icon style="margin-right: 5px"><Search /></el-icon>
            查询
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 统计卡片 -->
      <el-row :gutter="20" class="stats-row">
        <el-col :span="8">
          <div class="stat-card diagnosis">
            <div class="stat-value">{{ stats.diagnosis }}</div>
            <div class="stat-label">诊断记录</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card evaluation">
            <div class="stat-value">{{ stats.evaluation }}</div>
            <div class="stat-label">评价记录</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card prediction">
            <div class="stat-value">{{ stats.prediction }}</div>
            <div class="stat-label">预测记录</div>
          </div>
        </el-col>
      </el-row>

      <!-- 健康记录表格 -->
      <el-table :data="records" border stripe style="width: 100%; margin-top: 20px" max-height="500">
        <el-table-column prop="tailNumber" label="机号" width="100" />
        <el-table-column prop="recordType" label="类型" width="120">
          <template #default="scope">
            <el-tag :type="typeTag(scope.row.recordType)" size="small">
              {{ scope.row.recordType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="indicatorName" label="指标名称" width="150" />
        <el-table-column prop="indicatorValue" label="指标值" width="150" />
        <el-table-column prop="confidence" label="置信度" width="100" />
        <el-table-column prop="dataSourceTable" label="数据来源" width="150" />
        <el-table-column prop="recordTime" label="记录时间" width="180" />
      </el-table>

      <el-empty v-if="!loading && records.length === 0 && filterTailNumber" description="暂无健康记录" style="margin-top: 40px" />
      <el-empty v-else-if="!filterTailNumber" description="请选择机型与机号后查询" style="margin-top: 40px" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import axios from 'axios'

const models = ref([])
const tailNumbers = ref([])
const filterModel = ref('')
const filterTailNumber = ref('')
const filterType = ref('')
const records = ref([])
const loading = ref(false)

const stats = reactive({ diagnosis: 0, evaluation: 0, prediction: 0 })

const loadModels = async () => {
  try {
    const res = await axios.get('/api/aircraft/models')
    models.value = res.data
  } catch (e) {
    console.error('加载机型失败', e)
  }
}

const handleModelChange = async (modelCode) => {
  filterTailNumber.value = ''
  tailNumbers.value = []
  if (!modelCode) return
  try {
    const res = await axios.get('/api/aircraft/tail-numbers', { params: { modelCode } })
    tailNumbers.value = res.data
  } catch (e) {
    console.error('加载机号失败', e)
  }
}

const loadRecords = async () => {
  if (!filterTailNumber.value) return
  loading.value = true
  try {
    const params = {}
    if (filterTailNumber.value) params.tailNumber = filterTailNumber.value
    if (filterType.value) params.recordType = filterType.value

    const res = await axios.get('/api/aircraft/health-records', { params })
    records.value = res.data

    // 统计
    stats.diagnosis = res.data.filter(r => r.recordType === 'DIAGNOSIS').length
    stats.evaluation = res.data.filter(r => r.recordType === 'EVALUATION').length
    stats.prediction = res.data.filter(r => r.recordType === 'PREDICTION').length
  } catch (e) {
    console.error('加载健康记录失败', e)
  } finally {
    loading.value = false
  }
}

const typeTag = (type) => {
  if (type === 'DIAGNOSIS') return 'warning'
  if (type === 'EVALUATION') return 'primary'
  if (type === 'PREDICTION') return 'danger'
  return 'info'
}

onMounted(() => {
  loadModels()
})
</script>

<style scoped>
.health-page {
  max-width: 1400px;
  margin: 0 auto;
}
.card-header {
  font-size: 18px;
  font-weight: bold;
}
.filter-bar {
  padding: 15px 0;
  border-bottom: 1px solid #e4e7ed;
  margin-bottom: 20px;
}
.stats-row {
  margin-bottom: 20px;
}
.stat-card {
  padding: 20px;
  border-radius: 8px;
  text-align: center;
  color: white;
}
.stat-card.diagnosis {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}
.stat-card.evaluation {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}
.stat-card.prediction {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
}
.stat-value {
  font-size: 32px;
  font-weight: bold;
}
.stat-label {
  font-size: 14px;
  opacity: 0.9;
  margin-top: 5px;
}
</style>
