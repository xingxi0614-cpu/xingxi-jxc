<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">出入库流水</div>
        <div class="page-sub">所有库存变动按时间倒序展示，可用于追溯和核对。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button type="primary" :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!rows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :icon="CopyDocument" @click="copyTable">复制</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
          <el-form-item label="物料" data-q="matId">
            <MaterialLookup v-model="query.matId" />
          </el-form-item>
          <el-form-item label="仓库" data-q="whId">
            <el-select v-model="query.whId" placeholder="全部仓库" clearable filterable style="width:180px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型" data-q="transType">
            <el-select v-model="query.transType" placeholder="全部类型" clearable style="width:150px">
              <el-option label="暂估入库" value="IN_ESTIMATE" />
              <el-option label="发票入库" value="IN_INVOICE" />
              <el-option label="材料出库" value="OUT" />
              <el-option label="退料入库" value="RETURN" />
              <el-option label="销售出库" value="SALE_OUT" />
              <el-option label="销售退货入库" value="SALE_RETURN" />
              <el-option label="借料出库" value="LOAN" />
              <el-option label="借料归还" value="LOAN_RETURN" />
              <el-option label="转库出库" value="TRANSFER_OUT" />
              <el-option label="转库入库" value="TRANSFER_IN" />
              <el-option label="差额调整" value="ADJUST" />
              <el-option label="差额-计入当期" value="ADJUST_EXPENSE" />
              <el-option label="反审冲回" value="REVERSE" />
              <el-option label="期初" value="INIT" />
            </el-select>
          </el-form-item>
          <el-form-item label="单据号" data-q="bizDocNo">
            <el-input v-model="query.bizDocNo" placeholder="业务单号" clearable style="width:180px" @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item label="发生日期" data-q="dateRange">
            <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <!-- 汇总卡与合计行按【全部查询结果】算(后端 totals),不只本页;入库、出库分开加,因为页面金额显示的是绝对值 -->
      <KpiCards v-if="searched && total" :items="kpis" />
      <el-table class="readable-table" border :fit="false" :data="rows" :index-offset="(query.current - 1) * query.size" v-loading="loading" :max-height="600"
                show-summary :summary-method="getSummary"
                :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @selection-change="selectedRows = $event">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" type="selection" width="44" />
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" label="日期" width="120">
          <template #default="{ row }">{{ fmtDate(field(row, 'createdAt', 'created_at')) }}</template>
        </el-table-column>
        <el-table-column prop="matCode" label="物料编码" width="120" show-overflow-tooltip />
        <el-table-column prop="matName" label="物料名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="matSpec" label="规格型号" min-width="140" />
        <el-table-column prop="whName" label="仓库" min-width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ fmtInventoryTransType(field(row, 'transType', 'trans_type')) }}</template>
        </el-table-column>
        <el-table-column label="单据" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <DocLink v-if="field(row, 'bizDocNo', 'biz_doc_no')" :doc-no="field(row, 'bizDocNo', 'biz_doc_no')" />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" align="right" width="100">
          <template #default="{ row }">
            <span :style="{ color: row.qty > 0 ? 'var(--color-success)' : row.qty < 0 ? 'var(--color-danger)' : 'var(--text-3)' }">
              {{ row.qty > 0 ? '+' : '' }}{{ $fmtQty(row.qty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="单价" align="right" width="120">
          <template #default="{ row }">{{ fmtPrice(field(row, 'unitPrice', 'unit_price')) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="金额" align="right" width="140">
          <template #default="{ row }">{{ fmtInventoryAmount(row.amount, field(row, 'transType', 'trans_type')) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="其中尾差" align="right" width="100">
          <template #default="{ row }">{{ field(row, 'tailAmt', 'tail_amt') == null ? '' : fmtPrice(field(row, 'tailAmt', 'tail_amt')) }}</template>
        </el-table-column>
        <el-table-column label="本仓结存数量" align="right" width="120">
          <template #default="{ row }">{{ $fmtQty(field(row, 'balQty', 'bal_qty')) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="公司结存数量" align="right" width="120">
          <template #default="{ row }">{{ field(row, 'orgBalQty', 'org_bal_qty') == null ? '-' : $fmtQty(field(row, 'orgBalQty', 'org_bal_qty')) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="结存单价" align="right" width="120">
          <template #default="{ row }"><b style="color:var(--color-primary)">{{ fmtPrice(field(row, 'balAvgPrice', 'bal_avg_price')) }}</b></template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="160" show-overflow-tooltip />
      </el-table>
      <!-- 2026-10-19 改后端真分页:以前只取最近 N 条(最多 200),更早的流水翻不到 -->
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[50, 100, 200, 500]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="query.current = 1; load()" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CopyDocument, Download } from '@/icons'
import { ElMessage } from 'element-plus'
import { inventoryApi, masterApi } from '@/api'
import { fmtDate, fmtInventoryAmount, fmtInventoryTransType, fmtMoney, fmtPrice, fmtQty } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, rowsForExport, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import DocLink from '@/components/DocLink.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import KpiCards from '@/components/KpiCards.vue'

const user = useUserStore()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
/** 全部查询结果的合计(后端算,不只本页):total/inQty/inAmount/outQty/outAmount/netQty/netAmount/tailAmt */
const totals = ref<any>({})
const selectedRows = ref<any[]>([])
const searched = ref(false)
const query = ref<any>({ matId: null, whId: null, transType: '', bizDocNo: '', startDate: null, endDate: null, current: 1, size: 50 })
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])
const canSeeCost = user.hasFinanceAccessOrAdmin()

