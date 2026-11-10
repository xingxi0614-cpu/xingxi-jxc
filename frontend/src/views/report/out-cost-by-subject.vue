<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">出库成本科目汇总</div>
        <div class="page-sub">按期间把材料出库金额（退料自动负数冲减）按「出库类型 → 会计科目」归集，供材料会计照科目金额去财务软件记账。仅统计已审批的材料出库/退料。</div>
      </div>
      <el-space>
        <el-button :disabled="!bySubject.length" @click="exportExcel">导出Excel</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" size="default" style="margin-bottom:8px">
        <el-form-item v-if="companies.length > 1" label="公司">
          <el-select v-model="orgId" placeholder="全部（汇总）" clearable filterable style="width:200px" @change="load">
            <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="出库期间">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:260px" :shortcuts="dateShortcuts" @change="load" />
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="load">查询</el-button></el-form-item>
      </el-form>

      <KpiCards :items="kpis" />

      <div class="tbl-title">① 按会计科目汇总（记账口径）</div>
      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="bySubject" v-loading="loading" empty-text="所选期间没有已审批的材料出库" show-summary :summary-method="sumSubject">
        <el-table-column label="总账借方科目" prop="subject" min-width="320" show-overflow-tooltip>
          <template #default="{ row }"><span class="subj" :class="{ warn: row.subject.includes('未配置') || row.subject.includes('未填') }">{{ row.subject }}</span></template>
        </el-table-column>
        <el-table-column label="金额" width="180" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
      </el-table>

      <div class="tbl-title" style="margin-top:16px">② 按出库类型明细</div>
      <el-table class="readable-table" border :data="byType" v-loading="loading" empty-text="无" show-summary :summary-method="sumByType">
        <el-table-column label="适用范围" prop="category" width="120"><template #default="{ row }">{{ row.category || '-' }}</template></el-table-column>
        <el-table-column label="出库类型" prop="out_type" min-width="170" show-overflow-tooltip />
        <el-table-column label="对应科目" prop="subject" min-width="260" show-overflow-tooltip>
          <template #default="{ row }"><span class="subj" :class="{ warn: row.subject.includes('未配置') || row.subject.includes('未填') }">{{ row.subject }}</span></template>
        </el-table-column>
        <el-table-column label="单据数" prop="doc_cnt" width="90" align="right" />
        <el-table-column label="金额" width="150" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
      </el-table>

      <el-alert type="info" :closable="false" show-icon class="mt-3"
        title="金额=材料出库金额−退料金额（系统按移动加权平均算成本）。标红的「未配置科目/未填出库类型」说明有出库单的类型没在【基础资料→出库类型科目映射】里配好，请补映射或修正单据后重查。" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, ref } from 'vue'
import { reportApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { makeLineSummary } from '@/utils/tableSummary'

const loading = ref(false)
const bySubject = ref<any[]>([])
const byType = ref<any[]>([])
const companies = ref<any[]>([])
const total = ref(0)
const orgId = ref<number | null>(null)
const dateRange = ref<any>(null)

function firstOfMonth() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01` }
function today() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const dateShortcuts = [
  { text: '本月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth(), 1), d] } },
  { text: '上月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth() - 1, 1), new Date(d.getFullYear(), d.getMonth(), 0)] } },
  { text: '本季度', value: () => { const d = new Date(); const q = Math.floor(d.getMonth() / 3) * 3; return [new Date(d.getFullYear(), q, 1), d] } },
  { text: '本年至今', value: () => { const d = new Date(); return [new Date(d.getFullYear(), 0, 1), d] } }
]

async function load() {
  loading.value = true
  try {
    const r: any = await reportApi.outCostBySubject({
      orgId: orgId.value,
      startDate: dateRange.value?.[0] || null,
      endDate: dateRange.value?.[1] || null
    })
    bySubject.value = r.bySubject || []
    byType.value = r.byType || []
    companies.value = r.companies || []
    total.value = Number(r.total || 0)
  } finally { loading.value = false }
}

// 按列名合计：表格会自动插「序号」列、固定列排最前，按列序号写会错位（2026-10-01 修）
const sumSubject = makeLineSummary({ 金额: { field: 'amount', type: 'money' } })

const exportColumns: ExportColumn[] = [
  { label: '总账借方科目', key: 'subject' },
  { label: '金额', money: true, value: (r: any) => fmtMoney(r.amount) }
]
function exportExcel() { exportRowsToExcel('出库成本科目汇总', exportColumns, bySubject.value, '出库成本科目汇总') }

onMounted(() => { dateRange.value = [firstOfMonth(), today()]; load() })

const kpis = computed(() => {
  const unset = byType.value.filter((r: any) => String(r.subject || '').includes('未配置') || String(r.subject || '').includes('未填'))
  return [
    { label: '本期出库成本合计', value: fmtMoney(total.value), hint: '退料已负数冲减', tone: 'blue' as const },
    { label: '会计科目', value: `${bySubject.value.length} 个` },
    { label: '出库类型', value: `${byType.value.length} 类` },
    { label: '没配科目', value: `${unset.length} 类`, hint: unset.length ? '先去「出库类型科目映射」补上' : '全部已配', tone: unset.length ? 'red' as const : 'green' as const }
  ]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const sumByType = makeLineSummary({ 单据数: { field: 'doc_cnt', type: 'count' }, 金额: { field: 'amount', type: 'money' } })
</script>

<style scoped>
.rc-total { font-size: 14px; color: #374151; margin: 4px 0 12px; }
.rc-total b { font-size: 20px; color: #2465a8; }
.tbl-title { font-weight: 700; color: #374151; margin: 8px 0; }
.subj { font-family: ui-monospace, Menlo, monospace; }
.subj.warn { color: #d64b46; font-weight: 600; }
.mt-3 { margin-top: 12px; }
</style>
