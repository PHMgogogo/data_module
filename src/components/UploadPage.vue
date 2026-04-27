<template>
  <div class="upload-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>上传CSV文件</span>
        </div>
      </template>
      
      <el-form :model="form" label-width="80px" class="upload-form">
        <el-form-item label="选择文件">
          <el-upload
            class="upload-demo"
            :auto-upload="false"
            :on-change="handleFileChange"
            :limit="1"
            :file-list="fileList"
            accept=".csv"
          >
            <el-button type="primary">
              <el-icon><Upload /></el-icon>
              选择CSV文件
            </el-button>
            <template #tip>
              <div class="el-upload__tip">
                请选择UTF-8编码的CSV文件，首行为列名
              </div>
            </template>
          </el-upload>
        </el-form-item>
        
        <el-form-item label="表名" prop="tableName" :rules="[{ required: true, message: '请输入表名', trigger: 'blur' }, { pattern: /^csv_/i, message: '表名必须以csv_开头', trigger: 'blur' }]">
          <el-input v-model="form.tableName" placeholder="请输入表名，如 csv_user" />
        </el-form-item>
        
        <el-form-item>
          <el-button 
            type="primary" 
            @click="handleUpload" 
            :loading="uploading"
            :disabled="!canUpload"
          >
            {{ uploading ? '上传中...' : '上传并入库' }}
          </el-button>
        </el-form-item>
      </el-form>
      
      <!-- 上传进度显示 -->
      <div v-if="showProgress" class="progress-section">
        <div class="progress-info">
          <span class="status-badge" :class="statusClass">{{ statusText }}</span>
        </div>
        
        <el-progress 
          :percentage="progressPercent" 
          :status="progressStatus"
          style="margin-top: 10px"
        />
        
        <div class="progress-stats" v-if="taskInfo">
          <el-row :gutter="20">
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-label">总条数</div>
                <div class="stat-value">{{ taskInfo.totalRows }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-label">已处理</div>
                <div class="stat-value">{{ taskInfo.processedRows }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-label">成功</div>
                <div class="stat-value success">{{ taskInfo.successRows }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="stat-item">
                <div class="stat-label">失败</div>
                <div class="stat-value error">{{ taskInfo.failedRows }}</div>
              </div>
            </el-col>
          </el-row>
        </div>
      </div>
      
      <el-alert
        v-if="result.show"
        :title="result.message"
        :type="result.type"
        show-icon
        class="result-alert"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import axios from 'axios'

const form = reactive({
  tableName: ''
})

const fileList = ref([])
const uploading = ref(false)
const showProgress = ref(false)
const progressPercent = ref(0)
const progressStatus = ref('') // '', 'success', 'exception', 'warning'
const currentTaskId = ref('')
const pollInterval = ref(null)
const taskInfo = ref(null)

const result = reactive({
  show: false,
  message: '',
  type: 'success'
})

const canUpload = computed(() => {
  return fileList.value.length > 0 && form.tableName && form.tableName.toLowerCase().startsWith('csv_') && !uploading.value
})

const statusClass = computed(() => {
  if (!taskInfo.value) return 'pending'
  switch (taskInfo.value.status) {
    case 'PENDING': return 'pending'
    case 'PROCESSING': return 'processing'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'error'
    default: return 'pending'
  }
})

const statusText = computed(() => {
  if (!taskInfo.value) return '等待中'
  switch (taskInfo.value.status) {
    case 'PENDING': return '等待处理'
    case 'PROCESSING': return '正在处理'
    case 'COMPLETED': return '处理完成'
    case 'FAILED': return '处理失败'
    default: return '等待中'
  }
})

const handleFileChange = (file) => {
  fileList.value = [file]
}

const pollTaskStatus = async () => {
  if (!currentTaskId.value) return
  
  try {
    const response = await axios.get('/api/csv/task-status', {
      params: { taskId: currentTaskId.value }
    })
    taskInfo.value = response.data
    progressPercent.value = response.data.progress || 0
    
    // 更新进度条状态
    if (response.data.status === 'COMPLETED') {
      progressStatus.value = 'success'
      result.show = true
      result.message = response.data.message || '上传成功！'
      result.type = 'success'
      stopPolling()
      uploading.value = false
    } else if (response.data.status === 'FAILED') {
      progressStatus.value = 'exception'
      result.show = true
      result.message = response.data.message || '上传失败'
      result.type = 'error'
      stopPolling()
      uploading.value = false
    }
  } catch (error) {
    console.error('查询任务状态失败', error)
  }
}

const stopPolling = () => {
  if (pollInterval.value) {
    clearInterval(pollInterval.value)
    pollInterval.value = null
  }
}

const handleUpload = async () => {
  if (fileList.value.length === 0) {
    result.show = true
    result.message = '请选择CSV文件'
    result.type = 'warning'
    return
  }

  const formData = new FormData()
  formData.append('file', fileList.value[0].raw)
  formData.append('tableName', form.tableName)

  uploading.value = true
  showProgress.value = true
  progressPercent.value = 0
  progressStatus.value = ''
  result.show = false

  try {
    const response = await axios.post('/api/csv/upload-async', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })

    currentTaskId.value = response.data.taskId
    
    // 开始轮询任务状态
    pollInterval.value = setInterval(pollTaskStatus, 1000)
    
  } catch (error) {
    uploading.value = false
    progressStatus.value = 'exception'
    result.show = true
    result.message = error.response?.data?.error || '上传失败，请重试'
    result.type = 'error'
  }
}

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.upload-page {
  max-width: 800px;
  margin: 0 auto;
}

.card-header {
  font-size: 18px;
  font-weight: bold;
}

.upload-form {
  margin-top: 20px;
}

.progress-section {
  margin-top: 20px;
  padding: 20px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.progress-info {
  margin-bottom: 10px;
}

.status-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: bold;
}

.status-badge.pending {
  background-color: #e6a23c;
  color: white;
}

.status-badge.processing {
  background-color: #409eff;
  color: white;
}

.status-badge.success {
  background-color: #67c23a;
  color: white;
}

.status-badge.error {
  background-color: #f56c6c;
  color: white;
}

.progress-stats {
  margin-top: 20px;
}

.stat-item {
  text-align: center;
  padding: 10px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 5px;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.stat-value.success {
  color: #67c23a;
}

.stat-value.error {
  color: #f56c6c;
}

.result-alert {
  margin-top: 20px;
}
</style>
