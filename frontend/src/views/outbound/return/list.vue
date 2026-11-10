<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">退料单</div>
        <div class="page-sub">关联已审批材料出库单或原出库申请退回数量，审批后自动增加库存并写入出入库流水</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse')" type="primary" @click="openCreate">新增退料单</el-button>
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
          <el-form-item data-q="dateRange" label="退料日期">
            <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
          </el-form-item>
          <el-form-item data-q="keyword" label="退料单号">
            <DocNoLookup v-model="query.keyword" type="RET" multiple placeholder="点一下列出最近退料单" width="220px" @enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="sourceOutNo" label="来源出库">
            <DocNoLookup v-model="query.sourceOutNo" type="OUT" placeholder="点一下列出最近出库单" width="220px" @enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="sourceReqNo" label="来源申请">
            <DocNoLookup v-model="query.sourceReqNo" type="OREQ" placeholder="点一下列出最近申请" width="220px" @enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="warehouseId" label="仓库">
            <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="projectId" label="工程项目">
            <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable style="width:190px">
              <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
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
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="退料单号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="sourceOutNo" label="来源出库" width="160" sortable="custom" />
        <el-table-column prop="sourceReqNo" label="来源申请" width="160" sortable="custom" />
        <el-table-column prop="returnDate" label="退料日期" width="110" sortable="custom" />
        <el-table-column prop="projectName" label="工程项目" min-width="160" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="warehouseId" label="仓库" min-width="140" sortable="custom">
          <template #default="{ row }">{{ whMap[row.warehouseId]?.name || row.warehouseId }}</template>
        </el-table-column>
        <el-table-column prop="returner" label="退料人" width="110" sortable="custom" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column prop="totalAmt" sortable="custom" v-if="canSeeCost" label="金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip sortable="custom" />
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printReturn(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="row.status === 'DRAFT' && user.hasRole('warehouse')" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="success" @click="approveOrReject(row, true)">审批</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="danger" @click="approveOrReject(row, false)">驳回</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="confirmDeleteDoc(row, outApi.returnDelete, load, '退料单')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <el-dialog draggable v-model="createVisible" :before-close="createGuard.beforeClose" title="新建退料单" width="min(1080px, calc(100vw - 48px))">
      <DocGuardHint :active="createVisible" :date="returnForm.returnDate" :warehouse-ids="[returnForm.warehouseId]" v-model:blocked="guardBlocked" />
      <div class="source-mode">
        <el-radio-group v-model="sourceMode" @change="switchSourceMode">
          <el-radio-button label="OUT">按材料出库单退料</el-radio-button>
          <el-radio-button label="OREQ">按出库申请退料</el-radio-button>
        </el-radio-group>
      </div>
      <div class="create-grid">
        <section>
          <div class="section-title">{{ sourceMode === 'OUT' ? '已审批材料出库单' : '已产生出库的出库申请' }}</div>
          <div class="request-filter">
            <el-input v-model="outQuery.keyword" :placeholder="sourceMode === 'OUT' ? '出库单号' : '出库申请号'" clearable @keyup.enter="loadSources" />
            <el-input v-if="sourceMode === 'OUT'" v-model="outQuery.sourceReqNo" placeholder="出库申请号" clearable @keyup.enter="loadSources" />
            <el-input v-else v-model="outQuery.applicantName" placeholder="材料申请人" clearable @keyup.enter="loadSources" />
            <el-select v-model="outQuery.warehouseId" placeholder="仓库" clearable filterable>
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
            <el-select v-model="outQuery.projectId" placeholder="工程项目" clearable filterable>
              <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
            </el-select>
            <div class="request-filter-actions">
              <el-button type="primary" @click="loadSources">查询</el-button>
              <el-button @click="resetOutQuery">重置</el-button>
            </div>
          </div>
          <el-table class="readable-table" v-if="sourceMode === 'OUT'" :fit="false" :data="approvedOuts" v-loading="outLoading" height="260" border highlight-current-row @row-click="selectOut">
            <el-table-column prop="docNo" label="出库单号" width="210" />
            <el-table-column prop="outDate" label="出库日期" width="105" />
            <el-table-column prop="sourceReqNo" label="来源申请" width="140" />
            <el-table-column prop="projectName" label="工程项目" min-width="150" show-overflow-tooltip />
            <el-table-column prop="totalQty" label="数量" width="90" align="right" :formatter="$qtyFmt" />
            <el-table-column label="操作" width="86">
              <template #default="{ row }"><el-button link type="primary" @click.stop="selectOut(row)">选择</el-button></template>
            </el-table-column>
          </el-table>
          <el-table class="readable-table" v-else :fit="false" :data="approvedReqs" v-loading="outLoading" height="260" border highlight-current-row @row-click="selectReq">
            <el-table-column prop="docNo" label="申请单号" width="210" />
            <el-table-column prop="applyDate" label="申请日期" width="105" />
            <el-table-column prop="projectName" label="工程项目" min-width="150" show-overflow-tooltip />
            <el-table-column prop="outQty" label="已出库" width="90" align="right" :formatter="$qtyFmt" />
            <el-table-column label="操作" width="86">
              <template #default="{ row }"><el-button link type="primary" @click.stop="selectReq(row)">选择</el-button></template>
            </el-table-column>
          </el-table>
        </section>
        <section>
          <div class="section-title">本次退料数量</div>
          <el-alert v-if="selectedSource" type="info" show-icon :closable="false" class="mb-3" :title="`已选择 ${selectedSource.docNo}，请输入本次退料数量`" />
          <el-form label-position="top" :model="returnForm" label-width="86px" class="mb-3">
            <el-row :gutter="12">
              <el-col :span="12"><el-form-item label="退回仓库" required><el-select v-model="returnForm.warehouseId" style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
              </el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="退料日期" required><el-date-picker v-model="returnForm.returnDate" value-format="YYYY-MM-DD" type="date" style="width:100%" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="退料人"><el-input v-model="returnForm.returner" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="退回后">
                <el-radio-group v-model="returnForm.reissue">
                  <el-radio :value="0">不再领</el-radio>
                  <el-radio :value="1">还要再领</el-radio>
                </el-radio-group>
                <span class="reissue-tip">{{ returnForm.reissue === 1 ? '审批通过后，原出库申请可按退回的数量再出库' : '退回后不能再按原申请领取（需要时请采购修订申请）' }}</span>
              </el-form-item></el-col>
            </el-row>
          </el-form>
          <el-table class="readable-table" :fit="false" :data="returnLines" height="220" border :empty-text="sourceMode === 'OUT' ? '请先选择一张已审批出库单' : '请先选择一张已产生出库的出库申请'">
            <el-table-column label="物料" min-width="210">
              <template #default="{ row }">{{ matText(row.materialId) }}</template>
            </el-table-column>
            <el-table-column :label="sourceMode === 'OUT' ? '原出库数量' : '已出库数量'" prop="sourceQty" width="110" align="right" />
            <el-table-column label="退料数量" width="160">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0" :max="Number(row.sourceQty || 0)" :precision="4" :step="1" controls-position="right" style="width:100%" />
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" :disabled="guardBlocked || !canCreateReturn" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="createReturn">生成退料单</el-button>
      </template>
    </el-dialog>

    <DocDrawer v-model="detailVisible" kind="退料单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :nums="detailNums">

      <template v-if="detailData">
        <DocStepGuide :guide="returnGuide(detailData)" />
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="退料单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="来源出库">{{ detailData.sourceOutNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源申请">{{ detailData.sourceReqNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="退料日期">{{ detailData.returnDate }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ detailData.projectName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ whMap[detailData.warehouseId]?.name || detailData.warehouseId }}</el-descriptions-item>
          <el-descriptions-item label="退料人">{{ detailData.returner || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退回后">{{ detailData.reissue === 1 ? '还要再领' : '不再领' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="金额">{{ fmtMoney(detailData.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detailData.remark }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
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
          <el-table-column label="退料数量" prop="qty" width="110" align="right" :formatter="$qtyFmt" />
          <el-table-column v-if="canSeeCost" label="参考单价" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="参考金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
        </el-table>
        <DocAttachments biz-type="RETURN" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
        <el-button v-if="detailData && user.canDeleteDoc(detailData)" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          type="primary"
          @print="(paper, orientation) => printReturn(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printReturn(detailData, 'export', paper, orientation)"
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
import DocStepGuide from '@/components/DocStepGuide.vue'
import { returnGuide } from '@/utils/docSteps'
import { computed, onMounted, reactive, ref } from 'vue'
import { useBizOptions } from '@/composables/useBizOptions'
import { ElMessage, ElMessageBox } from 'element-plus'
import { outApi, masterApi, sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useRoute } from 'vue-router'
import { applyRouteQuery } from '@/utils/routeQuery'
import { fmtDate, fmtMoney, today, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { confirmDeleteDoc } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { buildPrintQr } from '@/utils/docQr'
import { approvalRecordNote } from '@/utils/approvalPrint'
import StatusTag from '@/components/StatusTag.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'

const user = useUserStore()
const canSeeCost = computed(() => user.hasFinanceAccess())
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const route = useRoute()
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', warehouseId: null, sourceOutNo: '', sourceReqNo: '', projectId: null, constructionUnit: '', outType: '', businessCategory: '', startDate: null, endDate: null })
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])
const materials = ref<any[]>([])
const whMap = ref<Record<number, any>>({})
const matMap = ref<Record<number, any>>({})
const { outTypes, businessCategories, loadBizOptions } = useBizOptions()
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '退料单号', key: 'docNo' },
  { label: '来源出库', key: 'sourceOutNo' },
  { label: '来源申请', key: 'sourceReqNo' },
  { label: '退料日期', key: 'returnDate' },
  { label: '工程项目', key: 'projectName' },
  { label: '仓库', value: row => whMap.value[row.warehouseId]?.name || row.warehouseId || '' },
  { label: '退料人', key: 'returner' },
  { label: '数量', qty: true, key: 'totalQty' },
  ...(canSeeCost.value ? [{ label: '金额', money: true, value: (row: any) => fmtMoney(row.totalAmt) }] : []),
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '备注', key: 'remark' }
])

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => outApi.returnPage(q), ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await outApi.returnPage(query)
    rows.value = r.records
    total.value = r.total
  } finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  applyDateRange()
  query.current = 1
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', warehouseId: null, sourceOutNo: '', sourceReqNo: '', projectId: null, constructionUnit: '', outType: '', businessCategory: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const outLoading = ref(false)
const createSubmitting = ref(false)
const sourceMode = ref<'OUT' | 'OREQ'>('OUT')
const approvedOuts = ref<any[]>([])
const approvedReqs = ref<any[]>([])
const selectedSource = ref<any>(null)
const returnLines = ref<any[]>([])
const returnForm = reactive<any>({ warehouseId: null, returnDate: today(), returner: '', remark: '', reissue: 0 })
const outQuery = reactive<any>({ keyword: '', sourceReqNo: '', applicantName: '', warehouseId: null, projectId: null })
const canCreateReturn = computed(() => !!selectedSource.value && returnLines.value.some((line: any) => Number(line.qty) > 0))

