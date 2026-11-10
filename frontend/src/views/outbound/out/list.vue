<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">材料出库</div>
        <div class="page-sub">仓管关联已审批出库申请制单，只维护本次出库数量；审批后扣减实时库存</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse')" type="primary" @click="openCreateFromReq">+ 关联出库申请制单</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="status" label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="PENDING" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="dateRange" label="出库日期">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item data-q="keyword" label="单据号">
          <DocNoLookup v-model="query.keyword" type="OUT" multiple placeholder="点一下列出最近出库单" width="220px" @enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="projectId" label="工程项目">
          <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable style="width:190px">
            <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="constructionUnit" label="单位">
          <el-input v-model="query.constructionUnit" placeholder="施工单位" clearable style="width:180px" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="warehouseId" label="仓库">
          <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="outType" label="出库类型">
          <el-select v-model="query.outType" placeholder="全部" clearable style="width:140px">
            <el-option v-for="t in outTypes" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="businessCategory" label="业务分类">
          <el-select v-model="query.businessCategory" placeholder="全部" clearable style="width:140px">
            <el-option v-for="c in businessCategories" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="sourceReqNo" label="来源申请">
          <DocNoLookup v-model="query.sourceReqNo" type="OREQ" placeholder="点一下列出最近申请" width="220px" @enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="reqText" label="用途/备注">
          <el-input v-model="query.reqText" placeholder="申请上的用途或备注" clearable style="width:180px" @keyup.enter="doSearch" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" show-summary :summary-method="pageSummary" @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo"        label="出库单号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" aria-label="查看单据" :title="`查看单据 ${row.docNo}`" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="sourceReqNo"  label="来源申请" min-width="170" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="outDate"      label="日期"     width="110" sortable="custom" />
        <el-table-column prop="projectName" label="工程项目" min-width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="constructionUnit" label="单位" min-width="150" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="outType" label="出库类型" min-width="110" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="businessCategory" label="业务分类" min-width="110" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="materialReceiver" label="材料领料人" min-width="110" show-overflow-tooltip sortable="custom" />
        <!-- 用途/备注来自来源出库申请(后端查询时读出),不是出库单的列,所以不能排序 -->
        <el-table-column prop="purpose" label="用途" min-width="120" show-overflow-tooltip />
        <el-table-column prop="totalQty"     label="数量"     width="100" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column v-if="canSeeCost" prop="totalAmt" sortable="custom" label="金额" width="180" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.totalAmt) > 0 ? 'var(--color-primary)' : 'var(--text-3)' }">
              {{ fmtMoney(row.totalAmt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printOut(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="row.status === 'DRAFT' && user.hasRole('warehouse')" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="openApprove(row)">审批</el-button>
            <el-button v-if="canApprovePending(row)" link type="danger"  @click="rejectOut(row)">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('OUT', row, load)">取消审批</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="confirmDeleteDoc(row, outApi.outDelete, load, '出库单')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <!-- 关联已审批出库申请制单-->
    <el-dialog draggable v-model="createVisible" :before-close="createGuard.beforeClose" title="关联出库申请制单" width="min(1180px, calc(100vw - 48px))">
      <DocGuardHint :active="createVisible" :source-org-id="selectedReq?.orgId" :warehouse-ids="[selectedReq?.warehouseId]" v-model:blocked="guardBlocked" />
      <el-steps :active="createStep" finish-status="success" align-center class="create-steps">
        <el-step title="选择出库申请" />
        <el-step title="确认本次出库数量" />
      </el-steps>

      <!-- 第一步：挑一张已审批的出库申请 -->
      <div v-show="createStep === 0">
        <div class="request-filter">
          <el-date-picker v-model="requestDateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
          <el-input v-model="requestQuery.keyword" placeholder="申请单号" clearable @keyup.enter="loadApprovedRequests" />
          <el-input v-model="requestQuery.applicantName" placeholder="申请人" clearable @keyup.enter="loadApprovedRequests" />
          <el-select v-model="requestQuery.warehouseId" placeholder="仓库" clearable filterable>
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
          <el-select v-model="requestQuery.projectId" placeholder="工程项目" clearable filterable>
            <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
          </el-select>
          <el-input v-model="requestQuery.constructionUnit" placeholder="施工单位" clearable @keyup.enter="loadApprovedRequests" />
          <el-select v-model="requestQuery.outType" placeholder="出库类型" clearable>
            <el-option v-for="t in outTypes" :key="t" :value="t" :label="t" />
          </el-select>
          <el-select v-model="requestQuery.businessCategory" placeholder="业务分类" clearable>
            <el-option v-for="c in businessCategories" :key="c" :value="c" :label="c" />
          </el-select>
          <el-input v-model="requestQuery.purpose" placeholder="用途" clearable @keyup.enter="loadApprovedRequests" />
          <div class="request-filter-actions">
            <el-button type="primary" @click="loadApprovedRequests">查询</el-button>
            <el-button @click="resetRequestQuery">重置</el-button>
          </div>
        </div>
        <el-table class="readable-table" :fit="false"
          :data="approvedRequests" v-loading="requestLoading" height="380" border
          empty-text="暂无可制单的出库申请" highlight-current-row @row-click="selectRequest">
          <el-table-column prop="docNo" label="申请单号" width="210" sortable="custom" />
          <el-table-column prop="applyDate" label="申请日期" width="105" />
          <el-table-column prop="projectName" label="工程项目" min-width="170" show-overflow-tooltip />
          <el-table-column prop="constructionUnit" label="施工单位" min-width="160" show-overflow-tooltip />
          <el-table-column prop="outType" label="出库类型" width="120" show-overflow-tooltip />
          <el-table-column prop="purpose" label="用途" min-width="120" show-overflow-tooltip />
          <el-table-column label="可出数量" align="right" width="100">
            <template #default="{ row }">{{ reqRemain(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="92">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="selectRequest(row)">选择 →</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 第二步：填本次出库数量 -->
      <div v-show="createStep === 1">
        <el-descriptions v-if="selectedReq" :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="申请单号">{{ selectedReq.docNo }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ selectedReq.projectName || projectMap[selectedReq.projectId]?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="施工单位">{{ selectedReq.constructionUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务分类">{{ selectedReq.businessCategory || '-' }}</el-descriptions-item>
          <el-descriptions-item label="材料申请人">{{ selectedReq.materialApplicant || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="out-type-picker">
          <span class="otp-label"><span class="otp-req">*</span> 出库类型</span>
          <el-select v-model="createOutType" filterable placeholder="请选择本次出库类型" style="width:260px">
            <el-option v-for="t in outTypes" :key="t" :value="t" :label="t" />
          </el-select>
          <span class="otp-hint">决定本次材料成本归入的会计科目，出库时由仓管确定，财务审核时复核</span>
        </div>
        <el-table class="readable-table" :fit="false" :data="createLines" height="360" border empty-text="该申请无可出库明细">
          <el-table-column label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="申请数量" prop="qty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column label="已制/已出" width="100" align="right">
            <template #default="{ row }">{{ lineUsed(row) }}</template>
          </el-table-column>
          <el-table-column label="剩余" width="90" align="right">
            <template #default="{ row }">{{ lineRemain(row) }}</template>
          </el-table-column>
          <!-- 当前库存:制单时就地看够不够发,不用等审批才报"库存不足"。只提醒不拦(2026-10-08)
               和"本次出库"一起固定在右侧:表格比弹窗宽,不固定就被挤到要横向滚动才看得见 -->
          <el-table-column label="当前库存" width="100" align="right" fixed="right">
            <template #default="{ row }">
              <span v-if="stockOf(row.materialId) === null" class="stk-muted">{{ stockLoading ? '查询中…' : '暂不可用' }}</span>
              <span v-else-if="stockOf(row.materialId)! <= 0" class="stk-zero">无库存</span>
              <span v-else class="stk-ok">{{ $fmtQty(stockOf(row.materialId)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本次出库" width="170" fixed="right">
            <template #default="{ row }">
              <el-input-number v-model="row.outQtyInput" :min="0" :max="lineRemain(row)" :precision="2" :step="1" controls-position="right" style="width: 100%" />
              <div v-if="stockTip(row)" class="stk-warn">{{ stockTip(row) }}</div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <template v-if="createStep === 1">
          <el-button @click="createStep = 0">‹ 上一步</el-button>
          <el-button :loading="createSubmitting" :disabled="guardBlocked || !canCreateOut" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="createOutFromReq(false)">仅生成草稿</el-button>
          <el-button type="primary" :loading="createSubmitting" :disabled="guardBlocked || !canCreateOut" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="createOutFromReq(true)">生成并提交</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 出库单详情-->
    <DocDrawer v-model="detailVisible" kind="材料出库单" :doc-no="detailData?.docNo" :status="detailData?.status"
      :sub="detailSub" :steps="['制单', '审批（扣减库存）', '已出库']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'"
      :nums="detailNums">
      <template v-if="detailData">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="出库单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="来源申请">{{ detailData.sourceReqNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="出库日期">{{ detailData.outDate }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ detailData.projectName || projectMap[detailData.projectId]?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detailData.constructionUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="出库类型">{{ detailData.outType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务分类">{{ detailData.businessCategory || '-' }}</el-descriptions-item>
          <el-descriptions-item label="材料领料人">{{ detailData.materialReceiver || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="金额">{{ fmtMoney(detailData.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="用途">{{ detailData.purpose || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detailData.remark || detailData.reqRemark }}</el-descriptions-item>
        </el-descriptions>
        <div class="dd-sec">出库明细 <small>共 {{ detailData.lines?.length || 0 }} 行</small></div>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="160">
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="150">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" prop="qty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column v-if="canSeeCost" label="加权平均单价" width="130" align="right">
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="金额" width="130" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || row.reqRemark }}</template>
          </el-table-column>
        </el-table>
        <DocAttachments biz-type="OUT" :biz-id="detailData.id" />
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
          :type="detailData.status === 'PENDING' && detailData.canApprove ? '' : 'primary'"
          @print="(paper, orientation) => printOut(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printOut(detailData, 'export', paper, orientation)"
        />
        <el-button v-if="detailData && detailData.status === 'PENDING' && detailData.canApprove" type="primary" @click="approveFromDetail()">审批通过（扣减库存）</el-button>
      </template>
    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import DocNoLookup from '@/components/DocNoLookup.vue'
import { makeLineSummary } from '@/utils/tableSummary'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { useWarehouseStock, sumQty, subQty, exceedsStock } from '@/composables/useWarehouseStock'
import { computed, onMounted, reactive, ref } from 'vue'
import { useBizOptions } from '@/composables/useBizOptions'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showError } from '@/utils/notify'
import { outApi, masterApi, sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtDate, fmtMoney, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { applyRouteQuery } from '@/utils/routeQuery'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { buildPrintQr } from '@/utils/docQr'
import { approvalRecordNote } from '@/utils/approvalPrint'
import StatusTag from '@/components/StatusTag.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'

const route = useRoute()
const user = useUserStore()
const canSeeCost = computed(() => user.hasFinanceAccess())

function canApprovePending(row: any) {
  // 仅当后端判定当前用户是本节点审批人时才显示审批/驳回（仓管等非审批人不显示）
  return row.status === 'PENDING' && row.canApprove === true
}
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ sortField: '', sortOrder: '',
  current: 1,
  size: 20,
  status: '',
  keyword: '',
  warehouseId: null,
  sourceReqNo: '',
  reqText: '',
  projectId: null,
  constructionUnit: '',
  outType: '',
  businessCategory: '',
  startDate: null,
  endDate: null
})
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])
const { outTypes, businessCategories, loadBizOptions } = useBizOptions()
const exportColumns: ExportColumn[] = [
  { label: '出库单号', key: 'docNo' },
  { label: '来源申请', key: 'sourceReqNo' },
  { label: '日期', key: 'outDate' },
  { label: '工程项目', key: 'projectName' },
  { label: '单位', key: 'constructionUnit' },
  { label: '出库类型', key: 'outType' },
  { label: '业务分类', key: 'businessCategory' },
  { label: '材料领料人', key: 'materialReceiver' },
  { label: '用途', key: 'purpose' },
  { label: '数量', qty: true, key: 'totalQty' },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '备注', value: (row: any) => row.remark || row.reqRemark || '' }
]
const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const createStep = ref(0)   // 0=选择申请 1=填出库数量（两步向导）
const requestLoading = ref(false)
const createSubmitting = ref(false)
const approvedRequests = ref<any[]>([])
const selectedReq = ref<any>(null)
const createOutType = ref('')
const createLines = ref<any[]>([])
const requestDateRange = ref<any>(null)
const requestQuery = reactive<any>({
  keyword: '',
  warehouseId: null,
  projectId: null,
  constructionUnit: '',
  outType: '',
  businessCategory: '',
  purpose: '',
  applicantName: '',
  startDate: null,
  endDate: null
})

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [
  { label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' },
  { label: '已审批', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }
]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => outApi.outPage(q), ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await outApi.outPage(query)
    rows.value = r.records
    total.value = r.total
  } finally { loading.value = false }
}

// 合计行：只合计本页（与表格显示的行一致）；金额列仅有权限的人可见
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a, r) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a, r) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那 20 条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  applyDateRange()
  query.current = 1
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', warehouseId: null, sourceReqNo: '', reqText: '', projectId: null, constructionUnit: '', outType: '', businessCategory: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

onMounted(() => {
  loadOptions()
  loadBizOptions()
  masterApi.projects(true).then((list: any) => { projects.value = list }).catch(() => {})
  applyRouteQuery(query, route, ['status', 'keyword', 'warehouseId', 'sourceReqNo', 'projectId', 'constructionUnit', 'outType', 'businessCategory'])
  load()
})

masterApi.warehouses(true).then((list: any) => { warehouses.value = list }).catch(() => {})

async function loadOptions() {
  const [types, cats] = await Promise.all([
    sysApi.optionList('out.type.options').catch(() => []),
    sysApi.optionList('business.category.options').catch(() => [])
  ]) as any[]
  if (types?.length) outTypes.value = types
  if (cats?.length) businessCategories.value = cats
}

const canCreateOut = computed(() => !!selectedReq.value && !!createOutType.value && createLines.value.some((line: any) => Number(line.outQtyInput) > 0))

// 剩余量一律走 subQty:小数直接相减会算出 0.19999999999999998 这种数(见 useWarehouseStock)
function reqRemain(row: any) {
  return Math.max(0, subQty(row.totalQty, row.outQty))
}

function lineRemain(row: any) {
  return Math.max(0, subQty(row.qty, lineUsed(row)))
}

function lineUsed(row: any) {
  return Math.max(Number(row.lockedOutQty || 0), Number(row.outQty || 0))
}

// 当前库存(制单时只提醒;提交时后端拦,见 SubmitStockGuard):口径与注意事项见 useWarehouseStock
const { stockLoading, loadStock, stockOf } = useWarehouseStock()

/**
 * 本次出库数超过库存时的提醒文字(空串=不提醒)。
 * 同一物料在申请里占多行时按合计比,否则每行都"够"、加起来却不够。
 */
function stockTip(row: any): string {
  const stock = stockOf(row?.materialId)
  if (stock === null || !(Number(row?.outQtyInput) > 0)) return ''
  // 合计与比较都走 sumQty/exceedsStock:小数直接相加会把"刚好够"误报成不足
  const qtys = createLines.value
    .filter((line: any) => line.materialId === row.materialId)
    .map((line: any) => line.outQtyInput)
  if (!exceedsStock(qtys, stock)) return ''
  return exceedsStock([row.outQtyInput], stock)
    ? '库存不足，提交审批时会被拦（可先只生成草稿）'
    : `同一物料合计 ${fmtQty(sumQty(qtys))}，超过库存`
}

async function openCreateFromReq() {
  await loadOptions()
  createVisible.value = true
  createStep.value = 0
  selectedReq.value = null
  createOutType.value = ''
  createLines.value = []
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  projectMap.value = Object.fromEntries(projects.value.map((p: any) => [p.id, p]))
  await loadApprovedRequests()
}

async function loadApprovedRequests() {
  requestQuery.startDate = requestDateRange.value?.[0] || null
  requestQuery.endDate = requestDateRange.value?.[1] || null
  requestLoading.value = true
  try {
    const baseQuery = {
      current: 1,
      size: 100,
      keyword: requestQuery.keyword,
      warehouseId: requestQuery.warehouseId,
      projectId: requestQuery.projectId,
      constructionUnit: requestQuery.constructionUnit,
      outType: requestQuery.outType,
      businessCategory: requestQuery.businessCategory,
      purpose: requestQuery.purpose,
      applicantName: requestQuery.applicantName,
      startDate: requestQuery.startDate,
      endDate: requestQuery.endDate
    }
    const pages: any[] = await Promise.all([
      outApi.oreqPage({ ...baseQuery, status: 'APPROVED' }),
      outApi.oreqPage({ ...baseQuery, status: 'PARTIAL_OUT' })
    ])
    const merged = new Map<number, any>()
    for (const page of pages) {
      for (const row of (page.records || [])) {
        if (reqRemain(row) > 0) merged.set(row.id, row)
      }
    }
    approvedRequests.value = Array.from(merged.values())
  } finally {
    requestLoading.value = false
  }
}

function resetRequestQuery() {
  Object.assign(requestQuery, {
    keyword: '',
    warehouseId: null,
    projectId: null,
    constructionUnit: '',
    outType: '',
    businessCategory: '',
    purpose: '',
    applicantName: '',
    startDate: null,
    endDate: null
  })
  requestDateRange.value = null
  loadApprovedRequests()
}

async function selectRequest(row: any) {
  const detail: any = await outApi.oreqDetail(row.id)
  selectedReq.value = detail
  // 老申请若已带出库类型则预填，仓管可改；新申请为空需仓管选择
  if (detail.outType && !createOutType.value) createOutType.value = detail.outType
  createLines.value = (detail.lines || [])
    .filter((line: any) => lineRemain(line) > 0)
    .map((line: any) => ({ ...line, outQtyInput: lineRemain(line) }))
  if (!createLines.value.length) {
    ElMessage.warning('该申请已无可出库数量，请另选一张')
    return
  }
  void loadStock(detail.warehouseId)   // 不等它回来,库存列先显示"查询中…"
  createStep.value = 1   // 选好申请，进入第二步填数量
}

async function createOutFromReq(submitAfter = false) {
  if (!selectedReq.value) return
  const lines = createLines.value
    .filter((line: any) => Number(line.outQtyInput) > 0)
    .map((line: any) => ({ reqLineId: line.id, qty: Number(line.outQtyInput) }))
  for (const line of createLines.value) {
    if (exceedsStock([line.outQtyInput], lineRemain(line))) {
      ElMessage.error(`物料 ${matMap.value[line.materialId]?.name || line.materialId} 超过申请剩余数量`)
      return
    }
  }
  createSubmitting.value = true
  try {
    const out: any = await outApi.outFromOreq({ oreqId: selectedReq.value.id, lines, outType: createOutType.value })
    if (submitAfter) {
      // 草稿已落库：提交没过（如 2026-10-20 起提交时库存不足就拦）也要关掉弹窗，否则再点一次会多生成一张草稿
      try { await outApi.outSubmit(out.id) }
      catch (e: any) {
        createVisible.value = false
        showError(e, '提交失败')
        ElMessage.warning(`出库单 ${out.docNo} 已保存为草稿，没有提交审批；库存补足后在列表里点「提交」`)
        await load()
        return
      }
      ElMessage.success(`出库单 ${out.docNo} 已生成并提交审批`)
    } else {
      ElMessage.success(`已生成出库单 ${out.docNo}（草稿）`)
    }
    createVisible.value = false
    await load()
  } finally {
    createSubmitting.value = false
  }
}

async function submit(row: any) { await outApi.outSubmit(row.id); ElMessage.success('已提交'); await load() }

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交出库单 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await outApi.outWithdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

// 详情弹窗内直接审批/驳回
function approveFromDetail() { const row = detailData.value; detailVisible.value = false; openApprove(row) }
// 详情弹窗内直接删除：与列表行同口径（谁提交谁删除），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, outApi.outDelete, () => {}, '出库单') } catch { return }
  detailVisible.value = false
  await load()
}
function rejectFromDetail() { const row = detailData.value; detailVisible.value = false; rejectOut(row) }

// 一键通过：直接调审批接口，不再弹库存预览框（误批可走“取消审批”撤回）
async function openApprove(row: any) {
  await outApi.outApprove(row.id, '同意出库')
  ElMessage.success(`已审批 ${row.docNo || ''}`)
  await load()
}
async function rejectOut(row: any) {
  try {
    const { value: comment } = await ElMessageBox.prompt('请输入驳回原因', `驳回 ${row.docNo}`, {
      inputType: 'textarea', inputValue: ''
    })
    await outApi.outReject(row.id, comment)
    ElMessage.success('已驳回')
    await load()
  } catch {}
}

const detailVisible = ref(false)
const detailData = ref<any>(null)
// 详情面板顶部：一行摘要 + 步骤（草稿=第 1 步、待审批/已驳回=第 2 步、已审批=全部完成）+ 关键数字
const detailSub = computed(() => {
  const d = detailData.value
  if (!d) return ''
  const proj = d.projectName || projectMap.value[d.projectId]?.name
  return [proj, d.sourceReqNo && `来源申请 ${d.sourceReqNo}`, d.outDate && `出库 ${d.outDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({ DRAFT: 0, PENDING: 1, REJECTED: 1, APPROVED: 3 } as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d = detailData.value
  if (!d) return []
  const list: { label: string; value: string | number; hl?: boolean }[] = [
    { label: '明细行数', value: d.lines?.length || 0 },
    { label: '出库数量', value: fmtQty(d.totalQty) }
  ]
  // 草稿/待审批的金额要等审批扣库存时才按当时单价算出，先显示「—」
  if (canSeeCost.value) list.push({ label: '出库金额', value: d.status === 'APPROVED' ? fmtMoney(d.totalAmt) : '—', hl: true })
  return list
})
const materials = ref<any[]>([])
const matMap = ref<Record<number, any>>({})
const projectMap = ref<Record<number, any>>({})

async function openDetail(id: number) {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  projectMap.value = Object.fromEntries(projects.value.map((p: any) => [p.id, p]))
  detailData.value = await outApi.outDetail(id)
  detailVisible.value = true
}

function exportExcel() {
  exportRowsToExcel('材料出库', exportColumns, rows.value, '材料出库', { total: total.value, fetch: p => outApi.outPage({ ...query, ...p }) })
}

async function printOut(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'portrait') {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  projectMap.value = Object.fromEntries(projects.value.map((p: any) => [p.id, p]))
  const whMap = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  const doc: any = rowOrDoc?.lines ? rowOrDoc : await outApi.outDetail(rowOrDoc.id)
  const projectName = doc.projectName || projectMap.value[doc.projectId]?.name || ''
  const columns: PrintColumn[] = [
    { label: '序号', value: (_row, index) => index + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: line => matMap.value[line.materialId]?.code || '', width: '13%' },
    { label: '物料名称', value: line => matMap.value[line.materialId]?.name || line.materialId || '', width: '20%' },
    { label: '规格型号', value: line => matMap.value[line.materialId]?.spec || '', width: '14%' },
    { label: '单位', value: line => matMap.value[line.materialId]?.unit || '', width: '6%', align: 'center' },
    { label: '出库数量', key: 'qty', width: '10%', align: 'right' },
    ...(canSeeCost.value ? [
      { label: '单价', value: (line: any) => fmtMoney(line.unitPrice), width: '11%', align: 'right' },
      { label: '金额', value: (line: any) => fmtMoney(line.amount), width: '12%', align: 'right' }
    ] as PrintColumn[] : []),
    { label: '备注', value: line => line.remark || line.reqRemark || '', width: '9%' }
  ]
  const options = {
    orgId: doc?.orgId,
    title: '材料出库单',
    subtitle: `${paper} 领料签字版`,
    fileName: `${doc.docNo || '材料出库'}-领料签字单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('OUT', doc.id, doc.docNo),
    fields: [
      { label: '出库单号', value: doc.docNo },
      { label: '来源申请', value: doc.sourceReqNo || '' },
      { label: '状态', value: docStatusLabel(doc.status) },
      { label: '出库日期', value: doc.outDate || '' },
      { label: '仓库', value: whMap[doc.warehouseId]?.name || doc.warehouseId || '' },
      { label: '工程项目', value: projectName },
      { label: '施工单位', value: doc.constructionUnit || '' },
      { label: '出库类型', value: doc.outType || '' },
      { label: '业务分类', value: doc.businessCategory || '' },
      { label: '材料领料人', value: doc.materialReceiver || '' },
      { label: '数量', value: doc.totalQty || 0 },
      ...(canSeeCost.value ? [{ label: '金额', value: fmtMoney(doc.totalAmt) }] : []),
      { label: '用途', value: doc.purpose || '' },
      { label: '备注', value: doc.remark || doc.reqRemark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '领料人签字', lines: ['签字：', '日期：'] },
      { label: '仓管发料', lines: ['签字：', '日期：'] },
      { label: '审批确认', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '本单据用于材料出库交接，领料人员签字后作为仓库发料和项目领用依据。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 金额: { field: 'amount', type: 'money' } })
</script>

<style scoped lang="scss">
.create-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: 14px;
}

.section-title {
  margin-bottom: 8px;
  color: var(--text-1);
  font-size: 13px;
  font-weight: 650;
}

.create-steps {
  max-width: 560px;
  margin: 4px auto 18px;
}

/* 制单·当前库存列(与出库申请页同一套颜色) */
.stk-muted { color: var(--text-3, #98a1b0); }
.stk-ok    { color: var(--text-1, #1a1f29); font-variant-numeric: tabular-nums; }
.stk-zero  { color: #d93b3b; font-weight: 650; }
.stk-warn  { margin-top: 3px; font-size: 11.5px; line-height: 1.3; color: #d93b3b; }

.out-type-picker {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
  background: var(--el-color-primary-light-9, #eef4fb);
  border: 1px solid var(--el-color-primary-light-7, #cfe0f2);
  border-radius: 10px;

  .otp-label { font-size: 14px; font-weight: 650; color: var(--text-1); }
  .otp-req { color: #d64b46; margin-right: 2px; }
  .otp-hint { font-size: 12px; color: var(--text-3, #8a94a6); }
}

.request-filter {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;

  :deep(.el-date-editor),
  :deep(.el-input),
  :deep(.el-select) {
    width: 100%;
  }

  /* 日期范围占两格，操作按钮靠右占满整行 */
  > :deep(.el-date-editor) {
    grid-column: span 2;
  }
}

.request-filter-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 960px) {
  .request-filter {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
