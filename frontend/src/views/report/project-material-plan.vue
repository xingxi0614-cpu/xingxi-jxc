<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">工程项目材料计划执行表</div>
        <div class="page-sub">数据来自出库申请单和修订记录；退料会回冲实际执行数量。</div>
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
          <el-form-item data-q="dateRange" label="申请日期">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
            />
          </el-form-item>
          <el-form-item data-q="warehouseId" label="仓库">
            <el-select v-model="q.warehouseId" filterable clearable placeholder="全部仓库">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="projectId" label="工程项目">
            <el-select v-model="q.projectId" filterable clearable placeholder="全部项目">
              <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="businessCategory" label="业务分类">
            <el-select v-model="q.businessCategory" clearable placeholder="全部分类">
              <el-option v-for="t in businessCategories" :key="t" :value="t" :label="t" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="status" label="单据状态">
            <el-select v-model="q.status" clearable placeholder="默认已审批/已出库">
              <el-option label="已审批" value="APPROVED" />
              <el-option label="部分出库" value="PARTIAL_OUT" />
              <el-option label="已出库" value="OUT" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="materialId" label="物料">
            <MaterialLookup v-model="q.materialId" placeholder="按物料编码或名称选择" />
          </el-form-item>
          <el-form-item data-q="onlyRemaining" label="只看未完成">
            <el-switch v-model="q.onlyRemaining" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-empty v-if="!rows.length && !loading" :description="queried ? '没有符合条件的数据，可放宽期间/仓库等条件再查' : '请选择条件后点击查询'" />
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border
        v-else
        :fit="false"
        :data="pageRows" :index-offset="pgOffset"
        v-loading="loading"
        show-summary
        :summary-method="(p: any) => getSummary({ ...p, data: rows })"
        @selection-change="selectedRows = $event"
      >
        <el-table-column type="selection" width="44" :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="request_no" label="出库申请单号" min-width="150" :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="project_name" label="工程项目" min-width="170" show-overflow-tooltip :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="material_code" label="物料编码" min-width="130" show-overflow-tooltip :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="material_name" label="物料名称" min-width="160" show-overflow-tooltip :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="spec" label="规格型号" min-width="120" />
        <el-table-column prop="unit_name" label="单位" width="90" />
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column label="计划数量" align="right" width="110">
          <template #default="{ row }">{{ fmtQty(row.plan_qty) }}</template>
        </el-table-column>
        <el-table-column label="变更数量" align="right" width="110">
          <template #default="{ row }">{{ fmtQty(row.change_qty) }}</template>
        </el-table-column>
        <el-table-column label="变更后数量" align="right" width="120">
          <template #default="{ row }">{{ fmtQty(row.changed_qty) }}</template>
        </el-table-column>
        <el-table-column label="实际出库数量" align="right" width="130">
          <template #default="{ row }">{{ fmtQty(row.actual_out_qty) }}</template>
        </el-table-column>
        <el-table-column label="退料数量" align="right" width="110">
          <template #default="{ row }">{{ fmtQty(row.return_qty) }}</template>
        </el-table-column>
        <el-table-column label="实际单价" align="right" width="110">
          <template #default="{ row }">{{ row.unit_price == null ? '-' : fmtMoney(row.unit_price) }}</template>
        </el-table-column>
        <el-table-column label="实际金额" align="right" width="120">
          <template #default="{ row }">{{ row.amount == null ? '-' : fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="已转交数量" width="120" align="right"><template #default="{row}">{{ fmtQty(row.transferred_qty) }}</template></el-table-column>
        <el-table-column label="不再领数量" width="120" align="right"><template #default="{row}">{{ fmtQty(row.closed_qty) }}</template></el-table-column>
        <el-table-column label="待执行数量" align="right" width="120">
          <template #default="{ row }"><b>{{ fmtQty(row.available_qty) }}</b></template>
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
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useBizOptions } from '@/composables/useBizOptions'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { reportApi, masterApi, sysApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { makeLineSummary } from '@/utils/tableSummary'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, rowsForExport, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const loading = ref(false)
const queried = ref(false)   // 查过但没数据时提示“没有数据”，别让人以为还没查
const rows = ref<any[]>([])
const { page: pgPage, size: pgSize, pageRows, offset: pgOffset } = useClientPager(rows)
const freezeLeft = ref(true)   // 冻结左侧标识列(单号/项目/物料),横向滚动看数字时保持可见
const selectedRows = ref<any[]>([])
const dateRange = ref<any>(null)
const q = reactive<any>({
  warehouseId: null,
  projectId: null,
  businessCategory: '',
  status: '',
  materialId: null,
  onlyRemaining: false,
  startDate: null,
  endDate: null
})
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])
const { businessCategories, loadBizOptions } = useBizOptions()

