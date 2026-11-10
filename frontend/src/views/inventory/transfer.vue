<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">库存转库</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse')" type="primary" @click="openCreate">新建转库单</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default">
          <el-form-item data-q="status" label="状态">
            <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="待实际交接" value="READY" />
              <el-option label="已审批/已交接" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已作废" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="fromWarehouseId" label="转出仓库">
            <el-select v-model="query.fromWarehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="warehouseLabel(w)" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="toWarehouseId" label="转入仓库">
            <el-select v-model="query.toWarehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="warehouseLabel(w)" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="dateRange" label="转库日期">
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
          </el-form-item>
          <el-form-item data-q="keyword" label="单据号">
            <DocNoLookup v-model="query.keyword" type="TRANSFER" multiple placeholder="点一下列出最近转库单" width="220px" @enter="doSearch" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="转库单号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="transferDate" label="转库日期" width="110" sortable="custom" />
        <el-table-column label="转出仓库" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ whName(row.fromWarehouseId) }}</template>
        </el-table-column>
        <el-table-column label="转入仓库" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ whName(row.toWarehouseId) }}</template>
        </el-table-column>
        <el-table-column prop="transferer" label="经办人" width="110" sortable="custom" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column prop="totalAmt" sortable="custom" v-if="canSeeCost" label="金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button v-if="canEdit(row)" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canEdit(row)" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="success" @click="approveOrReject(row, true)">审批</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="danger" @click="approveOrReject(row, false)">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED' && !row.sourceReqId" link type="warning" @click="requestCancelApproval('TRANSFER', row, load)">取消审批</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <el-dialog draggable v-model="formVisible" :before-close="formGuard.beforeClose" :title="form.id ? '编辑转库单' : '新建转库单'" width="min(1080px, calc(100vw - 48px))" class="doc-entry-dialog">
      <DocGuardHint :active="formVisible" :date="form.transferDate" :warehouse-ids="[form.fromWarehouseId, form.toWarehouseId]" v-model:blocked="guardBlocked" />
      <el-form label-position="top" ref="formRef" :model="form" label-width="90px">
        <el-row :gutter="12" class="doc-key-fields">
          <el-col :span="8">
            <el-form-item label="转库日期" required>
              <el-date-picker v-model="form.transferDate" value-format="YYYY-MM-DD" type="date" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="转出仓库" required>
              <el-select v-model="form.fromWarehouseId" filterable style="width:100%" @change="onWarehouseChange">
                <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="warehouseLabel(w)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="转入仓库" required>
              <el-select v-model="form.toWarehouseId" filterable style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="warehouseLabel(w)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="经办人">
              <el-input v-model="form.transferer" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="备注">
              <el-input v-model="form.remark" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <div class="table-toolbar">
        <div class="section-title">转库明细</div>
        <el-button type="primary" link @click="addLine">添加物料</el-button>
      </div>
      <el-table max-height="min(56vh, 560px)" class="readable-table" border :fit="false" :data="form.lines" empty-text="请添加转库物料">
        <el-table-column label="物料" min-width="230">
          <template #default="{ row }">
            <MaterialLookup v-model="row.materialId" placeholder="选择物料" title="选择物料编码" @change="mat => onMaterialChange(row, mat)" />
          </template>
        </el-table-column>
        <el-table-column label="转出仓当前库存" width="130" align="right">
          <template #default="{ row }">{{ $fmtQty(row.currentQty) }}</template>
        </el-table-column>
        <el-table-column label="转库数量" width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0" :precision="4" :step="1" controls-position="right" style="width:100%" />
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="180">
          <template #default="{ row }"><el-input v-model="row.remark" /></template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }"><el-button link type="danger" @click="removeLine($index)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <DocAttachments ref="transferAttRef" biz-type="TRANSFER" :biz-id="form.id" />

      <template #footer>
        <el-button @click="formGuard.cancel">取消</el-button>
        <el-button :loading="saving" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(false)">保存</el-button>
        <el-button type="primary" :loading="saving" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <DocDrawer v-model="detailVisible" kind="库存转库单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :steps="['制单', '审批', '实际交接']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detailData">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="转库单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="statusType(detailData.status)" size="small">{{ statusText(detailData.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="转库日期">{{ detailData.transferDate }}</el-descriptions-item>
          <el-descriptions-item label="转出仓库">{{ whName(detailData.fromWarehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="转入仓库">{{ whName(detailData.toWarehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="经办人">{{ detailData.transferer || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="金额">{{ fmtMoney(detailData.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="canSeeCost ? 1 : 2">{{ detailData.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <PickupPanel v-if="detailData.sourceReqId" :request-id="detailData.sourceReqId" :source-warehouse="detailData.fromWarehouseId" @changed="openDetail(detailData.id); load()" />
        <el-table max-height="min(56vh, 560px)" class="readable-table" border :fit="false" :data="detailData.lines || []" empty-text="无明细" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" prop="lineNo" label="行" width="60" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="150">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="数量" width="110" align="right" :formatter="$qtyFmt" />
          <el-table-column v-if="canSeeCost" label="单价" width="120" align="right">
            <template #default="{ row }">{{ fmtPrice(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        </el-table>
        <DocAttachments biz-type="TRANSFER" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          type="primary"
          @print="(paper, orientation) => printTransfer(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printTransfer(detailData, 'export', paper, orientation)"
        />
      </template>

    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import DocNoLookup from '@/components/DocNoLookup.vue'
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import PickupPanel from '@/components/PickupPanel.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showError } from '@/utils/notify'
import { inventoryApi, masterApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useRoute } from 'vue-router'
import { applyRouteQuery } from '@/utils/routeQuery'
import { fmtDate, fmtMoney, fmtPrice, today, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { buildPrintQr } from '@/utils/docQr'
import { approvalRecordNote } from '@/utils/approvalPrint'
import { requestCancelApproval } from '@/utils/docActions'
import MaterialLookup from '@/components/MaterialLookup.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'

const user = useUserStore()
const canSeeCost = computed(() => user.hasFinanceAccessOrAdmin())
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const dateRange = ref<any>(null)
const route = useRoute()
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', fromWarehouseId: null, toWarehouseId: null, startDate: null, endDate: null })
const warehouses = ref<any[]>([])
const materials = ref<any[]>([])
const whMap = ref<Record<number, any>>({})
const matMap = ref<Record<number, any>>({})
const formVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const formGuard = useUnsavedGuard(formVisible)
const transferAttRef = ref()
const detailVisible = ref(false)
const saving = ref(false)
const detailData = ref<any>(null)
const formRef = ref<any>(null)
const form = reactive<any>({ id: null, fromWarehouseId: null, toWarehouseId: null, transferDate: today(), transferer: '', remark: '', lines: [] })

const exportColumns: ExportColumn[] = [
  { label: '转库单号', key: 'docNo' },
  { label: '转库日期', key: 'transferDate' },
  { label: '转出仓库', value: row => whName(row.fromWarehouseId) },
  { label: '转入仓库', value: row => whName(row.toWarehouseId) },
  { label: '经办人', key: 'transferer' },
  { label: '数量', qty: true, key: 'totalQty' },
  { label: '状态', value: row => statusText(row.status) },
  { label: '备注', key: 'remark' }
]

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '待实际交接', value: 'READY' }, { label: '已审批/已交接', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => inventoryApi.transferPage(q), ['DRAFT', 'PENDING', 'READY', 'APPROVED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const res: any = await inventoryApi.transferPage(query)
    rows.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  query.current = 1
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', fromWarehouseId: null, toWarehouseId: null, startDate: null, endDate: null })
  dateRange.value = null
  load()
}

async function loadOptions() {
  const [whs, mats] = await Promise.all([
    masterApi.warehouses(true),
    masterApi.allMaterials({ includeDisabled: true })
  ])
  warehouses.value = whs as any[]
  materials.value = mats as any[]
  whMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
}

function openCreate() {
  transferAttRef.value?.reset()  // 清掉上一单残留的暂存附件,防串单
  const from = warehouses.value[0]?.id ?? null
  const to = warehouses.value.find((w: any) => w.id !== from)?.id ?? null
  Object.assign(form, { id: null, fromWarehouseId: from, toWarehouseId: to, transferDate: today(), transferer: user.userInfo?.name || '', remark: '', lines: [] })
  addLine()
  formVisible.value = true
}

async function openEdit(row: any) {
  const detail: any = await inventoryApi.transferDetail(row.id)
  Object.assign(form, {
    id: detail.id,
    fromWarehouseId: detail.fromWarehouseId,
    toWarehouseId: detail.toWarehouseId,
    transferDate: detail.transferDate || today(),
    transferer: detail.transferer || '',
    remark: detail.remark || '',
    lines: (detail.lines || []).map((line: any) => ({ ...line, currentQty: null }))
  })
  formVisible.value = true
  await refreshAllCurrentQty()
}

async function openDetail(id: number) {
  detailData.value = await inventoryApi.transferDetail(id)
  detailVisible.value = true
}

function addLine() {
  form.lines.push({ materialId: null, qty: 1, currentQty: null, remark: '' })
}

function removeLine(index: number) {
  form.lines.splice(index, 1)
}

async function onWarehouseChange() {
  await refreshAllCurrentQty()
}

async function onMaterialChange(row: any, mat: any) {
  row.materialName = mat?.name || ''
  await loadLineStock(row)
}

async function refreshAllCurrentQty() {
  await Promise.all((form.lines || []).map((line: any) => loadLineStock(line)))
}

async function loadLineStock(row: any) {
  if (!row.materialId || !form.fromWarehouseId) {
    row.currentQty = null
    return
  }
  const balance: any = await inventoryApi.single(row.materialId, form.fromWarehouseId)
  row.currentQty = Number(balance?.qty ?? 0)
}

function validateForm() {
  if (!form.transferDate) return '请选择转库日期'
  if (!form.fromWarehouseId) return '请选择转出仓库'
  if (!form.toWarehouseId) return '请选择转入仓库'
  if (form.fromWarehouseId === form.toWarehouseId) return '转出仓库和转入仓库不能相同'
  const validLines = form.lines.filter((line: any) => line.materialId && Number(line.qty || 0) > 0)
  if (!validLines.length) return '请至少填写一条转库明细'
  const seen = new Set<number>()
  for (const line of validLines) {
    if (seen.has(line.materialId)) return '同一物料请合并为一条明细'
    seen.add(line.materialId)
    if (line.currentQty !== null && line.currentQty !== undefined && Number(line.qty || 0) > Number(line.currentQty || 0)) {
      return `${matText(line.materialId)} 转库数量不能超过转出仓当前库存`
    }
  }
  return ''
}

async function save(andSubmit: boolean) {
  const error = validateForm()
  if (error) {
    ElMessage.warning(error)
    return
  }
  saving.value = true
  try {
    const payload = {
      fromWarehouseId: form.fromWarehouseId,
      toWarehouseId: form.toWarehouseId,
      transferDate: form.transferDate,
      transferer: form.transferer,
      remark: form.remark,
      lines: form.lines
        .filter((line: any) => line.materialId && Number(line.qty || 0) > 0)
        .map((line: any) => ({ materialId: line.materialId, qty: line.qty, remark: line.remark }))
    }
    const saved: any = form.id
      ? await inventoryApi.transferUpdate(form.id, payload)
      : await inventoryApi.transferCreate(payload)
    if (transferAttRef.value) await transferAttRef.value.uploadPending(saved.id)
    if (andSubmit) {
      // 已保存：提交没过（如提交时库存不足）也关掉弹窗，否则再点一次会把同一张转库单再新建一张
      try { await inventoryApi.transferSubmit(saved.id) }
      catch (e: any) {
        formVisible.value = false
        showError(e, '提交失败')
        ElMessage.warning(`转库单 ${saved.docNo} 已保存为草稿，没有提交审批`)
        await load()
        return
      }
      ElMessage.success(`已提交转库单 ${saved.docNo}`)
    } else {
      ElMessage.success(form.id ? '已更新' : `已保存转库单 ${saved.docNo}`)
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function submit(row: any) {
  await ElMessageBox.confirm(`确认提交转库单 ${row.docNo}？`, '提交确认', { type: 'warning' })
  await inventoryApi.transferSubmit(row.id)
  ElMessage.success('已提交财务审批')
  await load()
}

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交转库单 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await inventoryApi.transferWithdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

async function approveOrReject(row: any, approve: boolean) {
  const { value } = await ElMessageBox.prompt('审批意见', `${approve ? '通过' : '驳回'} ${row.docNo}`, {
    inputType: 'textarea',
    inputValue: approve ? '同意转库' : ''
  })
  if (approve) {
    await inventoryApi.transferApprove(row.id, value || '')
    ElMessage.success('已审批，库存已联动更新')
  } else {
    await inventoryApi.transferReject(row.id, value || '')
    ElMessage.success('已驳回')
  }
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除转库单 ${row.docNo}？`, '删除确认', { type: 'warning' })
  await inventoryApi.transferDelete(row.id)
  ElMessage.success('已删除')
  await load()
}

function canEdit(row: any) {
  return !row.sourceReqId && user.hasRole('warehouse') && ['DRAFT', 'REJECTED'].includes(row.status)
}

function exportExcel() {
  const cols = canSeeCost.value
    ? [...exportColumns.slice(0, 6), { label: '金额', money: true, value: (row: any) => fmtMoney(row.totalAmt) }, ...exportColumns.slice(6)]
    : exportColumns
  exportRowsToExcel('库存转库', cols, rows.value, '库存转库', { total: total.value, fetch: p => inventoryApi.transferPage({ ...query, ...p }) })
}

async function printTransfer(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'portrait') {
  if (!warehouses.value.length || !materials.value.length) await loadOptions()
  const doc: any = rowOrDoc?.lines ? rowOrDoc : await inventoryApi.transferDetail(rowOrDoc.id)
  const columns: PrintColumn[] = [
    { label: '序号', value: (_row, index) => index + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: line => matMap.value[line.materialId]?.code || '', width: '13%' },
    { label: '物料名称', value: line => matMap.value[line.materialId]?.name || line.materialId || '', width: '20%' },
    { label: '规格型号', value: line => matMap.value[line.materialId]?.spec || '', width: '14%' },
    { label: '单位', value: line => matMap.value[line.materialId]?.unit || '', width: '6%', align: 'center' },
    { label: '转库数量', key: 'qty', width: '10%', align: 'right' },
    ...(canSeeCost.value ? [
      { label: '单价', value: (line: any) => fmtPrice(line.unitPrice), width: '11%', align: 'right' },
      { label: '金额', value: (line: any) => fmtMoney(line.amount), width: '12%', align: 'right' }
    ] as PrintColumn[] : []),
    { label: '备注', key: 'remark', width: '9%' }
  ]
  const options = {
    orgId: doc?.orgId,
    title: '库存转库单',
    subtitle: `${paper} 打印版`,
    fileName: `${doc.docNo || '库存转库'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('TRANSFER', doc.id, doc.docNo),
    fields: [
      { label: '转库单号', value: doc.docNo },
      { label: '状态', value: statusText(doc.status) },
      { label: '转库日期', value: doc.transferDate || '' },
      { label: '转出仓库', value: whName(doc.fromWarehouseId) },
      { label: '转入仓库', value: whName(doc.toWarehouseId) },
      { label: '经办人', value: doc.transferer || '' },
      { label: '总数量', value: doc.totalQty || 0 },
      ...(canSeeCost.value ? [{ label: '金额', value: fmtMoney(doc.totalAmt) }] : []),
      { label: '备注', value: doc.remark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '转出仓确认', lines: ['签字：', '日期：'] },
      { label: '转入仓确认', lines: ['签字：', '日期：'] },
      { label: '财务审核', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '本单据用于仓库之间材料数量转移确认，审批通过后系统同步扣减转出仓并增加转入仓。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

function warehouseLabel(w: any) {
  return `${w.name}${w.warehouseType === 'MAIN' ? '（总仓）' : '（二级仓）'}`
}

function whName(id: number) {
  return whMap.value[id]?.name || id || '-'
}

function matText(id: number) {
  const mat = matMap.value[id]
  return mat ? `${mat.code} ${mat.name}` : id || '-'
}

function statusText(status: string) {
  return ({ READY: '待实际交接', DRAFT: '草稿', PENDING: '待审批', APPROVED: '已审批', REJECTED: '已驳回' } as Record<string, string>)[status] || status || '-'
}

function statusType(status: string): any {
  return ({ DRAFT: 'info', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' } as Record<string, string>)[status] || 'info'
}

onMounted(async () => {
  await loadOptions()
  // 从月末结账「问题单据」点单号跳来时带 keyword，自动筛出那一张
  applyRouteQuery(query, route, ['status', 'keyword'])
  load()
})

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a: number, r: any) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detailData.value
  if (!d) return ''
  return [d.fromWarehouseId && `${whName(d.fromWarehouseId)} → ${whName(d.toWarehouseId)}`, d.transferDate && `转库 ${d.transferDate}`, d.transferer && `经办人 ${d.transferer}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "READY": 2, "APPROVED": 3} as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '转库数量', value: fmtQty(d.totalQty) }, canSeeCost.value && { label: '金额（单价不变）', value: fmtMoney(d.totalAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 金额: { field: 'amount', type: 'money' } })
</script>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 4px 0 10px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}
</style>
