<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">单据编码规则</div>
        <div class="page-sub">系统按单据类型自动生成连续编号</div>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading">
        <el-table-column label="单据类型" width="120">
          <template #default="{ row }">{{ docTypeText(row.docType) }}</template>
        </el-table-column>
        <el-table-column prop="prefix" label="前缀" width="100" />
        <el-table-column prop="pattern" label="规则" min-width="190" />
        <el-table-column prop="currentNo" label="当前流水" width="120" align="right" />
        <el-table-column label="重置周期" width="120">
          <template #default="{ row }">{{ resetFreqText(row.resetFreq) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { sysApi } from '@/api'

const loading = ref(false)
const rows = ref<any[]>([])

function docTypeText(type: string) {
  const map: Record<string, string> = {
    PO: '采购订单',
    RECV: '采购入库',
    INV: '采购发票',
    OREQ: '出库申请',
    OREQ_REV: '申请修订',
    OUT: '材料出库',
    RETURN: '退料',
    LOAN: '借料',
    TRANSFER: '库存转库'
  }
  return map[type] || type || '-'
}

function resetFreqText(freq: string) {
  const map: Record<string, string> = {
    DAILY: '按日',
    MONTHLY: '按月',
    YEARLY: '按年',
    NEVER: '不重置'
  }
  return map[freq] || freq || '-'
}

async function load() {
  loading.value = true
  try {
    const data = await sysApi.docRules() as any[]
    rows.value = (data || []).filter(row => row.docType !== 'PR')
  } finally { loading.value = false }
}
onMounted(load)
</script>
