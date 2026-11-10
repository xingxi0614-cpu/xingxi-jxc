<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售出库执行查询</div>
        <div class="page-sub">跟踪<b>已审批</b>销售出库单的开票情况(待审批出库单不在此统计)。勾选"只看已出库未开票"可筛出还没做销售发票的出库单。</div>
      </div>
      <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" size="default" style="margin-bottom:8px">
        <el-form-item><el-checkbox v-model="onlyUninvoiced" @change="load">只看已出库未开票</el-checkbox></el-form-item>
        <el-form-item label="关键词"><el-input v-model="keyword" placeholder="出库号/订单号/客户" clearable style="width:200px" @keyup.enter="load" /></el-form-item>
        <el-form-item label="出库日期"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="load" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无已审批的销售出库单——出库审批通过后才进入开票统计" show-summary :summary-method="summary">
        <el-table-column prop="doc_no" label="出库单号" width="200" show-overflow-tooltip />
        <el-table-column prop="source_so_no" label="来源订单" width="190" show-overflow-tooltip />
        <el-table-column prop="out_date" label="出库日期" width="110" />
        <el-table-column prop="customer" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column prop="sales_person" label="业务员" width="90" />
        <el-table-column label="数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.total_qty) }}</template></el-table-column>
        <el-table-column label="成本额" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.total_amt) }}</template></el-table-column>
        <el-table-column label="已退货" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.return_qty) }}</template></el-table-column>
        <el-table-column label="净出库" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.net_qty) }}</template></el-table-column>
        <el-table-column label="开票状态" width="110" align="center">
          <template #default="{ row }"><el-tag :type="row.invoiced ? 'success' : 'warning'" size="small">{{ row.invoiced ? '已开票' : '未开票' }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { salesApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { makeLineSummary } from '@/utils/tableSummary'

const loading = ref(false)
const rows = ref<any[]>([])
const onlyUninvoiced = ref(false)
const keyword = ref('')
const dateRange = ref<any>(null)

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }

async function load() {
  loading.value = true
  try { rows.value = await salesApi.execOut({ onlyUninvoiced: onlyUninvoiced.value, keyword: keyword.value, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }) as any[] }
  finally { loading.value = false }
}
// 按列名合计：表格会自动插「序号」列、固定列排最前，按列序号写会错位（2026-10-01 修）
const summary = makeLineSummary({ 数量: { field: 'total_qty', type: 'qty' }, 成本额: { field: 'total_amt', type: 'money' }, 已退货: { field: 'return_qty', type: 'qty' }, 净出库: { field: 'net_qty', type: 'qty' } }, '合计', fmtQty)
const exportColumns: ExportColumn[] = [
  { label: '出库单号', key: 'doc_no' }, { label: '来源订单', key: 'source_so_no' }, { label: '出库日期', key: 'out_date' },
  { label: '客户', key: 'customer' }, { label: '业务员', key: 'sales_person' },
  { label: '数量', value: (r: any) => fmtQty(r.total_qty), qty: true }, { label: '成本额', money: true, value: (r: any) => fmtMoney(r.total_amt) },
  { label: '已退货', key: 'return_qty', qty: true }, { label: '净出库', key: 'net_qty', qty: true },
  { label: '开票状态', value: (r: any) => (r.invoiced ? '已开票' : '未开票') }
]
function exportExcel() { exportRowsToExcel('销售出库执行查询', exportColumns, rows.value, '销售出库执行查询') }

onMounted(load)
</script>
