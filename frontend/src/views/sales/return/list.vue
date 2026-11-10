<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售退货</div>
        <div class="page-sub">参照已审批销售出库单退货，审批通过按原出库成本把货退回库存(回冲成本)，并记录应冲收入(价税合计)冲减应收。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!rows.length" :title="!rows.length ? '当前没有数据' : '勾了哪几行导哪几行；不勾导出全部查询结果'" @click="exportExcel">导出Excel</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">+ 参照出库退货</el-button>
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
          <el-form-item label="关键词" data-q="keyword"><el-input v-model="query.keyword" placeholder="单号/客户/出库号" clearable style="width:190px" @keyup.enter="doSearch" /></el-form-item>
          <el-form-item label="退货日期" data-q="dateRange"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="doSearch" /></el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无数据" @sort-change="onSortChange" show-summary :summary-method="listSummary">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="退货单号" width="200" sortable="custom"><template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template></el-table-column>
        <el-table-column prop="sourceOutNo" label="来源出库" width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="returnDate" label="退货日期" width="110" sortable="custom" />
        <el-table-column prop="customerName" sortable="custom" label="客户" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
        <el-table-column label="物料编码" min-width="125" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialCodes) }}</template>
        </el-table-column>
        <el-table-column label="物料名称" min-width="170">
          <template #default="{ row }"><span class="mat-name-cell">{{ firstMat(row.materialNames) }}<MoreMaterialsTag :codes="row.materialCodes" :names="row.materialNames" :specs="row.materialSpecs" /></span></template>
        </el-table-column>
        <el-table-column label="规格型号" min-width="115" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialSpecs) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.totalQty) }}</template></el-table-column>
        <el-table-column v-if="canSeeCost" label="回冲成本" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template></el-table-column>
        <el-table-column label="应冲收入" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.refundAmt) }}</template></el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="270"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">查看</el-button><el-button v-if="canEdit && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="doSubmit(row.id)">提交</el-button><el-button v-if="row.status === 'PENDING' && user.ownsDoc(row)" link type="warning" @click="doWithdraw(row.id)">撤回</el-button><el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('SALE_RETURN', row, load)">取消审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="success" @click="doApprove(row.id)">审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="danger" @click="doReject(row.id)">驳回</el-button></template></el-table-column>
      </el-table>
      <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total" :current-page="query.current" :page-size="query.size" @current-change="onPage" /></div>
    </el-card>

    <!-- 生成退货 -->
    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="参照销售出库退货" width="min(1000px, calc(100vw - 48px))" align-center>
      <DocGuardHint :active="createVisible" :source-org-id="pickedOut?.orgId" :warehouse-ids="[pickedOut?.warehouseId]" v-model:blocked="guardBlocked" />
      <template v-if="!pickedOut">
        <el-input v-model="outKeyword" placeholder="搜索出库单号/客户后回车" clearable style="width:280px;margin-bottom:10px" @keyup.enter="loadAvailableOut" @clear="loadAvailableOut" />
        <el-table class="readable-table" border :data="availableOut" height="340" v-loading="outLoading" empty-text="没有已审批的销售出库单">
          <el-table-column prop="docNo" label="出库单号" width="200" />
          <el-table-column label="客户" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
          <el-table-column label="物料" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ fmtMaterials(row.materialSummary) }}</template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.totalQty) }}</template></el-table-column>
          <el-table-column label="" width="80"><template #default="{ row }"><el-button link type="primary" @click="pickOut(row)">选择 →</el-button></template></el-table-column>
        </el-table>
      </template>
      <template v-else>
        <el-descriptions :column="2" border size="small" class="mb-3">
          <el-descriptions-item label="来源出库单">{{ pickedOut.docNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ pickedOut.customerName || pickedOut.customerUnit || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table class="readable-table" :data="createLines" border size="small" height="300">
          <el-table-column label="物料" min-width="200"><template #default="{ row }">{{ matName(row.materialId) }}</template></el-table-column>
          <el-table-column label="出库数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column label="本次退货" width="150" fixed="right"><template #default="{ row }"><el-input-number v-model="row.retQty" :min="0" :max="Number(row.qty)" :precision="2" :controls="false" style="width:100%" /></template></el-table-column>
        </el-table>
        <el-form :inline="true" style="margin-top:10px"><el-form-item label="备注"><el-input v-model="retRemark" style="width:360px" /></el-form-item></el-form>
      </template>
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button v-if="pickedOut" @click="pickedOut = null">‹ 重选</el-button>
        <el-button v-if="pickedOut" type="primary" :loading="creating" :disabled="guardBlocked || !canCreate" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate">生成退货单</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <DocDrawer v-model="detailVisible" kind="销售退货单" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['登记退货', '审批（退回库存）', '冲减收入 · 应收']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="退货单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="来源出库">{{ detail.sourceOutNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="客户" :span="2">{{ detail.customerName || detail.customerUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货日期">{{ detail.returnDate }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="回冲成本">{{ fmtMoney(detail.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="应冲收入(价税合计)">{{ fmtMoney(detail.refundAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="detail.lines || []" border size="small" show-summary :summary-method="lineSummary">
          <el-table-column fixed="left" type="index" label="#" width="48" />
          <el-table-column fixed="left" label="物料编码" width="120"><template #default="{ row }">{{ matField(row.materialId, 'code') }}</template></el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'name') }}</template></el-table-column>
          <el-table-column label="规格型号" min-width="110" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'spec') }}</template></el-table-column>
          <el-table-column label="退货数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column v-if="canSeeCost" label="成本单价" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template></el-table-column>
          <el-table-column v-if="canSeeCost" label="回冲成本" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
          <el-table-column label="应冲收入" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.saleAmount) }}</template></el-table-column>
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
        <el-button v-if="detail && detail.status === 'PENDING' && detail.canApprove" type="primary" @click="doApprove(detail.id)">审批通过（退回库存）</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { salesApi, masterApi } from '@/api'
