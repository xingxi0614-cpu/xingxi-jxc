<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购成本差异调整表</div>
        <div class="page-sub">
          发票与入库的差额:<b>在库部分调库存单价、已领用部分计入当期损益</b>(暂估开票、货票同到同一规则);
          采购退货按原入库价冲库存,库存不足以冲原价时差额计入损益。发票按开票日期、退货按退货日期归属期间。
          <b>进损益部分</b>在外部账做完分录后,回来点「本月差异已入账」——<b>未确认的月份不能结账</b>。
        </div>
      </div>
      <el-space>
        <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
        <el-button @click="loadAll">刷新</el-button>
      </el-space>
    </div>

    <KpiCards v-if="summary.length" :items="kpis" />
    <!-- 上半:按月汇总 + 确认动作 -->
    <el-card shadow="never" class="mb-3">
      <div class="sec-title">按月汇总(这就是做分录要的数)</div>
      <el-table :data="visibleSummary" border class="readable-table" v-loading="loadingSum" empty-text="暂无差异">
        <el-table-column prop="period_month" label="期间" width="110" />
        <el-table-column prop="org_name" label="公司" min-width="150" show-overflow-tooltip />
        <el-table-column label="进损益合计(要做分录)" width="170" align="right">
          <template #default="{ row }">
            <b :class="Number(row.diff_amount) < 0 ? 'neg' : ''">{{ fmtMoney(row.diff_amount) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="进库存合计" width="130" align="right">
          <template #default="{ row }">
            <span :class="Number(row.in_stock_amount) < 0 ? 'neg' : ''">{{ fmtMoney(row.in_stock_amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="entry_count" label="笔数" width="80" align="right" />
        <el-table-column label="入账状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.confirm_status === 'CONFIRMED'" type="success">已入账</el-tag>
            <el-tag v-else-if="row.confirm_status === 'CHANGED'" type="warning">有新增待补</el-tag>
            <el-tag v-else type="danger">未入账</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认人 / 时间" min-width="190">
          <template #default="{ row }">
            <span v-if="row.confirmed_at">{{ row.confirmed_name || '-' }} · {{ row.confirmed_at }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="确认时金额" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.confirmed_amount !== null && row.confirmed_amount !== undefined">
              {{ fmtMoney(row.confirmed_amount) }}
              <div v-if="row.confirm_status === 'CHANGED'" class="delta">
                之后又新增 {{ fmtMoney(Number(row.diff_amount) - Number(row.confirmed_amount)) }}
              </div>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="pick(row)">看明细</el-button>
            <el-button
              v-if="row.confirm_status !== 'CONFIRMED'"
              link type="success" @click="confirmMonth(row)"
            >{{ row.confirm_status === 'CHANGED' ? '重新确认' : '已入账' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 下半:差异明细 -->
    <el-card shadow="never">
      <!-- 2026-10-20 查询面板第二批:按期间 / 单据号 / 供应商 / 物料查明细(上面按月汇总点「看明细」也是改这里的期间) -->
      <QueryDrawer :model="query" @search="loadList" @reset="resetQuery">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
          <el-form-item label="期间" data-q="periodMonth">
            <el-date-picker v-model="query.periodMonth" type="month" value-format="YYYY-MM" placeholder="全部期间" clearable style="width:150px" />
          </el-form-item>
          <el-form-item label="单据号" data-q="keyword">
            <el-input v-model="query.keyword" placeholder="发票号 / 退货单号" clearable style="width:200px" @keyup.enter="loadList" />
          </el-form-item>
          <el-form-item label="供应商" data-q="supplierId">
            <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
          </el-form-item>
          <el-form-item label="物料" data-q="material">
            <el-input v-model="query.material" placeholder="编码或名称" clearable style="width:180px" @keyup.enter="loadList" />
          </el-form-item>
        </el-form>
      </QueryDrawer>
      <div class="sec-title">
        差异明细
        <span v-if="period" class="muted">· 期间 {{ period }}</span>
        <el-button v-if="period" link type="primary" @click="period=''; loadList()">看全部</el-button>
      </div>
      <el-table export-select :data="rows" border class="readable-table" v-loading="loading" show-summary
                :summary-method="sumRow" max-height="calc(100vh - 460px)" empty-text="暂无差异">
        <el-table-column prop="period_month" label="期间" width="100" />
        <el-table-column prop="invoice_date" label="单据日期" width="110" />
        <el-table-column prop="invoice_no" label="单据号" width="180" show-overflow-tooltip />
        <el-table-column label="来源" width="190">
          <template #default="{ row }">
            {{ row.source_type }}<el-tag v-if="Number(row.is_reverse) === 1" size="small" type="info" class="ml-1">取消审批</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="supplier_name" label="供应商" min-width="150" show-overflow-tooltip />
        <el-table-column prop="material_code" label="物料编码" width="130" />
        <el-table-column prop="material_name" label="物料名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="org_name" label="公司" min-width="120" show-overflow-tooltip />
        <el-table-column label="总差额" width="120" align="right">
          <template #default="{ row }">
            <span :class="Number(row.total_amount) < 0 ? 'neg' : ''">{{ fmtMoney(row.total_amount) }}</span>
            <div v-if="String(row.remark || '').includes('超1%')" class="warn-flag">⚠ 差异超1%,请复核</div>
          </template>
        </el-table-column>
        <el-table-column label="进库存" width="110" align="right">
          <template #default="{ row }">{{ fmtMoney(row.in_stock_amount) }}</template>
        </el-table-column>
        <el-table-column label="进损益" width="110" align="right">
          <template #default="{ row }">
            <span :class="Number(row.diff_amount) < 0 ? 'neg' : ''">{{ fmtMoney(row.diff_amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="240" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '@/api'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'

const summary = ref<any[]>([])
const rows = ref<any[]>([])
const loading = ref(false)
const loadingSum = ref(false)
const route = useRoute()
const query = reactive<any>({
  periodMonth: /^\d{4}-\d{2}$/.test(String(route.query.periodMonth || '')) ? String(route.query.periodMonth) : '',
  keyword: '', supplierId: null, material: ''
})
/** 期间同时管上面的按月汇总(只看这个月)和下面的明细 */
const period = computed({ get: () => query.periodMonth || '', set: (v: string) => { query.periodMonth = v } })
const visibleSummary = computed(() => period.value ? summary.value.filter(row => row.period_month === period.value) : summary.value)

function fmtMoney(v: any) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function loadSummary() {
  loadingSum.value = true
  try { summary.value = (await reportApi.estDiffSummary()) as any || [] }
  finally { loadingSum.value = false }
}
async function loadList() {
  loading.value = true
  try { rows.value = (await reportApi.estDiffList({ ...query, periodMonth: period.value || null })) as any || [] }
  finally { loading.value = false }
}
async function loadAll() { await Promise.all([loadSummary(), loadList()]) }
function resetQuery() { Object.assign(query, { periodMonth: '', keyword: '', supplierId: null, material: '' }); loadList() }

function pick(row: any) { period.value = row.period_month; loadList() }

/** 确认前把数字摆给人看再让他点 —— 这个动作对应外部账的一笔分录,不能点得太随意 */
async function confirmMonth(row: any) {
  const ok = await ElMessageBox.confirm(
    `确认【${row.org_name} · ${row.period_month}】进当期损益的采购成本差异 ${fmtMoney(row.diff_amount)} 元(${row.entry_count} 笔)已在账务系统入账?\n\n` +
    `确认后该月即可结账。若之后又补录了迟到的发票,系统会提示需要补做分录。`,
    '确认已入账', { type: 'warning', confirmButtonText: '确认已入账', cancelButtonText: '取消' }
  ).catch(() => null)
  if (!ok) return
  await reportApi.estDiffConfirm({ periodMonth: row.period_month, orgId: row.org_id })
  ElMessage.success('已记录:' + row.period_month + ' 差异已入账')
  await loadSummary()
}

function sumRow({ columns, data }: { columns: any[]; data: any[] }) {
  return columns.map((c: any, i: number) => {
    if (i === 0) return '合计'
    const key = ({ '总差额': 'total_amount', '进库存': 'in_stock_amount', '进损益': 'diff_amount' } as Record<string, string>)[c.label]
    if (key) return fmtMoney(data.reduce((s, r) => s + Number(r[key] || 0), 0))
    return ''
  })
}

/** 导出用项目统一的封装(与其它报表同一套,格式/编码/文件名规则一致) */
const exportColumns: ExportColumn[] = [
  { label: '期间',     key: 'period_month' },
  { label: '单据日期', key: 'invoice_date' },
  { label: '单据号',   key: 'invoice_no' },
  { label: '来源',     value: (r: any) => r.source_type + (Number(r.is_reverse) === 1 ? '(取消审批)' : '') },
  { label: '供应商',   key: 'supplier_name' },
  { label: '物料编码', key: 'material_code' },
  { label: '物料名称', key: 'material_name' },
  { label: '公司',     key: 'org_name' },
  { label: '总差额',   money: true, value: (r: any) => Number(r.total_amount || 0).toFixed(2) },
  { label: '进库存',   money: true, value: (r: any) => Number(r.in_stock_amount || 0).toFixed(2) },
  { label: '进损益',   money: true, value: (r: any) => Number(r.diff_amount || 0).toFixed(2) },
  { label: '说明',     key: 'remark' },
]
function exportExcel() {
  exportRowsToExcel('采购成本差异调整表', exportColumns, rows.value, '采购成本差异调整表')
}

onMounted(loadAll)

// 汇总卡：跟随上表当前显示的月份（选了某月就只算那个月）
const kpis = computed(() => {
  const list = visibleSummary.value
  const open = list.filter((r: any) => r.confirm_status !== 'CONFIRMED')
  const sum = (f: string) => list.reduce((a: number, r: any) => a + Number(r[f] || 0), 0)
  return [
    { label: '还没确认入账', value: `${open.length} 个月`, hint: open.length ? '未确认的月份不能结账' : '全部已入账', tone: open.length ? 'red' as const : 'green' as const },
    { label: '进损益合计（要做分录）', value: fmtMoney(sum('diff_amount')), tone: 'blue' as const },
    { label: '进库存合计', value: fmtMoney(sum('in_stock_amount')), hint: '已调库存单价' },
    { label: '差异笔数', value: `${sum('entry_count')} 笔` }
  ]
})
</script>

<style scoped>
.mb-3 { margin-bottom: 14px; }
.sec-title { font-size: 14px; font-weight: 650; margin-bottom: 10px; color: var(--text-1, #1a1f29); }
.muted { color: var(--text-3, #98a1b0); font-weight: 400; }
.neg { color: #d93b3b; }
.delta { font-size: 11px; color: #d6881f; }
.ml-1 { margin-left: 4px; }
.warn-flag { font-size: 11px; color: #d93b3b; font-weight: 600; white-space: nowrap; }
</style>