async function loadOptions() {
  const [whs, mats, projs, types, cats] = await Promise.all([
    masterApi.warehouses(true),
    masterApi.allMaterials({ includeDisabled: true }),
    masterApi.projects(true),
    sysApi.optionList('out.type.options').catch(() => []),
    sysApi.optionList('business.category.options').catch(() => [])
  ]) as any[]
  warehouses.value = whs || []
  materials.value = mats || []
  projects.value = projs || []
  whMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  if (types?.length) outTypes.value = types
  if (cats?.length) businessCategories.value = cats
}

async function openCreate() {
  await loadOptions()
  createVisible.value = true
  sourceMode.value = 'OUT'
  selectedSource.value = null
  returnLines.value = []
  Object.assign(returnForm, { warehouseId: null, returnDate: today(), returner: user.userInfo?.name || '', remark: '', reissue: 0 })
  await loadSources()
}

function switchSourceMode() {
  selectedSource.value = null
  returnLines.value = []
  returnForm.warehouseId = null
  loadSources()
}

function loadSources() {
  return sourceMode.value === 'OUT' ? loadApprovedOuts() : loadApprovedReqs()
}

async function loadApprovedOuts() {
  outLoading.value = true
  try {
    const r: any = await outApi.outPage({ current: 1, size: 100, status: 'APPROVED', keyword: outQuery.keyword, sourceReqNo: outQuery.sourceReqNo, warehouseId: outQuery.warehouseId, projectId: outQuery.projectId })
    approvedOuts.value = r.records || []
  } finally {
    outLoading.value = false
  }
}

