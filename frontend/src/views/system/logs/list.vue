<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">操作日志</div>
        <div class="page-sub">所有写操作均通过 OpLog AOP 自动记录</div>
      </div>
      <el-space><el-button @click="load">刷新</el-button></el-space>
    </div>

    <el-card shadow="never">
      <QueryDrawer :model="query" @search="load" @reset="resetQuery">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="username" label="用户名"><el-input v-model="query.username" clearable style="width:140px" /></el-form-item>
        <el-form-item data-q="module" label="模块"><el-input v-model="query.module" clearable style="width:140px" /></el-form-item>
        <el-form-item data-q="docNo" label="单据"><el-input v-model="query.docNo" clearable style="width:160px" /></el-form-item>
        <el-form-item data-q="success" label="状态">
          <el-select v-model="query.success" clearable placeholder="全部" style="width:100px">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading">
        <el-table-column prop="createdAt"  label="时间"     width="160" :formatter="$fmtMinute" />
        <el-table-column prop="username"   label="用户"     width="100" />
        <el-table-column prop="module"     label="模块"     width="120" />
        <el-table-column prop="action"     label="动作"     width="100" />
        <el-table-column prop="docNo"      label="单据"     width="160" />
        <el-table-column prop="ip"         label="IP"      width="120" />
        <el-table-column prop="requestUri" label="接口"    min-width="220" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" min-width="200" />
      </el-table>
      <el-pagination v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     layout="total, prev, pager, next" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { get } from '@/api/request'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive<any>({ current: 1, size: 20, username: '', module: '', docNo: '', success: null })

async function load() {
  loading.value = true
  try {
    const r: any = await get('/sys/logs', query)
    rows.value = r.records
    total.value = r.total
  } finally { loading.value = false }
}
function resetQuery() {
  Object.assign(query, { current: 1, size: 20, username: '', module: '', docNo: '', success: null })
  load()
}
onMounted(load)
</script>
