<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">收发存汇总表</div>
        <div class="page-sub">按物料汇总期初、收入、发出、结存数量和金额。存货按公司核算：金额以「按公司」口径为准，公司内转库同时计收入和发出。</div>
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
          <el-form-item data-q="dateRange" label="统计期间">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
          </el-form-item>
          <el-form-item data-q-fixed label="统计口径">
            <el-radio-group v-model="q.dim">
              <el-radio-button value="ORG">按公司</el-radio-button>
              <el-radio-button value="WH">按仓库</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item data-q="warehouseId" v-if="q.dim === 'WH'" label="仓库">
            <el-select v-model="q.warehouseId" filterable clearable placeholder="全部仓库">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="materialId" label="物料">
            <MaterialLookup v-model="q.materialId" placeholder="按物料编码或名称选择" />
          </el-form-item>
        </el-form>
      </QueryDrawer>
      <el-alert v-if="qtyOnly" type="info" show-icon :closable="false" class="mb-3" title="按仓库只看数量：存货已按公司核算，仓库不单独算成本，金额请切换到「按公司」查看" />
      <el-empty v-if="!rows.length && !loading" :description="queried ? '没有符合条件的数据，可放宽期间/仓库等条件再查' : '请选择条件后点击查询'" />
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" v-else :data="pageRows" :index-offset="pgOffset" v-loading="loading" show-summary :summary-method="(p: any) => getSummary({ ...p, data: rows })" @selection-change="selectedRows = $event">
        <el-table-column type="selection" :fixed="freezeLeft ? 'left' : undefined" width="44" />
        <el-table-column prop="mat_name" :fixed="freezeLeft ? 'left' : undefined" label="物料名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="mat_code" label="物料编码" min-width="130" show-overflow-tooltip />
        <el-table-column prop="batch_no" label="批次号" width="100" />
        <el-table-column prop="mat_spec" label="规格" min-width="120" />
        <el-table-column prop="mat_model" label="型号" width="90" />
        <el-table-column prop="mat_brand" label="品牌" width="90" />
        <el-table-column prop="mat_level" label="物料等级" width="100" show-overflow-tooltip />
        <el-table-column prop="mat_unit" label="主单位" width="80" />
        <el-table-column prop="wh_name" :label="dimLabel" min-width="110" show-overflow-tooltip />
        <el-table-column label="期初数量" align="right" width="110"><template #default="{ row }">{{ fmtQty(row.open_qty) }}</template></el-table-column>
        <el-table-column label="期初单价" align="right" width="110"><template #default="{ row }">{{ fmtUnitPrice(row.open_amt, row.open_qty) }}</template></el-table-column>
        <el-table-column label="期初金额" align="right" width="120"><template #default="{ row }">{{ fmtMoney(row.open_amt) }}</template></el-table-column>
        <el-table-column label="收入数量" align="right" width="110"><template #default="{ row }">{{ fmtQty(row.in_qty) }}</template></el-table-column>
        <el-table-column label="收入单价" align="right" width="110"><template #default="{ row }">{{ fmtUnitPrice(row.in_amt, row.in_qty) }}</template></el-table-column>
        <el-table-column label="收入金额" align="right" width="120"><template #default="{ row }">{{ fmtMoney(row.in_amt) }}</template></el-table-column>
        <el-table-column label="发出数量" align="right" width="110"><template #default="{ row }">{{ fmtQty(row.out_qty) }}</template></el-table-column>
        <el-table-column label="发出单价" align="right" width="110"><template #default="{ row }">{{ fmtUnitPrice(row.out_amt, row.out_qty) }}</template></el-table-column>
        <el-table-column label="发出金额" align="right" width="120"><template #default="{ row }">{{ fmtMoney(row.out_amt) }}</template></el-table-column>
        <el-table-column label="结存数量" align="right" width="110"><template #default="{ row }"><b>{{ fmtQty(row.close_qty) }}</b></template></el-table-column>
        <el-table-column label="结存单价" align="right" width="110"><template #default="{ row }"><b>{{ fmtUnitPrice(row.close_amt, row.close_qty) }}</b></template></el-table-column>
        <el-table-column label="单价变动" align="right" width="100">
          <template #default="{ row }"><span :class="{ 'price-jump': priceJump(row) }">{{ fmtPriceChange(row) }}</span></template>
        </el-table-column>
        <el-table-column label="结存金额" align="right" width="120"><template #default="{ row }"><b>{{ fmtMoney(row.close_amt) }}</b></template></el-table-column>
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
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { reportApi, masterApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { makeLineSummary } from '@/utils/tableSummary'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, rowsForExport, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const queried = ref(false)   // 查过但没数据时提示“没有数据”，别让人以为还没查
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const { page: pgPage, size: pgSize, pageRows, offset: pgOffset } = useClientPager(rows)
const selectedRows = ref<any[]>([])
const dateRange = ref<any>(null)
const q = reactive<any>({ dim: 'ORG', materialId: null, warehouseId: null, startDate: null, endDate: null })
const queriedDim = ref('ORG')
const dimLabel = computed(() => queriedDim.value === 'ORG' ? '公司' : '仓库')
// 切换后按仓库口径后端不给金额(置空),据此提示
const qtyOnly = computed(() => queriedDim.value === 'WH' && rows.value.length > 0 && rows.value[0].close_amt == null)
const warehouses = ref<any[]>([])

/**
 * 单价变动 = (结存单价 − 期初单价)÷ 期初单价(2026-10-16 对标财务软件操作手册:月末看期初与期末单价波动是否异常)。
 * 超过 ±20% 标红,提醒核对是否录错单价或发票;只提醒,不改任何金额。
 * 期初没有库存/金额、或期末已没有库存的不算(显示 —),免得新物料、刚出光的物料全部标红。
 * 没有金额权限的岗位后端不返回金额,同样显示 —。
 */
const PRICE_JUMP = 0.2
function priceChange(row: any): number | null {
  const oq = Number(row.open_qty), oa = Number(row.open_amt), cq = Number(row.close_qty), ca = Number(row.close_amt)
  if (!(oq > 0) || !(oa > 0) || !(cq > 0) || !Number.isFinite(ca)) return null
  const op = oa / oq
  return (ca / cq - op) / op
}
function priceJump(row: any) { const c = priceChange(row); return c !== null && Math.abs(c) > PRICE_JUMP }
function fmtPriceChange(row: any) {
  const c = priceChange(row)
  if (c === null) return '—'
  const pct = (c * 100).toFixed(1)
  return (c > 0 ? '+' : c < 0 ? '−' : '') + pct.replace('-', '') + '%'
}

const exportColumns: ExportColumn[] = [
  { label: '物料名称', key: 'mat_name' },
  { label: '物料编码', key: 'mat_code' },
  { label: '批次号', key: 'batch_no' },
  { label: '规格', key: 'mat_spec' },
  { label: '型号', key: 'mat_model' },
  { label: '品牌', key: 'mat_brand' },
  { label: '物料等级', key: 'mat_level' },
  { label: '主单位', key: 'mat_unit' },
  { label: '公司/仓库', key: 'wh_name' },
  { label: '期初数量', value: row => fmtQty(row.open_qty), qty: true },
  { label: '期初单价', money: true, value: row => fmtUnitPrice(row.open_amt, row.open_qty) },
  { label: '期初金额', money: true, value: row => fmtMoney(row.open_amt) },
  { label: '收入数量', value: row => fmtQty(row.in_qty), qty: true },
  { label: '收入单价', money: true, value: row => fmtUnitPrice(row.in_amt, row.in_qty) },
  { label: '收入金额', money: true, value: row => fmtMoney(row.in_amt) },
  { label: '发出数量', value: row => fmtQty(row.out_qty), qty: true },
  { label: '发出单价', money: true, value: row => fmtUnitPrice(row.out_amt, row.out_qty) },
  { label: '发出金额', money: true, value: row => fmtMoney(row.out_amt) },
  { label: '结存数量', value: row => fmtQty(row.close_qty), qty: true },
  { label: '结存单价', money: true, value: row => fmtUnitPrice(row.close_amt, row.close_qty) },
  { label: '单价变动', value: row => fmtPriceChange(row) },
  { label: '结存金额', money: true, value: row => fmtMoney(row.close_amt) }
]

function fmtUnitPrice(amt: any, qty: any) {
  const a = Number(amt), q = Number(qty)
  if (amt == null || !Number.isFinite(a) || !q) return '-'
  return fmtMoney(a / q)
}

function fmtQty(v: any) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
}

