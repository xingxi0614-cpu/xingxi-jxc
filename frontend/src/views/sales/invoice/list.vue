<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售发票维护</div>
        <div class="page-sub">货物发出后参照销售出库开票，发票类型分增值税专票/普通发票，税率按本公司实际维护。审批后确认收入与应收账款。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!rows.length" :title="!rows.length ? '当前没有数据' : '勾了哪几行导哪几行；不勾导出全部查询结果'" @click="exportExcel">导出Excel</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">+ 参照出库开票</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
          <el-form-item label="状态" data-q="status">
            <el-select v-model="query.status" placeholder="全部" clearable style="width:130px" @change="doSearch">
              <el-option v-for="s in statusOptions" :key="s.v" :value="s.v" :label="s.l" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键词" data-q="keyword"><el-input v-model="query.keyword" placeholder="单号/发票号/客户" clearable style="width:190px" @keyup.enter="doSearch" /></el-form-item>
          <el-form-item label="开票日期" data-q="dateRange"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="doSearch" /></el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" empty-text="暂无数据" @sort-change="onSortChange">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="发票单号" width="190" sortable="custom"><template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template></el-table-column>
        <el-table-column prop="invoiceType" label="发票类型" width="140" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="invoiceNo" label="发票号" width="140" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="sourceOutNo" label="来源出库" width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="customerName" sortable="custom" label="客户" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
        <el-table-column prop="invoiceDate" label="开票日期" width="110" sortable="custom" />
        <el-table-column label="不含税" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
        <el-table-column label="销项税" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.taxAmt) }}</template></el-table-column>
        <el-table-column prop="totalAmt" sortable="custom" label="价税合计" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template></el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="270"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">查看</el-button><el-button v-if="canEdit && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="doSubmit(row.id)">提交</el-button><el-button v-if="row.status === 'PENDING' && user.ownsDoc(row)" link type="warning" @click="doWithdraw(row.id)">撤回</el-button><el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('SALE_INV', row, load)">取消审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="success" @click="doApprove(row.id)">审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="danger" @click="doReject(row.id)">驳回</el-button></template></el-table-column>
      </el-table>
      <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total" :current-page="query.current" :page-size="query.size" @current-change="onPage" /></div>
    </el-card>

    <!-- 参照出库开票 -->
    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="参照销售出库开票" width="min(1000px, calc(100vw - 48px))" align-center>
      <DocGuardHint :active="createVisible" :date="invForm.invoiceDate" :source-org-id="pickedOut?.orgId" v-model:blocked="guardBlocked" />
      <template v-if="!pickedOut">
        <el-input v-model="outKeyword" placeholder="搜索出库单号/客户后回车" clearable style="width:280px;margin-bottom:10px" @keyup.enter="loadAvailableOut" @clear="loadAvailableOut" />
        <el-table class="readable-table" border :data="availableOut" height="360" v-loading="outLoading" empty-text="没有可开票的已审批销售出库单">
          <el-table-column prop="docNo" label="出库单号" width="200" />
          <el-table-column prop="sourceSoNo" label="来源订单" width="180" show-overflow-tooltip />
          <el-table-column label="客户" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
          <el-table-column label="物料" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ fmtMaterials(row.materialSummary) }}</template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.totalQty) }}</template></el-table-column>
          <el-table-column label="" width="80"><template #default="{ row }"><el-button link type="primary" @click="pickedOut = row">选择 →</el-button></template></el-table-column>
        </el-table>
      </template>
      <template v-else>
        <el-descriptions :column="2" border size="small" class="mb-3">
          <el-descriptions-item label="来源出库单">{{ pickedOut.docNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ pickedOut.customerName || pickedOut.customerUnit || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top" :model="invForm" label-width="92px">
          <el-row :gutter="16">
            <el-col :span="12"><el-form-item label="发票类型"><el-select v-model="invForm.invoiceType" style="width:100%"><el-option v-for="t in invoiceTypes" :key="t" :value="t" :label="t" /></el-select></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="发票号"><el-input v-model="invForm.invoiceNo" placeholder="选填" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="开票日期"><el-date-picker v-model="invForm.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="税率%"><el-input model-value="13" disabled style="width:100%"><template #append>销售统一 13%</template></el-input></el-form-item></el-col>
            <el-col :span="24"><el-form-item label="备注"><el-input v-model="invForm.remark" /></el-form-item></el-col>
          </el-row>
        </el-form>
        <el-alert type="info" :closable="false" show-icon title="金额取销售订单售价(价税合计)，按 13% 倒挤不含税/销项税额（销售模块税率统一锁定 13%）。开票后可再提交审批。" />
      </template>
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button v-if="pickedOut" @click="pickedOut = null">‹ 重选</el-button>
        <el-button v-if="pickedOut" type="primary" :loading="creating" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate">生成发票</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <DocDrawer v-model="detailVisible" kind="销售发票" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['开票', '审批', '确认收入 · 记应收']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="发票单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="发票类型">{{ detail.invoiceType }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="发票号">{{ detail.invoiceNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源出库">{{ detail.sourceOutNo }}</el-descriptions-item>
          <el-descriptions-item label="开票日期">{{ detail.invoiceDate }}</el-descriptions-item>
          <el-descriptions-item label="客户" :span="2">{{ detail.customerName || detail.customerUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="不含税">{{ fmtMoney(detail.amount) }}</el-descriptions-item>
          <el-descriptions-item label="销项税额">{{ fmtMoney(detail.taxAmt) }}</el-descriptions-item>
          <el-descriptions-item label="价税合计(应收)">{{ fmtMoney(detail.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="detail.lines || []" border size="small" show-summary :summary-method="lineSummary">
          <el-table-column fixed="left" type="index" label="#" width="48" />
          <el-table-column fixed="left" label="物料" min-width="200"><template #default="{ row }">{{ matName(row.materialId) }}</template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column label="税率%" width="80" align="right"><template #default="{ row }">{{ row.taxRate }}</template></el-table-column>
          <el-table-column label="不含税" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amountExclTax) }}</template></el-table-column>
          <el-table-column label="税额" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.taxAmount) }}</template></el-table-column>
          <el-table-column label="价税合计" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
        </el-table>
      </template>
      <template v-if="detail">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detail.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detail && detail.docNo" :float-left="false" />
        <el-button v-if="detail && detail.status === 'PENDING' && canEdit" @click="doWithdraw(detail.id)">取消提交</el-button>
        <el-button v-if="detail && detail.status === 'PENDING' && detail.canApprove" type="danger" plain @click="doReject(detail.id)">驳回</el-button>
        <el-button v-if="detail && ['DRAFT','REJECTED'].includes(detail.status) && canEdit" type="danger" plain @click="doDelete(detail.id)">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail && ['DRAFT','REJECTED'].includes(detail.status) && canEdit" type="primary" @click="doSubmit(detail.id)">提交审批</el-button>
        <el-button v-if="detail && detail.status === 'PENDING' && detail.canApprove" type="primary" @click="doApprove(detail.id)">审批通过</el-button>
      </template>

    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import QueryDrawer from '@/components/QueryDrawer.vue'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { salesApi, masterApi } from '@/api'
import { applyRouteQuery } from '@/utils/routeQuery'
import { requestCancelApproval } from '@/utils/docActions'
import { useUserStore } from '@/stores/user'
import StatusTag from '@/components/StatusTag.vue'
import { fmtMoney, today, fmtMaterials } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { makeLineSummary } from '@/utils/tableSummary'
import { showError } from '@/utils/notify'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { takeInvoiceNavigationIntent } from '@/utils/invoiceNavigation'

const user = useUserStore()
const canEdit = computed(() => user.hasFinanceAccessOrAdmin())
const invoiceTypes = ['增值税专用发票', '普通发票']
const statusOptions = [{ v: 'DRAFT', l: '草稿' }, { v: 'PENDING', l: '待审批' }, { v: 'APPROVED', l: '已审批' }, { v: 'REJECTED', l: '已驳回' }]
// 明细表合计（数量+不含税+税额+价税合计），便于核对发票总额
const lineSummary = makeLineSummary({
  数量: { field: 'qty', type: 'qty' },
  不含税: { field: 'amountExclTax', type: 'money' },
  税额: { field: 'taxAmount', type: 'money' },
  价税合计: { field: 'amount', type: 'money' }
})

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const dateRange = ref<any>(null)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '' })
const matCache = ref<Record<number, any>>({})

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }

const exportColumns: ExportColumn[] = [
  { label: '发票单号', key: 'docNo' },
  { label: '发票类型', key: 'invoiceType' },
  { label: '发票号', key: 'invoiceNo' },
  { label: '来源出库', key: 'sourceOutNo' },
  { label: '客户', value: (row: any) => row.customerName || row.customerUnit || '-' },
  { label: '开票日期', key: 'invoiceDate' },
  { label: '不含税', money: true, value: (row: any) => fmtMoney(row.amount) },
  { label: '销项税', money: true, value: (row: any) => fmtMoney(row.taxAmt) },
  { label: '价税合计', money: true, value: (row: any) => fmtMoney(row.totalAmt) },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) }
]
function exportExcel() { exportRowsToExcel('销售发票', exportColumns, rows.value, '销售发票', { total: total.value, fetch: p => salesApi.invoicePage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null, ...p }) }) }
function matName(id: number) { const m = matCache.value[id]; return m ? `${m.code} ${m.name}` : id }

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...statusOptions.map(o => ({ label: o.l, value: o.v }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => salesApi.invoicePage({ ...q, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }), statusOptions.map(o => o.v))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try { const r: any = await salesApi.invoicePage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }); rows.value = r.records || []; total.value = r.total || 0 }
  finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)
