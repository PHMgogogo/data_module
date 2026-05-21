<template>
  <div class="upload-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>上传CSV — 自动识别列属性并绑定飞机构型</span>
        </div>
      </template>

      <!-- 步骤1: 选择文件 -->
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
          <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
          <template #tip>
            <div class="el-upload__tip">支持UTF-8编码的CSV文件</div>
          </template>
        </el-upload>
      </div>

      <!-- 步骤2: 列属性分析结果 + 飞机构型关联 -->
      <div class="step-section" v-if="analysisResult">
        <div class="step-title">
          <el-tag :type="currentStep >= 2 ? 'primary' : 'info'" size="large">步骤2</el-tag>
          列属性分析 & 飞机构型关联
        </div>

        <!-- 列类型统计 -->
        <el-row :gutter="20" class="stats-row">
          <el-col :span="6">
            <div class="stat-card columns">
              <div class="stat-value">{{ analysisResult.columns.length }}</div>
              <div class="stat-label">总列数</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card valid">
              <div class="stat-value">{{ analysisResult.numericColumns.length }}</div>
              <div class="stat-label">数值列</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card total">
              <div class="stat-value">{{ analysisResult.textColumns.length }}</div>
              <div class="stat-label">文本列</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card time">
              <div class="stat-value">{{ analysisResult.totalRows }}</div>
              <div class="stat-label">数据行数</div>
            </div>
          </el-col>
        </el-row>

        <!-- 列详细信息 -->
        <el-table :data="columnTableData" border stripe size="small" style="width: 100%; margin-bottom: 20px" max-height="250">
          <el-table-column prop="name" label="列名" width="180" />
          <el-table-column prop="type" label="推断类型" width="120">
            <template #default="scope">
              <el-tag :type="scope.row.type === 'TEXT' ? 'info' : 'success'" size="small">{{ scope.row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="timestampBadge" label="时间戳" width="100">
            <template #default="scope">
              <el-tag v-if="scope.row.isTimestamp" type="warning" size="small">时间列</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sample" label="样本值" />
        </el-table>

        <!-- 飞机构型关联 -->
        <el-divider content-position="left">飞机构型关联</el-divider>
        <el-form :model="form" label-width="100px">
          <el-row :gutter="10">
            <el-col :span="8">
              <el-form-item label="机型" required>
                <el-select v-model="form.modelCode" style="width: 100%" @change="handleModelChange">
                  <el-option v-for="m in models" :key="m.modelCode" :label="m.modelCode" :value="m.modelCode" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="机号" required>
                <el-select v-model="form.tailNumber" style="width: 100%" :disabled="!form.modelCode" @change="handleTailChange">
                  <el-option v-for="tn in tailNumbers" :key="tn" :label="tn" :value="tn" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="数据类型">
                <el-select v-model="form.dataType" style="width: 100%">
                  <el-option label="原始数据 RAW" value="RAW" />
                  <el-option label="诊断 DIAGNOSIS" value="DIAGNOSIS" />
                  <el-option label="评价 EVALUATION" value="EVALUATION" />
                  <el-option label="预测 PREDICTION" value="PREDICTION" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="10" style="margin-top: 10px">
            <el-col :span="12">
              <el-form-item label="设备名">
                <el-input v-model="form.tableName" placeholder="自动生成或手动输入（不含csv_前缀）" style="width: 100%">
                  <template #prepend>csv_</template>
                </el-input>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="所属设备">
                <el-tree-select
                  v-model="form.parentItemId"
                  :data="configTree"
                  :props="{ label: 'label', value: 'itemId', children: 'children' }"
                  placeholder="选择所属设备/系统"
                  clearable
                  filterable
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>

        <div style="text-align: center; margin-top: 20px">
          <el-button type="primary" size="large" @click="handleUpload"
            :loading="uploading" :disabled="!form.tailNumber || !form.tableName">
            开始上传
          </el-button>
        </div>
      </div>

      <!-- 步骤3: 上传结果 -->
      <div class="step-section" v-if="uploadResult">
        <div class="step-title">
          <el-tag type="success" size="large">✓</el-tag>
          上传结果
        </div>
        <el-alert title="数据已写入达梦数据库" type="success" show-icon style="margin-bottom: 20px" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备名">{{ uploadResult.deviceName || form.tableName }}</el-descriptions-item>
          <el-descriptions-item label="机号">{{ uploadResult.tailNumber || form.tailNumber }}</el-descriptions-item>
          <el-descriptions-item label="总行数">{{ uploadResult.totalCount }}</el-descriptions-item>
          <el-descriptions-item label="成功导入">{{ uploadResult.successCount }}</el-descriptions-item>
        </el-descriptions>
        <el-button type="primary" style="margin-top: 20px" @click="resetForm">继续上传</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'

const selectedFile = ref(null)
const currentStep = ref(1)
const analysisResult = ref(null)
const columnTableData = ref([])
const uploading = ref(false)
const uploadResult = ref(null)

// 飞机构型数据
const models = ref([])
const tailNumbers = ref([])
const configTree = ref([])

const form = reactive({
  modelCode: '',
  tailNumber: '',
  tableName: '',
  parentItemId: null,
  dataType: 'RAW'
})

// 选择文件 → 分析列属性
const handleFileSelect = async (file) => {
  selectedFile.value = file
  analysisResult.value = null
  columnTableData.value = []
  uploadResult.value = null
  currentStep.value = 1

  // 重置飞机表单
  form.modelCode = ''
  form.tailNumber = ''
  form.tableName = ''
  form.parentItemId = null
  form.dataType = 'RAW'

  // 加载机型
  try {
    const res = await axios.get('/api/aircraft/models')
    models.value = res.data
  } catch (e) {
    console.error('加载机型失败', e)
  }

  // 分析列属性
  try {
    const formData = new FormData()
    formData.append('file', file.raw)
    const res = await axios.post('/api/csv/analyze-columns', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    analysisResult.value = res.data
    currentStep.value = 2

    // 构建列表格数据
    const tableData = []
    const cols = res.data.columns || []
    for (let i = 0; i < cols.length; i++) {
      const col = cols[i]
      const isNumeric = (res.data.numericColumns || []).includes(col)
      const isTimestamp = col === res.data.timestampColumn
      const sampleRow = res.data.sampleData && res.data.sampleData.length > 0 ? res.data.sampleData[0] : {}
      tableData.push({
        name: col,
        type: res.data.columnTypes ? res.data.columnTypes[col] : 'TEXT',
        isTimestamp: isTimestamp,
        sample: sampleRow[col] || ''
      })
    }
    columnTableData.value = tableData

    // 默认设备名取自文件名
    if (file.raw) {
      const name = file.raw.name.replace(/\.csv$/i, '').toLowerCase().replace(/[^a-z0-9_]/g, '_')
      form.tableName = name
    }
  } catch (e) {
    console.error('列分析失败', e)
  }
}

// 机型变更
const handleModelChange = async (modelCode) => {
  form.tailNumber = ''
  form.parentItemId = null
  tailNumbers.value = []
  configTree.value = []
  if (!modelCode) return
  try {
    const [tnRes, treeRes] = await Promise.all([
      axios.get('/api/aircraft/tail-numbers', { params: { modelCode } }),
      axios.get('/api/aircraft/config-items/tree', { params: { modelCode } })
    ])
    tailNumbers.value = tnRes.data
    configTree.value = buildTreeDisplay(treeRes.data)
  } catch (e) {
    console.error('加载构型数据失败', e)
  }
}

// 机号变更
const handleTailChange = (tailNumber) => {
  // 可在此处根据机号自动填充设备名
}

const buildTreeDisplay = (nodes) => {
  if (!nodes) return []
  return nodes.map(node => ({
    ...node,
    label: getNodeLabel(node),
    children: node.children ? buildTreeDisplay(node.children) : []
  }))
}

const getNodeLabel = (node) => {
  const parts = []
  if (node.ataChapter) parts.push(`[${node.ataChapter}]`)
  if (node.systemName) parts.push(node.systemName)
  if (node.subSystemName) parts.push(node.subSystemName)
  if (node.equipmentName) parts.push(node.equipmentName)
  return parts.join(' > ') || `ID:${node.itemId}`
}

// 上传
const handleUpload = async () => {
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value.raw)
    formData.append('tableName', form.tableName)
    formData.append('tailNumber', form.tailNumber)
    if (form.parentItemId) formData.append('parentItemId', form.parentItemId)
    formData.append('dataType', form.dataType)

    const res = await axios.post('/api/csv/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    uploadResult.value = res.data
    currentStep.value = 3
  } catch (e) {
    console.error('上传失败', e)
  } finally {
    uploading.value = false
  }
}

const resetForm = () => {
  selectedFile.value = null
  analysisResult.value = null
  columnTableData.value = []
  uploadResult.value = null
  currentStep.value = 1
  form.modelCode = ''
  form.tailNumber = ''
  form.tableName = ''
  form.parentItemId = null
  form.dataType = 'RAW'
}
</script>

<style scoped>
.upload-page { max-width: 1400px; margin: 0 auto; }
.card-header { font-size: 18px; font-weight: bold; }
.step-section { margin-top: 30px; padding: 20px; background-color: #f5f7fa; border-radius: 8px; }
.step-title { font-size: 16px; font-weight: bold; margin-bottom: 20px; display: flex; align-items: center; gap: 10px; }
.stats-row { margin-bottom: 20px; }
.stat-card { padding: 20px; border-radius: 8px; text-align: center; color: white; }
.stat-card.total { background: linear-gradient(135deg, #667eea, #764ba2); }
.stat-card.valid { background: linear-gradient(135deg, #11998e, #38ef7d); }
.stat-card.columns { background: linear-gradient(135deg, #f093fb, #f5576c); }
.stat-card.time { background: linear-gradient(135deg, #4facfe, #00f2fe); }
.stat-value { font-size: 32px; font-weight: bold; }
.stat-label { font-size: 14px; opacity: 0.9; margin-top: 5px; }
.upload-demo { margin-top: 10px; }
</style>
