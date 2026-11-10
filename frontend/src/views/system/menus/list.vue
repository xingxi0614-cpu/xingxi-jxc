<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">菜单管理</div>
        <div class="page-sub">查看系统菜单、路由组件与权限码</div>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-card shadow="never">
      <el-tree :data="menus" :props="{ label: 'name', children: 'children' }" v-loading="loading" default-expand-all node-key="id">
        <template #default="{ node, data }">
          <span>{{ node.label }}
            <el-tag size="small" type="info" style="margin-left:8px">{{ data.type }}</el-tag>
            <span v-if="data.path" style="font-size:12px;color:var(--text-3);margin-left:8px">{{ data.path }}</span>
            <span v-if="data.permCode" style="font-size:12px;color:var(--text-3);margin-left:8px">{{ data.permCode }}</span>
          </span>
        </template>
      </el-tree>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { sysApi } from '@/api'

const loading = ref(false)
const menus = ref<any[]>([])
async function load() {
  loading.value = true
  try { menus.value = await sysApi.menuTree() as any } finally { loading.value = false }
}
onMounted(load)
</script>
