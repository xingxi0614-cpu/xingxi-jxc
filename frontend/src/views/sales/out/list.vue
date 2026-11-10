<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售出库</div>
        <div class="page-sub">支持按订单分批出库；可用数量扣除已出库及其他草稿、待审批占用。审批通过后扣减库存。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :loading="exporting" @click="exportDetail">导出物料明细</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">+ 关联订单出库</el-button>
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
          <el-form-item label="关键词" data-q="keyword"><el-input v-model="query.keyword" placeholder="单号/客户/订单号" clearable style="width:190px" @keyup.enter="doSearch" /></el-form-item>
          <el-form-item label="出库日期" data-q="dateRange"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="doSearch" /></el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无数据" @sort-change="onSortChange" show-summary :summary-method="listSummary">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="出库单号" width="200" sortable="custom"><template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template></el-table-column>
        <el-table-column prop="sourceSoNo" label="来源订单" width="190" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="outDate" label="出库日期" width="110" sortable="custom" />
        <el-table-column prop="customerName" sortable="custom" label="客户" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
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
        <el-table-column v-if="canSeeCost" label="成本额" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template></el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="320"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">查看</el-button><el-button link type="primary" :loading="printingId === row.id" @click="printOut(row.id)">打印</el-button><el-button v-if="canEdit && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="doSubmit(row.id)">提交</el-button><el-button v-if="row.status === 'PENDING' && user.ownsDoc(row)" link type="warning" @click="doWithdraw(row.id)">撤回</el-button><el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('SALE_OUT', row, load)">取消审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="success" @click="doApprove(row.id)">审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="danger" @click="doReject(row.id)">驳回</el-button></template></el-table-column>
      </el-table>
      <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total" :current-page="query.current" :page-size="query.size" @current-change="onPage" /></div>
    </el-card>

    <!-- 生成销售出库 -->
    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="关联销售订单出库" width="min(1040px, calc(100vw - 48px))" align-center>
      <DocGuardHint :active="createVisible" :warehouse-ids="[warehouseId]" :source-org-id="selectedSo?.orgId" v-model:blocked="guardBlocked" />
      <el-steps :active="step" finish-status="success" align-center style="max-width:520px;margin:0 auto 16px">
        <el-step title="选择销售订单" /><el-step title="填实发数量" />
      </el-steps>

      <div v-show="step === 0">
        <el-input v-model="soKeyword" placeholder="搜索订单号/客户后回车" clearable style="width:280px;margin-bottom:10px" @keyup.enter="loadAvailableSo" @clear="loadAvailableSo" />
        <el-table class="readable-table" border :data="availableSo" height="360" v-loading="soLoading" empty-text="没有可出库的已审批订单">
          <el-table-column prop="docNo" label="订单号" width="200" />
          <el-table-column label="客户" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template></el-table-column>
          <el-table-column label="物料" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ fmtMaterials(row.materialSummary) }}</template></el-table-column>
          <el-table-column label="数量/已出" width="120" align="center"><template #default="{ row }">{{ fmtQty(row.totalQty) }} / {{ fmtQty(row.outQty) }}</template></el-table-column>
          <el-table-column label="" width="80"><template #default="{ row }"><el-button link type="primary" @click="selectSo(row)">选择 →</el-button></template></el-table-column>
        </el-table>
      </div>

      <div v-show="step === 1">
        <el-descriptions v-if="selectedSo" :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="订单号">{{ selectedSo.docNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ selectedSo.customerName || selectedSo.customerUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ selectedSo.salesPerson || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-form :inline="true" size="default" style="margin-bottom:8px">
          <el-form-item label="出库仓库" required>
            <el-select v-model="warehouseId" filterable placeholder="选择仓库" style="width:220px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table class="readable-table" :data="createLines" border size="small" height="330">
          <el-table-column label="物料" min-width="200"><template #default="{ row }">{{ matName(row.materialId) }}</template></el-table-column>
          <el-table-column label="订单数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column label="已出" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.outQty) }}</template></el-table-column>
          <el-table-column label="其他单占用" width="110" align="right"><template #default="{ row }">{{ fmtQty(row.reservedOutQty) }}</template></el-table-column>
          <el-table-column label="可用数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(remain(row)) }}</template></el-table-column>
          <el-table-column label="本次实发" width="150" fixed="right"><template #default="{ row }"><el-input-number v-model="row.outQtyInput" :min="0" :max="remain(row)" :precision="4" :controls="false" style="width:100%" /></template></el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <template v-if="step === 1">
          <el-button @click="step = 0">‹ 上一步</el-button>
          <el-button :loading="creating" :disabled="guardBlocked || !canCreate" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate(false)">仅生成草稿</el-button>
          <el-button type="primary" :loading="creating" :disabled="guardBlocked || !canCreate" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate(true)">生成并提交</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <DocDrawer v-model="detailVisible" kind="销售出库单" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['制单', '审批（扣库存）', '开票']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="出库单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="来源订单">{{ detail.sourceSoNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="仓库">{{ whName(detail.warehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customerName || detail.customerUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="出库日期">{{ detail.outDate }}</el-descriptions-item>
          <el-descriptions-item label="业务流程">{{ detail.bizFlow }}</el-descriptions-item>
          <el-descriptions-item label="出入库类型">{{ detail.outType }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="成本额">{{ fmtMoney(detail.totalAmt) }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="detail.lines || []" border size="small" show-summary :summary-method="lineSummary">
          <el-table-column fixed="left" type="index" label="#" width="48" />
          <el-table-column fixed="left" label="物料编码" width="120"><template #default="{ row }">{{ matField(row.materialId, 'code') }}</template></el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'name') }}</template></el-table-column>
          <el-table-column label="规格型号" min-width="110" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'spec') }}</template></el-table-column>
          <el-table-column label="实发数量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column v-if="canSeeCost" label="成本单价" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template></el-table-column>
          <el-table-column v-if="canSeeCost" label="成本额" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
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
        <el-button v-if="detail" :loading="printingId === detail.id" @click="printOut(detail.id)">打印出库单</el-button>
        <el-button v-if="detail && ['DRAFT','REJECTED'].includes(detail.status) && canEdit" type="primary" @click="doSubmit(detail.id)">提交审批</el-button>
        <el-button v-if="detail && detail.status === 'PENDING' && detail.canApprove" type="primary" @click="doApprove(detail.id)">审批通过（扣库存）</el-button>
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
import { printDocument, type PrintColumn } from '@/utils/printExport'
import { approvalRecordNote } from '@/utils/approvalPrint'
import { docStatusLabel } from '@/utils/statusLabel'
import { saveBlob } from '@/utils/download'
import { selectedRowsOf } from '@/utils/exportSelection'

// 明细表合计（实发数量 + 成本额，成本列仅财务/管理员可见时才有）
const lineSummary = makeLineSummary({
  实发数量: { field: 'qty', type: 'qty' },
  成本额: { field: 'amount', type: 'money' }
})

function firstMat(s?: string | null) { return (s || '').split('、')[0] || '-' }

function matField(id: number, k: 'code' | 'name' | 'spec') { const m = matCache.value[id]; return (m && (m as any)[k]) || (k === 'code' ? String(id) : '-') }

const user = useUserStore()
const canEdit = computed(() => user.hasRole('warehouse') || user.hasRole('account_admin') || user.hasRole('admin'))
const canSeeCost = computed(() => user.hasFinanceAccessOrAdmin())
const statusOptions = [{ v: 'DRAFT', l: '草稿' }, { v: 'PENDING', l: '待审批' }, { v: 'APPROVED', l: '已审批' }, { v: 'REJECTED', l: '已驳回' }]

const loading = ref(false)
const exporting = ref(false)
const printingId = ref<number | null>(null)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const dateRange = ref<any>(null)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '' })
const matCache = ref<Record<number, any>>({})
const warehouses = ref<any[]>([])

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
function matName(id: number) { const m = matCache.value[id]; return m ? `${m.code} ${m.name}` : id }
function whName(id: number) { return warehouses.value.find(w => w.id === id)?.name || id }

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...statusOptions.map(o => ({ label: o.l, value: o.v }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => salesApi.outPage({ ...q, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }), statusOptions.map(o => o.v))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try {
    const r: any = await salesApi.outPage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null })
    rows.value = r.records || []; total.value = r.total || 0
  } finally { loading.value = false }
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
const step = ref(0)
const soKeyword = ref('')
const availableSo = ref<any[]>([])
const soLoading = ref(false)
const selectedSo = ref<any>(null)
const createLines = ref<any[]>([])
const warehouseId = ref<number | null>(null)
const creating = ref(false)
const canCreate = computed(() => !!selectedSo.value && !!warehouseId.value && createLines.value.some(l => Number(l.outQtyInput) > 0))
function remain(row: any) { return Math.max(0, Number(row.availableOutQty ?? (Number(row.qty || 0) - Number(row.outQty || 0) - Number(row.reservedOutQty || 0)))) }

async function openCreate() {
  step.value = 0; selectedSo.value = null; createLines.value = []; warehouseId.value = null; soKeyword.value = ''
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses() as any
  await ensureAllMats()
  await loadAvailableSo()
  createVisible.value = true
}
async function loadAvailableSo() {
  soLoading.value = true
  try { const r: any = await salesApi.orderAvailable({ current: 1, size: 50, keyword: soKeyword.value }); availableSo.value = r.records || [] }
  finally { soLoading.value = false }
}
async function selectSo(row: any) {
  const d: any = await salesApi.orderDetail(row.id)
  selectedSo.value = d
  createLines.value = (d.lines || []).filter((l: any) => remain(l) > 0).map((l: any) => ({ ...l, outQtyInput: remain(l) }))
  if (!createLines.value.length) { ElMessage.warning('该订单已无可出库数量'); return }
  step.value = 1
}
async function doCreate(submitAfter: boolean) {
  if (creating.value) return
  const lines = createLines.value.filter(l => Number(l.outQtyInput) > 0).map(l => ({ soLineId: l.id, qty: Number(l.outQtyInput) }))
  if (!lines.length) { ElMessage.warning('请填写实发数量'); return }
  creating.value = true
  try {
    const out: any = await salesApi.outFromSo({ soId: selectedSo.value.id, warehouseId: warehouseId.value, lines })
    createVisible.value = false // 草稿已落库，后续提交失败也不能再次生成同一张
    if (submitAfter) {
      try { await salesApi.outSubmit(out.id) }
      catch (e: any) {
        showError(e, '提交失败')
        ElMessage.warning(`出库单 ${out.docNo} 已保存为草稿，请在列表中重试提交`)
        await load()
        return
      }
      ElMessage.success(`出库单 ${out.docNo} 已生成并提交`)
    }
    else ElMessage.success(`已生成出库单 ${out.docNo}（草稿）`)
    load()
  } catch (e: any) { showError(e, '生成失败') }
  finally { creating.value = false }
}

/* 详情+审批 */
const detailVisible = ref(false)
const detail = ref<any>(null)
async function openDetail(id: number) {
  detail.value = await salesApi.outDetail(id) as any
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses() as any
  await ensureAllMats()
  detailVisible.value = true
}
async function ensureAllMats(strict = false) {
  if (Object.keys(matCache.value).length) return
  // 历史出库单仍需显示后来停用的物料；这里仅用于标签解析，不作为新增物料选择器。
  try { const list: any = await masterApi.allMaterials({ includeDisabled: true }); for (const m of (list || [])) matCache.value[m.id] = m } catch (e) { if (strict) throw e }
}
async function doSubmit(id: number) { await salesApi.outSubmit(id); ElMessage.success('已提交'); await refresh(id) }
async function doWithdraw(id: number) { await salesApi.outWithdraw(id); ElMessage.success('已取消提交'); await refresh(id) }
async function doApprove(id: number) { const c = await promptComment('审批通过意见（可空）'); if (c === false) return; await salesApi.outApprove(id, c as string); ElMessage.success('已通过，库存已扣减'); await refresh(id) }
async function doReject(id: number) { const c = await promptComment('驳回原因'); if (c === false) return; await salesApi.outReject(id, c as string); ElMessage.success('已驳回'); await refresh(id) }
async function doDelete(id: number) { await ElMessageBox.confirm('确认删除该销售出库单？', '提示', { type: 'warning' }); await salesApi.outDelete(id); ElMessage.success('已删除'); detailVisible.value = false; load() }
async function refresh(id: number) { detail.value = await salesApi.outDetail(id) as any; load() }
async function promptComment(title: string): Promise<string | false> {
  try { const { value } = await ElMessageBox.prompt('', title, { confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea' }); return value || '' } catch { return false }
}

async function exportDetail() {
  if (exporting.value) return
  exporting.value = true
  try {
    // 勾了行就只导勾选的出库单(单号集合精确查,末尾补逗号让单张也走精确匹配)；没勾按当前筛选全导。2026-10-15 补勾选框
    const picked = selectedRowsOf(rows.value)
    const blob = await salesApi.outExport(picked
      ? { keyword: picked.map((r: any) => r.docNo).join(',') + ',' }
      : { status: query.status, keyword: query.keyword, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null })
    // Blob响应也可能是业务错误，不能把JSON保存成Excel。
    if (blob.type.includes('json')) {
      const error = JSON.parse(await blob.text())
      throw new Error(error.msg || '导出失败')
    }
    saveBlob(blob, '销售出库-物料单据明细查询.xlsx')
    ElMessage.success(picked ? `已导出勾选的 ${picked.length} 张出库单` : '未勾选，已按当前筛选导出全部出库单')
  } catch (e: any) { showError(e, '导出失败') }
  finally { exporting.value = false }
}

async function printOut(id: number) {
  if (printingId.value !== null) return
  printingId.value = id
  try {
    const doc: any = await salesApi.outDetail(id)
    // 历史仓库可能已停用，打印单据也必须保留其名称。
    const printWarehouses: any = await masterApi.warehouses({ includeDisabled: true })
    await ensureAllMats(true)
    const columns: PrintColumn[] = [
      { label: '序号', value: (_r, index) => index + 1, width: '5%', align: 'center' },
      { label: '物料编码', value: r => matField(r.materialId, 'code'), width: '14%' },
      { label: '物料名称', value: r => matField(r.materialId, 'name'), width: '20%' },
      { label: '规格型号', value: r => matField(r.materialId, 'spec'), width: '14%' },
      { label: '单位', value: r => matCache.value[r.materialId]?.unit || '', width: '6%' },
      { label: '实发数量', value: r => fmtQty(r.qty), width: '10%', align: 'right' },
      ...(canSeeCost.value ? [
        { label: '成本单价', value: (r: any) => r.unitPrice == null ? '' : fmtMoney(r.unitPrice), align: 'right' },
        { label: '成本金额', value: (r: any) => r.amount == null ? '' : fmtMoney(r.amount), align: 'right' }
      ] as PrintColumn[] : []),
      { label: '备注', key: 'remark' }
    ]
    printDocument({
      orgId: doc.orgId, title: '销售出库单', paper: 'A4', orientation: 'landscape',
      fileName: `${doc.docNo}-销售出库单`, columns, rows: doc.lines || [],
      fields: [
        { label: '出库单号', value: doc.docNo }, { label: '来源订单', value: doc.sourceSoNo },
        { label: '出库日期', value: doc.outDate }, { label: '状态', value: docStatusLabel(doc.status) },
        { label: '客户', value: doc.customerName || doc.customerUnit }, { label: '仓库', value: printWarehouses.find((w: any) => w.id === doc.warehouseId)?.name || doc.warehouseId },
        { label: '业务员', value: doc.salesPerson }, { label: '数量合计', value: fmtQty(doc.totalQty) },
        { label: '收货地址', value: doc.shipAddress }, { label: '备注', value: doc.remark }
      ],
      signatures: [{ label: '客户签收' }, { label: '仓库发货' }, { label: '经办人' }],
      note: await approvalRecordNote(doc.docNo, doc.status === 'APPROVED'
        ? '本单用于销售出库交接，请按实发物料核对签收。' : '本单尚未审批，仅供核对，不作为已出库凭证。')
    })
  } catch (e: any) { showError(e, '打印预览生成失败') }
  finally { printingId.value = null }
}

const route = useRoute()
onMounted(() => { applyRouteQuery(query, route, ['status', 'keyword']); load() })

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.customerName || d.customerUnit, d.sourceSoNo && `来源订单 ${d.sourceSoNo}`, d.outDate && `出库 ${d.outDate}`, whName(d.warehouseId)].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 2, "PARTIAL_INVOICED": 2, "INVOICED": 3} as Record<string, number>)[detail.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detail.value
  if (!d) return []
  return [{ label: '明细行数', value: (d.lines || []).length }, canSeeCost.value && { label: '结转成本', value: fmtMoney(d.totalAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 数量: { field: 'totalQty', type: 'qty' }, 成本额: { field: 'totalAmt', type: 'money' } })
</script>

<style scoped>
.doc-link { background: none; border: none; color: #2465a8; cursor: pointer; padding: 0; font: inherit; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.mb-3 { margin-bottom: 12px; }
</style>
