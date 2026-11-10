<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购订单跟踪表</div>
        <div class="page-sub">报表默认空白；先点查询设置筛选项</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!rows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :icon="CopyDocument" @click="copyTable">复制</el-button>
      </el-space>
    </div>

    <KpiCards v-if="rows.length" :items="kpis" />
    <el-card shadow="never">
      <QueryDrawer :model="q" @clear="k => { if (k === 'dateRange') dateRange = null }" @search="doQuery" @reset="reset">
        <el-form :model="q" inline>
          <el-form-item label="订单号" data-q="poNo">
            <DocNoLookup v-model="q.poNo" type="PO" placeholder="点一下列出最近采购订单，也可输入单号" @enter="doQuery" />
          </el-form-item>
          <el-form-item label="供应商" data-q="supplierId">
            <SupplierLookup v-model="q.supplierId" placeholder="请选择供应商" width="220px" />
          </el-form-item>
          <el-form-item label="物料" data-q="materialId">
            <MaterialLookup v-model="q.materialId" placeholder="请选择物料" />
          </el-form-item>
          <el-form-item label="订单状态" data-q="status">
            <el-select v-model="q.status" clearable placeholder="全部状态">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="已审批" value="APPROVED" />
              <el-option label="部分入库" value="PARTIAL_RECEIVED" />
              <el-option label="已入库" value="RECEIVED" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期" data-q="dateRange">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
          </el-form-item>
        </el-form>
      </QueryDrawer>
      <el-empty v-if="!rows.length && !loading" :description="queried ? '没有符合条件的数据，可放宽期间/仓库等条件再查' : '请设置条件后点击查询'" />
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" v-else :data="pageRows" :index-offset="pgOffset" v-loading="loading" @selection-change="selectedRows = $event" show-summary :summary-method="(p: any) => listSummary({ ...p, data: rows })">
        <el-table-column type="selection" :fixed="freezeLeft ? 'left' : undefined" width="44" />
        <el-table-column prop="doc_no" :fixed="freezeLeft ? 'left' : undefined"        label="订单号"   width="160">
          <template #default="{ row }"><DocLink :doc-no="row.doc_no" /></template>
        </el-table-column>
        <el-table-column prop="supplier_name" label="供应商" min-width="200" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="120" show-overflow-tooltip />
        <el-table-column prop="material_name" label="物料名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="material_spec" label="规格型号" min-width="140" />
        <el-table-column prop="qty"           label="订货"     width="80" align="right" :formatter="$qtyFmt" />
        <el-table-column prop="received_qty"  label="已入"     width="80" align="right" />
        <el-table-column prop="remaining_qty" label="未入"     width="80" align="right" />
        <el-table-column label="单价" align="right" width="100">
          <template #default="{ row }">{{ fmtMoney(row.unit_price) }}</template>
        </el-table-column>
        <el-table-column label="金额" align="right" width="120">
          <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column prop="order_date" label="下单日期" width="110" />
      </el-table>
      <!-- 前端分页：只画当前页，合计/汇总卡/导出仍按全部行（见 useClientPager） -->
      <el-pagination v-if="rows.length > pgSize || pgSize !== 100" v-model:current-page="pgPage" v-model:page-size="pgSize" :total="rows.length"
                     :page-sizes="[50, 100, 200, 500]" layout="total, sizes, prev, pager, next, jumper"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useClientPager } from '@/composables/useClientPager'
import { makeLineSummary } from '@/utils/tableSummary'
import KpiCards from '@/components/KpiCards.vue'
import { computed, reactive, ref } from 'vue'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { reportApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, rowsForExport, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import StatusTag from '@/components/StatusTag.vue'
import DocLink from '@/components/DocLink.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const queried = ref(false)   // 查过但没数据时提示“没有数据”，别让人以为还没查
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const { page: pgPage, size: pgSize, pageRows, offset: pgOffset } = useClientPager(rows)
const selectedRows = ref<any[]>([])
const q = reactive<any>({ supplierId: null, materialId: null, status: '', poNo: '', startDate: null, endDate: null })
const dateRange = ref<any>(null)

const exportColumns: ExportColumn[] = [
  { label: '订单号', key: 'doc_no' },
  { label: '供应商', key: 'supplier_name' },
  { label: '物料编码', key: 'material_code' },
  { label: '物料名称', key: 'material_name' },
  { label: '规格型号', key: 'material_spec' },
  { label: '订货', qty: true, key: 'qty' },
  { label: '已入', qty: true, key: 'received_qty' },
  { label: '未入', qty: true, key: 'remaining_qty' },
  { label: '单价', money: true, value: row => fmtMoney(row.unit_price) },
  { label: '金额', money: true, value: row => fmtMoney(row.amount) },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '下单日期', key: 'order_date' }
]

function reset() {
  queried.value = false
  Object.assign(q, { supplierId: null, materialId: null, status: '', poNo: '', startDate: null, endDate: null })
  dateRange.value = null
  rows.value = []
  selectedRows.value = []
}
async function doQuery() {
  queried.value = true
  if (dateRange.value) {
    q.startDate = dateRange.value[0]
    q.endDate = dateRange.value[1]
  } else { q.startDate = null; q.endDate = null }
  loading.value = true
  try {
    rows.value = await reportApi.poTracking(q) as any
  } finally { loading.value = false }
}

function exportExcel() {
  exportRowsToExcel('采购订单跟踪表', exportColumns, rowsForExport(rows.value, selectedRows.value), '采购订单跟踪')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('采购订单跟踪表', exportColumns, rowsForExport(rows.value, selectedRows.value), action, paper, '采购订单执行跟踪')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns, rowsForExport(rows.value, selectedRows.value))
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

// 顶部汇总卡（2026-10-17 全站界面改版）：按查出来的全部行计算；金额被隐藏（无权限）时显示「—」，不显示误导人的 0
const sumOf = (list: any[], f: string) => list.reduce((a, r) => a + Number(r[f] || 0), 0)
const moneyOrDash = (list: any[], f: string) => list.length && list.every(r => r[f] == null) ? '—' : fmtMoney(sumOf(list, f))
const distinct = (list: any[], f: string) => new Set(list.map(r => r[f])).size
const kpis = computed(() => {
  const open = rows.value.filter(r => Number(r.remaining_qty || 0) > 0)
  return [
    { label: '订单明细', value: `${rows.value.length} 行`, hint: `${distinct(rows.value, 'doc_no')} 张订单` },
    { label: '订单金额', value: moneyOrDash(rows.value, 'amount'), tone: 'blue' as const },
    { label: '还没入完', value: `${open.length} 行`, hint: `涉及 ${distinct(open, 'doc_no')} 张订单`, tone: open.length ? 'amber' as const : undefined },
    { label: '已入完', value: `${rows.value.length - open.length} 行` }
  ]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 订货: { field: 'qty', type: 'qty' }, 已入: { field: 'received_qty', type: 'qty' }, 未入: { field: 'remaining_qty', type: 'qty' }, 金额: { field: 'amount', type: 'money' } })
</script>