function resetOutQuery() {
  Object.assign(outQuery, { keyword: '', sourceReqNo: '', applicantName: '', warehouseId: null, projectId: null })
  loadSources()
}

async function selectOut(row: any) {
  const detail: any = await outApi.outDetail(row.id)
  selectedSource.value = detail
  returnForm.warehouseId = detail.warehouseId
  returnForm.returner = detail.materialReceiver || user.userInfo?.name || ''
  returnLines.value = (detail.lines || []).map((line: any) => ({
    sourceOutLineId: line.id,
    materialId: line.materialId,
    sourceQty: Number(line.qty || 0),
    qty: Number(line.qty || 0),
    remark: ''
  }))
}

async function loadApprovedReqs() {
  outLoading.value = true
  try {
    const [partial, done] = await Promise.all([
      outApi.oreqPage({ current: 1, size: 100, status: 'PARTIAL_OUT', keyword: outQuery.keyword, applicantName: outQuery.applicantName, warehouseId: outQuery.warehouseId, projectId: outQuery.projectId }),
      outApi.oreqPage({ current: 1, size: 100, status: 'OUT', keyword: outQuery.keyword, applicantName: outQuery.applicantName, warehouseId: outQuery.warehouseId, projectId: outQuery.projectId })
    ]) as any[]
    approvedReqs.value = [...(partial.records || []), ...(done.records || [])]
  } finally {
    outLoading.value = false
  }
}

