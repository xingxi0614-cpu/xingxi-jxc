<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">存货明细账</div>
        <div class="page-sub">按公司逐笔看一种物料的收、发、结存(对应财务软件存货明细账);口径与收发存汇总(按公司)一致。</div>
      </div>
      <el-space>
        <el-button :icon="Download" :disabled="!ledger" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!ledger" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
      </el-space>
    </div>

    <el-card shadow="never">
      <QueryDrawer :model="q" @search="doQuery" @reset="reset" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :model="q" inline>
          <el-form-item v-if="companies.length > 1" label="公司" required data-q="orgId">
            <el-select v-model="q.orgId" placeholder="请选择公司" style="width:200px">
              <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="物料" required data-q="materialId">
            <MaterialLookup v-model="q.materialId" placeholder="按物料编码或名称选择" />
          </el-form-item>
          <el-form-item label="期间" data-q="dateRange">
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
              range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
          </el-form-item>
          <el-form-item label="仓库" data-q="warehouseId">
            <el-select v-model="q.warehouseId" placeholder="全部仓库(全公司)" clearable filterable>
              <el-option v-for="w in companyWarehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="单据类型" data-q="docTypes">
            <el-select v-model="q.docTypes" placeholder="全部单据类型" multiple clearable collapse-tags collapse-tags-tooltip>
              <el-option v-for="t in DOC_TYPE_OPTIONS" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-empty v-if="!ledger && !loading" description="请选择物料后点击查询(不填期间默认本年初到今天)" />
      <template v-else-if="ledger">
        <div class="meta">
          <span>成本域(公司):<b>{{ ledger.orgName }}</b></span>
          <span>物料:<b>{{ ledger.material.code }} {{ ledger.material.name }}</b></span>
          <span>规格:<b>{{ ledger.material.spec || '-' }}</b></span>
          <span>主单位:<b>{{ ledger.material.unit || '-' }}</b></span>
          <span>期间:<b>{{ ledger.startDate }} ~ {{ ledger.endDate }}</b></span>
          <span class="spacer" />
          <el-checkbox v-model="showWarehouse">显示仓库</el-checkbox>
          <el-checkbox v-model="showTransfer">显示转库明细行</el-checkbox>
        </div>
        <el-alert v-if="!ledger.qtyOnly" type="info" show-icon :closable="false" class="mb-2"
          :title="showTransfer ? '2026-10-15 22:04 起按公司核算;此前的单据按当时实际记账金额列示,结存按全公司累计。出光那一笔若有尾差,在备注里注明。'
                               : '已隐藏转库明细行(只是不显示);本月合计、本年累计仍包含转库,与收发存汇总一致。'" />
        <el-alert v-if="ledger.qtyOnly" type="warning" show-icon :closable="false" class="mb-2"
          :title="`已选仓库「${ledger.warehouseName || ''}」:只显示这个仓库自己的收、发、结存数量(含转入转出)。按公司核算后仓库没有独立金额,看金额请不选仓库。`" />
        <el-alert v-else-if="ledger.costMasked" type="warning" show-icon :closable="false" class="mb-2" title="当前岗位只看数量,单价和金额不显示" />
        <el-alert v-if="ledger.docTypesFiltered" type="info" show-icon :closable="false" class="mb-2"
          title="只显示所选单据类型;结存列仍是包含全部单据的实际结存,本月合计/本年累计只合计显示出来的这些类型。" />

        <el-table :data="viewRows" border class="readable-table ledger" :fit="false" max-height="calc(100vh - 300px)" v-loading="loading" :row-class-name="rowClass">
          <!-- 自带「序号」列:全站表格不再自动插序号;只给明细行编号,期初/合计行留空 -->
          <el-table-column label="序号" width="60" align="center"><template #default="{ row }">{{ row._no || '' }}</template></el-table-column>
          <el-table-column label="日期" width="110"><template #default="{ row }">{{ row.date || '' }}</template></el-table-column>
          <el-table-column label="单据号" min-width="240">
            <template #default="{ row }">
              <b v-if="row.kind !== 'ROW'">{{ row.label }}</b>
              <button v-else-if="docPath(row.docType)" type="button" class="doc-link" @click="openDoc(row)">{{ row.docNo }}</button>
              <span v-else>{{ row.docNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源单据号" min-width="230" show-overflow-tooltip><template #default="{ row }">{{ row.sourceNo || '' }}</template></el-table-column>
          <el-table-column label="单据类型" min-width="130"><template #default="{ row }">{{ row.typeLabel || '' }}</template></el-table-column>
          <el-table-column v-if="showWarehouse" label="仓库" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.warehouse || '' }}</template></el-table-column>
          <el-table-column label="备注" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ remarkText(row) }}</template></el-table-column>
          <el-table-column label="收入" align="center">
            <el-table-column label="数量" align="right" width="100"><template #default="{ row }"><span :class="neg(row.inQty)">{{ qty(row.inQty) }}</span></template></el-table-column>
            <el-table-column v-if="!masked" label="单价" align="right" width="100"><template #default="{ row }">{{ price(row.inPrice) }}</template></el-table-column>
            <el-table-column v-if="!masked" label="金额" align="right" width="120"><template #default="{ row }"><span :class="neg(row.inAmt)">{{ money(row.inAmt) }}</span></template></el-table-column>
          </el-table-column>
          <el-table-column label="发出" align="center">
            <el-table-column label="数量" align="right" width="100"><template #default="{ row }"><span :class="neg(row.outQty)">{{ qty(row.outQty) }}</span></template></el-table-column>
            <el-table-column v-if="!masked" label="单价" align="right" width="100"><template #default="{ row }">{{ price(row.outPrice) }}</template></el-table-column>
            <el-table-column v-if="!masked" label="金额" align="right" width="120"><template #default="{ row }"><span :class="neg(row.outAmt)">{{ money(row.outAmt) }}</span></template></el-table-column>
          </el-table-column>
          <el-table-column label="结存" align="center">
            <el-table-column label="数量" align="right" width="100"><template #default="{ row }">{{ qty(row.balQty) }}</template></el-table-column>
            <el-table-column v-if="!masked" label="单价" align="right" width="100"><template #default="{ row }">{{ price(row.balPrice) }}</template></el-table-column>
            <el-table-column v-if="!masked" label="金额" align="right" width="120"><template #default="{ row }">{{ money(row.balAmt) }}</template></el-table-column>
          </el-table-column>
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download } from '@/icons'
import { masterApi, reportApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, printOrExportRows, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const router = useRouter()
const loading = ref(false)
const ledger = ref<any>(null)
const companies = ref<any[]>([])
const dateRange = ref<any>(null)
const q = reactive<any>({ orgId: null, materialId: null, warehouseId: null, docTypes: [] as string[] })
// 仓库只列所选公司的(明细账一次看一家公司);数据本身由后端按账号可见范围给
const warehouses = ref<any[]>([])
const companyWarehouses = computed(() => warehouses.value.filter((w: any) => !q.orgId || w.orgId === q.orgId))
watch(() => q.orgId, () => { if (q.warehouseId && !companyWarehouses.value.some((w: any) => w.id === q.warehouseId)) q.warehouseId = null })
// 单据类型与后端 typeLabel 一一对应;期初有 OPENING/INIT 两种写法,一并带上
const DOC_TYPE_OPTIONS = [
  { value: 'RECV', label: '采购入库' }, { value: 'PRET', label: '采购退货' }, { value: 'INV', label: '采购发票调整' },
  { value: 'OUT', label: '材料出库' }, { value: 'RETURN', label: '退料' }, { value: 'TRANSFER', label: '转库' },
  { value: 'STOCKTAKE', label: '盘盈/盘亏' }, { value: 'SALE_OUT', label: '销售出库' }, { value: 'SALE_RETURN', label: '销售退货' },
  { value: 'LOAN', label: '借料' }, { value: 'LOAN_RETURN', label: '借料归还' }, { value: 'OPENING,INIT', label: '期初导入' }
]
const showWarehouse = ref(true)
const showTransfer = ref(true)
const masked = computed(() => !!ledger.value?.costMasked)

// 期初 + 明细(含本月合计/本年累计);隐藏转库只隐藏明细行,合计不变(同收发存口径)
const viewRows = computed(() => {
  if (!ledger.value) return []
  let n = 0
  const rows = ledger.value.rows
    .filter((r: any) => showTransfer.value || !(r.kind === 'ROW' && r.docType === 'TRANSFER'))
    .map((r: any) => (r.kind === 'ROW' ? { ...r, _no: ++n } : r))
  return [ledger.value.opening, ...rows]
})

// 单据号点进对应列表并按单号筛出该单(各列表都支持 ?keyword=)
const DOC_PATHS: Record<string, string> = {
  RECV: '/inbound/recv', OUT: '/outbound/out', RETURN: '/outbound/return', TRANSFER: '/inventory/transfer',
  SALE_OUT: '/sale/out', SALE_RETURN: '/sale/return', PRET: '/inbound/purchase-return', LOAN: '/outbound/loan',
  LOAN_RETURN: '/outbound/loan', INV: '/invoice/list'
}
function docPath(type?: string) { return type ? DOC_PATHS[type] : '' }
function openDoc(row: any) { router.push({ path: docPath(row.docType), query: { keyword: row.docNo, auto: '1' } }) }

function rowClass({ row }: any) {
  if (row.kind === 'OPENING') return 'row-open'
  if (row.kind === 'MONTH') return 'row-month'
  if (row.kind === 'YEAR') return 'row-year'
  return row.docType === 'TRANSFER' ? 'row-transfer' : ''
}
// 备注只留有用的话:系统自动写的"材料出库/暂估入库/转库出库："等与单据类型重复,去掉;
// 保留转库单上业务填的备注、出光尾差说明、取消审批原因
const AUTO_REMARKS = ['转库出库：', '转库入库：', '暂估入库', '发票入库', '材料出库', '退料入库', '借料登记', '借料归还',
  '销售出库', '销售退货入库', '采购退货', '期初库存导入(Excel)', '盘盈调整', '盘亏调整']
function remarkText(row: any) {
  if (row.kind !== 'ROW') return ''
  let r = String(row.remark || '').trim()
  for (const g of AUTO_REMARKS) if (r.startsWith(g)) { r = r.slice(g.length).trim(); break }
  if (r === row.docNo) r = ''
  return r.replace(/^（(.*)）$/, '$1')
}
function isNum(v: any) { return v !== null && v !== undefined && v !== '' }
function qty(v: any) { return isNum(v) ? Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '' }
function price(v: any) { return isNum(v) ? fmtMoney(v) : '' }
function money(v: any) { return isNum(v) ? fmtMoney(v) : '' }
function neg(v: any) { return isNum(v) && Number(v) < 0 ? 'neg' : '' }

async function doQuery() {
  if (companies.value.length > 1 && !q.orgId) { ElMessage.warning('请选择公司'); return }
  if (!q.materialId) { ElMessage.warning('请选择物料'); return }
  loading.value = true
  try {
    ledger.value = await reportApi.inventoryLedger({
      orgId: q.orgId, materialId: q.materialId,
      startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null,
      warehouseId: q.warehouseId || null,
      docTypes: q.docTypes.length ? q.docTypes.join(',') : null
    }) as any
    if (ledger.value) {
      ledger.value.warehouseName = q.warehouseId ? warehouses.value.find((w: any) => w.id === q.warehouseId)?.name : ''
      ledger.value.docTypesFiltered = q.docTypes.length > 0
    }
  } finally {
    loading.value = false
  }
}

function reset() {
  ledger.value = null
  dateRange.value = null
  q.materialId = null
  q.warehouseId = null
  q.docTypes = []
  if (companies.value.length > 1) q.orgId = null
}

function exportColumns(): ExportColumn[] {
  const cols: ExportColumn[] = [
    { label: '日期', key: 'date' },
    { label: '单据号', value: (r: any) => r.kind === 'ROW' ? r.docNo : r.label },
    { label: '来源单据号', key: 'sourceNo' },
    { label: '单据类型', key: 'typeLabel' },
    ...(showWarehouse.value ? [{ label: '仓库', key: 'warehouse' }] : []),
    { label: '备注', value: (r: any) => remarkText(r) },
    { label: '收入数量', key: 'inQty', qty: true }
  ]
  if (!masked.value) cols.push({ label: '收入单价', money: true, value: (r: any) => price(r.inPrice) }, { label: '收入金额', money: true, value: (r: any) => money(r.inAmt) })
  cols.push({ label: '发出数量', key: 'outQty', qty: true })
  if (!masked.value) cols.push({ label: '发出单价', money: true, value: (r: any) => price(r.outPrice) }, { label: '发出金额', money: true, value: (r: any) => money(r.outAmt) })
  cols.push({ label: '结存数量', key: 'balQty', qty: true })
  if (!masked.value) cols.push({ label: '结存单价', money: true, value: (r: any) => price(r.balPrice) }, { label: '结存金额', money: true, value: (r: any) => money(r.balAmt) })
  return cols
}
function title() {
  const l = ledger.value
  return `存货明细账 ${l.orgName}${l.warehouseName ? ' ' + l.warehouseName : ''} ${l.material.code} ${l.material.name} ${l.startDate}~${l.endDate}`
}
function exportExcel() { exportRowsToExcel(title(), exportColumns(), viewRows.value, '存货明细账') }
function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('存货明细账', exportColumns(), viewRows.value, action, paper,
    `${ledger.value.orgName} · ${ledger.value.material.code} ${ledger.value.material.name}(${ledger.value.material.spec || '-'}) · ${ledger.value.startDate} ~ ${ledger.value.endDate}`)
}

onMounted(async () => {
  companies.value = await reportApi.inventoryLedgerCompanies() as any
  masterApi.warehouses(true).then((list: any) => { warehouses.value = list || [] }).catch(() => {})
  if (companies.value.length === 1) q.orgId = companies.value[0].id
})
</script>

<style scoped lang="scss">
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 24px; padding: 4px 2px 10px; font-size: 13px; color: var(--el-text-color-regular); }
.meta b { color: var(--el-text-color-primary); }
.meta .spacer { flex: 1; }
.mb-2 { margin-bottom: 8px; }
.neg { color: var(--el-color-danger); }
.ledger :deep(.row-open td), .ledger :deep(.row-month td) { background: var(--el-fill-color-lighter); font-weight: 600; }
.ledger :deep(.row-year td) { background: var(--el-color-primary-light-9); font-weight: 600; }
.ledger :deep(.row-transfer td) { color: var(--el-text-color-regular); }
</style>
