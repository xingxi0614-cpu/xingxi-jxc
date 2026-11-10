<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售收入成本明细表</div>
        <div class="page-sub">
          {{ KIND_NAME }}：收入取<b>已审批销售发票</b>不含税金额（按开票日期归月），成本取对应<b>销售出库单</b>结转成本；
          退货冲减退货当月。可打印签字，也可导出 Excel 再按需修改。
        </div>
      </div>
      <el-space>
        <el-button :disabled="!data" @click="doExport">导出Excel</el-button>
        <el-button type="primary" :disabled="!data" @click="doPrint">打印</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" style="margin-bottom:4px" @submit.prevent>
        <el-form-item v-if="companies.length > 1" label="公司">
          <el-select v-model="companyId" style="width:180px" @change="load">
            <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票期间">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" :clearable="false" :shortcuts="shortcuts" style="width:250px" />
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-alert v-if="loadError" type="error" :closable="false" show-icon class="mb-2" :title="loadError" />
      <KpiCards v-if="data" :items="kpis" />

      <el-alert type="info" :closable="false" class="mb-2">
        本表范围：<b>销售模块</b>（{{ KIND_NAME }}），税率 <b>{{ data ? Number(data.taxRate) : 13 }}%</b>；材料出库/领料（维修改造等）不在本表。
        <template v-if="data">　编制单位：<b>{{ data.company.name }}</b></template>
      </el-alert>
      <el-alert v-if="data?.unapprovedInvoices" type="warning" :closable="false" show-icon class="mb-2"
        :title="`期间内另有 ${data.unapprovedInvoices} 张销售发票还没审批通过，未计入收入。审批后再查即可计入。`" />
      <el-alert v-if="rateWarnCount" type="error" :closable="false" show-icon class="mb-2"
        :title="`有 ${rateWarnCount} 行销售单据对应原票税率偏离 13% 或包含多税率，请到「明细」核对。`" />

      <el-tabs v-model="tab">
        <el-tab-pane label="按月汇总" name="sum">
          <el-table :data="data?.summary || []" border class="readable-table" v-loading="loading" empty-text="该期间没有已审批的销售发票"
            show-summary :summary-method="sumSummary">
            <el-table-column prop="month" label="月份" width="100" />
            <el-table-column prop="invoice_count" label="发票张数" width="90" align="right" />
            <el-table-column label="收入(不含税)" min-width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.revenue) }}</template></el-table-column>
            <el-table-column label="其中:退货冲减" min-width="110" align="right"><template #default="{ row }">{{ Number(row.return_revenue) ? fmtMoney(row.return_revenue) : '—' }}</template></el-table-column>
            <el-table-column label="税额(原票)" min-width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.tax) }}</template></el-table-column>
            <el-table-column label="价税合计" min-width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.total) }}</template></el-table-column>
            <el-table-column label="销售成本" min-width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.cost) }}</template></el-table-column>
            <el-table-column label="毛利" min-width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.profit) }}</template></el-table-column>
            <el-table-column label="毛利率" width="90" align="right">
              <template #default="{ row }"><span :class="{ low: isLow(row) }">{{ pct(row.margin) }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="明细" name="det">
          <div class="hint">红色"毛利低/亏损"= 毛利率低于 {{ LOW }}% 或卖价低于成本；红色税率提示=原票税率偏离 13% 或包含多税率。请核对原票（只提示，不拦截）。</div>
          <el-table :data="data?.detail || []" border class="readable-table" v-loading="loading" empty-text="该期间没有已审批的销售发票" max-height="560"
            show-summary :summary-method="detailSummary" :row-class-name="({ row }: any) => row.kind === 'RETURN' ? 'ret-row' : ''">
            <el-table-column prop="biz_date" label="日期" width="112" class-name="nw" />
            <el-table-column label="单号" min-width="230" class-name="nw">
              <template #default="{ row }">
                <el-tag v-if="row.kind === 'RETURN'" type="warning" size="small">退货</el-tag>
                {{ row.doc_no }}
                <div v-if="row.invoice_no" class="sub">发票号 {{ row.invoice_no }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="customer" label="客户" min-width="90" show-overflow-tooltip />
            <el-table-column prop="source_out_no" label="来源出库单" min-width="200" class-name="nw" />
            <el-table-column label="物料" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.material_code }} {{ row.material_name }}<span v-if="row.spec">({{ row.spec }})</span></template>
            </el-table-column>
            <el-table-column label="数量" width="80" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
            <el-table-column label="收入(不含税)" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.revenue) }}</template></el-table-column>
            <el-table-column label="税额(原票)" width="120" align="right">
              <template #default="{ row }">{{ fmtMoney(row.tax) }}<el-tag v-if="row.rate_warn" type="danger" size="small" class="ml">{{ taxRateLabel(row) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="成本" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.cost) }}</template></el-table-column>
            <el-table-column label="毛利" width="140" align="right">
              <template #default="{ row }">{{ fmtMoney(row.profit) }}<el-tag v-if="row.kind !== 'RETURN' && isLow(row)" type="danger" size="small" class="ml">{{ Number(row.profit) < 0 ? '亏损' : '毛利低' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="毛利率" width="80" align="right"><template #default="{ row }">{{ pct(row.margin) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="un">
          <template #label>已出库未开票<el-badge v-if="data?.uninvoiced?.length" :value="data.uninvoiced.length" class="tab-badge" /></template>
          <el-alert type="warning" :closable="false" show-icon class="mb-2"
            :title="`这些货已经出库、成本已经结转，但还没有审批通过的销售发票——收入可能漏记。截至 ${data?.endDate || '-'}，合计成本 ${fmtMoney(unCost)} 元。`" />
          <el-table :data="data?.uninvoiced || []" border class="readable-table" v-loading="loading" empty-text="没有未开票的出库单" max-height="560" show-summary :summary-method="uninvoicedSummary">
            <el-table-column prop="out_date" label="出库日期" width="112" class-name="nw" />
            <el-table-column prop="doc_no" label="出库单号" min-width="200" class-name="nw" />
            <el-table-column prop="customer" label="客户" min-width="100" show-overflow-tooltip />
            <el-table-column prop="line_count" label="物料行数" width="90" align="right" />
            <el-table-column label="已结转成本(扣退货)" width="150" align="right"><template #default="{ row }">{{ fmtMoney(row.cost) }}</template></el-table-column>
            <el-table-column label="截至期末天数" width="110" align="right"><template #default="{ row }"><span :class="{ low: row.days > 30 }">{{ row.days }}</span></template></el-table-column>
            <el-table-column label="发票状态" width="100">
              <template #default="{ row }"><StatusTag v-if="row.invoice_status" :status="row.invoice_status" /><span v-else class="muted">未开票</span></template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }"><el-button link type="primary" @click="goInvoice(row)">去处理</el-button></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import KpiCards from '@/components/KpiCards.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { navigateToInvoice } from '@/utils/invoiceNavigation'
