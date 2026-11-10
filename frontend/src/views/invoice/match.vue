<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">发票匹配核销</div>
        <div class="page-sub">查看已审批发票与入库单的核销结果</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!rows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
      </el-space>
    </div>
    <el-card shadow="never">
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="keyword" label="单据/发票号">
          <el-input v-model="query.keyword" placeholder="系统单号或供应商发票号" clearable style="width:230px" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="supplierId" label="供应商">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
        <el-form-item data-q="dateRange" label="开票日期">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" show-summary :summary-method="listSummary">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="系统发票号" width="210" />
        <el-table-column prop="invoiceNo" label="供应商发票号" width="150" />
        <el-table-column prop="invoiceDate" label="开票日期" width="110" />
        <el-table-column prop="totalQty" label="数量" width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column label="净额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.netAmt) }}</template>
        </el-table-column>
        <el-table-column label="税额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.taxAmt) }}</template>
        </el-table-column>
        <el-table-column label="库存差额" width="120" align="right">
          <template #default="{ row }">{{ Number(row.adjustAmt || 0) > 0 ? '+' : '' }}{{ fmtMoney(row.adjustAmt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import { reactive, ref } from 'vue'
import { invoiceApi, masterApi } from '@/api'
import { fmtDate, fmtMoney } from '@/utils/format'
import { exportRowsToExcel, printOrExportRows, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import StatusTag from '@/components/StatusTag.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const searched = ref(false)
const query = reactive<any>({ current: 1, size: 100, status: 'MATCHED', keyword: '', supplierId: null, startDate: null, endDate: null })
const dateRange = ref<any>(null)
const suppliers = ref<any[]>([])
const supplierMap = ref<Record<number, any>>({})
const exportColumns: ExportColumn[] = [
  { label: '系统发票号', key: 'docNo' },
  { label: '供应商发票号', key: 'invoiceNo' },
  { label: '开票日期', key: 'invoiceDate' },
  { label: '供应商', value: row => supplierMap.value[row.supplierId]?.name || row.supplierId || '' },
  { label: '数量', qty: true, key: 'totalQty' },
  { label: '净额', money: true, value: row => fmtMoney(row.netAmt) },
  { label: '税额', money: true, value: row => fmtMoney(row.taxAmt) },
  { label: '库存差额', money: true, value: row => fmtMoney(row.adjustAmt) },
  { label: '状态', key: 'status' }
]
async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  try {
    const r: any = await invoiceApi.page(query)
    rows.value = r.records
  } finally { loading.value = false }
}
function doSearch() {
  applyDateRange()
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 100, status: 'MATCHED', keyword: '', supplierId: null, startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

function exportExcel() {
  exportRowsToExcel('发票匹配核销', exportColumns, rows.value, '发票匹配核销')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('发票匹配核销', exportColumns, rows.value, action, paper, '发票与入库核销结果')
}

masterApi.allSuppliers().then((list: any) => {
  suppliers.value = list
  supplierMap.value = Object.fromEntries(list.map((s: any) => [s.id, s]))
}).catch(() => {})
// 进页面直接列出数据，不用先点一次查询
load()

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 数量: { field: 'totalQty', type: 'qty' }, 净额: { field: 'netAmt', type: 'money' }, 税额: { field: 'taxAmt', type: 'money' } })
</script>
