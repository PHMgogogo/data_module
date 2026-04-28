<template>
  <div class="upload-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>上传CSV文件 - 数据一致性校验</span>
        </div>
      </template>

      <!-- 步骤1: 选择文件和表名 -->
      <div class="step-section">
        <div class="step-title">
          <el-tag :type="currentStep >= 1 ? 'primary' : 'info'" size="large">步骤1</el-tag>
          选择CSV文件
        </div>
        <el-upload
          class="upload-demo"
          drag
          :auto-upload="false"
          :on-change="handleFileSelect"
          :limit="1"
          accept=".csv"
        >
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">
            将文件拖到此处，或<em>点击上传</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">
              支持UTF-8编码的CSV文件
            </div>
          </template>
        </el-upload>
      </div>

      <!-- 表名输入 -->
      <div class="step-section" v-if="selectedFile">
        <el-form :model="form" label-width="80px">
          <el-form-item 
            label="表名" 
            :rules="[{ required: true, message: '请输入表名', trigger: 'blur' }, { pattern: /^csv_/i, message: '表名必须以csv_开头', trigger: 'blur' }]"
            prop="tableName"
          >
            <el-input 
              v-model="form.tableName" 
              placeholder="请输入表名，如 csv_user" 
              style="width: 300px"
            />
          </el-form-item>
        </el-form>
      </div>

      <!-- 操作按钮 -->
      <div class="action-buttons">
        <el-button 
          type="primary" 
          size="large"
          @click="handleUploadAndProcess" 
          :loading="processing"
          :disabled="!selectedFile || !form.tableName"
        >
          开始上传并校验
        </el-button>
      </div>

      <!-- 步骤2: 上传预览和校验 -->
      <div class="step-section" v-if="previewResult">
        <div class="step-title">
          <el-tag :type="currentStep >= 2 ? 'primary' : 'info'" size="large">步骤2</el-tag>
          数据校验预览
        </div>
        
        <!-- 校验结果统计卡片 -->
        <el-row :gutter="20" class="stats-row">
          <el-col :span="6">
            <div class="stat-card total">
              <div class="stat-value">{{ previewResult.totalRows }}</div>
              <div class="stat-label">总数据行数</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card valid">
              <div class="stat-value">{{ previewResult.validRows }}</div>
              <div class="stat-label">有效行数</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card invalid">
              <div class="stat-value">{{ previewResult.invalidRows }}</div>
              <div class="stat-label">无效/空行</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card columns">
              <div class="stat-value">{{ previewResult.columns.length }}</div>
              <div class="stat-label">列数</div>
            </div>
          </el-col>
        </el-row>

        <!-- 警告信息 -->
        <el-alert
          v-if="previewResult.warnings && previewResult.warnings.length > 0"
          title="警告信息"
          type="warning"
          :closable="false"
          show-icon
          class="validation-alert"
        >
          <ul>
            <li v-for="(warning, idx) in previewResult.warnings" :key="idx">{{ warning }}</li>
          </ul>
        </el-alert>

        <!-- 列信息 -->
        <div class="columns-section">
          <h4>列名信息</h4>
          <el-tag 
            v-for="(col, idx) in previewResult.columns" 
            :key="idx"
            style="margin: 5px"
          >{{ col }}</el-tag>
        </div>

        <!-- 数据预览表格 -->
        <div class="preview-section">
          <h4>数据预览（前5行）</h4>
          <el-table :data="previewResult.sampleData" border stripe style="width: 100%">
            <el-table-column
              v-for="(col, colIdx) in previewResult.columns"
              :key="colIdx"
              :prop="String(colIdx)"
              :label="col"
            />
          </el-table>
        </div>
      </div>

      <!-- 步骤3: 处理进度和存储校验 -->
      <div class="step-section" v-if="currentStep >= 3">
        <div class="step-title">
          <el-tag :type="currentStep >= 3 ? 'primary' : 'info'" size="large">步骤3</el-tag>
          处理进度 & 存储校验
        </div>

        <!-- 进度条 -->
        <el-progress 
          :percentage="taskStatus.progress" 
          :status="progressStatus"
          style="margin: 20px 0"
        >
          <template #default="{ percentage }">
            <span class="percentage-value">{{ percentage }}%</span>
          </template>
        </el-progress>

        <!-- 处理统计 -->
        <el-row :gutter="20" v-if="taskStatus.status === 'COMPLETED'">
          <el-col :span="8">
            <div class="stat-card process">
              <div class="stat-value">{{ taskStatus.processedRows }}</div>
              <div class="stat-label">已处理</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="stat-card success">
              <div class="stat-value">{{ taskStatus.successRows }}</div>
              <div class="stat-label">成功入库</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="stat-card time">
              <div class="stat-value">{{ formatDuration(taskStatus.processingTime) }}</div>
              <div class="stat-label">处理时间</div>
            </div>
          </el-col>
        </el-row>

        <!-- 存储校验结果 -->
        <div v-if="taskStatus.storageValidation" class="validation-result">
          <el-alert
            :title="taskStatus.storageValidation.message"
            :type="validationType"
            :closable="false"
            show-icon
          >
            <template #default>
              <div class="validation-details">
                <span>预期行数: <strong>{{ taskStatus.storageValidation.expectedRows }}</strong></span>
                <span>实际入库: <strong>{{ taskStatus.storageValidation.actualRows }}</strong></span>
                <span>差异: <strong>{{ taskStatus.storageValidation.diffRows }}</strong></span>
                <span>一致性: <strong>{{ taskStatus.storageValidation.isConsistent ? '✓一致' : '✗不一致' }}</strong></span>
              </div>
            </template>
          </el-alert>
        </div>

        <!-- 成功消息 -->
        <el-alert
          v-if="taskStatus.status === 'COMPLETED'"
          title="数据导入完成！"
          type="success"
          show-icon
          style="margin-top: 20px"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'