async function selectReq(row: any) {
  const detail: any = await outApi.oreqDetail(row.id)
  selectedSource.value = detail
  returnForm.warehouseId = detail.warehouseId
  returnForm.returner = detail.materialApplicant || user.userInfo?.name || ''
  returnLines.value = (detail.lines || [])
    .filter((line: any) => Number(line.outQty || 0) > 0)
    .map((line: any) => ({
      sourceReqLineId: line.id,
      materialId: line.materialId,
      sourceQty: Number(line.outQty || 0),
      qty: Number(line.outQty || 0),
      remark: ''
    }))
}

async function createReturn() {
  if (!selectedSource.value) return
  if (!returnForm.warehouseId) { ElMessage.warning('请选择退回仓库'); return }
  const lines = returnLines.value
    .filter((line: any) => Number(line.qty) > 0)
    .map((line: any) => ({
      sourceOutLineId: line.sourceOutLineId,
      sourceReqLineId: line.sourceReqLineId,
      qty: line.qty,
      remark: line.remark
    }))
  if (!lines.length) { ElMessage.warning('请填写退料数量'); return }
  createSubmitting.value = true
  try {
    const payload = {
      warehouseId: returnForm.warehouseId,
      returnDate: returnForm.returnDate,
      returner: returnForm.returner,
      remark: returnForm.remark,
      reissue: returnForm.reissue,
      lines
    }
    const ret: any = sourceMode.value === 'OUT'
      ? await outApi.returnFromOut({ ...payload, sourceOutId: selectedSource.value.id })
      : await outApi.returnFromOreq({ ...payload, sourceReqId: selectedSource.value.id })
    ElMessage.success(`已生成退料单 ${ret.docNo}`)
    createVisible.value = false
    await load()
  } finally {
    createSubmitting.value = false
  }
}

async function submit(row: any) {
  await outApi.returnSubmit(row.id)
  ElMessage.success('已提交')
  await load()
}

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交退料单 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await outApi.returnWithdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

async function approveOrReject(row: any, approve: boolean) {
  try {
    const { value } = await ElMessageBox.prompt('审批意见', `${approve ? '通过' : '驳回'} ${row.docNo}`, { inputType: 'textarea', inputValue: approve ? '同意退料' : '' })
    await (approve ? outApi.returnApprove(row.id, value) : outApi.returnReject(row.id, value))
    ElMessage.success(approve ? '已通过，库存已增加' : '已驳回')
    await load()
  } catch {}
}

const detailVisible = ref(false)
// 详情弹窗内直接删除：与列表行同口径（谁提交谁删除），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, outApi.returnDelete, () => {}, '退料单') } catch { return }
  detailVisible.value = false
  await load()
}
const detailData = ref<any>(null)

async function openDetail(id: number) {
  if (!materials.value.length || !warehouses.value.length) await loadOptions()
  detailData.value = await outApi.returnDetail(id)
  detailVisible.value = true
}