function doSearch() { query.current = 1; load() }
/** 查询面板「重置」:清掉状态、关键词、日期,回到第一页重查 */
function resetQuery() { query.status = ''; query.keyword = ''; dateRange.value = null; doSearch() }
function onPage(p: number) { query.current = p; load() }

/* 开票 */
const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const outKeyword = ref('')
const availableOut = ref<any[]>([])
const outLoading = ref(false)
const pickedOut = ref<any>(null)
const creating = ref(false)
const invForm = reactive<any>({ invoiceType: '增值税专用发票', invoiceNo: '', invoiceDate: today(), remark: '' })

async function openCreate() {
  pickedOut.value = null; outKeyword.value = ''
  Object.assign(invForm, { invoiceType: '增值税专用发票', invoiceNo: '', invoiceDate: today(), remark: '' })
  await ensureAllMats()
  await loadAvailableOut()
  createVisible.value = true
}
async function loadAvailableOut() {
  outLoading.value = true
  try { availableOut.value = await salesApi.outAvailableInvoice(outKeyword.value) as any[] } finally { outLoading.value = false }
}
async function doCreate() {
  creating.value = true
  try {
    const inv: any = await salesApi.invoiceFromOut({ outId: pickedOut.value.id, invoiceType: invForm.invoiceType, invoiceNo: invForm.invoiceNo || null, invoiceDate: invForm.invoiceDate, remark: invForm.remark })
    ElMessage.success(`已生成销售发票 ${inv.docNo}`)
    createVisible.value = false; load()
  } catch (e: any) { showError(e, '生成失败') }
  finally { creating.value = false }
}