const selectedFile = ref(null)
const processing = ref(false)
const currentStep = ref(1)
const previewResult = ref(null)
const taskStatus = ref({
  progress: 0,
  status: 'PENDING'
})
const pollTimer = ref(null)

const form = reactive({
  tableName: ''
})

const progressStatus = computed(() => {
  if (taskStatus.value.status === 'COMPLETED') {
    return taskStatus.value.storageValidation?.isConsistent ? 'success' : 'warning'
  }
  if (taskStatus.value.status === 'FAILED') return 'exception'
  return null
})

const validationType = computed(() => {
  if (!taskStatus.value.storageValidation) return 'info'
  return taskStatus.value.storageValidation.isConsistent ? 'success' : 'warning'
})

// 选择文件
const handleFileSelect = async (file) => {
  selectedFile.value = file
  previewResult.value = null
  currentStep.value = 1

  // 开始预览校验
  try {
    const formData = new FormData()
    formData.append('file', file.raw)
    
    const response = await axios.post('/api/csv/preview', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    previewResult.value = response.data
    currentStep.value = 2
  } catch (error) {
    console.error('预览失败:', error)
  }
}

// 上传并处理
const handleUploadAndProcess = async () => {
  processing.value = true
  currentStep.value = 3

  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value.raw)
    formData.append('tableName', form.tableName)

    const response = await axios.post('/api/csv/upload-async', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })

    const taskId = response.data.taskId
    
    // 开始轮询状态
    startPolling(taskId)

  } catch (error) {
    processing.value = false
  }
}

// 轮询任务状态
const startPolling = (taskId) => {
  pollTimer.value = setInterval(async () => {
    try {
      const response = await axios.get('/api/csv/task-status', {
        params: { taskId }
      })
      taskStatus.value = response.data

      if (['COMPLETED', 'FAILED'].includes(response.data.status)) {
        clearInterval(pollTimer.value)
        processing.value = false
      }
    } catch (error) {
      console.error('查询状态失败:', error)
    }
  }, 500)
}

// 格式化时间
const formatDuration = (ms) => {
  if (!ms) return '0s'
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

watch(() => currentStep.value, () => {
  // 清理定时器
  if (currentStep.value < 3 && pollTimer.value) {
    clearInterval(pollTimer.value)
  }
})
</script>

<style scoped>
.upload-page {
  max-width: 1200px;
  margin: 0 auto;
}

.card-header {
  font-size: 20px;
  font-weight: bold;
}

.step-section {
  margin-top: 30px;
  padding: 20px;
  background-color: #f5f7fa;
  border-radius: 8px;
}

.step-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
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

.stat-card.total {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.stat-card.valid {
  background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
}

.stat-card.invalid {
  background: linear-gradient(135deg, #eb3349 0%, #f45c43 100%);
}

.stat-card.columns {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.stat-card.process {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.stat-card.success {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.stat-card.time {
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

.validation-alert {
  margin-bottom: 20px;
}

.columns-section {
  margin: 20px 0;
}

.columns-section h4 {
  margin-bottom: 10px;
}

.preview-section {
  margin: 20px 0;
}

.preview-section h4 {
  margin-bottom: 10px;
}

.action-buttons {
  margin-top: 30px;
  text-align: center;
}

.validation-result {
  margin: 20px 0;
}

.validation-details {
  display: flex;
  gap: 30px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.validation-details span {
  font-size: 14px;
}

.percentage-value {
  font-size: 18px;
  font-weight: bold;
  color: #409eff;
}

.upload-demo {
  margin-top: 10px;
}
</style>
