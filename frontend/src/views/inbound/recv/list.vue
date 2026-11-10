<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">{{ pageTitle }}</div>
        <div class="page-sub">入库审批通过后实时按加权平均更新库存与结存单价</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse')" type="primary" @click="openPushFromPo">下推入库</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="状态" data-q="status">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="PENDING" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库日期" data-q="dateRange">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item label="入库单号" data-q="keyword">
          <DocNoLookup v-model="query.keyword" type="RECV" multiple placeholder="点一下列出最近入库单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="供应商" data-q="supplierId">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
        <el-form-item label="仓库" data-q="warehouseId">
          <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料" data-q="materialId">
          <MaterialLookup v-model="query.materialId" placeholder="编码、名称或规格" />
        </el-form-item>
        <el-form-item v-if="!route.path.includes('/est')" label="入库类型" data-q="recvType">
          <el-select v-model="query.recvType" placeholder="全部" clearable>
            <el-option label="暂估入库" value="ESTIMATE" />
            <el-option label="发票入库" value="INVOICE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联订单" data-q="sourcePoNo">
          <DocNoLookup v-model="query.sourcePoNo" type="PO" placeholder="点一下列出最近采购订单，也可输入单号" @enter="doSearch" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @sort-change="onSortChange">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="入库单号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" aria-label="查看单据" :title="`查看单据 ${row.docNo}`" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="sourcePoNo" label="关联订单" min-width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="supplierName" sortable="custom" label="供应商" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ supplierMap[row.supplierId]?.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="物料编码" min-width="125" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialCodes) }}</template>
        </el-table-column>
        <el-table-column label="物料名称" min-width="170">
          <template #default="{ row }"><span class="mat-name-cell">{{ firstMat(row.materialNames) }}<MoreMaterialsTag :codes="row.materialCodes" :names="row.materialNames" :specs="row.materialSpecs" /></span></template>
        </el-table-column>
        <el-table-column label="规格型号" min-width="115" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialSpecs) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.recvType === 'ESTIMATE' ? 'warning' : 'primary'" size="small">
              {{ row.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="recvDate" label="入库日期" width="110" sortable="custom" />
        <el-table-column prop="totalQty" label="数量" width="90" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column v-if="canSeeCost" prop="totalAmt" sortable="custom" label="金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template>
        </el-table-column>
        <el-table-column prop="invoiceNo" label="关联发票" min-width="210" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="status" sortable="custom" label="状态" width="100">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printRecv(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="row.status === 'DRAFT' && user.hasRole('warehouse')" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="openApprove(row)">审批</el-button>
            <el-button v-if="canApprovePending(row)" link type="danger"  @click="rejectRecv(row)">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('RECV', row, load)">取消审批</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="confirmDeleteDoc(row, recvApi.delete, load, '入库单')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <!-- 入库单详情-->
    <DocDrawer v-model="detailVisible" kind="采购入库单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :steps="['下推入库', '审批（增加库存）', '已入库']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detailData">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="入库单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="关联订单">{{ detailData.sourcePoNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ supplierMap[detailData.supplierId]?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="入库类型">
            <el-tag :type="detailData.recvType === 'ESTIMATE' ? 'warning' : 'primary'" size="small">
              {{ detailData.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="入库日期">{{ detailData.recvDate }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseMap[detailData.warehouseId]?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联发票">{{ detailData.invoiceNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="不含税合计">{{ fmtMoney(detailData.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="税额">{{ fmtMoney(detailData.taxAmt) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="含税合计"><b>{{ fmtMoney(detailData.totalAmtInclTax) }}</b></el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailData.remark }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="160">
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="170">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" prop="qty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column v-if="canSeeCost" label="不含税单价" width="110" align="right">
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="税率%" width="80" align="right">
            <template #default="{ row }">{{ Number(row.taxRate || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="不含税金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="含税金额" width="120" align="right">
            <template #default="{ row }"><b>{{ fmtMoney(row.amountInclTax) }}</b></template>
          </el-table-column>
          <el-table-column label="备注" prop="remark" min-width="150" show-overflow-tooltip />
        </el-table>
        <DocAttachments biz-type="RECV" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
        <el-button v-if="detailData && detailData.status === 'PENDING' && detailData.canApprove" type="danger" plain @click="rejectFromDetail()">驳回</el-button>
        <el-button v-if="detailData && user.canDeleteDoc(detailData)" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          :type="detailData && detailData.status === 'PENDING' && detailData.canApprove ? '' : 'primary'"
          @print="(paper, orientation) => printRecv(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printRecv(detailData, 'export', paper, orientation)"
        />
        <el-button v-if="detailData && detailData.status === 'PENDING' && detailData.canApprove" type="primary" @click="approveFromDetail()">审批通过</el-button>
      </template>

    </DocDrawer>

    <!-- 下推入库弹窗 -->
    <el-dialog draggable v-model="pushVisible" :before-close="pushGuard.beforeClose" title="下推生成入库单" width="min(960px, calc(100vw - 56px))" align-center class="doc-entry-dialog">
      <DocGuardHint :active="pushVisible" :source-org-id="pushPoList.find(p => Number(p.id) === Number(pushPoId))?.orgId" v-model:blocked="guardBlocked" />
      <el-form label-position="top" label-width="100px" class="doc-key-fields">
        <el-form-item label="采购订单" required>
          <el-select v-model="pushPoId" filterable placeholder="请选择已审批或部分入库的采购订单" style="width:100%" @change="onPushPoChange">
            <el-option v-for="po in pushPoList" :key="po.id" :value="po.id"
              :label="`${po.docNo} — ${po.supplierName || ''} （未入 ${Number(po.totalQty || 0) - Number(po.receivedQty || 0)}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库类型" required>
          <el-radio-group v-model="pushRecvType">
            <el-radio value="ESTIMATE">暂估入库（货到票未到）</el-radio>
            <el-radio value="INVOICE">发票入库（货票同行）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="pushRemark" placeholder="可选" />
        </el-form-item>
      </el-form>

      <!-- PO 行选择表（选 PO 后加载） -->
      <div v-if="pushPoLines.length > 0" style="margin-top:14px">
        <el-alert type="info" show-icon :closable="false" style="margin-bottom:10px">
          <template #title>请选择本次要入库的行并填写数量（默认填入剩余可入库数）</template>
        </el-alert>
        <el-table ref="pushPoTableRef" row-key="id" :fit="false" :data="pushPoLines" border size="small"
                  max-height="380" @selection-change="onPushSelectionChange">
          <el-table-column type="selection" width="50" />
          <el-table-column label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="150">
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="170">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="订单数量" width="90" align="right">
            <template #default="{ row }">{{ $fmtQty(row.qty) }}</template>
          </el-table-column>
          <el-table-column label="已入库" width="80" align="right">
            <template #default="{ row }">{{ $fmtQty(row.receivedQty) }}</template>
          </el-table-column>
          <el-table-column label="本次入库" width="140" align="right">
            <template #default="{ row }">
              <el-input-number v-model="pushLineQtys[row.id]" :min="0"
                :max="pushLineMax(row)" :precision="2" size="small" style="width:128px"
                @change="onPushQtyChange(row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
      <DocAttachments ref="recvAttRef" biz-type="RECV" :biz-id="null" />

      <template #footer>
        <el-button @click="pushGuard.cancel">取消</el-button>
        <el-button :disabled="guardBlocked || !pushCanConfirm" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doPushFromPo(false)">仅生成草稿</el-button>
        <el-button type="primary" :disabled="guardBlocked || !pushCanConfirm" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doPushFromPo(true)">生成并提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { recvApi, masterApi, poApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtDate, fmtMoney, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { applyRouteQuery } from '@/utils/routeQuery'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { approvalRecordNote } from '@/utils/approvalPrint'
import { buildPrintQr } from '@/utils/docQr'
import StatusTag from '@/components/StatusTag.vue'
import MoreMaterialsTag from '@/components/MoreMaterialsTag.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import { showError } from '@/utils/notify'

function firstMat(s?: string | null) { return (s || '').split('、')[0] || '-' }

const route = useRoute()
const user = useUserStore()
// 材料会计/财务/管理员看成本；采购在采购订单上本就可见价、入库为下游同价，故采购也可见（仓管等纯数量角色不可见）
const canSeeCost = computed(() => user.hasFinanceAccess() || user.hasRole('admin') || user.hasRole('purchaser'))

function canApprovePending(row: any) {
  // 仅当后端判定当前用户是本节点审批人时才显示审批/驳回（仓管等非审批人不显示）
  return row.status === 'PENDING' && row.canApprove === true
}
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', recvType: '', keyword: '', supplierId: null, warehouseId: null, materialId: null, sourcePoNo: '', startDate: null, endDate: null })
const dateRange = ref<any>(null)
const suppliers = ref<any[]>([])
const warehouses = ref<any[]>([])

// 下推入库
const pushVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const pushGuard = useUnsavedGuard(pushVisible)
const recvAttRef = ref()
const pushPoId = ref<number | null>(null)
const pushPoList = ref<any[]>([])
const pushRecvType = ref('ESTIMATE')
const pushRemark = ref('')
const pushPoLines = ref<any[]>([])
const pushLineQtys = ref<Record<number, number>>({})
const pushSelRows = ref<any[]>([])
const pushCanConfirm = ref(false)
const pushPoTableRef = ref<any>(null)

async function openPushFromPo() {
  pushPoId.value = null
  pushRecvType.value = 'ESTIMATE'
  pushRemark.value = ''
  pushPoLines.value = []
  pushLineQtys.value = {}
  pushSelRows.value = []
  pushCanConfirm.value = false
  // 加载可入库的采购订单（已审批 或 部分入库）
  try {
    const page = await poApi.page({ status: 'APPROVED', size: 100 }) as any
    const partial = await poApi.page({ status: 'PARTIAL_RECEIVED', size: 100 }) as any
    pushPoList.value = [...(page.records || []), ...(partial.records || [])]
  } catch { pushPoList.value = [] }
  pushVisible.value = true
}

function pushLineMax(line: any) {
  const ordered = Number(line.qty || 0)
  const received = Number(line.receivedQty || 0)
  return Math.max(0, ordered - received)
}

function onPushSelectionChange(rows: any[]) {
  pushSelRows.value = rows
  updatePushCanConfirm()
}

function onPushQtyChange(_line: any) {
  updatePushCanConfirm()
}

function updatePushCanConfirm() {
  // 必须选中至少一行，且该行的入库数量 > 0
  pushCanConfirm.value = pushSelRows.value.length > 0 && pushSelRows.value.some((r: any) => (pushLineQtys.value[r.id] || 0) > 0)
}

async function selectDefaultPushRows(rowsToSelect: any[]) {
  await nextTick()
  await nextTick()
  const table = pushPoTableRef.value
  const clearSelection = table?.clearSelection
  const toggleRowSelection = table?.toggleRowSelection
  if (typeof clearSelection === 'function') {
    clearSelection.call(table)
  }
  if (typeof toggleRowSelection === 'function') {
    for (const row of rowsToSelect) {
      toggleRowSelection.call(table, row, true)
    }
  }
  pushSelRows.value = [...rowsToSelect]
  updatePushCanConfirm()
}

async function onPushPoChange(poId: number | null) {
  pushPoLines.value = []
  pushLineQtys.value = {}
  pushSelRows.value = []
  pushCanConfirm.value = false
  if (!poId) return

  // 确保物料数据已加载
  if (!materials.value.length) {
    try { materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any } catch { materials.value = [] }
    matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  }

  try {
    const po: any = await poApi.detail(poId)
    const lines = po.lines || []
    pushPoLines.value = lines
    // 默认填入剩余可入库数
    const qtys: Record<number, number> = {}
    const toCheck: any[] = []
    for (const line of lines) {
      const ordered = Number(line.qty || 0)
      const received = Number(line.receivedQty || 0)
      const def = Math.max(0, ordered - received)
      qtys[line.id] = def
      if (def > 0) toCheck.push(line)
    }
    pushLineQtys.value = qtys
    await selectDefaultPushRows(toCheck)
  } catch (e: any) {
    showError(e, '加载订单明细失败')
  }
}

async function doPushFromPo(submitAfter = false) {
  if (!pushPoId.value) return
  // 构建 lines 数组：选中且入库数量 > 0 的行
  const lines = pushSelRows.value
    .filter((r: any) => (pushLineQtys.value[r.id] || 0) > 0)
    .map((r: any) => ({ poLineId: r.id, qty: pushLineQtys.value[r.id] }))
  if (lines.length === 0) {
    ElMessage.warning('请至少选择一行并填写入库数量')
    return
  }
  try {
    const recv = await recvApi.fromPo({ poId: pushPoId.value, recvType: pushRecvType.value, lines, remark: pushRemark.value }) as any
    if (recvAttRef.value) await recvAttRef.value.uploadPending(recv.id)
    if (submitAfter) {
      await recvApi.submit(recv.id)
      ElMessage.success(`入库单 ${recv.docNo} 已生成并提交审批`)
    } else {
      ElMessage.success(`入库单 ${recv.docNo} 已生成（草稿）`)
    }
    pushVisible.value = false
    await load()
  } catch (e: any) {
    showError(e, '下推失败')
  }
}

const pageTitle = computed(() => route.path.includes('/est') ? '暂估入库' : '采购入库单')
const supplierMap = computed<Record<number, any>>(() => Object.fromEntries(suppliers.value.map((s: any) => [s.id, s])))
const warehouseMap = computed<Record<number, any>>(() => Object.fromEntries(warehouses.value.map((w: any) => [w.id, w])))
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '入库单号', key: 'docNo' },
  { label: '关联订单', key: 'sourcePoNo' },
  { label: '供应商', value: row => supplierMap.value[row.supplierId]?.name || '' },
  { label: '物料编码', key: 'materialCodes' },
  { label: '物料名称', key: 'materialNames' },
  { label: '规格型号', key: 'materialSpecs' },
  { label: '类型', value: row => row.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库' },
  { label: '入库日期', key: 'recvDate' },
  { label: '数量', qty: true, key: 'totalQty' },
  ...(canSeeCost.value ? [{ label: '金额', money: true, value: (row: any) => fmtMoney(row.totalAmt) }] : []),
  { label: '关联发票', key: 'invoiceNo' },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '备注', key: 'remark' }
])

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => recvApi.page(q), ['DRAFT', 'PENDING', 'APPROVED', 'CANCELLED'])

async function load() {
  // 暂估入库菜单自动按 recvType 过滤
  if (route.path.includes('/est')) query.recvType = 'ESTIMATE'
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await recvApi.page(query)
    rows.value = r.records
    total.value = r.total
  } finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那 20 条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  applyDateRange()
  query.current = 1
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', recvType: route.path.includes('/est') ? 'ESTIMATE' : '', keyword: '', supplierId: null, warehouseId: null, materialId: null, sourcePoNo: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

onMounted(async () => {
  applyRouteQuery(query, route, ['status', 'keyword', 'supplierId', 'warehouseId', 'sourcePoNo'])
  load()
  // 由采购订单页「下推入库」跳转而来：自动打开下推弹窗并预选该订单（与本页下推同一流程，可改数量）
  const pushPo = Number(route.query.pushPo)
  if (pushPo) {
    await openPushFromPo()
    pushPoId.value = pushPo
    await onPushPoChange(pushPo)
  }
})

async function submit(row: any) {
  await recvApi.submit(row.id)
  ElMessage.success('已提交，待财务审批')
  await load()
}

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交入库单 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await recvApi.withdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

// 详情弹窗内直接审批/驳回（沿用带库存预览的审批弹窗）
function approveFromDetail() { const row = detailData.value; detailVisible.value = false; openApprove(row) }
// 详情弹窗内直接删除：与列表行同口径（谁提交谁删除），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, recvApi.delete, () => {}, '入库单') } catch { return }
  detailVisible.value = false
  await load()
}
function rejectFromDetail() { const row = detailData.value; detailVisible.value = false; rejectRecv(row) }

// 一键通过：直接调审批接口，不弹确认框（误批可走“取消审批”撤回）
async function openApprove(row: any) {
  await recvApi.approve(row.id, '验收完毕，同意入库')
  ElMessage.success('已审批，库存已更新')
  await load()
}

async function rejectRecv(row: any) {
  try {
    const { value: comment } = await ElMessageBox.prompt('请输入驳回原因', `驳回入库单 ${row.docNo}`, {
      inputType: 'textarea', inputValue: ''
    })
    await recvApi.reject(row.id, comment)
    ElMessage.success('已驳回')
    await load()
  } catch {}
}

const detailVisible = ref(false)
const detailData = ref<any>(null)
const materials = ref<any[]>([])
const matMap = ref<Record<number, any>>({})

masterApi.allSuppliers().then((list: any) => { suppliers.value = list }).catch(() => {})
masterApi.warehouses(true).then((list: any) => { warehouses.value = list }).catch(() => {})

async function openDetail(id: number) {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  detailData.value = await recvApi.detail(id)
  detailVisible.value = true
}

function exportExcel() {
  exportRowsToExcel(pageTitle.value, exportColumns.value, rows.value, pageTitle.value, { total: total.value, fetch: p => recvApi.page({ ...query, ...p }) })
}

async function printRecv(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'landscape') {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  const doc: any = rowOrDoc?.lines ? rowOrDoc : await recvApi.detail(rowOrDoc.id)
  const columns: PrintColumn[] = [
    { label: '序号', value: (_row, index) => index + 1, width: '4%', align: 'center' },
    { label: '物料编码', value: line => matMap.value[line.materialId]?.code || '', width: '11%' },
    { label: '物料名称', value: line => matMap.value[line.materialId]?.name || line.materialId || '', width: '16%' },
    { label: '规格型号', value: line => matMap.value[line.materialId]?.spec || '', width: '12%' },
    { label: '单位', value: line => matMap.value[line.materialId]?.unit || '', width: '5%', align: 'center' },
    { label: '数量', key: 'qty', width: '8%', align: 'right' },
    ...(canSeeCost.value ? [
      { label: '不含税单价', value: (line: any) => fmtMoney(line.unitPrice), width: '10%', align: 'right' },
      { label: '税率%', value: (line: any) => Number(line.taxRate || 0).toFixed(2), width: '6%', align: 'right' },
      { label: '不含税金额', value: (line: any) => fmtMoney(line.amount), width: '10%', align: 'right' },
      { label: '含税金额', value: (line: any) => fmtMoney(line.amountInclTax), width: '10%', align: 'right' }
    ] as PrintColumn[] : []),
    { label: '备注', key: 'remark', width: '8%' }
  ]
  const supplier = supplierMap.value[doc.supplierId] || {}
  const warehouse = warehouseMap.value[doc.warehouseId] || {}
  const options = {
    orgId: doc?.orgId,
    title: doc.recvType === 'ESTIMATE' ? '暂估入库单' : '采购入库单',
    subtitle: `${paper} 打印版`,
    fileName: `${doc.docNo || '入库单'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('RECV', doc.id, doc.docNo),
    fields: [
      { label: '入库单号', value: doc.docNo },
      { label: '关联订单', value: doc.sourcePoNo || '' },
      { label: '状态', value: docStatusLabel(doc.status) },
      { label: '入库类型', value: doc.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库' },
      { label: '入库日期', value: doc.recvDate || '' },
      { label: '供应商', value: supplier.name || doc.supplierId || '' },
      { label: '仓库', value: warehouse.name || doc.warehouseId || '' },
      { label: '数量', value: doc.totalQty || 0 },
      ...(canSeeCost.value ? [
        { label: '不含税合计', value: fmtMoney(doc.totalAmt) },
        { label: '税额', value: fmtMoney(doc.taxAmt) },
        { label: '含税合计', value: fmtMoney(doc.totalAmtInclTax) }
      ] : []),
      { label: '备注', value: doc.remark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '仓管验收', lines: ['签字：', '日期：'] },
      { label: '审批确认', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '本单据用于采购到货入库确认，签字后作为入库交接依据。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}


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
  return [supplierMap.value[d.supplierId]?.name, d.sourcePoNo && `关联订单 ${d.sourcePoNo}`, d.recvDate && `入库 ${d.recvDate}`, d.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库'].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 3} as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '入库数量', value: fmtQty(d.totalQty) }, canSeeCost.value && { label: '不含税合计', value: fmtMoney(d.totalAmt) }, canSeeCost.value && { label: '税额', value: fmtMoney(d.taxAmt) }, canSeeCost.value && { label: '含税合计', value: fmtMoney(d.totalAmtInclTax), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 不含税金额: { field: 'amount', type: 'money' }, 含税金额: { field: 'amountInclTax', type: 'money' } })
</script>