const exportColumns = computed<ExportColumn[]>(() => {
  const cols: ExportColumn[] = [
    { label: '日期', value: row => fmtDate(field(row, 'createdAt', 'created_at')) },
    { label: '物料', key: 'matName' },
    { label: '规格型号', key: 'matSpec' },
    { label: '仓库', key: 'whName' },
    { label: '类型', value: row => fmtInventoryTransType(field(row, 'transType', 'trans_type')) },
    { label: '单据', value: row => field(row, 'bizDocNo', 'biz_doc_no') || '-' },
    { label: '数量', qty: true, key: 'qty' }
  ]
  if (canSeeCost) {
    cols.push(
      { label: '单价', money: true, value: row => fmtPrice(field(row, 'unitPrice', 'unit_price')) },
      { label: '金额', money: true, value: row => fmtInventoryAmount(row.amount, field(row, 'transType', 'trans_type')) },
      { label: '其中尾差', money: true, value: row => field(row, 'tailAmt', 'tail_amt') == null ? '' : fmtPrice(field(row, 'tailAmt', 'tail_amt')) }
    )
  }
  cols.push(
    { label: '本仓结存数量', qty: true, value: row => field(row, 'balQty', 'bal_qty') ?? '-' },
    ...(canSeeCost ? [{ label: '公司结存数量', qty: true, value: (row: any) => field(row, 'orgBalQty', 'org_bal_qty') ?? '-' }] : []),
    ...(canSeeCost ? [{ label: '结存单价', money: true, value: (row: any) => fmtPrice(field(row, 'balAvgPrice', 'bal_avg_price')) }] : []),
    { label: '说明', key: 'remark' }
  )
  return cols
})

function field(row: any, camel: string, snake: string) {
  return row?.[camel] ?? row?.[snake]
}

async function loadOptions() {
  warehouses.value = await masterApi.warehouses(true) as any
}

function doSearch() {
  applyDateRange()
  query.value.current = 1
  return load()
}

function resetQuery() {
  query.value = { matId: null, whId: null, transType: '', bizDocNo: '', startDate: null, endDate: null, current: 1, size: query.value.size || 50 }
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.value.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.value.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  try {
    const res: any = await inventoryApi.transactionsPage(query.value)
    rows.value = res.records || []
    total.value = Number(res.total || 0)
    totals.value = res.totals || {}
  } finally {
    loading.value = false
  }
}

/** 金额为空 = 本岗位看不到成本(后端脱敏),显示「—」,别显示 0.00 让人以为是零 */
const moneyOrDash = (v: any) => v == null ? '—' : fmtMoney(v)
const kpis = computed(() => [
  { label: '流水条数', value: total.value, hint: '全部查询结果' },
  { label: '入库', value: fmtQty(totals.value.inQty), hint: canSeeCost ? `金额 ${moneyOrDash(totals.value.inAmount)}` : '数量' },
  { label: '出库', value: fmtQty(totals.value.outQty), hint: canSeeCost ? `金额 ${moneyOrDash(totals.value.outAmount)}` : '数量' },
  ...(canSeeCost ? [{ label: '净变动金额', value: moneyOrDash(totals.value.netAmount), hint: '= 入库 − 出库 ± 差额调整', tone: 'blue' as const }] : [])
])

/**
 * 合计行:按全部查询结果(不只本页)。数量、金额是「净变动」——入库为正、出库为负加在一起,
 * 和数量列的 +/- 同一个口径;入库、出库各多少看上面的汇总卡。结存、单价类加总无意义,不合计。
 */
function getSummary({ columns }: { columns: any[] }) {
  const labelAt = Math.max(0, columns.findIndex((c: any) => c.type !== 'selection'))
  const signed = (v: any, f: (n: any) => string) => v == null ? '—' : `${Number(v) > 0 ? '+' : ''}${f(v)}`
  return columns.map((col: any, i: number) => {
    if (i === labelAt) return '合计'
    if (!searched.value || !total.value) return ''
    // 序号列太窄放不下,"全部 N 条"写在日期列,说明这行合计的是全部查询结果而不只本页
    if (col.label === '日期') return `全部 ${total.value} 条`
    if (col.label === '数量') return signed(totals.value.netQty, fmtQty)
    if (col.label === '金额') return signed(totals.value.netAmount, fmtMoney)
    if (col.label === '其中尾差') return moneyOrDash(totals.value.tailAmt)
    return ''
  })
}

function exportExcel() {
  exportRowsToExcel('出入库流水', exportColumns.value, rowsForExport(rows.value, selectedRows.value), '出入库流水',
    selectedRows.value.length ? undefined : { total: total.value, fetch: p => inventoryApi.transactionsPage({ ...query.value, ...p }) })
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('出入库流水', exportColumns.value, rowsForExport(rows.value, selectedRows.value), action, paper, '库存变动流水')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns.value, rowsForExport(rows.value, selectedRows.value))
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

onMounted(() => { loadOptions(); load() })
</script>
