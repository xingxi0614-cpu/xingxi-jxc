<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">暂估清单报表</div>
        <div class="page-sub">已审批的暂估入库单，跟踪是否核销</div>
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
      <QueryDrawer :model="q" @search="doQuery" @reset="reset" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :model="q" inline>
          <el-form-item label="入库日期" data-q="dateRange">
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
          </el-form-item>
          <el-form-item label="入库单号" data-q="recvNo">
            <DocNoLookup v-model="q.recvNo" type="RECV" :params="{ recvType: 'ESTIMATE', status: 'APPROVED' }" placeholder="点一下列出最近入库单，也可输入单号" @enter="doQuery" />
          </el-form-item>
          <el-form-item label="供应商" data-q="supplierId">
            <SupplierLookup v-model="q.supplierId" width="220px" />
          </el-form-item>
          <el-form-item label="物料" data-q="materialId">
            <MaterialLookup v-model="q.materialId" />
          </el-form-item>
          <el-form-item label="只看待核销" data-q="onlyUnmatched">
            <el-switch v-model="q.onlyUnmatched" />
          </el-form-item>
          <el-form-item label="只看超 90 天" data-q="overdueOnly">
            <el-switch v-model="q.overdueOnly" />
          </el-form-item>
        </el-form>
      </QueryDrawer>
      <!-- 2026-10-16:暂估挂账超过 90 天还没开票的,提醒催票(只提醒,口径同工作台提醒卡、月末结账) -->
      <el-alert v-if="overdue.docs" type="error" :closable="false" show-icon class="overdue-tip">
        <template #title>
          当前结果里有 <b>{{ overdue.docs }}</b> 张暂估入库挂账超过 90 天还没开票<template v-if="overdue.amt !== null">，未开票金额合计 <b>{{ fmtMoney(overdue.amt) }}</b> 元</template>。请联系供应商尽快开票，或说明长期暂估的原因。
          <el-button v-if="!q.overdueOnly" link type="primary" @click="q.overdueOnly = true; doQuery()">只看超 90 天</el-button>
        </template>
      </el-alert>
      <el-empty v-if="!rows.length && !loading" :description="queried ? '没有符合条件的数据，可放宽期间/仓库等条件再查' : '请设置条件后点击查询'" />
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" v-else :data="pageRows" :index-offset="pgOffset" v-loading="loading" @selection-change="selectedRows = $event" show-summary :summary-method="(p: any) => listSummary({ ...p, data: rows })">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" type="selection" width="44" />
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="doc_no"        label="入库单号"   width="160">
          <template #default="{ row }"><DocLink :doc-no="row.doc_no" /></template>
        </el-table-column>
        <el-table-column prop="recv_date"     label="入库日期"   width="110" />
        <el-table-column label="挂账天数" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.is_matched" class="muted">—</span>
            <span v-else :class="{ 'aging-over': isOverdue(row) }">{{ row.aging_days }} 天</span>
          </template>
        </el-table-column>
        <el-table-column prop="supplier_name" label="供应商" min-width="180" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="120" show-overflow-tooltip />
        <el-table-column prop="material_name" label="物料名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="material_spec" label="规格型号" min-width="140" />
        <el-table-column prop="qty"           label="数量"      width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column label="暂估金额" align="right" width="140">
          <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="核销状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.is_matched ? 'success' : 'warning'" size="small">{{ row.is_matched ? '已核销' : '待核销' }}</el-tag>
          </template>
        </el-table-column>
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
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { reportApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, rowsForExport, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import DocLink from '@/components/DocLink.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const queried = ref(false)   // 查过但没数据时提示“没有数据”，别让人以为还没查
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const { page: pgPage, size: pgSize, pageRows, offset: pgOffset } = useClientPager(rows)
const selectedRows = ref<any[]>([])
const q = reactive<any>({ supplierId: null, materialId: null, onlyUnmatched: false, overdueOnly: false, recvNo: '' })
const route = useRoute()
/** 超期 = 还没核销完 且 挂账超过 90 天(与后端 EstimateAging 同口径) */
const OVERDUE_DAYS = 90
function isOverdue(row: any) { return !row.is_matched && Number(row.aging_days) > OVERDUE_DAYS }
/** 当前结果里超期的张数和未开票金额(没有金额权限的岗位后端不返回金额,只显示张数) */
const overdue = computed(() => {
  const list = rows.value.filter(isOverdue)
  const docs = new Set(list.map(r => r.doc_no)).size
  const hasAmt = list.some(r => r.remain_amount !== undefined && r.remain_amount !== null)
  return { docs, amt: hasAmt ? list.reduce((s, r) => s + Number(r.remain_amount || 0), 0) : null }
})
const dateRange = ref<[string, string] | null>(null)

const exportColumns: ExportColumn[] = [
  { label: '入库单号', key: 'doc_no' },
  { label: '入库日期', key: 'recv_date' },
  { label: '挂账天数', value: row => row.is_matched ? '' : String(row.aging_days) },
  { label: '供应商', key: 'supplier_name' },
  { label: '物料编码', key: 'material_code' },
  { label: '物料名称', key: 'material_name' },
  { label: '规格型号', key: 'material_spec' },
  { label: '数量', qty: true, key: 'qty' },
  { label: '暂估金额', money: true, value: row => fmtMoney(row.amount) },
  { label: '核销状态', value: row => row.is_matched ? '已核销' : '待核销' }
]

function reset() {
  queried.value = false
  Object.assign(q, { supplierId: null, materialId: null, onlyUnmatched: false, overdueOnly: false, recvNo: '' })
  dateRange.value = null
  rows.value = []
  selectedRows.value = []
}

async function doQuery() {
  queried.value = true
  loading.value = true
  try {
    rows.value = await reportApi.estimate({ ...q, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }) as any
  } finally { loading.value = false }
}
function exportExcel() {
  exportRowsToExcel('暂估清单报表', exportColumns, rowsForExport(rows.value, selectedRows.value), '暂估清单')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('暂估清单报表', exportColumns, rowsForExport(rows.value, selectedRows.value), action, paper, '暂估入库核销')
}

// 从工作台提醒卡 / 月末结账「去处理」进来(?overdue=1):直接只看超 90 天并查询。
// 🔴 计算审查 U20:页面被缓存(KeepAlive)后再点同一个提醒,不会再走 onMounted,以前就不再查询。
//    现在页面每次重新显示时看浏览器历史编号:是一次新的跳转才执行提醒的意图;
//    只是切换标签页回来(编号没变)不重查,免得覆盖用户自己改过的筛选条件。
let consumedNav: unknown = null
function applyNavIntent() {
  const nav = window.history.state?.position ?? window.history.state?.current
  if (route.query.overdue !== '1' || nav === consumedNav) return
  consumedNav = nav
  q.overdueOnly = true
  doQuery()
}
onMounted(applyNavIntent)
onActivated(applyNavIntent)

async function copyTable() {
  await copyRowsForPaste(exportColumns, rowsForExport(rows.value, selectedRows.value))
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

// 顶部汇总卡（2026-10-17 全站界面改版）：按查出来的全部行计算；金额被隐藏（无权限）时显示「—」，不显示误导人的 0
const sumOf = (list: any[], f: string) => list.reduce((a, r) => a + Number(r[f] || 0), 0)
const moneyOrDash = (list: any[], f: string) => list.length && list.every(r => r[f] == null) ? '—' : fmtMoney(sumOf(list, f))
const distinct = (list: any[], f: string) => new Set(list.map(r => r[f])).size
const kpis = computed(() => {
  const open = rows.value.filter(r => !r.is_matched)
  const over = open.filter(r => isOverdue(r))
  const oldest = open.reduce((m: any, r) => (!m || Number(r.aging_days) > Number(m.aging_days) ? r : m), null)
  return [
    { label: '待核销', value: `${distinct(open, 'doc_no')} 张`, hint: `未核销 ${moneyOrDash(open, 'remain_amount')}` },
    { label: `超 ${OVERDUE_DAYS} 天`, value: `${distinct(over, 'doc_no')} 张`, hint: over.length ? `未核销 ${moneyOrDash(over, 'remain_amount')}，请催票` : '没有超期', tone: over.length ? 'red' as const : undefined },
    { label: '已核销', value: `${distinct(rows.value.filter(r => r.is_matched), 'doc_no')} 张` },
    { label: '最长挂账', value: oldest ? `${oldest.aging_days} 天` : '—', hint: oldest?.doc_no || '' }
  ]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 暂估金额: { field: 'amount', type: 'money' } })
</script>

<style scoped>
.overdue-tip { margin-bottom: 10px; }
.aging-over { color: var(--el-color-danger); font-weight: 600; }
.muted { color: var(--el-text-color-placeholder); }
</style>
