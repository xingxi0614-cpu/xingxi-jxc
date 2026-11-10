<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售发票查询</div>
        <div class="page-sub">按物料明细行查询销售发票，含不含税/销项税额/价税合计，支持按客户/物料/日期/状态筛选并导出。</div>
      </div>
      <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
      <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
    </div>
    <el-card shadow="never">
      <SalesQueryFilter v-model="filter" :status-options="statusOptions" @search="load" />
      <StatusChips v-model="statusSel" :options="CHIP_OPTIONS" :counts="chipCounts" />
      <el-table export-select class="readable-table" border :data="shownRows" v-loading="loading" empty-text="暂无数据" show-summary :summary-method="summary" max-height="620">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="doc_no" label="发票单号" width="180" show-overflow-tooltip />
        <el-table-column prop="invoice_type" label="发票类型" width="130" show-overflow-tooltip />
        <el-table-column prop="invoice_no" label="发票号" width="120" show-overflow-tooltip />
        <el-table-column prop="biz_date" label="开票日期" width="106" />
        <el-table-column prop="customer" label="客户" min-width="120" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="110" show-overflow-tooltip />
        <el-table-column prop="material_name" label="物料名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="60" />
        <el-table-column label="数量" width="86" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
        <el-table-column label="税率%" width="70" align="right"><template #default="{ row }">{{ row.tax_rate }}</template></el-table-column>
        <el-table-column label="不含税" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount_excl_tax) }}</template></el-table-column>
        <el-table-column label="销项税额" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.tax_amount) }}</template></el-table-column>
        <el-table-column label="价税合计" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import StatusChips from '@/components/StatusChips.vue'
import { salesApi } from '@/api'
import StatusTag from '@/components/StatusTag.vue'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import SalesQueryFilter from './sales-query-filter.vue'
import { defaultFilter, salesQuerySummary, toQuery, type SalesFilter } from './salesQueryShared'

const statusOptions = [{ v: 'DRAFT', l: '草稿' }, { v: 'PENDING', l: '待审批' }, { v: 'APPROVED', l: '已审批' }, { v: 'REJECTED', l: '已驳回' }]
// 状态页签：本页一次查出全部明细行，在页面上按状态分开看（数字 = 明细行数，与表格行数一致）
const statusSel = ref('')
const CHIP_OPTIONS = [{ label: '全部', value: '' }, ...statusOptions.map(o => ({ label: o.l, value: o.v }))]
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const shownRows = computed(() => statusSel.value ? rows.value.filter((r: any) => r.status === statusSel.value) : rows.value)
const chipCounts = computed(() => Object.fromEntries([['', rows.value.length], ...statusOptions.map(o => [o.v, rows.value.filter((r: any) => r.status === o.v).length])]))
const filter = ref<SalesFilter>(defaultFilter())
function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
async function load() { loading.value = true; try { rows.value = await salesApi.reportInvoice(toQuery(filter.value)) as any[] } finally { loading.value = false } }
const summary = salesQuerySummary(shownRows, { 数量: { key: 'qty' }, 不含税: { key: 'amount_excl_tax', money: true }, 销项税额: { key: 'tax_amount', money: true }, 价税合计: { key: 'amount', money: true } })
const cols: ExportColumn[] = [
  { label: '发票单号', key: 'doc_no' }, { label: '发票类型', key: 'invoice_type' }, { label: '发票号', key: 'invoice_no' }, { label: '开票日期', key: 'biz_date' }, { label: '客户', key: 'customer' },
  { label: '物料编码', key: 'material_code' }, { label: '物料名称', key: 'material_name' }, { label: '单位', key: 'unit' },
  { label: '数量', value: (r: any) => fmtQty(r.qty), qty: true }, { label: '税率%', numeric: true, key: 'tax_rate' }, { label: '不含税', money: true, value: (r: any) => fmtMoney(r.amount_excl_tax) }, { label: '销项税额', money: true, value: (r: any) => fmtMoney(r.tax_amount) }, { label: '价税合计', money: true, value: (r: any) => fmtMoney(r.amount) }, { label: '状态', key: 'status' }
]
function exportExcel() { exportRowsToExcel('销售发票查询', cols, shownRows.value, '销售发票查询') }
onMounted(load)
</script>
