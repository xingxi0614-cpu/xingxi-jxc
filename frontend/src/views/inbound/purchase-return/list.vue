<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购退货单</div>
        <div class="page-sub">把采购入库的货按行退回供应商：审批后自动减库存、冲销该行暂估、回退采购订单已入库数</div>
      </div>
      <el-space>
        <el-button :disabled="!rows.length" :title="!rows.length ? '当前没有数据' : '勾了哪几行导哪几行；不勾导出全部查询结果'" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse') || user.hasRole('admin')" type="primary" @click="openCreate">新增退货单</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="状态" data-q="status">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:130px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="PENDING" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货日期" data-q="dateRange">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item label="退货单号" data-q="keyword">
          <DocNoLookup v-model="query.keyword" type="PRET" multiple placeholder="点一下列出最近退货单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="来源入库单" data-q="sourceRecvNo">
          <DocNoLookup v-model="query.sourceRecvNo" type="RECV" placeholder="点一下列出最近入库单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="入库类型" data-q="recvType">
          <el-select v-model="query.recvType" placeholder="全部" clearable>
            <el-option label="暂估入库" value="ESTIMATE" />
            <el-option label="发票入库" value="INVOICE" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" data-q="supplierId">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" />
        </el-form-item>
        <el-form-item label="仓库" data-q="warehouseId">
          <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable>
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料" data-q="materialId">
          <MaterialLookup v-model="query.materialId" placeholder="编码、名称或规格" />
        </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" border size="default" style="width:100%" :max-height="560" @sort-change="onSortChange">
        <el-table-column prop="docNo" label="退货单号" width="190" fixed="left" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="sourceRecvNo" label="来源入库单" width="190" show-overflow-tooltip sortable="custom" />
        <el-table-column label="入库类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.recvType === 'ESTIMATE' ? 'warning' : 'success'">
              {{ row.recvType === 'ESTIMATE' ? '暂估' : '发票入库' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="180" show-overflow-tooltip />
        <el-table-column label="物料" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialSummary) }}</template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="150" show-overflow-tooltip />
        <el-table-column prop="returnDate" label="退货日期" width="120" class-name="nowrap-col" sortable="custom">
          <template #default="{ row }">{{ fmtDate(row.returnDate) }}</template>
        </el-table-column>
        <el-table-column prop="totalQty" label="退货数量" width="110" align="right" class-name="nowrap-col" sortable="custom" />
        <el-table-column v-if="canSeeCost" label="库存冲减额" width="130" align="right" class-name="nowrap-col">
          <template #default="{ row }">{{ fmtMoney(row.totalStockAmt) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="原入库额" width="130" align="right" class-name="nowrap-col">
          <template #default="{ row }">{{ fmtMoney(row.totalRecvAmt) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="混批差额" width="120" align="right" class-name="nowrap-col">
          <template #default="{ row }">
            <span :class="{ 'diff-amt': Number(row.totalDiffAmt || 0) !== 0 }">{{ fmtMoney(row.totalDiffAmt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button v-if="row.status === 'DRAFT' && canWrite" link type="primary" @click="submit(row.id)">提交</el-button>
            <el-button v-if="row.status === 'PENDING' && canWrite" link @click="withdraw(row.id)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="openApprove(row)">审批</el-button>
            <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('PRET', row, load)">取消审批</el-button>
            <el-button v-if="(row.status === 'DRAFT' || row.status === 'REJECTED') && canWrite" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top:12px;justify-content:flex-end" background layout="total, sizes, prev, pager, next"
                     :total="total" v-model:current-page="query.current" v-model:page-size="query.size"
                     :page-sizes="[10, 20, 50]" @current-change="load" @size-change="load" />
    </el-card>

    <!-- ── 新增退货单：先选入库单，再按行填退货数量 ── -->
    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="新增采购退货单" width="1000px" top="6vh">
      <DocGuardHint :active="createVisible" :date="createDate" :source-org-id="recvList.find(r => Number(r.id) === Number(createRecvId))?.orgId" v-model:blocked="guardBlocked" />
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:14px">
        <template #title>退货数量受三重限制</template>
        <div style="font-size:13px;line-height:1.7">
          可退数量 = 入库数量 − 已开票数量 − 已退货数量，并且不能超过当前库存。<br>
          <b>已开票的部分不能在这里退</b>，需要走发票红冲；账上没有的货也退不了，请先核对实物与账面。
        </div>
      </el-alert>

      <el-form :inline="true" size="default" style="margin-bottom:10px">
        <el-form-item label="来源入库单" required>
          <el-select v-model="createRecvId" placeholder="选择已审批的采购入库单" filterable style="width:420px" @change="loadReturnable">
            <el-option v-for="r in recvList" :key="r.id" :value="r.id"
                       :label="`${r.docNo}  ${r.recvType === 'ESTIMATE' ? '[暂估]' : '[发票入库]'}  ${r.materialSummary ? firstMat(r.materialSummary) : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="退货日期" required>
          <el-date-picker v-model="createDate" type="date" value-format="YYYY-MM-DD" style="width:160px" />
        </el-form-item>
        <el-form-item label="经办人">
          <el-input v-model="createReturner" placeholder="经办人" style="width:130px" />
        </el-form-item>
      </el-form>
      <el-form :inline="true" size="default" style="margin-bottom:6px">
        <el-form-item label="退货原因" required>
          <el-input v-model="createReason" placeholder="如：质量问题 / 规格不符 / 多送" style="width:300px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createRemark" placeholder="退货单/签收单编号等" style="width:380px" />
        </el-form-item>
      </el-form>
      <div class="date-hint">退货日期请填 <b>实物实际拉走那天</b>，不是今天录单的日期——这样账实的时间点才对得上。</div>

      <el-table :data="returnableLines" border size="small" style="width:100%" :max-height="330" empty-text="该入库单没有可退的行（可能已全部开票或库存不足）">
        <el-table-column label="物料" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialCode }} {{ row.materialName }}<span v-if="row.materialSpec">（{{ row.materialSpec }}）</span></template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="可退数量" width="100" align="right" class-name="nowrap-col">
          <template #default="{ row }">{{ row.returnableQty }}</template>
        </el-table-column>
        <el-table-column label="当前库存" width="100" align="right" class-name="nowrap-col">
          <template #default="{ row }">{{ row.stockQty }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="入库单价" width="105" align="right" class-name="nowrap-col">
          <template #default="{ row }">{{ fmtMoney(row.recvUnitPrice) }}</template>
        </el-table-column>
        <el-table-column label="本次退货" width="150" align="right">
          <template #default="{ row }">
            <el-input-number v-model="createQtys[row.sourceRecvLineId]" :min="0" :max="Number(row.returnableQty)"
                             :controls="false" size="small" style="width:120px" />
          </template>
        </el-table-column>
      </el-table>
      <div class="line-foot">
        <el-button link type="primary" size="small" @click="fillAll">全部按可退数量填满</el-button>
        <span>已填 <b>{{ filledCount }}</b> 行，合计 <b>{{ filledQty }}</b></span>
      </div>

      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button type="primary" :disabled="guardBlocked || (filledCount === 0 || !createReason)" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="doCreate">生成草稿</el-button>
      </template>
    </el-dialog>

    <!-- ── 详情 ── -->
    <DocDrawer v-model="detailVisible" kind="采购退货单" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['制单', '审批（冲减库存）', '已退货']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'">

      <template v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="来源入库单">{{ detail.sourceRecvNo }}</el-descriptions-item>
          <el-descriptions-item label="入库类型">{{ detail.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库' }}</el-descriptions-item>
          <el-descriptions-item label="来源采购订单">{{ detail.sourcePoNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplierName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货日期">{{ fmtDate(detail.returnDate) }}</el-descriptions-item>
          <el-descriptions-item label="经办人">{{ detail.returner || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货原因" :span="2">{{ detail.reason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 三个金额：财务拿它去外部账务系统做分录，缺一不可 -->
        <div v-if="canSeeCost && detail.status === 'APPROVED'" class="amt-board">
          <div class="amt-item">
            <div class="amt-label">库存冲减额<span>按加权均价</span></div>
            <div class="amt-value">{{ fmtMoney(detail.totalStockAmt) }}</div>
            <div class="amt-note">贷记原材料的数</div>
          </div>
          <div class="amt-item">
            <div class="amt-label">原入库不含税额<span>按入库价</span></div>
            <div class="amt-value">{{ fmtMoney(detail.totalRecvAmt) }}</div>
            <div class="amt-note">红字发票、冲暂估的数</div>
          </div>
          <div class="amt-item" :class="{ 'amt-warn': Number(detail.totalDiffAmt || 0) !== 0 }">
            <div class="amt-label">混批差额<span>= 冲减额 − 入库额</span></div>
            <div class="amt-value">{{ fmtMoney(detail.totalDiffAmt) }}</div>
            <div class="amt-note">进当期损益，科目由财务定</div>
          </div>
        </div>
        <div v-if="canSeeCost && detail.status === 'APPROVED' && Number(detail.totalDiffAmt || 0) !== 0" class="diff-explain">
          <b>差额从哪来</b>：同一物料不同批次进价不同，一入库就在账上混成一个加权均价、此后无法再分离。
          退货时库存按均价冲减，供应商只退原价，差额是早期批次遗留的成本，只能计入当期损益。
          这不是差错，也无法通过改单据消除。
        </div>

        <el-table max-height="min(56vh, 560px)" :data="detail.lines || []" border size="small" style="width:100%;margin-top:14px" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" prop="lineNo" label="#" width="50" />
          <el-table-column fixed="left" label="物料" min-width="230" show-overflow-tooltip>
            <template #default="{ row }">{{ row.materialCode }} {{ row.materialName }}<span v-if="row.materialSpec">（{{ row.materialSpec }}）</span></template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column prop="qty" label="退货数量" width="100" align="right" class-name="nowrap-col" />
          <el-table-column v-if="canSeeCost" label="入库单价" width="100" align="right" class-name="nowrap-col">
            <template #default="{ row }">{{ fmtMoney(row.recvUnitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="原入库额" width="110" align="right" class-name="nowrap-col">
            <template #default="{ row }">{{ fmtMoney(row.recvAmt) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="出库均价" width="100" align="right" class-name="nowrap-col">
            <template #default="{ row }">{{ row.stockUnitPrice == null ? '待过账' : fmtMoney(row.stockUnitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="库存冲减额" width="115" align="right" class-name="nowrap-col">
            <template #default="{ row }">{{ row.stockAmt == null ? '待过账' : fmtMoney(row.stockAmt) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="差额" width="100" align="right" class-name="nowrap-col">
            <template #default="{ row }">{{ row.diffAmt == null ? '-' : fmtMoney(row.diffAmt) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template v-if="detail">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detail.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detail && detail.docNo" :float-left="false" />
        <el-button v-if="detail && (detail.status === 'DRAFT' || detail.status === 'REJECTED') && canWrite" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>

    </DocDrawer>

    <!-- ── 审批 ── -->
    <el-dialog v-model="apvVisible" title="审批采购退货单" width="560px">
      <div v-if="apvRow">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="退货单号">{{ apvRow.docNo }}</el-descriptions-item>
          <el-descriptions-item label="来源入库单">{{ apvRow.sourceRecvNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ apvRow.supplierName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货数量">{{ apvRow.totalQty }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="原入库额">{{ fmtMoney(apvRow.totalRecvAmt) }}</el-descriptions-item>
          <el-descriptions-item label="退货原因">{{ apvRow.reason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert type="info" :closable="false" style="margin-top:12px">
          通过后立即减库存、冲销该行暂估、回退采购订单已入库数，同单其他行不受影响。
        </el-alert>
        <el-input v-model="apvComment" type="textarea" :rows="3" placeholder="审批意见" style="margin-top:12px" />
      </div>
      <template #footer>
        <el-button @click="apvVisible = false">取消</el-button>
        <el-button type="danger" @click="doReject">驳回</el-button>
        <el-button type="primary" @click="doApprove">通过 + 减库存</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { applyRouteQuery } from '@/utils/routeQuery'
import { ElMessage } from 'element-plus'
import { purchaseReturnApi, recvApi, masterApi } from '@/api'
import SupplierLookup from '@/components/SupplierLookup.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import { useUserStore } from '@/stores/user'
import { fmtDate, fmtMoney, fmtQty, fmtMaterials } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import StatusTag from '@/components/StatusTag.vue'
import { showError } from '@/utils/notify'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { docStatusLabel } from '@/utils/statusLabel'

const route = useRoute()
const user = useUserStore()
// 与采购入库单同口径：仓管等纯数量角色不可见金额；采购在采购订单上本就看得到价
const canSeeCost = computed(() => user.hasFinanceAccess() || user.hasRole('admin') || user.hasRole('purchaser'))
const canWrite = computed(() => user.hasRole('warehouse') || user.hasRole('admin'))

const firstMat = fmtMaterials

// 导出列与列表显示一致;金额列只给能看成本的岗位(与列表 canSeeCost 同口径);物料导出全部
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '退货单号', key: 'docNo' },
  { label: '来源入库单', key: 'sourceRecvNo' },
  { label: '入库类型', value: (row: any) => row.recvType === 'ESTIMATE' ? '暂估' : '发票入库' },
  { label: '供应商', key: 'supplierName' },
  { label: '物料', key: 'materialSummary' },
  { label: '仓库', key: 'warehouseName' },
  { label: '退货日期', value: (row: any) => fmtDate(row.returnDate) },
  { label: '退货数量', key: 'totalQty', qty: true },
  ...(canSeeCost.value ? [
    { label: '库存冲减额', money: true, value: (row: any) => fmtMoney(row.totalStockAmt) },
    { label: '原入库额', money: true, value: (row: any) => fmtMoney(row.totalRecvAmt) },
    { label: '混批差额', money: true, value: (row: any) => fmtMoney(row.totalDiffAmt) }
  ] : []),
  { label: '状态', value: (row: any) => docStatusLabel(row.status) }
])
function exportExcel() { exportRowsToExcel('采购退货单', exportColumns.value, rows.value, '采购退货单', { total: total.value, fetch: p => purchaseReturnApi.page({ ...query, ...p }) }) }
function canApprovePending(row: any) {
  // 只有后端判定为本节点审批人时才显示，避免仓管看到点不动的按钮
  return row.status === 'PENDING' && row.canApprove === true
}

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])
masterApi.warehouses(true).then((list: any) => { warehouses.value = list }).catch(() => {})
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', sourceRecvNo: '', recvType: '', supplierId: null, warehouseId: null, materialId: null, startDate: null, endDate: null })

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => purchaseReturnApi.page(q), ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED'])

async function load() {
  loading.value = true
  refreshStatusCounts(query)
  try {
    query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
    query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
    const res: any = await purchaseReturnApi.page(query)
    rows.value = res.records || []
    total.value = res.total || 0
    searched.value = true
  } finally {
    loading.value = false
  }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)
function doSearch() { query.current = 1; load() }
function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', sourceRecvNo: '', recvType: '', supplierId: null, warehouseId: null, materialId: null, startDate: null, endDate: null })
  dateRange.value = null
  load()
}

/* ── 新增 ── */
const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const createRecvId = ref<number | null>(null)
const createDate = ref<string>('')
const createReturner = ref('')
const createReason = ref('')
const createRemark = ref('')
const recvList = ref<any[]>([])
const returnableLines = ref<any[]>([])
const createQtys = ref<Record<number, number>>({})

const filledCount = computed(() => Object.values(createQtys.value).filter(v => Number(v) > 0).length)
const filledQty = computed(() => Object.values(createQtys.value).reduce((s, v) => s + Number(v || 0), 0))

async function openCreate() {
  createRecvId.value = null
  createDate.value = fmtDate(new Date())
  createReturner.value = ''
  createReason.value = ''
  createRemark.value = ''
  returnableLines.value = []
  createQtys.value = {}
  try {
    // 已审批的入库单都可能要退货（暂估、发票入库都算）
    const res: any = await recvApi.page({ status: 'APPROVED', size: 200 })
    recvList.value = res.records || []
  } catch { recvList.value = [] }
  createVisible.value = true
}

async function loadReturnable() {
  returnableLines.value = []
  createQtys.value = {}
  if (!createRecvId.value) return
  try {
    returnableLines.value = (await purchaseReturnApi.returnable(createRecvId.value) as any) || []
    if (!returnableLines.value.length) {
      ElMessage.warning('该入库单没有可退的行：可能已全部开票（需走发票红冲），或库存不足')
    }
  } catch (e: any) {
    showError(e, '加载可退明细失败')
  }
}

function fillAll() {
  const m: Record<number, number> = {}
  returnableLines.value.forEach(l => { m[l.sourceRecvLineId] = Number(l.returnableQty) })
  createQtys.value = m
}

async function doCreate() {
  const lines = returnableLines.value
    .filter(l => Number(createQtys.value[l.sourceRecvLineId]) > 0)
    .map(l => ({ recvLineId: l.sourceRecvLineId, qty: Number(createQtys.value[l.sourceRecvLineId]) }))
  if (!lines.length) return ElMessage.warning('请至少填写一行退货数量')
  try {
    await purchaseReturnApi.fromRecv({
      recvId: createRecvId.value,
      returnDate: createDate.value,
      returner: createReturner.value,
      reason: createReason.value,
      remark: createRemark.value,
      lines
    })
    ElMessage.success('已生成退货单草稿，请提交审批')
    createVisible.value = false
    load()
  } catch (e: any) {
    showError(e, '生成失败')
  }
}

/* ── 流转 ── */
async function submit(id: number) {
  try { await purchaseReturnApi.submit(id); ElMessage.success('已提交'); load() }
  catch (e: any) { showError(e, '提交失败') }
}
async function withdraw(id: number) {
  try { await purchaseReturnApi.withdraw(id); ElMessage.success('已取消提交'); load() }
  catch (e: any) { showError(e, '操作失败') }
}
async function remove(row: any) {
  try { await confirmDeleteDoc(row, (id) => purchaseReturnApi.delete(id), load, '采购退货单') }
  catch (e: any) { if (e !== 'cancel') showError(e, '删除失败') }
}

const detailVisible = ref(false)
// 详情弹窗内直接删除：与列表行同口径（草稿/已驳回 + 仓管/管理员），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detail.value
  if (!row) return
  try { await confirmDeleteDoc(row, (id) => purchaseReturnApi.delete(id), () => {}, '采购退货单') }
  catch { return }
  detailVisible.value = false
  await load()
}
const detail = ref<any>(null)
async function openDetail(id: number) {
  detail.value = await purchaseReturnApi.detail(id) as any
  detailVisible.value = true
}

const apvVisible = ref(false)
const apvRow = ref<any>(null)
const apvComment = ref('')
function openApprove(row: any) { apvRow.value = row; apvComment.value = ''; apvVisible.value = true }
async function doApprove() {
  try {
    await purchaseReturnApi.approve(apvRow.value.id, apvComment.value)
    ElMessage.success('已通过，库存与暂估已更新')
    apvVisible.value = false
    load()
  } catch (e: any) { showError(e, '审批失败') }
}
async function doReject() {
  if (!apvComment.value) return ElMessage.warning('驳回请填写意见')
  try {
    await purchaseReturnApi.reject(apvRow.value.id, apvComment.value)
    ElMessage.success('已驳回')
    apvVisible.value = false
    load()
  } catch (e: any) { showError(e, '驳回失败') }
}

onMounted(() => { applyRouteQuery(query, route, ['status', 'keyword']); load() })

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.supplierName, d.sourceRecvNo && `来源入库 ${d.sourceRecvNo}`, d.returnDate && `退货 ${fmtDate(d.returnDate)}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 3} as Record<string, number>)[detail.value?.status] ?? 0)

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 退货数量: { field: 'qty', type: 'qty' }, 原入库额: { field: 'recvAmt', type: 'money' }, 库存冲减额: { field: 'stockAmt', type: 'money' }, 差额: { field: 'diffAmt', type: 'money' } })
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 600; }
.page-sub { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 4px; }
.date-hint { font-size: 12px; color: var(--el-color-warning); margin: 0 0 10px 2px; }
.line-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; font-size: 13px; color: var(--el-text-color-secondary); }
.diff-amt { color: var(--el-color-warning); font-weight: 600; }

.amt-board { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 14px; }
.amt-item { border: 1px solid var(--el-border-color); border-radius: 6px; padding: 12px 14px; background: var(--el-fill-color-lighter); }
.amt-item.amt-warn { border-color: var(--el-color-warning); background: var(--el-color-warning-light-9); }
.amt-label { font-size: 12px; color: var(--el-text-color-secondary); display: flex; justify-content: space-between; align-items: baseline; gap: 6px; }
.amt-label span { font-size: 11px; opacity: .75; }
.amt-value { font-size: 20px; font-weight: 600; margin: 6px 0 4px; font-variant-numeric: tabular-nums; }
.amt-note { font-size: 12px; color: var(--el-text-color-secondary); }
.diff-explain { margin-top: 10px; padding: 10px 12px; border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9); font-size: 12.5px; line-height: 1.75; border-radius: 0 4px 4px 0; }

:deep(.nowrap-col .cell) { white-space: nowrap; }
</style>