import { salesApi } from '@/api'
import StatusTag from '@/components/StatusTag.vue'
import { useUserStore } from '@/stores/user'
import { fmtMoney, fmtQty } from '@/utils/format'
import { KIND_NAME, exportIncomeCostXls, pct, printIncomeCost, taxRateLabel, type IncomeCostData } from './salesIncomeCostPrint'

/** 毛利率低于 10% 或亏损时标红提醒核对单价（用户 2026-10-01 认可的阈值，只提示不拦截） */
const LOW = 10
const router = useRouter()
const route = useRoute()
const user = useUserStore()
const loading = ref(false)
const loadError = ref('')
// 首页「看未开票清单」带 ?tab=un 进来时直接停在未开票页签(2026-10-15)
const tab = ref(['sum', 'det', 'un'].includes(String(route.query.tab)) ? String(route.query.tab) : 'sum')
const data = ref<IncomeCostData & { companies: any[]; uninvoiced: any[]; unapprovedInvoices: number } | null>(null)
const companies = ref<any[]>([])
const companyId = ref<number | undefined>()

const pad = (n: number) => String(n).padStart(2, '0')
const fmtD = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const now = new Date()
const range = ref<[string, string]>([fmtD(new Date(now.getFullYear(), now.getMonth(), 1)), fmtD(now)])
let loadSequence = 0
watch(range, () => {
  // The date picker changes before the user queries again; the old period is no longer printable.
  loadSequence++
  data.value = null
  loading.value = false
  loadError.value = ''
}, { deep: true, flush: 'sync' })
onBeforeUnmount(() => {
  loadSequence++
  data.value = null
})
const shortcuts = [
  { text: '本月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth(), 1), d] } },
  { text: '上月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth() - 1, 1), new Date(d.getFullYear(), d.getMonth(), 0)] } },
  { text: '本季度', value: () => { const d = new Date(); const q = Math.floor(d.getMonth() / 3) * 3; return [new Date(d.getFullYear(), q, 1), d] } },
  { text: '本年至今', value: () => { const d = new Date(); return [new Date(d.getFullYear(), 0, 1), d] } }
]

const rateWarnCount = computed(() => (data.value?.detail || []).filter((r: any) => r.rate_warn).length)
const unCost = computed(() => (data.value?.uninvoiced || []).reduce((s: number, r: any) => s + Number(r.cost || 0), 0))
const isLow = (r: any) => r.margin !== null && r.margin !== undefined && (Number(r.profit) < 0 || Number(r.margin) < LOW)

/**
 * 合计行直接用后端算好的 total（BigDecimal 精确值），按列名对位，别在前端用浮点重加。
 * 按列名而不按列序号：表格会被自动插一列「序号」，按序号写会错位（2026-10-01 踩过）。
 */
function totalsBy(map: (t: any) => Record<string, string>) {
  return ({ columns }: { columns: any[] }) => {
    const m = map(data.value?.total || {})
    return columns.map((c: any, i: number) => (i === 0 ? '合计' : m[c.label] ?? ''))
  }
}
const detailSummary = totalsBy(t => ({
  '收入(不含税)': fmtMoney(t.revenue), '税额(原票)': fmtMoney(t.tax), 成本: fmtMoney(t.cost), 毛利: fmtMoney(t.profit), 毛利率: pct(t.margin)
}))
const sumSummary = totalsBy(t => ({
  发票张数: String(t.invoice_count ?? ''), '收入(不含税)': fmtMoney(t.revenue), '其中:退货冲减': Number(t.return_revenue) ? fmtMoney(t.return_revenue) : '—',
  '税额(原票)': fmtMoney(t.tax), 价税合计: fmtMoney(t.total), 销售成本: fmtMoney(t.cost), 毛利: fmtMoney(t.profit), 毛利率: pct(t.margin)
}))

async function load() {
  const requestId = ++loadSequence
  if (!range.value?.[0] || !range.value?.[1]) {
    data.value = null
    loading.value = false
    return
  }
  const query = { companyId: companyId.value, startDate: range.value[0], endDate: range.value[1] }
  loading.value = true
  data.value = null
  loadError.value = ''
  try {
    const res: any = await salesApi.reportIncomeCost(query)
    if (requestId !== loadSequence) return
    data.value = res
    companies.value = res.companies || []
    companyId.value = res.company?.id
  } catch (err: any) {
    if (requestId === loadSequence) loadError.value = err?.message || '报表查询失败，请核对单据后重试'
  } finally {
    if (requestId === loadSequence) loading.value = false
  }
}
function doPrint() { if (data.value) printIncomeCost(data.value, user.userInfo?.name || '') }
function doExport() { if (data.value) exportIncomeCostXls(data.value) }
// 未开票的出库单本来就没有发票,按单号去发票列表搜必然是空的(2026-10-15 业务反馈)。
// 改为带 fromOut 进销售发票页,直接打开「参照出库开票」并选中这张出库单。
function goInvoice(row: any) { return navigateToInvoice(router, { path: '/sale/invoice', query: { fromOut: row.doc_no } }) }
onMounted(load)

// 汇总卡：直接用后端算好的 total（精确值），与表格合计行同一组数
const kpis = computed(() => {
  const t: any = data.value?.total || {}
  return [
    { label: '收入（不含税）', value: fmtMoney(t.revenue), hint: Number(t.return_revenue) ? `已扣退货 ${fmtMoney(Math.abs(Number(t.return_revenue)))}` : '本期无退货' },
    { label: '销售成本', value: fmtMoney(t.cost), hint: '取销售出库结转成本' },
    { label: '毛利', value: fmtMoney(t.profit), hint: `毛利率 ${pct(t.margin)}`, tone: 'blue' as const },
    { label: '没进本表', value: `${(data.value?.uninvoiced || []).length} 张出库`, hint: data.value?.unapprovedInvoices ? `另有 ${data.value.unapprovedInvoices} 张发票待审批` : '已出库未开票，开票后计入', tone: (data.value?.uninvoiced || []).length ? 'amber' as const : undefined }
  ]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const uninvoicedSummary = makeLineSummary({ 物料行数: { field: 'line_count', type: 'count' }, '已结转成本(扣退货)': { field: 'cost', type: 'money' } })
</script>

<style scoped>
.mb-2 { margin-bottom: 10px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.muted { color: var(--el-text-color-secondary); }
.low { color: var(--el-color-danger); font-weight: 600; }
.ml { margin-left: 4px; }
.tab-badge { margin-left: 4px; vertical-align: top; }
:deep(.ret-row td) { color: var(--el-color-danger); }
/* 日期/单号不折行(全站约定：数字/日期列不折行) */
:deep(td.nw .cell) { white-space: nowrap; }
</style>