const exportColumns: ExportColumn[] = [
  { label: '出库申请单号', key: 'request_no' },
  { label: '工程项目', key: 'project_name' },
  { label: '物料编码', key: 'material_code' },
  { label: '物料名称', key: 'material_name' },
  { label: '规格型号', key: 'spec' },
  { label: '单位', key: 'unit_name' },
  { label: '备注', key: 'remark' },
  { label: '计划数量', value: row => fmtQty(row.plan_qty), qty: true },
  { label: '变更数量', value: row => fmtQty(row.change_qty), qty: true },
  { label: '变更后数量', value: row => fmtQty(row.changed_qty), qty: true },
  { label: '实际出库数量', value: row => fmtQty(row.actual_out_qty), qty: true },
  { label: '退料数量', value: row => fmtQty(row.return_qty), qty: true },
  { label: '实际单价', money: true, value: row => row.unit_price == null ? '-' : fmtMoney(row.unit_price) },
  { label: '实际金额', money: true, value: row => row.amount == null ? '-' : fmtMoney(row.amount) },
  { label: '已转交数量', value: row => fmtQty(row.transferred_qty), qty: true },
  { label: '不再领数量', value: row => fmtQty(row.closed_qty), qty: true },
  { label: '待执行数量', value: row => fmtQty(row.available_qty), qty: true }
]

function fmtQty(v: any) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
}

function reset() {
  queried.value = false
  dateRange.value = null
  Object.assign(q, {
    warehouseId: null,
    projectId: null,
    businessCategory: '',
    status: '',
    materialId: null,
    onlyRemaining: false,
    startDate: null,
    endDate: null
  })
  rows.value = []
  selectedRows.value = []
}

async function doQuery() {
  queried.value = true
  q.startDate = dateRange.value?.[0] || null
  q.endDate = dateRange.value?.[1] || null
  loading.value = true
  try {
    rows.value = await reportApi.projectMaterialPlan(q) as any
  } finally {
    loading.value = false
  }
}

// 按列名合计（原来按列序号写，错位一列且字段顺序与列不一致；单价加总无意义，不合计）
const getSummary = makeLineSummary({
  计划数量: { field: 'plan_qty', type: 'qty' },
  变更数量: { field: 'change_qty', type: 'qty' },
  变更后数量: { field: 'changed_qty', type: 'qty' },
  实际出库数量: { field: 'actual_out_qty', type: 'qty' },
  退料数量: { field: 'return_qty', type: 'qty' },
  实际金额: { field: 'amount', type: 'money' },
  已转交数量: { field: 'transferred_qty', type: 'qty' },
  不再领数量: { field: 'closed_qty', type: 'qty' },
  待执行数量: { field: 'available_qty', type: 'qty' }
}, '合计', fmtQty)

function exportExcel() {
  exportRowsToExcel('工程项目材料计划执行表', exportColumns, rowsForExport(rows.value, selectedRows.value), '工程项目材料计划执行表')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('工程项目材料计划执行表', exportColumns, rowsForExport(rows.value, selectedRows.value), action, paper, '工程材料计划执行')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns, rowsForExport(rows.value, selectedRows.value))
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

onMounted(async () => {
  const [whs, projs, categories] = await Promise.all([
    masterApi.warehouses(),
    masterApi.projects(true),
    sysApi.optionList('business.category.options').catch(() => [])
  ])
  warehouses.value = whs as any
  projects.value = projs as any
  if ((categories as any[])?.length) businessCategories.value = categories as any
})

// 顶部汇总卡（2026-10-17 全站界面改版）：按查出来的全部行计算；金额被隐藏（无权限）时显示「—」，不显示误导人的 0
const sumOf = (list: any[], f: string) => list.reduce((a, r) => a + Number(r[f] || 0), 0)
const moneyOrDash = (list: any[], f: string) => list.length && list.every(r => r[f] == null) ? '—' : fmtMoney(sumOf(list, f))
const distinct = (list: any[], f: string) => new Set(list.map(r => r[f])).size
const kpis = computed(() => {
  const pending = rows.value.filter(r => Number(r.available_qty || 0) > 0)
  return [
    { label: '申请明细', value: `${rows.value.length} 行`, hint: `${distinct(rows.value, 'request_no')} 张出库申请` },
    { label: '涉及工程项目', value: `${distinct(rows.value, 'project_name')} 个` },
    { label: '实际出库金额', value: moneyOrDash(rows.value, 'amount'), hint: '已扣退料', tone: 'blue' as const },
    { label: '还有待执行', value: `${pending.length} 行`, hint: '计划量还没领完', tone: pending.length ? 'amber' as const : undefined }
  ]
})
</script>