function reset() {
  queried.value = false
  dateRange.value = null
  Object.assign(q, { dim: 'ORG', materialId: null, warehouseId: null, startDate: null, endDate: null })
  rows.value = []
  selectedRows.value = []
}

async function doQuery() {
  queried.value = true
  q.startDate = dateRange.value?.[0] || null
  q.endDate = dateRange.value?.[1] || null
  if (q.dim === 'ORG') q.warehouseId = null
  loading.value = true
  try {
    rows.value = await reportApi.inOutSummary(q) as any
    queriedDim.value = q.dim
  } finally {
    loading.value = false
  }
}

// 按列名合计（“合计”落在物料名称列，不挤进 44px 勾选列）
const lineSummary = makeLineSummary({
  期初数量: { field: 'open_qty', type: 'qty' }, 期初金额: { field: 'open_amt', type: 'money' },
  收入数量: { field: 'in_qty', type: 'qty' }, 收入金额: { field: 'in_amt', type: 'money' },
  发出数量: { field: 'out_qty', type: 'qty' }, 发出金额: { field: 'out_amt', type: 'money' },
  结存数量: { field: 'close_qty', type: 'qty' }, 结存金额: { field: 'close_amt', type: 'money' }
}, '合计', fmtQty)
// 按仓库只看数量时金额为空,合计也显示「-」,别显示 0.00 让人误以为金额是零
function getSummary(param: { columns: any[]; data: any[] }) {
  const sums = lineSummary(param)
  if (!qtyOnly.value) return sums
  return sums.map((v, i) => (String(param.columns[i]?.label || '').includes('金额') ? '-' : v))
}

