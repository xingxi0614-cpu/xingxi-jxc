<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售订单执行查询</div>
        <div class="page-sub">跟踪<b>已审批</b>销售订单的出库/开票/收款进度(待审批订单不在此统计,请先在「销售订单」页完成审批)。勾选"只看已收款未安装"可筛出已收钱但还没出库的订单。</div>
      </div>
      <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
      <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" size="default" style="margin-bottom:8px">
        <el-form-item><el-checkbox v-model="onlyUnshipped" @change="load">只看已收款未安装</el-checkbox></el-form-item>
        <el-form-item label="关键词"><el-input v-model="keyword" placeholder="订单号/客户" clearable style="width:180px" @keyup.enter="load" /></el-form-item>
        <el-form-item label="下单日期"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="load" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无已审批的销售订单——订单审批通过后才进入执行统计" show-summary :summary-method="summary">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="doc_no" label="订单号" width="200" show-overflow-tooltip />
        <el-table-column prop="order_date" label="日期" width="110" />
        <el-table-column prop="order_type" label="类型" width="80" />
        <el-table-column prop="customer" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="sales_person" label="业务员" width="90" />
        <el-table-column label="订单数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.total_qty) }}</template></el-table-column>
        <el-table-column label="已出库" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.out_qty) }}</template></el-table-column>
        <el-table-column label="待出库" width="90" align="right"><template #default="{ row }"><span :class="{ warn: Number(row.wait_out_qty) > 0 }">{{ fmtQty(row.wait_out_qty) }}</span></template></el-table-column>
        <el-table-column label="已开票" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.invoice_qty) }}</template></el-table-column>
        <el-table-column label="收款金额" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.receipt_amount) }}</template></el-table-column>
        <el-table-column label="价税合计" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.total_amt) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { salesApi } from '@/api'
import StatusTag from '@/components/StatusTag.vue'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { makeLineSummary } from '@/utils/tableSummary'

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const onlyUnshipped = ref(false)
const keyword = ref('')
const dateRange = ref<any>(null)

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }

async function load() {
  loading.value = true
  try { rows.value = await salesApi.execOrder({ onlyUnshipped: onlyUnshipped.value, keyword: keyword.value, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }) as any[] }
  finally { loading.value = false }
}
// 按列名合计：表格会自动插「序号」列、固定列排最前，按列序号写会错位（2026-10-01 修）
const summary = makeLineSummary({
  订单数量: { field: 'total_qty', type: 'qty' }, 已出库: { field: 'out_qty', type: 'qty' },
  待出库: { field: 'wait_out_qty', type: 'qty' }, 已开票: { field: 'invoice_qty', type: 'qty' },
  收款金额: { field: 'receipt_amount', type: 'money' }, 价税合计: { field: 'total_amt', type: 'money' }
}, '合计', fmtQty)
const exportColumns: ExportColumn[] = [
  { label: '订单号', key: 'doc_no' }, { label: '日期', key: 'order_date' }, { label: '类型', key: 'order_type' },
  { label: '客户', key: 'customer' }, { label: '业务员', key: 'sales_person' },
  { label: '订单数量', value: (r: any) => fmtQty(r.total_qty), qty: true }, { label: '已出库', value: (r: any) => fmtQty(r.out_qty), qty: true },
  { label: '待出库', value: (r: any) => fmtQty(r.wait_out_qty), qty: true }, { label: '已开票', value: (r: any) => fmtQty(r.invoice_qty), qty: true },
  { label: '收款金额', money: true, value: (r: any) => fmtMoney(r.receipt_amount) }, { label: '价税合计', money: true, value: (r: any) => fmtMoney(r.total_amt) }
]
function exportExcel() { exportRowsToExcel('销售订单执行查询', exportColumns, rows.value, '销售订单执行查询') }

onMounted(load)
</script>

<style scoped>
.warn { color: #d64b46; font-weight: 600; }
</style>
