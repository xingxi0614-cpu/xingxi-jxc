<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">订单跟踪</div>
        <div class="page-sub">按订单行查看订货、已入库和未入库数量</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!rows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :icon="CopyDocument" @click="copyTable">复制</el-button>
      </el-space>
    </div>
    <el-card shadow="never">
      <QueryDrawer :model="query" @clear="k => { if (k === 'dateRange') dateRange = null }" @search="doSearch" @reset="resetQuery">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="订单号" data-q="poNo">
          <DocNoLookup v-model="query.poNo" type="PO" placeholder="点一下列出最近采购订单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="状态" data-q="status">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:150px">
            <el-option label="已审批" value="APPROVED" />
            <el-option label="部分入库" value="PARTIAL_RECEIVED" />
            <el-option label="已完成" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="下单日期" data-q="dateRange">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item label="供应商" data-q="supplierId">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
        <el-form-item label="物料" data-q="materialId">
          <MaterialLookup v-model="query.materialId" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" show-summary :summary-method="listSummary">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="doc_no" label="订单号" width="210" />
        <el-table-column prop="order_date" label="下单日期" width="110" />
        <el-table-column prop="supplier_name" label="供应商" min-width="180" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="120" show-overflow-tooltip />
        <el-table-column prop="material_name" label="物料名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="qty" label="订货" width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column prop="received_qty" label="已入" width="90" align="right" />
        <el-table-column prop="remaining_qty" label="未入" width="90" align="right" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import { reactive, ref } from 'vue'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { masterApi, reportApi } from '@/api'
import { fmtDate } from '@/utils/format'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import StatusTag from '@/components/StatusTag.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const searched = ref(false)
const query = reactive<any>({ status: '', supplierId: null, materialId: null, poNo: '', startDate: null, endDate: null })
const dateRange = ref<any>(null)
const suppliers = ref<any[]>([])
const exportColumns: ExportColumn[] = [
  { label: '订单号', key: 'doc_no' },
  { label: '下单日期', key: 'order_date' },
  { label: '供应商', key: 'supplier_name' },
  { label: '物料编码', key: 'material_code' },
  { label: '物料名称', key: 'material_name' },
  { label: '订货', qty: true, key: 'qty' },
  { label: '已入', qty: true, key: 'received_qty' },
  { label: '未入', qty: true, key: 'remaining_qty' },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) }
]
async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  try { rows.value = await reportApi.poTracking(query) as any } finally { loading.value = false }
}
function doSearch() {
  applyDateRange()
  return load()
}
function resetQuery() {
  Object.assign(query, { status: '', supplierId: null, materialId: null, poNo: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

function exportExcel() {
  exportRowsToExcel('订单跟踪', exportColumns, rows.value, '订单跟踪')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('订单跟踪', exportColumns, rows.value, action, paper, '采购订单执行跟踪')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns, rows.value)
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

masterApi.allSuppliers().then((list: any) => { suppliers.value = list }).catch(() => {})
// 进页面直接列出数据，不用先点一次查询
load()

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 订货: { field: 'qty', type: 'qty' }, 已入: { field: 'received_qty', type: 'qty' }, 未入: { field: 'remaining_qty', type: 'qty' } })
</script>
