<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售退货查询</div>
        <div class="page-sub">按物料明细行查询销售退货，含回冲成本与应冲收入，支持按客户/物料/日期/状态筛选并导出。</div>
      </div>
      <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
      <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
    </div>
    <el-card shadow="never">
      <SalesQueryFilter v-model="filter" :status-options="statusOptions" @search="load" />
      <StatusChips v-model="statusSel" :options="CHIP_OPTIONS" :counts="chipCounts" />
      <el-table export-select class="readable-table" border :data="shownRows" v-loading="loading" empty-text="暂无数据" show-summary :summary-method="summary" max-height="620">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="doc_no" label="退货单号" width="180" show-overflow-tooltip />
        <el-table-column prop="source_out_no" label="来源出库" width="170" show-overflow-tooltip />
        <el-table-column prop="biz_date" label="退货日期" width="106" />
        <el-table-column prop="customer" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="110" show-overflow-tooltip />
        <el-table-column prop="material_name" label="物料名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="110" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="60" />
        <el-table-column label="退货数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
        <el-table-column label="成本单价" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.unit_price) }}</template></el-table-column>
        <el-table-column label="回冲成本" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
        <el-table-column label="应冲收入" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.sale_amount) }}</template></el-table-column>
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
async function load() { loading.value = true; try { rows.value = await salesApi.reportReturn(toQuery(filter.value)) as any[] } finally { loading.value = false } }
const summary = salesQuerySummary(shownRows, { 退货数量: { key: 'qty' }, 回冲成本: { key: 'amount', money: true }, 应冲收入: { key: 'sale_amount', money: true } })
const cols: ExportColumn[] = [
  { label: '退货单号', key: 'doc_no' }, { label: '来源出库', key: 'source_out_no' }, { label: '退货日期', key: 'biz_date' }, { label: '客户', key: 'customer' },
  { label: '物料编码', key: 'material_code' }, { label: '物料名称', key: 'material_name' }, { label: '规格', key: 'spec' }, { label: '单位', key: 'unit' },
  { label: '退货数量', value: (r: any) => fmtQty(r.qty), qty: true }, { label: '成本单价', money: true, value: (r: any) => fmtMoney(r.unit_price) }, { label: '回冲成本', money: true, value: (r: any) => fmtMoney(r.amount) }, { label: '应冲收入', money: true, value: (r: any) => fmtMoney(r.sale_amount) }, { label: '状态', key: 'status' }
]
function exportExcel() { exportRowsToExcel('销售退货查询', cols, shownRows.value, '销售退货查询') }
onMounted(load)
</script>
