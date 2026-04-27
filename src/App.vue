<template>
  <div class="app">
    <el-container>
      <el-header height="60px" class="header">
        <h1>CSV上传解析与数据管理系统</h1>
      </el-header>
      <el-container>
        <el-aside width="200px" class="aside">
          <el-menu
            :default-active="activeMenu"
            class="menu"
            @select="handleMenuSelect"
          >
            <el-menu-item index="upload">
              <el-icon><Upload /></el-icon>
              <span>上传CSV</span>
            </el-menu-item>
            <el-menu-item index="manage">
              <el-icon><DataAnalysis /></el-icon>
              <span>数据管理</span>
            </el-menu-item>
          </el-menu>
        </el-aside>
        <el-main class="main">
          <UploadPage v-if="activeMenu === 'upload'" />
          <ManagePage v-else-if="activeMenu === 'manage'" />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Upload, DataAnalysis } from '@element-plus/icons-vue'
import UploadPage from './components/UploadPage.vue'
import ManagePage from './components/ManagePage.vue'

const activeMenu = ref('upload')

const handleMenuSelect = (key) => {
  activeMenu.value = key
}
</script>

<style scoped>
.app {
  height: 100vh;
  overflow: hidden;
}

.header {
  background-color: #409EFF;
  color: white;
  display: flex;
  align-items: center;
  padding-left: 20px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.header h1 {
  font-size: 20px;
  margin: 0;
}

.aside {
  background-color: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}

.menu {
  height: 100%;
  border-right: none;
}

.main {
  padding: 20px;
  overflow-y: auto;
}
</style>