function exportExcel() {
  exportRowsToExcel('退料单', exportColumns.value, rows.value, '退料单', { total: total.value, fetch: p => outApi.returnPage({ ...query, ...p }) })
}

async function printReturn(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'landscape') {
  if (!materials.value.length || !warehouses.value.length) await loadOptions()
  const doc = rowOrDoc.lines ? rowOrDoc : await outApi.returnDetail(rowOrDoc.id)
  const amountColumns = canSeeCost.value ? [
    { label: '参考单价', value: (row: any) => fmtMoney(row.unitPrice), width: '12%', align: 'right' as const },
    { label: '参考金额', value: (row: any) => fmtMoney(row.amount), width: '12%', align: 'right' as const }
  ] as PrintColumn[] : []
  const columns: PrintColumn[] = [
    { label: '行号', key: 'lineNo', width: '8%', align: 'center' },
    { label: '物料编码', value: row => matMap.value[row.materialId]?.code || '', width: '14%' },
    { label: '物料名称', value: row => matMap.value[row.materialId]?.name || row.materialId, width: '20%' },
    { label: '规格型号', value: row => matMap.value[row.materialId]?.spec || '', width: '14%' },
    { label: '单位', value: row => matMap.value[row.materialId]?.unit || '', width: '7%', align: 'center' },
    { label: '退料数量', key: 'qty', width: '11%', align: 'right' },
    ...amountColumns,
    { label: '备注', key: 'remark', width: '22%' }
  ]
  const options = {
    orgId: doc?.orgId,
    title: '退料单',
    subtitle: `${paper} 签字版`,
    fileName: `${doc.docNo || '退料单'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('RETURN', doc.id, doc.docNo),
    fields: [
      { label: '退料单号', value: doc.docNo },
      { label: '来源出库', value: doc.sourceOutNo || '-' },
      { label: '来源申请', value: doc.sourceReqNo || '-' },
      { label: '状态', value: docStatusLabel(doc.status) },
      { label: '退料日期', value: doc.returnDate },
      { label: '仓库', value: whMap.value[doc.warehouseId]?.name || doc.warehouseId || '-' },
      { label: '工程项目', value: doc.projectName || '-' },
      { label: '退料人', value: doc.returner || '-' },
      { label: '数量', value: doc.totalQty },
      ...(canSeeCost.value ? [{ label: '金额', value: fmtMoney(doc.totalAmt) }] : []),
      { label: '备注', value: doc.remark || '-', span: 2 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '退料人签字', lines: ['签字：', '日期：'] },
      { label: '仓管验收', lines: ['签字：', '日期：'] },
      { label: '审批确认', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '退料单审批后库存数量自动增加，并写入出入库流水；本单可作为现场退料交接签字依据。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

function matText(materialId: number) {
  const m = matMap.value[materialId]
  return m ? `${m.code} ${m.name}` : materialId || '-'
}

onMounted(() => {
  loadOptions(); loadBizOptions()
  // 从月末结账「问题单据」点单号跳来时带 keyword，自动筛出那一张；
  // 不带参数正常进入时也自动列出第一页
  applyRouteQuery(query, route, ['status', 'keyword', 'sourceOutNo', 'sourceReqNo'])
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
  return [d.projectName, d.sourceOutNo && `来源出库 ${d.sourceOutNo}`, d.returnDate && `退料 ${d.returnDate}`].filter(Boolean).join(' · ')
})
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '退料数量', value: fmtQty(d.totalQty) }, { label: '退回后', value: d.reissue === 1 ? '还要再领' : '不再领' }, canSeeCost.value && { label: '退料金额（按原出库成本）', value: fmtMoney(d.totalAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 退料数量: { field: 'qty', type: 'qty' }, 参考金额: { field: 'amount', type: 'money' } })
</script>

<style scoped lang="scss">
.reissue-tip { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
.source-mode {
  margin-bottom: 14px;
}

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

.request-filter {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;

  :deep(.el-input),
  :deep(.el-select) {
    width: 100%;
  }
}

.request-filter-actions {
  display: flex;
  gap: 8px;
}

@media (max-width: 960px) {
  .create-grid {
    grid-template-columns: 1fr;
  }
}
</style>