/* 详情+审批 */
const detailVisible = ref(false)
const detail = ref<any>(null)
async function openDetail(id: number) { detail.value = await salesApi.invoiceDetail(id) as any; await ensureAllMats(); detailVisible.value = true }
async function ensureAllMats() { if (Object.keys(matCache.value).length) return; try { const list: any = await masterApi.allMaterials({ includeDisabled: true }); for (const m of (list || [])) matCache.value[m.id] = m } catch {} }
async function doSubmit(id: number) { await salesApi.invoiceSubmit(id); ElMessage.success('已提交'); await refresh(id) }
async function doWithdraw(id: number) { await salesApi.invoiceWithdraw(id); ElMessage.success('已取消提交'); await refresh(id) }
async function doApprove(id: number) { const c = await promptComment('审批通过意见（可空）'); if (c === false) return; await salesApi.invoiceApprove(id, c as string); ElMessage.success('已通过'); await refresh(id) }
async function doReject(id: number) { const c = await promptComment('驳回原因'); if (c === false) return; await salesApi.invoiceReject(id, c as string); ElMessage.success('已驳回'); await refresh(id) }
async function doDelete(id: number) { await ElMessageBox.confirm('确认删除该销售发票？', '提示', { type: 'warning' }); await salesApi.invoiceDelete(id); ElMessage.success('已删除'); detailVisible.value = false; load() }
async function refresh(id: number) { detail.value = await salesApi.invoiceDetail(id) as any; load() }
async function promptComment(title: string): Promise<string | false> { try { const { value } = await ElMessageBox.prompt('', title, { confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea' }); return value || '' } catch { return false } }

const route = useRoute()
const entryFullPath = route.fullPath
let initialEntryConsumed = false
function consumeInvoiceEntry() {
  if (route.fullPath !== entryFullPath) return
  const requested = takeInvoiceNavigationIntent(entryFullPath)
  if (initialEntryConsumed && !requested) return
  initialEntryConsumed = true
  const fromOut = typeof route.query.fromOut === 'string' ? route.query.fromOut.trim() : ''
  if (fromOut) openCreateFromOut(fromOut).catch(error => showError(error, '开票录入打开失败'))
}
onMounted(() => {
  applyRouteQuery(query, route, ['status', 'keyword'])
  load()
  consumeInvoiceEntry()
})
onActivated(consumeInvoiceEntry)

/** 从「已出库未开票」清单点「去处理」进来：打开参照出库开票并选中那张出库单 */
async function openCreateFromOut(outNo: string) {
  if (!canEdit.value) { ElMessage.warning('录入销售发票需要财务权限，请联系财务处理'); return }
  await openCreate()
  outKeyword.value = outNo
  await loadAvailableOut()
  const hit = availableOut.value.find((o: any) => o.docNo === outNo)
  if (hit) pickedOut.value = hit
  else ElMessage.warning(`出库单 ${outNo} 当前不可开票（可能已开过票或已全部退货），请在列表中核对`)
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.customerName || d.customerUnit, d.invoiceType, d.invoiceNo && `发票号 ${d.invoiceNo}`, d.invoiceDate && `开票 ${d.invoiceDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 3} as Record<string, number>)[detail.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detail.value
  if (!d) return []
  return [{ label: '不含税收入', value: fmtMoney(d.amount) }, { label: '销项税额', value: fmtMoney(d.taxAmt) }, { label: '价税合计（应收）', value: fmtMoney(d.totalAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a: number, r: any) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}
</script>

<style scoped>
.doc-link { background: none; border: none; color: #2465a8; cursor: pointer; padding: 0; font: inherit; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.mb-3 { margin-bottom: 12px; }
</style>
