<template>
  <div class="page">
    <div class="page-head" data-tour="stocktake-page">
      <div>
        <div class="page-title">盘点单</div>
        <div class="page-sub">建单快照账面数 → 打印盘点表(可盲盘)线下清点 → 录入/导入实盘数 → 提交 → 财务确认后自动生成盘盈/盘亏调整。实盘留空的行视为未盘、不调整。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">+ 新建盘点单</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
      <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="状态" data-q="status">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:130px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待财务确认" value="SUBMITTED" />
            <el-option label="已确认" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点日期" data-q="dateRange">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="盘点单号" data-q="keyword">
          <DocNoLookup v-model="query.keyword" type="STOCKTAKE" multiple placeholder="点一下列出最近盘点单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="仓库" data-q="warehouseId">
          <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable>
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="制单人" data-q="creatorName">
          <el-input v-model="query.creatorName" placeholder="姓名" clearable @keyup.enter="doSearch" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无盘点单" @sort-change="onSortChange" show-summary :summary-method="listSummary">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" label="盘点单号" prop="docNo" min-width="190" show-overflow-tooltip sortable="custom" />
        <el-table-column label="仓库" prop="warehouseName" min-width="140" show-overflow-tooltip />
        <el-table-column label="盘点日期" prop="takeDate" width="110" sortable="custom" />
        <el-table-column label="行数" prop="lineCnt" width="70" align="right" sortable="custom" />
        <el-table-column v-if="canSeeCost" label="盘盈金额" width="110" align="right"><template #default="{ row }">{{ row.status === 'APPROVED' ? fmtMoney(row.gainAmt) : '-' }}</template></el-table-column>
        <el-table-column v-if="canSeeCost" label="盘亏金额" width="110" align="right"><template #default="{ row }">{{ row.status === 'APPROVED' ? fmtMoney(row.lossAmt) : '-' }}</template></el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).tone" size="small">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="制单人" prop="createdByName" width="90" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">{{ row.status === 'DRAFT' || row.status === 'REJECTED' ? '录实盘' : '查看' }}</el-button>
            <el-button v-if="canEdit && user.canDeleteDoc(row)" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top:10px; justify-content:flex-end" layout="total, prev, pager, next" background
        :total="total" :page-size="query.size" :current-page="query.current" @current-change="(p:number) => { query.current = p; load() }" />
    </el-card>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="新建盘点单" width="min(440px, 94vw)" align-center>
      <DocGuardHint :active="createVisible" :date="createForm.takeDate" :warehouse-ids="[createForm.warehouseId]" operation="STOCKTAKE_CREATE" v-model:blocked="guardBlocked" />
      <el-form label-position="top" label-width="92px">
        <el-form-item label="盘点仓库" required>
          <el-select v-model="createForm.warehouseId" placeholder="选择仓库" filterable style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点日期">
          <el-date-picker v-model="createForm.takeDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="范围">
          <el-checkbox v-model="createForm.includeZero">包含零结存物料</el-checkbox>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" maxlength="255" placeholder="可选" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon title="建单时会把该仓库当前账面数量、结存单价拍快照作为盘点底数；建议在没有出入库作业的时点建单。" />
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate">建单</el-button>
      </template>
    </el-dialog>

    <!-- 明细/录实盘 -->
    <DocDrawer v-model="detailVisible" :before-close="closeDetail" kind="盘点单" :doc-no="detail?.docNo" :status="detail?.status"
      :sub="detail ? [detail.warehouseName, detail.takeDate && `盘点 ${detail.takeDate}`, detail.financeBy && `确认 ${detail.financeBy}`].filter(Boolean).join(' · ') : ''"
      :nums="stkNums">
      <template #status><el-tag v-if="detail" :type="statusMeta(detail.status).tone" size="small">{{ statusMeta(detail.status).label }}</el-tag></template>
      <DocStepGuide :guide="stocktakeGuide(detail)" />
      <el-alert v-if="detail?.rejectReason" :title="`驳回原因：${detail.rejectReason}`" type="error" :closable="false" show-icon />
      <el-space wrap>
        <PrintExportDropdown label="打印盘点表" default-orientation="landscape"
          @print="(paper, orientation) => printSheet('print', paper, orientation)"
          @export="(paper, orientation) => printSheet('export', paper, orientation)" />
        <el-checkbox v-model="blindPrint" data-guard-ignore style="margin-right:4px">盲盘(不印账面数)</el-checkbox>
        <el-button @click="downloadSheet(blindPrint ? 1 : 0)">导出Excel(可回填导入)</el-button>
        <el-upload v-if="editable" :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadActual">
          <el-button type="primary" plain>导入实盘数</el-button>
        </el-upload>
      </el-space>
      <el-table class="readable-table" border :data="detail?.lines || []" max-height="440" v-loading="detailLoading" show-summary :summary-method="detailLineSummary">
        <el-table-column fixed="left" label="#" prop="lineNo" width="56" />
        <el-table-column fixed="left" label="物料编码" prop="matCode" width="120" show-overflow-tooltip />
        <el-table-column fixed="left" label="物料名称" prop="matName" min-width="170" show-overflow-tooltip />
        <el-table-column label="规格型号" prop="matSpec" min-width="110" show-overflow-tooltip />
        <el-table-column label="单位" prop="matUnit" width="64" />
        <el-table-column label="账面数" width="100" align="right"><template #default="{ row }">{{ Number(row.bookQty) }}</template></el-table-column>
        <el-table-column v-if="canSeeCost" label="账面单价" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template></el-table-column>
        <el-table-column v-if="canSeeCost" label="账面金额" width="120" align="right"><template #default="{ row }">{{ fmtMoney(bookAmt(row)) }}</template></el-table-column>
        <el-table-column label="实盘数" width="130" align="right">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.actualQty" :min="0" :controls="false" size="small" style="width:104px" placeholder="未盘" />
            <span v-else>{{ row.actualQty == null ? '未盘' : Number(row.actualQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异" width="90" align="right">
          <template #default="{ row }">
            <span v-if="diffOf(row) === null">-</span>
            <span v-else :class="{ gain: diffOf(row)! > 0, loss: diffOf(row)! < 0, big: bigDiff(row) }" :title="bigDiff(row) ? '差异大得反常，请复核是否录错' : ''">{{ bigDiff(row) ? '⚠ ' : '' }}{{ diffOf(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="盈亏金额" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.diffAmt == null">-</span>
            <span v-else :class="{ gain: Number(row.diffAmt) > 0, loss: Number(row.diffAmt) < 0 }">{{ fmtMoney(row.diffAmt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="120">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.remark" size="small" maxlength="255" />
            <span v-else>{{ row.remark || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <DocAttachments v-if="detail" biz-type="STOCKTAKE" :biz-id="detail.id" />
      <template v-if="detail">
        <!-- 盘点走自己的「财务确认」,不经过通用审批流:按盘点单字段显示,不能用通用审批记录(计算审查 U19:
             通用接口查不到流程,已提交/已确认的盘点也显示「还没有提交审批」) -->
        <div class="dd-sec">确认记录</div>
        <ol class="st-log">
          <li v-if="detail.status === 'SUBMITTED'"><i class="y"></i><span><b>等待财务确认</b></span><span class="st-time">—</span></li>
          <li v-if="detail.status === 'APPROVED'"><i class="g"></i><span><b>财务确认</b><template v-if="detail.financeBy"> · {{ detail.financeBy }}</template></span><span class="st-time">{{ fmtMinute(detail.financeAt) }}</span></li>
          <li v-if="detail.status === 'REJECTED'"><i class="r"></i><span><b>驳回</b><template v-if="detail.financeBy"> · {{ detail.financeBy }}</template><span v-if="detail.rejectReason" class="st-cmt">「{{ detail.rejectReason }}」</span></span><span class="st-time">{{ fmtMinute(detail.financeAt) }}</span></li>
          <li><i class="n"></i><span><b>制单</b><template v-if="detail.createdByName"> · {{ detail.createdByName }}</template></span><span class="st-time">{{ fmtMinute(detail.createdAt) }}</span></li>
        </ol>
      </template>
      <template #footer-left>
        <el-button v-if="detail?.status === 'SUBMITTED' && canApprove" type="danger" plain @click="reject">驳回</el-button>
      </template>
      <template #footer>
        <el-button @click="requestCloseDetail">关闭</el-button>
        <el-button v-if="editable" :loading="saving" @click="saveActual()">保存实盘数</el-button>
        <el-button v-if="editable && canEdit" type="primary" :loading="submitting" @click="submit">提交财务确认</el-button>
        <el-button v-if="detail?.status === 'SUBMITTED' && canApprove" type="primary" :loading="approving" @click="approve">确认并生成盘盈盘亏</el-button>
      </template>
    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocDrawer from '@/components/DocDrawer.vue'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import DocStepGuide from '@/components/DocStepGuide.vue'
import { stocktakeGuide } from '@/utils/docSteps'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { masterApi, stocktakeApi } from '@/api'
import { fmtDate, fmtMinute, fmtMoney } from '@/utils/format'
import DocNoLookup from '@/components/DocNoLookup.vue'
import { makeSortHandler } from '@/utils/tableSort'
import { saveBlob } from '@/utils/download'
import { useUserStore } from '@/stores/user'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import { showError } from '@/utils/notify'

const user = useUserStore()
const route = useRoute()
const canEdit = computed(() => user.hasRole('warehouse') || user.hasRole('account_admin') || user.hasRole('admin'))
const canApprove = computed(() => user.hasFinanceAccessOrAdmin())
const canSeeCost = user.hasFinanceAccess()

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', warehouseId: null, creatorName: '', startDate: null, endDate: null })
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])

function statusMeta(s?: string) {
  return ({
    DRAFT: { label: '草稿', tone: 'info' },
    SUBMITTED: { label: '待财务确认', tone: 'warning' },
    APPROVED: { label: '已确认', tone: 'success' },
    REJECTED: { label: '已驳回', tone: 'danger' }
  } as any)[s || ''] || { label: s || '-', tone: 'info' }
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待财务确认', value: 'SUBMITTED' }, { label: '已确认', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => stocktakeApi.page(q), ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'])

async function load() {
  loading.value = true
  try {
    query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
    query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
    refreshStatusCounts(query)
    const r: any = await stocktakeApi.page({ ...query })
    rows.value = r.records || []
    total.value = Number(r.total || 0)
  } finally { loading.value = false }
}

function doSearch() { query.current = 1; load() }
function resetQuery() {
  Object.assign(query, { current: 1, status: '', keyword: '', warehouseId: null, creatorName: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

// ── 新建 ──
const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const creating = ref(false)
const createForm = reactive<any>({ warehouseId: null, takeDate: null, includeZero: false, remark: '' })
function openCreate() {
  Object.assign(createForm, { warehouseId: warehouses.value[0]?.id || null, takeDate: null, includeZero: false, remark: '' })
  createVisible.value = true
}
async function doCreate() {
  if (!createForm.warehouseId) { ElMessage.warning('请选择盘点仓库'); return }
  creating.value = true
  try {
    const d: any = await stocktakeApi.create({ ...createForm })
    ElMessage.success(`盘点单 ${d.docNo} 已建，共 ${d.lineCnt} 行`)
    createVisible.value = false
    await load()
    detail.value = d
    detailVisible.value = true
  } catch (e: any) { showError(e, '建单失败') }
  finally { creating.value = false }
}

// ── 明细/录实盘 ──
const detailVisible = ref(false)
// 录了实盘数没点「保存实盘数」就关，会白录；只在还能改的状态下才拦
const detailGuard = useUnsavedGuard(detailVisible)
function closeDetail(done: () => void) { editable.value ? detailGuard.beforeClose(done) : done() }
// 底栏「关闭」与右上角 × 同一套：有没保存的实盘数先问一下
function requestCloseDetail() { closeDetail(() => { detailVisible.value = false }) }
// 详情面板顶部关键数字：行数 / 已盘 / 盘盈盘亏（确认后、有权限才显示金额）
const stkNums = computed(() => {
  const d: any = detail.value
  if (!d) return []
  const lines = d.lines || []
  const list: any[] = [
    { label: '盘点行数', value: d.lineCnt ?? lines.length },
    { label: '已录实盘', value: lines.filter((l: any) => l.actualQty != null).length }
  ]
  if (d.status === 'APPROVED' && canSeeCost) {
    list.push({ label: '盘盈金额', value: fmtMoney(d.gainAmt) }, { label: '盘亏金额', value: fmtMoney(d.lossAmt), hl: true })
  }
  return list
})
const detailLoading = ref(false)
const detail = ref<any>(null)
const saving = ref(false)
const submitting = ref(false)
const approving = ref(false)
const editable = computed(() => canEdit.value && (detail.value?.status === 'DRAFT' || detail.value?.status === 'REJECTED'))

/**
 * 差异大得反常的行（2026-10-01，同采购单价防错思路：让可能录错的那一行自己跳出来）。
 * 账面有数时实盘 ≥3 倍或 ≤三成、账面为 0 却盘出数 → 多半是多敲/少敲一个零或填错行，请复点。
 */
function bigDiff(row: any): boolean {
  if (row.actualQty == null || row.actualQty === '') return false
  const book = Number(row.bookQty || 0), actual = Number(row.actualQty)
  if (book <= 0) return actual > 0
  return actual >= book * 3 || actual <= book * 0.3
}
/** 提交/确认前摆给人看的盘点小结：几行未盘（不调整）、几行有差异、差异反常的是哪几行 */
function takeSummary(lines: any[]): string {
  const uncounted = lines.filter(l => l.actualQty == null || l.actualQty === '').length
  const diffs = lines.filter(l => { const d = diffOf(l); return d !== null && d !== 0 })
  const gain = diffs.filter(l => diffOf(l)! > 0).length
  const big = lines.filter(bigDiff)
  let t = `共 ${lines.length} 行：有差异 ${diffs.length} 行（盘盈 ${gain} 行、盘亏 ${diffs.length - gain} 行）`
  if (uncounted) t += `；未盘 ${uncounted} 行，这些行不做调整`
  if (big.length) {
    t += `\n\n⚠ 以下 ${big.length} 行差异大得反常，请复核是否录错：\n` + big.slice(0, 10)
      .map(l => `· ${l.matName || l.matCode}：账面 ${Number(l.bookQty)} → 实盘 ${Number(l.actualQty)} ${l.matUnit || ''}`).join('\n')
    if (big.length > 10) t += `\n……另有 ${big.length - 10} 行，见明细中标红的差异`
  }
  return t
}

function diffOf(row: any): number | null {
  if (row.actualQty == null || row.actualQty === '') return null
  return Number((Number(row.actualQty) - Number(row.bookQty)).toFixed(4))
}

function bookAmt(row: any): number | null {
  if (row.bookQty == null || row.unitPrice == null) return null
  return Number(row.bookQty) * Number(row.unitPrice)
}

async function openDetail(row: any) {
  detailVisible.value = true
  detailLoading.value = true
  try { detail.value = await stocktakeApi.detail(row.id) as any }
  finally { detailLoading.value = false }
}

async function saveActual(silent = false) {
  if (!detail.value) return
  saving.value = true
  try {
    await stocktakeApi.saveActual(detail.value.id, (detail.value.lines || []).map((l: any) => ({ id: l.id, actualQty: l.actualQty ?? null, remark: l.remark })))
    detailGuard.markSaved()
    if (!silent) ElMessage.success('实盘数已保存')
  } finally { saving.value = false }
}

async function uploadActual(options: any) {
  if (!detail.value) return
  try {
    const r: any = await stocktakeApi.importActual(detail.value.id, options.file)
    const msg = `导入完成：更新 ${r.updated || 0} 行，跳过 ${r.skipped || 0} 行`
    if (r.errors?.length) ElMessage.warning(`${msg}；${r.errors.slice(0, 3).join('；')}`)
    else ElMessage.success(msg)
    detail.value = await stocktakeApi.detail(detail.value.id) as any
    detailGuard.markSaved()
  } catch (e: any) { showError(e, '导入失败') }
}

async function downloadSheet(blind: 0 | 1) {
  if (!detail.value) return
  const blob = await stocktakeApi.sheet(detail.value.id, blind) as unknown as Blob
  saveBlob(blob, `盘点表-${detail.value.docNo}${blind ? '-盲盘' : ''}.xlsx`)
}

// ── 正规打印(公司抬头+签字栏,与其他单据同一版式;默认横版) ──
const blindPrint = ref(false)
function printSheet(action: PaperAction, paper: PaperSize, orientation: PrintOrientation) {
  const doc = detail.value
  if (!doc) return
  const blind = blindPrint.value
  const columns: PrintColumn[] = blind
    ? [
        { label: '序号', value: (_r: any, i: number) => i + 1, width: '5%', align: 'center' },
        { label: '物料编码', value: (l: any) => l.matCode || '', width: '13%' },
        { label: '物料名称', value: (l: any) => l.matName || '', width: '22%' },
        { label: '规格型号', value: (l: any) => l.matSpec || '', width: '16%' },
        { label: '单位', value: (l: any) => l.matUnit || '', width: '6%', align: 'center' },
        { label: '实盘数量', value: (l: any) => l.actualQty == null ? '' : String(Number(l.actualQty)), width: '14%', align: 'right' },
        { label: '备注', value: (l: any) => l.remark || '', width: '24%' }
      ]
    : [
        { label: '序号', value: (_r: any, i: number) => i + 1, width: '5%', align: 'center' },
        { label: '物料编码', value: (l: any) => l.matCode || '', width: '12%' },
        { label: '物料名称', value: (l: any) => l.matName || '', width: '19%' },
        { label: '规格型号', value: (l: any) => l.matSpec || '', width: '13%' },
        { label: '单位', value: (l: any) => l.matUnit || '', width: '5%', align: 'center' },
        { label: '账面数量', value: (l: any) => String(Number(l.bookQty ?? 0)), width: '9%', align: 'right' },
        { label: '实盘数量', value: (l: any) => l.actualQty == null ? '' : String(Number(l.actualQty)), width: '10%', align: 'right' },
        { label: '差异', value: (l: any) => { const d = diffOf(l); return d === null ? '' : String(d) }, width: '8%', align: 'right' },
        { label: '备注', value: (l: any) => l.remark || '', width: '19%' }
      ]
  const financeSign = doc.financeBy
    ? { label: '财务确认', lines: [`${doc.financeBy}（线上确认）`, String(doc.financeAt || '').slice(0, 16).replace('T', ' ')] }
    : { label: '财务确认', lines: ['签字：', '日期：'] }
  const options = {
    orgId: doc?.orgId,
    title: blind ? '盘点表（盲盘）' : '盘点表',
    paper,
    orientation,
    fileName: `盘点表-${doc.docNo}${blind ? '-盲盘' : ''}`,
    fields: [
      { label: '盘点单号', value: doc.docNo || '', span: 2 },
      { label: '盘点日期', value: doc.takeDate || '' },
      { label: '盘点仓库', value: doc.warehouseName || '', span: 2 },
      { label: '制单人', value: doc.createdByName || '' },
      { label: '备注', value: doc.remark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '盘点人', lines: ['签字：', '日期：'] },
      { label: '监盘人', lines: ['签字：', '日期：'] },
      { label: '仓管员', lines: ['签字：', '日期：'] },
      financeSign
    ],
    note: '说明：实盘数量由盘点人现场清点填写；差异=实盘-账面；财务确认后系统按结存单价自动生成盘盈/盘亏调整。'
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

async function submit() {
  if (!detail.value) return
  await saveActual(true)
  await ElMessageBox.confirm(takeSummary(detail.value.lines || []) + '\n\n提交后不可再改实盘数，由财务确认并生成盘盈盘亏调整。确定提交?', '提交盘点单',
    { type: 'warning', customStyle: { whiteSpace: 'pre-line' } as any })
  submitting.value = true
  try {
    await stocktakeApi.submit(detail.value.id)
    ElMessage.success('已提交财务确认')
    detailVisible.value = false
    await load()
  } catch (e: any) { showError(e, '提交失败') }
  finally { submitting.value = false }
}

async function approve() {
  if (!detail.value) return
  await ElMessageBox.confirm(takeSummary(detail.value.lines || []) + '\n\n确认后将按盘点快照单价自动生成盘盈(入库)/盘亏(出库)调整，直接改动库存。确定?', '财务确认',
    { type: 'warning', customStyle: { whiteSpace: 'pre-line' } as any })
  approving.value = true
  try {
    await stocktakeApi.approve(detail.value.id)
    ElMessage.success('已确认，盘盈盘亏已调整入库存')
    detail.value = await stocktakeApi.detail(detail.value.id) as any
    await load()
  } catch (e: any) { showError(e, '确认失败') }
  finally { approving.value = false }
}

async function reject() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt('填写驳回原因（仓管可改实盘数后重新提交）', '驳回', { inputPlaceholder: '如：3 行实盘数疑似录错' })
  await stocktakeApi.reject(detail.value.id, value || '')
  ElMessage.success('已驳回')
  detailVisible.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除盘点单 ${row.docNo}?（删除会计入操作日志）`, '提示', { type: 'warning' })
  await stocktakeApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  // 从待办跳转时带单号定位
  if (route.query.keyword) query.keyword = String(route.query.keyword)
  warehouses.value = await masterApi.warehouses(true) as any
  await load()
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 盘盈金额: { value: (r: any) => r.status === 'APPROVED' ? Number(r.gainAmt || 0) : 0, type: 'money' }, 盘亏金额: { value: (r: any) => r.status === 'APPROVED' ? Number(r.lossAmt || 0) : 0, type: 'money' } })

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 账面数: { field: 'bookQty', type: 'qty' }, 账面金额: { value: (r: any) => Number(bookAmt(r) || 0), type: 'money' }, 实盘数: { field: 'actualQty', type: 'qty' }, 盈亏金额: { field: 'diffAmt', type: 'money' } })
</script>

<style scoped>
/* 盘点确认记录(与审批记录同样式,最新在上) */
.st-log { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 10px; font-size: 13px; }
.st-log li { display: grid; grid-template-columns: 12px minmax(0, 1fr) auto; gap: 10px; align-items: start; }
.st-log i { width: 9px; height: 9px; border-radius: 50%; margin-top: 5px; background: #9aa5b1; }
.st-log i.g { background: #1e7a4c; }
.st-log i.y { background: #c77700; }
.st-log i.r { background: #b42318; }
.st-cmt { color: var(--text-2); margin-left: 6px; }
.st-time { color: var(--text-2); font-variant-numeric: tabular-nums; white-space: nowrap; }
.stk-head { display: flex; flex-wrap: wrap; gap: 16px; color: #4b5563; font-size: 13px; }
.gain { color: #2f9e6f; }
.loss { color: #c0392b; }
.big { font-weight: 700; background: #fff1f0; padding: 0 4px; border-radius: 3px; }
</style>
