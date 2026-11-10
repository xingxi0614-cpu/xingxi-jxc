<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">审批流配置</div>
        <div class="page-sub">按岗位角色配置采购、入库、发票、出库与转库审批；流程不绑定固定人员姓名</div>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading">
        <el-table-column label="单据类型" width="120">
          <template #default="{ row }">{{ bizTypeText(row.bizType) }}</template>
        </el-table-column>
        <el-table-column label="名称" prop="name" min-width="160" />
        <el-table-column label="触发条件" prop="conditionExpr" min-width="160">
          <template #default="{ row }">{{ row.conditionExpr || '所有' }}</template>
        </el-table-column>
        <el-table-column label="审批节点" min-width="340">
          <template #default="{ row }">{{ nodesText(row.nodesJson) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
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

function nodesText(raw: string) {
  try {
    return JSON.parse(raw || '[]')
      .map((n: any) => roleText(n.approverRole) || n.name || '-')
      .join(' -> ')
  } catch {
    return raw || '-'
  }
}

function bizTypeText(type: string) {
  const map: Record<string, string> = {
    PO: '采购订单',
    RECV: '采购入库',
    INV: '采购发票',
    OREQ: '出库申请',
    OUT: '材料出库',
    OREQ_REV: '出库申请修订',
    RETURN: '退料',
    TRANSFER: '库存转库'
  }
  return map[type] || type || '-'
}

function roleText(role?: string) {
  if (!role) return ''
  const map: Record<string, string> = {
    purchaser: '采购',
    warehouse: '仓管',
    finance: '材料会计',
    admin: '管理员',
    material_accountant: '材料会计',
    finance_manager: '财务部门负责人',
    deputy_general_manager: '分管副总',
    general_manager: '总经理',
    dept_manager: '技术部经理',
    field_manager: '现场管理员',
    service_staff: '网点客服',
    branch_manager: '网点负责人',
    business_leader: '分管副总',
    tech_manager: '技术部经理',
    operation_repair: '运维人员',
    area_manager: '片区经理',
    operation_leader: '运营副总',
    engineering_leader: '工程副总',
    market_leader: '市场副总',
    accountant: '材料会计',
    cashier: '材料会计'
  }
  return map[role] || role
}

async function load() {
  loading.value = true
  try { rows.value = await sysApi.workflows() as any } finally { loading.value = false }
}
onMounted(load)
</script>