import { applyRouteQuery } from '@/utils/routeQuery'
import { requestCancelApproval } from '@/utils/docActions'
import { useUserStore } from '@/stores/user'
import StatusTag from '@/components/StatusTag.vue'
import MoreMaterialsTag from '@/components/MoreMaterialsTag.vue'
import { fmtMoney, fmtMaterials } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { makeLineSummary } from '@/utils/tableSummary'
import { showError } from '@/utils/notify'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { docStatusLabel } from '@/utils/statusLabel'

// 明细表合计（退货数量 + 回冲成本 + 应冲收入）
const lineSummary = makeLineSummary({
  退货数量: { field: 'qty', type: 'qty' },
  回冲成本: { field: 'amount', type: 'money' },
  应冲收入: { field: 'saleAmount', type: 'money' }
})

function firstMat(s?: string | null) { return (s || '').split('、')[0] || '-' }

function matField(id: number, k: 'code' | 'name' | 'spec') { const m = matCache.value[id]; return (m && (m as any)[k]) || (k === 'code' ? String(id) : '-') }

const user = useUserStore()
const canEdit = computed(() => user.hasRole('warehouse') || user.hasRole('material_accountant') || user.hasRole('account_admin') || user.hasRole('admin'))
const canSeeCost = computed(() => !user.hasRole('warehouse') || user.hasRole('material_accountant') || user.hasRole('account_admin') || user.hasRole('admin'))
const statusOptions = [{ v: 'DRAFT', l: '草稿' }, { v: 'PENDING', l: '待审批' }, { v: 'APPROVED', l: '已审批' }, { v: 'REJECTED', l: '已驳回' }]

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const dateRange = ref<any>(null)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '' })
const matCache = ref<Record<number, any>>({})

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }

// 回冲成本只给能看成本的岗位导出(与列表显示一致)
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '退货单号', key: 'docNo' },
  { label: '来源出库', key: 'sourceOutNo' },
  { label: '退货日期', key: 'returnDate' },
  { label: '客户', value: (row: any) => row.customerName || row.customerUnit || '-' },
  { label: '物料编码', key: 'materialCodes' },
  { label: '物料名称', key: 'materialNames' },
  { label: '规格型号', key: 'materialSpecs' },
  { label: '数量', key: 'totalQty', qty: true },
  ...(canSeeCost.value ? [{ label: '回冲成本', money: true, value: (row: any) => fmtMoney(row.totalAmt) }] : []),
  { label: '应冲收入', money: true, value: (row: any) => fmtMoney(row.refundAmt) },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) }
])
function exportExcel() { exportRowsToExcel('销售退货', exportColumns.value, rows.value, '销售退货', { total: total.value, fetch: p => salesApi.returnPage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null, ...p }) }) }
function matName(id: number) { const m = matCache.value[id]; return m ? `${m.code} ${m.name}` : id }

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...statusOptions.map(o => ({ label: o.l, value: o.v }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => salesApi.returnPage({ ...q, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }), statusOptions.map(o => o.v))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try { const r: any = await salesApi.returnPage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }); rows.value = r.records || []; total.value = r.total || 0 }
  finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)
