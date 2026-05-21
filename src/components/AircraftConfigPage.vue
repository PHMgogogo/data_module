<template>
  <div class="config-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>飞机构型配置与管理</span>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <!-- ========== TAB 1: 机型管理 ========== -->
        <el-tab-pane label="机型管理" name="models">
          <div class="toolbar">
            <el-button type="primary" @click="showModelDialog = true">新增机型</el-button>
          </div>
          <el-table :data="models" border stripe style="width: 100%">
            <el-table-column prop="modelCode" label="机型代码" width="150" />
            <el-table-column prop="manufacturer" label="制造商" width="200" />
            <el-table-column prop="description" label="描述" />
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="scope">
                <el-button type="danger" size="small" @click="handleDeleteModel(scope.row.modelCode)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-dialog v-model="showModelDialog" title="新增机型" width="500px">
            <el-form :model="modelForm" label-width="100px">
              <el-form-item label="机型代码" required>
                <el-input v-model="modelForm.modelCode" placeholder="e.g. B737-800" />
              </el-form-item>
              <el-form-item label="制造商">
                <el-input v-model="modelForm.manufacturer" placeholder="e.g. Boeing" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="modelForm.description" type="textarea" />
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="showModelDialog = false">取消</el-button>
              <el-button type="primary" @click="handleAddModel" :loading="saving">确定</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <!-- ========== TAB 2: 飞机构型 ========== -->
        <el-tab-pane label="飞机构型" name="configs">
          <div class="toolbar">
            <el-select v-model="configFilterModel" placeholder="筛选机型" clearable style="width: 200px; margin-right: 10px" @change="loadConfigs">
              <el-option v-for="m in models" :key="m.modelCode" :label="m.modelCode" :value="m.modelCode" />
            </el-select>
            <el-button type="primary" @click="showConfigDialog = true" :disabled="models.length === 0">新增构型</el-button>
          </div>
          <el-table :data="configs" border stripe style="width: 100%">
            <el-table-column prop="tailNumber" label="机号" width="120" />
            <el-table-column prop="modelCode" label="机型" width="120" />
            <el-table-column prop="airline" label="所属航司" width="150" />
            <el-table-column prop="configVersion" label="构型版本" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="scope">
                <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'">
                  {{ scope.row.status === 'active' ? '在用' : scope.row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="scope">
                <el-button type="danger" size="small" @click="handleDeleteConfig(scope.row.tailNumber)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-dialog v-model="showConfigDialog" title="新增飞机构型" width="500px">
            <el-form :model="configForm" label-width="100px">
              <el-form-item label="机型" required>
                <el-select v-model="configForm.modelCode" style="width: 100%">
                  <el-option v-for="m in models" :key="m.modelCode" :label="m.modelCode" :value="m.modelCode" />
                </el-select>
              </el-form-item>
              <el-form-item label="机号" required>
                <el-input v-model="configForm.tailNumber" placeholder="e.g. B-1234" />
              </el-form-item>
              <el-form-item label="所属航司">
                <el-input v-model="configForm.airline" />
              </el-form-item>
              <el-form-item label="构型版本">
                <el-input v-model="configForm.configVersion" />
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="showConfigDialog = false">取消</el-button>
              <el-button type="primary" @click="handleAddConfig" :loading="saving">确定</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <!-- ========== TAB 3: 构型项目 ========== -->
        <el-tab-pane label="构型项目" name="items">
          <div class="toolbar">
            <el-select v-model="itemFilterModel" placeholder="筛选机型" clearable style="width: 200px; margin-right: 10px" @change="loadItems">
              <el-option v-for="m in models" :key="m.modelCode" :label="m.modelCode" :value="m.modelCode" />
            </el-select>
            <el-button type="primary" @click="showItemDialog = true" :disabled="!itemFilterModel">新增构型项目</el-button>
          </div>

          <el-row :gutter="20">
            <el-col :span="12">
              <h4>构型树（ATA章节）</h4>
              <el-tree
                :data="configTree"
                :props="treeProps"
                default-expand-all
                highlight-current
                @node-click="handleTreeNodeClick"
                style="margin-top: 10px"
              />
            </el-col>
            <el-col :span="12">
              <h4>项目列表</h4>
              <el-table :data="configItems" border stripe style="width: 100%; margin-top: 10px" max-height="400">
                <el-table-column prop="ataChapter" label="ATA" width="80" />
                <el-table-column prop="systemName" label="系统" width="120" />
                <el-table-column prop="subSystemName" label="子系统" width="120" />
                <el-table-column prop="equipmentName" label="设备" />
                <el-table-column prop="partNumber" label="件号" width="120" />
                <el-table-column prop="itemType" label="类型" width="100">
                  <template #default="scope">
                    <el-tag :type="scope.row.itemType === 'SYSTEM' ? 'primary' : scope.row.itemType === 'SUBSYSTEM' ? 'warning' : 'info'" size="small">
                      {{ scope.row.itemType }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="scope">
                    <el-button type="danger" size="small" @click="handleDeleteItem(scope.row.itemId)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-col>
          </el-row>

          <el-dialog v-model="showItemDialog" title="新增构型项目" width="550px">
            <el-form :model="itemForm" label-width="110px">
              <el-form-item label="机型" required>
                <span>{{ itemFilterModel }}</span>
              </el-form-item>
              <el-form-item label="父级项目">
                <el-select v-model="itemForm.parentItemId" clearable placeholder="留空则为顶层系统" style="width: 100%">
                  <el-option
                    v-for="item in configItems"
                    :key="item.itemId"
                    :label="getItemLabel(item)"
                    :value="item.itemId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="ATA章节号">
                <el-input v-model="itemForm.ataChapter" placeholder="e.g. 72-00" style="width: 150px" />
              </el-form-item>
              <el-form-item label="系统名称">
                <el-input v-model="itemForm.systemName" placeholder="e.g. 发动机" />
              </el-form-item>
              <el-form-item label="子系统名称">
                <el-input v-model="itemForm.subSystemName" placeholder="e.g. 低压压气机" />
              </el-form-item>
              <el-form-item label="设备名称">
                <el-input v-model="itemForm.equipmentName" placeholder="e.g. 振动传感器" />
              </el-form-item>
              <el-form-item label="件号">
                <el-input v-model="itemForm.partNumber" placeholder="e.g. P/N 12345" />
              </el-form-item>
              <el-form-item label="类型" required>
                <el-select v-model="itemForm.itemType">
                  <el-option label="系统 SYSTEM" value="SYSTEM" />
                  <el-option label="子系统 SUBSYSTEM" value="SUBSYSTEM" />
                  <el-option label="设备 EQUIPMENT" value="EQUIPMENT" />
                  <el-option label="LRU" value="LRU" />
                </el-select>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="showItemDialog = false">取消</el-button>
              <el-button type="primary" @click="handleAddItem" :loading="saving">确定</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>
      </el-tabs>

      <el-alert v-if="message.show" :title="message.content" :type="message.type" show-icon class="message-alert" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import axios from 'axios'

const activeTab = ref('models')

// 机型
const models = ref([])
const showModelDialog = ref(false)
const modelForm = reactive({ modelCode: '', manufacturer: '', description: '' })

// 飞机构型
const configs = ref([])
const configFilterModel = ref('')
const showConfigDialog = ref(false)
const configForm = reactive({ modelCode: '', tailNumber: '', airline: '', configVersion: '' })

// 构型项目
const configItems = ref([])
const configTree = ref([])
const itemFilterModel = ref('')
const showItemDialog = ref(false)
const itemForm = reactive({
  parentItemId: null, ataChapter: '', systemName: '',
  subSystemName: '', equipmentName: '', partNumber: '', itemType: 'EQUIPMENT'
})

const saving = ref(false)

const message = reactive({ show: false, content: '', type: 'success' })

const treeProps = { children: 'children', label: 'label' }

const showMsg = (content, type = 'success') => {
  message.show = true
  message.content = content
  message.type = type
  setTimeout(() => { message.show = false }, 3000)
}

// 获取项目显示标签
const getItemLabel = (item) => {
  const parts = []
  if (item.ataChapter) parts.push(`[${item.ataChapter}]`)
  if (item.systemName) parts.push(item.systemName)
  if (item.subSystemName) parts.push(item.subSystemName)
  if (item.equipmentName) parts.push(item.equipmentName)
  return parts.join(' > ') || `ID:${item.itemId}`
}

// 加载机型
const loadModels = async () => {
  try {
    const res = await axios.get('/api/aircraft/models')
    models.value = res.data
  } catch (e) {
    console.error('加载机型失败', e)
  }
}

// 新增机型
const handleAddModel = async () => {
  if (!modelForm.modelCode) { showMsg('请输入机型代码', 'warning'); return }
  saving.value = true
  try {
    await axios.post('/api/aircraft/models', { ...modelForm })
    showModelDialog.value = false
    modelForm.modelCode = ''; modelForm.manufacturer = ''; modelForm.description = ''
    showMsg('机型添加成功')
    loadModels()
  } catch (e) {
    showMsg(e.response?.data?.error || '添加失败', 'error')
  } finally {
    saving.value = false
  }
}

// 删除机型
const handleDeleteModel = async (code) => {
  try {
    await axios.delete(`/api/aircraft/models/${code}`)
    showMsg('机型已删除')
    loadModels()
  } catch (e) {
    showMsg(e.response?.data?.error || '删除失败', 'error')
  }
}

// 加载构型
const loadConfigs = async () => {
  try {
    const params = configFilterModel.value ? { modelCode: configFilterModel.value } : {}
    const res = await axios.get('/api/aircraft/configs', { params })
    configs.value = res.data
  } catch (e) {
    console.error('加载构型失败', e)
  }
}

// 新增构型
const handleAddConfig = async () => {
  if (!configForm.tailNumber || !configForm.modelCode) { showMsg('请填写完整信息', 'warning'); return }
  saving.value = true
  try {
    await axios.post('/api/aircraft/configs', { ...configForm, status: 'active' })
    showConfigDialog.value = false
    configForm.tailNumber = ''; configForm.airline = ''; configForm.configVersion = ''
    showMsg('构型添加成功')
    loadConfigs()
  } catch (e) {
    showMsg(e.response?.data?.error || '添加失败', 'error')
  } finally {
    saving.value = false
  }
}

// 删除构型
const handleDeleteConfig = async (tailNumber) => {
  try {
    await axios.delete(`/api/aircraft/configs/${tailNumber}`)
    showMsg('构型已删除')
    loadConfigs()
  } catch (e) {
    showMsg(e.response?.data?.error || '删除失败', 'error')
  }
}

// 加载构型项目
const loadItems = async () => {
  if (!itemFilterModel.value) {
    configItems.value = []
    configTree.value = []
    return
  }
  try {
    const [listRes, treeRes] = await Promise.all([
      axios.get('/api/aircraft/config-items', { params: { modelCode: itemFilterModel.value } }),
      axios.get('/api/aircraft/config-items/tree', { params: { modelCode: itemFilterModel.value } })
    ])
    configItems.value = listRes.data
    configTree.value = buildTreeDisplay(treeRes.data)
  } catch (e) {
    console.error('加载构型项目失败', e)
  }
}

// 构建树展示格式（加入label字段供 el-tree 显示）
const buildTreeDisplay = (nodes) => {
  return nodes.map(node => ({
    ...node,
    label: getItemLabelFromMap(node),
    children: node.children ? buildTreeDisplay(node.children) : []
  }))
}

const getItemLabelFromMap = (node) => {
  const parts = []
  if (node.ataChapter) parts.push(`[${node.ataChapter}]`)
  if (node.systemName) parts.push(node.systemName)
  if (node.subSystemName) parts.push(node.subSystemName)
  if (node.equipmentName) parts.push(node.equipmentName)
  if (node.itemType) parts.push(`(${node.itemType})`)
  return parts.join(' ') || `ID:${node.itemId}`
}

// 树节点点击
const handleTreeNodeClick = (node) => {
  // 选中树节点时高亮对应的表格行（简化处理：可在此执行额外逻辑）
}

// 新增构型项目
const handleAddItem = async () => {
  if (!itemForm.systemName && !itemForm.equipmentName) { showMsg('请填写系统或设备名称', 'warning'); return }
  saving.value = true
  try {
    await axios.post('/api/aircraft/config-items', {
      ...itemForm,
      modelCode: itemFilterModel.value
    })
    showItemDialog.value = false
    itemForm.parentItemId = null; itemForm.ataChapter = ''; itemForm.systemName = ''
    itemForm.subSystemName = ''; itemForm.equipmentName = ''; itemForm.partNumber = ''
    itemForm.itemType = 'EQUIPMENT'
    showMsg('构型项目添加成功')
    loadItems()
  } catch (e) {
    showMsg(e.response?.data?.error || '添加失败', 'error')
  } finally {
    saving.value = false
  }
}

// 删除构型项目
const handleDeleteItem = async (itemId) => {
  try {
    await axios.delete(`/api/aircraft/config-items/${itemId}`)
    showMsg('构型项目已删除')
    loadItems()
  } catch (e) {
    showMsg(e.response?.data?.error || '删除失败', 'error')
  }
}

onMounted(() => {
  loadModels()
})

watch(activeTab, (tab) => {
  if (tab === 'configs') loadConfigs()
  if (tab === 'items' && itemFilterModel.value) loadItems()
})
</script>

<style scoped>
.config-page {
  max-width: 1400px;
  margin: 0 auto;
}
.card-header {
  font-size: 18px;
  font-weight: bold;
}
.toolbar {
  margin-bottom: 15px;
  display: flex;
  align-items: center;
}
.message-alert {
  margin-top: 20px;
}
h4 {
  margin: 0 0 5px 0;
  color: #606266;
}
</style>