function exportExcel() {
  exportRowsToExcel('收发存汇总表', exportColumns, rowsForExport(rows.value, selectedRows.value), '收发存汇总表')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('收发存汇总表', exportColumns, rowsForExport(rows.value, selectedRows.value), action, paper, '库存收发存汇总')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns, rowsForExport(rows.value, selectedRows.value))
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

onMounted(async () => {
  warehouses.value = await masterApi.warehouses() as any
})

// 顶部汇总卡（2026-10-17 全站界面改版）：按查出来的全部行计算；金额被隐藏（无权限）时显示「—」，不显示误导人的 0
const sumOf = (list: any[], f: string) => list.reduce((a, r) => a + Number(r[f] || 0), 0)
const moneyOrDash = (list: any[], f: string) => list.length && list.every(r => r[f] == null) ? '—' : fmtMoney(sumOf(list, f))
const distinct = (list: any[], f: string) => new Set(list.map(r => r[f])).size
const kpis = computed(() => {
  const jumps = rows.value.filter(r => priceJump(r)).length
  return [
    { label: '期初金额', value: moneyOrDash(rows.value, 'open_amt') },
    { label: '本期收入', value: moneyOrDash(rows.value, 'in_amt'), hint: '含公司内转库收入' },
    { label: '本期发出', value: moneyOrDash(rows.value, 'out_amt'), hint: '含转库发出，已扣退料' },
    { label: '期末金额', value: moneyOrDash(rows.value, 'close_amt'), hint: `${rows.value.length} 种物料`, tone: 'blue' as const },
    { label: `单价变动超 ±${Math.round(PRICE_JUMP * 100)}%`, value: `${jumps} 种`, hint: jumps ? '表中标黄，请核对' : '没有异常', tone: jumps ? 'amber' as const : undefined }
  ]
})
</script>

<style scoped>
.price-jump { color: var(--el-color-danger); font-weight: 600; }
</style>