function doSearch() { query.current = 1; load() }
/** 查询面板「重置」:清掉状态、关键词、日期,回到第一页重查 */
function resetQuery() { query.status = ''; query.keyword = ''; dateRange.value = null; doSearch() }
function onPage(p: number) { query.current = p; load() }

/* 生成 */
const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const outKeyword = ref('')
const availableOut = ref<any[]>([])
const outLoading = ref(false)
const pickedOut = ref<any>(null)
const createLines = ref<any[]>([])
const retRemark = ref('')
const creating = ref(false)
const canCreate = computed(() => createLines.value.some(l => Number(l.retQty) > 0))

async function openCreate() {
  pickedOut.value = null; outKeyword.value = ''; retRemark.value = ''
  await ensureAllMats(); await loadAvailableOut(); createVisible.value = true
}
async function loadAvailableOut() {
  outLoading.value = true
  try { const r: any = await salesApi.outPage({ current: 1, size: 50, status: 'APPROVED', keyword: outKeyword.value }); availableOut.value = r.records || [] }
  finally { outLoading.value = false }
}
async function pickOut(row: any) {
  const d: any = await salesApi.outDetail(row.id)
  pickedOut.value = d
  createLines.value = (d.lines || []).map((l: any) => ({ ...l, retQty: 0 }))
}
async function doCreate() {
  const lines = createLines.value.filter(l => Number(l.retQty) > 0).map(l => ({ outLineId: l.id, qty: Number(l.retQty) }))
  if (!lines.length) { ElMessage.warning('请填写退货数量'); return }
  creating.value = true
  try { const r: any = await salesApi.returnFromOut({ outId: pickedOut.value.id, lines, remark: retRemark.value }); ElMessage.success(`已生成退货单 ${r.docNo}`); createVisible.value = false; load() }
  catch (e: any) { showError(e, '生成失败') }
  finally { creating.value = false }
}

/* 详情+审批 */
const detailVisible = ref(false)
const detail = ref<any>(null)
async function openDetail(id: number) { detail.value = await salesApi.returnDetail(id) as any; await ensureAllMats(); detailVisible.value = true }
async function ensureAllMats() { if (Object.keys(matCache.value).length) return; try { const list: any = await masterApi.allMaterials({ includeDisabled: true }); for (const m of (list || [])) matCache.value[m.id] = m } catch {} }
async function doSubmit(id: number) { await salesApi.returnSubmit(id); ElMessage.success('已提交'); await refresh(id) }
async function doWithdraw(id: number) { await salesApi.returnWithdraw(id); ElMessage.success('已取消提交'); await refresh(id) }
async function doApprove(id: number) { const c = await promptComment('审批通过意见（可空）'); if (c === false) return; await salesApi.returnApprove(id, c as string); ElMessage.success('已通过，货已退回库存'); await refresh(id) }
async function doReject(id: number) { const c = await promptComment('驳回原因'); if (c === false) return; await salesApi.returnReject(id, c as string); ElMessage.success('已驳回'); await refresh(id) }
async function doDelete(id: number) { await ElMessageBox.confirm('确认删除该销售退货单？', '提示', { type: 'warning' }); await salesApi.returnDelete(id); ElMessage.success('已删除'); detailVisible.value = false; load() }
async function refresh(id: number) { detail.value = await salesApi.returnDetail(id) as any; load() }
async function promptComment(title: string): Promise<string | false> { try { const { value } = await ElMessageBox.prompt('', title, { confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea' }); return value || '' } catch { return false } }

const route = useRoute()
onMounted(() => { applyRouteQuery(query, route, ['status', 'keyword']); load() })

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.customerName || d.customerUnit, d.sourceOutNo && `来源出库 ${d.sourceOutNo}`, d.returnDate && `退货 ${d.returnDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 3} as Record<string, number>)[detail.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detail.value
  if (!d) return []
  return [canSeeCost.value && { label: '回冲成本', value: fmtMoney(d.totalAmt) }, { label: '应冲收入（价税合计）', value: fmtMoney(d.refundAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 数量: { field: 'totalQty', type: 'qty' }, 回冲成本: { field: 'totalAmt', type: 'money' }, 应冲收入: { field: 'refundAmt', type: 'money' } })
</script>

<style scoped>
.doc-link { background: none; border: none; color: #2465a8; cursor: pointer; padding: 0; font: inherit; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.mb-3 { margin-bottom: 12px; }
</style>
