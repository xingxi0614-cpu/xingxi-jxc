<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">销售订单</div>
        <div class="page-sub">依据客服部销货单/收款明细录入销售订单，审批后可下推销售出库。客户以个人为主，可手填姓名/电话/收货地址，单位客户可选关联往来单位。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!rows.length" :title="!rows.length ? '当前没有数据' : '勾了哪几行导哪几行；不勾导出全部查询结果'" @click="exportExcel">导出Excel</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate()">+ 新增销售订单</el-button>
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
          <el-form-item label="关键词" data-q="keyword">
            <el-input v-model="query.keyword" placeholder="单号/客户" clearable style="width:180px" @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item label="下单日期" data-q="dateRange">
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" @change="doSearch" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" empty-text="暂无数据" @sort-change="onSortChange">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="订单号" width="200" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="orderDate" label="日期" width="110" sortable="custom" />
        <el-table-column prop="customerName" sortable="custom" label="客户" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || row.customerUnit || '-' }}</template>
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
        <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.totalQty) }}</template></el-table-column>
        <el-table-column prop="totalAmt" sortable="custom" label="价税合计" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template></el-table-column>
        <el-table-column label="已出/已开" width="110" align="center">
          <template #default="{ row }"><span class="muted">{{ fmtQty(row.outQty) }} / {{ fmtQty(row.invoiceQty) }}</span></template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="310">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button v-if="canEdit && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="openEdit(row.id)">编辑</el-button>
            <el-button v-if="canEdit && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="doSubmit(row.id)">提交</el-button>
            <el-button v-if="row.status === 'PENDING' && user.ownsDoc(row)" link type="warning" @click="doWithdraw(row.id)">撤回</el-button><el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('SO', row, load)">取消审批</el-button>
            <el-button v-if="row.status === 'PENDING' && row.canApprove" link type="success" @click="doApprove(row.id)">审批</el-button><el-button v-if="row.status === 'PENDING' && row.canApprove" link type="danger" @click="doReject(row.id)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total" :current-page="query.current" :page-size="query.size" @current-change="onPage" /></div>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="editVisible" :before-close="editGuard.beforeClose" :title="form.id ? '编辑销售订单' : '新增销售订单'" width="min(1080px, calc(100vw - 48px))" align-center>
      <el-form label-position="top" :model="form" label-width="88px">
        <el-row :gutter="16">
          <el-col :span="8"><el-form-item label="订单类型"><el-select v-model="form.orderType" style="width:100%"><el-option v-for="t in ['普通','工程','零售']" :key="t" :value="t" :label="t" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="客户单位">
            <el-select v-model="form.customerId" filterable clearable remote :remote-method="searchCustomers" :loading="cusLoading" placeholder="散户可不选" style="width:100%" @change="onCustomerChange">
              <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="客户姓名"><el-input v-model="form.customerName" placeholder="个人客户姓名" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="联系方式"><el-input v-model="form.customerPhone" /></el-form-item></el-col>
          <el-col :span="16"><el-form-item label="收货地址"><el-input v-model="form.shipAddress" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="部门"><el-input v-model="form.deptName" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="业务员"><el-input v-model="form.salesPerson" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="销售渠道"><el-input v-model="form.channel" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="下单日期"><el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="计划发货"><el-date-picker v-model="form.planShipDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="收款金额"><el-input-number v-model="form.receiptAmount" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="收款方式"><el-select v-model="form.receiptMethod" clearable style="width:100%"><el-option v-for="m in payMethods" :key="m" :value="m" :label="m" /></el-select></el-form-item></el-col>
        </el-row>

        <el-divider>订单明细</el-divider>
        <div style="margin-bottom:8px"><el-button size="small" type="primary" plain @click="addLine">增行</el-button><el-button size="small" type="success" plain @click="batchPasteRef?.open()">从Excel批量导入</el-button></div>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="form.lines" border size="small" show-summary :summary-method="formLineSummary">
          <el-table-column label="物料" min-width="220">
            <template #default="{ row }"><MaterialLookup v-model="row.materialId" placeholder="选择物料" title="选择物料编码" @change="(m:any) => onMat(row, m)" /></template>
          </el-table-column>
          <el-table-column label="规格" width="120"><template #default="{ row }">{{ row.spec || '-' }}</template></el-table-column>
          <el-table-column label="单位" width="70"><template #default="{ row }">{{ row.unit || '-' }}</template></el-table-column>
          <el-table-column label="数量" width="120"><template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :precision="2" :controls="false" style="width:100%" @change="calcLine(row)" /></template></el-table-column>
          <el-table-column label="含税净价" width="130"><template #default="{ row }"><el-input-number v-model="row.taxPrice" :min="0" :precision="4" :controls="false" style="width:100%" @change="calcLine(row)" /></template></el-table-column>
          <el-table-column label="税率%" width="100"><template #default="{ row }"><el-input-number v-model="row.taxRate" :min="0" :max="100" :precision="2" :controls="false" style="width:100%" /></template></el-table-column>
          <el-table-column label="价税合计" width="120" align="right"><template #default="{ row }">{{ fmtMoney(lineAmount(row)) }}</template></el-table-column>
          <el-table-column width="56"><template #default="{ $index }"><el-button link type="danger" @click="form.lines.splice($index,1)">删</el-button></template></el-table-column>
        </el-table>
        <div class="edit-total">价税合计：<b>{{ fmtMoney(formTotal) }}</b></div>
      </el-form>
      <template #footer>
        <el-button @click="editGuard.cancel">取消</el-button>
        <el-button :loading="saving" @click="save(false)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="save(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <DocDrawer v-model="detailVisible" kind="销售订单" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['制单', '审批', '出库', '开票']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="订单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="订单类型">{{ detail.orderType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="客户单位">{{ detail.customerUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="客户姓名">{{ detail.customerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联系方式">{{ detail.customerPhone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ detail.shipAddress || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ detail.salesPerson || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ detail.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="计划发货">{{ detail.planShipDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="收款金额/方式">{{ fmtMoney(detail.receiptAmount) }} / {{ detail.receiptMethod || '-' }}</el-descriptions-item>
          <el-descriptions-item label="价税合计">{{ fmtMoney(detail.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="税额">{{ fmtMoney(detail.taxAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="detail.lines || []" border size="small" show-summary :summary-method="lineSummary">
          <el-table-column fixed="left" type="index" label="#" width="48" />
          <el-table-column fixed="left" label="物料编码" width="120"><template #default="{ row }">{{ matField(row.materialId, 'code') }}</template></el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'name') }}</template></el-table-column>
          <el-table-column label="规格型号" min-width="110" show-overflow-tooltip><template #default="{ row }">{{ matField(row.materialId, 'spec') }}</template></el-table-column>
          <el-table-column label="数量" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.qty) }}</template></el-table-column>
          <el-table-column label="含税净价" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.taxPrice) }}</template></el-table-column>
          <el-table-column label="税率%" width="80" align="right"><template #default="{ row }">{{ row.taxRate }}</template></el-table-column>
          <el-table-column label="价税合计" width="110" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
          <el-table-column label="已出/已开" width="110" align="center"><template #default="{ row }">{{ fmtQty(row.outQty) }} / {{ fmtQty(row.invoiceQty) }}</template></el-table-column>
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
        <el-button v-if="detail && ['DRAFT','REJECTED'].includes(detail.status) && canEdit" @click="openEdit(detail.id)">编辑</el-button>
        <el-button v-if="detail && ['DRAFT','REJECTED'].includes(detail.status) && canEdit" type="primary" @click="doSubmit(detail.id)">提交审批</el-button>
        <el-button v-if="detail && detail.status === 'PENDING' && detail.canApprove" type="primary" @click="doApprove(detail.id)">审批通过</el-button>
      </template>

    </DocDrawer>
  </div>
  <BatchLinePaste ref="batchPasteRef" price-label="含税净价" @apply="applyBatchPaste" />
</template>

<script setup lang="ts">
import QueryDrawer from '@/components/QueryDrawer.vue'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { salesApi } from '@/api'
import { applyRouteQuery } from '@/utils/routeQuery'
import { requestCancelApproval } from '@/utils/docActions'
import { useUserStore } from '@/stores/user'
import StatusTag from '@/components/StatusTag.vue'
import MoreMaterialsTag from '@/components/MoreMaterialsTag.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import BatchLinePaste from '@/components/BatchLinePaste.vue'
import { fmtMoney, today, roundMoney } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { makeLineSummary } from '@/utils/tableSummary'
import { showError } from '@/utils/notify'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { docStatusLabel } from '@/utils/statusLabel'

// 明细表合计（数量 + 价税合计），便于核对订单总额
const lineSummary = makeLineSummary({
  数量: { field: 'qty', type: 'qty' },
  价税合计: { field: 'amount', type: 'money' }
})

function firstMat(s?: string | null) { return (s || '').split('、')[0] || '-' }

function matField(id: number, k: 'code' | 'name' | 'spec') { const m = matCache.value[id]; return (m && (m as any)[k]) || (k === 'code' ? String(id) : '-') }

const user = useUserStore()
const canEdit = computed(() => user.hasRole('purchaser') || user.hasRole('material_accountant') || user.hasRole('account_admin') || user.hasRole('admin'))
const payMethods = ['转账', '现金', '微信', '支付宝', '银行承兑', '其他']
const statusOptions = [
  { v: 'DRAFT', l: '草稿' }, { v: 'PENDING', l: '待审批' }, { v: 'APPROVED', l: '已审批' },
  { v: 'PARTIAL_OUT', l: '部分出库' }, { v: 'OUT', l: '已出库' }, { v: 'REJECTED', l: '已驳回' }
]

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const dateRange = ref<any>(null)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '' })
const matCache = ref<Record<number, any>>({})

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }

// 导出列与列表显示一致;物料列导出全部物料(列表上只显示第一种)
const exportColumns: ExportColumn[] = [
  { label: '订单号', key: 'docNo' },
  { label: '日期', key: 'orderDate' },
  { label: '客户', value: (row: any) => row.customerName || row.customerUnit || '-' },
  { label: '物料编码', key: 'materialCodes' },
  { label: '物料名称', key: 'materialNames' },
  { label: '规格型号', key: 'materialSpecs' },
  { label: '数量', key: 'totalQty', qty: true },
  { label: '价税合计', money: true, value: (row: any) => fmtMoney(row.totalAmt) },
  { label: '已出', key: 'outQty', qty: true },
  { label: '已开', key: 'invoiceQty', qty: true },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) }
]
function exportExcel() { exportRowsToExcel('销售订单', exportColumns, rows.value, '销售订单', { total: total.value, fetch: p => salesApi.orderPage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null, ...p }) }) }
function matName(id: number) { const m = matCache.value[id]; return m ? `${m.code} ${m.name}` : id }

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...statusOptions.map(o => ({ label: o.l, value: o.v }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => salesApi.orderPage({ ...q, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }), statusOptions.map(o => o.v))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try {
    const r: any = await salesApi.orderPage({ ...query, startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null })
    rows.value = r.records || []
    total.value = r.total || 0
  } finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)
function doSearch() { query.current = 1; load() }
/** 查询面板「重置」:清掉状态、关键词、日期,回到第一页重查 */
function resetQuery() { query.status = ''; query.keyword = ''; dateRange.value = null; doSearch() }
function onPage(p: number) { query.current = p; load() }

/* 客户下拉 */
const customers = ref<any[]>([])
const cusLoading = ref(false)
async function searchCustomers(kw: string) {
  cusLoading.value = true
  try { customers.value = await salesApi.customers(kw) as any[] } finally { cusLoading.value = false }
}
function onCustomerChange(id: number) {
  const c = customers.value.find(x => x.id === id)
  if (c) {
    form.customerUnit = c.name
    if (!form.customerPhone) form.customerPhone = c.contactPhone || ''
    if (!form.shipAddress) form.shipAddress = c.address || ''
    if (!form.customerName) form.customerName = c.contactName || ''
  } else { form.customerUnit = '' }
}

/* 新增/编辑 */
const editVisible = ref(false)
const editGuard = useUnsavedGuard(editVisible)
const saving = ref(false)
const form = reactive<any>({ id: null, orderType: '普通', customerId: null, customerUnit: '', customerName: '', customerPhone: '', shipAddress: '', deptName: '', salesPerson: '', channel: '', orderDate: today(), planShipDate: null, receiptAmount: 0, receiptMethod: '', remark: '', lines: [] })
function resetForm() { Object.assign(form, { id: null, orderType: '普通', customerId: null, customerUnit: '', customerName: '', customerPhone: '', shipAddress: '', deptName: '', salesPerson: '', channel: '', orderDate: today(), planShipDate: null, receiptAmount: 0, receiptMethod: '', remark: '', lines: [] }) }
function addLine() { form.lines.push({ materialId: null, spec: '', unit: '', qty: null, taxPrice: null, taxRate: 13, amount: null }) }
const batchPasteRef = ref()
function applyBatchPaste(rows: { material: any; qty: number; price: number | null }[]) {
  form.lines = form.lines.filter((l: any) => l.materialId)
  for (const r of rows) {
    form.lines.push({ materialId: r.material.id, spec: r.material.spec || '', unit: r.material.unit || '', qty: r.qty, taxPrice: r.price, taxRate: 13, amount: r.price != null ? roundMoney(r.qty * r.price) : null })
  }
}
function onMat(row: any, m: any) { row.spec = m?.spec || ''; row.unit = m?.unit || ''; }
// 金额始终跟着数量×单价走：原来清空单价/数量时金额停在旧值，合计显示的还是旧数（2026-10-01 修）
function calcLine(row: any) { row.amount = row.qty && row.taxPrice ? roundMoney(row.qty * row.taxPrice) : 0 }
function lineAmount(row: any) { return row.amount || (row.qty && row.taxPrice ? row.qty * row.taxPrice : 0) }
const formTotal = computed(() => form.lines.reduce((s: number, r: any) => s + Number(lineAmount(r) || 0), 0))

function openCreate() { resetForm(); addLine(); customers.value = []; editVisible.value = true }
async function openEdit(id: number) {
  const d: any = await salesApi.orderDetail(id)
  resetForm()
  Object.assign(form, { id: d.id, orderType: d.orderType || '普通', customerId: d.customerId || null, customerUnit: d.customerUnit || '', customerName: d.customerName || '', customerPhone: d.customerPhone || '', shipAddress: d.shipAddress || '', deptName: d.deptName || '', salesPerson: d.salesPerson || '', channel: d.channel || '', orderDate: d.orderDate, planShipDate: d.planShipDate || null, receiptAmount: Number(d.receiptAmount || 0), receiptMethod: d.receiptMethod || '', remark: d.remark || '' })
  form.lines = (d.lines || []).map((l: any) => ({ materialId: l.materialId, spec: matCache.value[l.materialId]?.spec || '', unit: matCache.value[l.materialId]?.unit || '', qty: Number(l.qty), taxPrice: Number(l.taxPrice), taxRate: Number(l.taxRate), amount: Number(l.amount) }))
  if (d.customerId) customers.value = [{ id: d.customerId, name: d.customerUnit }]
  detailVisible.value = false
  editVisible.value = true
}
async function save(submitAfter: boolean) {
  if (!form.customerName?.trim() && !form.customerId && !form.customerUnit?.trim()) { ElMessage.warning('请填写客户姓名或选择客户单位'); return }
  const lines = form.lines.filter((l: any) => l.materialId && Number(l.qty) > 0)
  if (!lines.length) { ElMessage.warning('请至少录入一行有效明细'); return }
  saving.value = true
  try {
    const payload = { ...form, lines: lines.map((l: any) => ({ materialId: l.materialId, qty: l.qty, taxPrice: l.taxPrice, taxRate: l.taxRate, amount: l.amount })) }
    let id = form.id
    if (form.id) { await salesApi.orderUpdate(form.id, payload) }
    else { const r: any = await salesApi.orderCreate(payload); id = r.id }
    if (submitAfter && id) await salesApi.orderSubmit(id)
    ElMessage.success(submitAfter ? '已提交审批' : '已保存')
    editVisible.value = false
    load()
  } catch (e: any) { showError(e, '保存失败') }
  finally { saving.value = false }
}

/* 详情 + 审批 */
const detailVisible = ref(false)
const detail = ref<any>(null)
async function openDetail(id: number) {
  detail.value = await salesApi.orderDetail(id) as any
  await ensureMats((detail.value.lines || []).map((l: any) => l.materialId))
  detailVisible.value = true
}
async function ensureMats(ids: number[]) {
  const miss = [...new Set(ids)].filter(id => id && !matCache.value[id])
  if (!miss.length) return
  try {
    const { masterApi } = await import('@/api')
    const list: any = await masterApi.allMaterials({ includeDisabled: true })
    for (const m of (list || [])) matCache.value[m.id] = m
  } catch {}
}
async function doSubmit(id: number) { await salesApi.orderSubmit(id); ElMessage.success('已提交'); await refresh(id) }
async function doWithdraw(id: number) { await salesApi.orderWithdraw(id); ElMessage.success('已取消提交'); await refresh(id) }
async function doApprove(id: number) {
  const c = await promptComment('审批通过意见（可空）'); if (c === false) return
  await salesApi.orderApprove(id, c as string); ElMessage.success('已通过'); await refresh(id)
}
async function doReject(id: number) {
  const c = await promptComment('驳回原因'); if (c === false) return
  await salesApi.orderReject(id, c as string); ElMessage.success('已驳回'); await refresh(id)
}
async function doDelete(id: number) {
  await ElMessageBox.confirm('确认删除该销售订单？', '提示', { type: 'warning' })
  await salesApi.orderDelete(id); ElMessage.success('已删除'); detailVisible.value = false; load()
}
async function refresh(id: number) { detail.value = await salesApi.orderDetail(id) as any; load() }
async function promptComment(title: string): Promise<string | false> {
  try { const { value } = await ElMessageBox.prompt('', title, { confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea' }); return value || '' }
  catch { return false }
}

const route = useRoute()
onMounted(() => { applyRouteQuery(query, route, ['status', 'keyword']); load() })

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.customerName || d.customerUnit, d.orderType, d.orderDate && `下单 ${d.orderDate}`, d.salesPerson && `业务员 ${d.salesPerson}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 2, "PARTIAL_OUT": 2, "OUT": 3, "PARTIAL_INVOICED": 3, "INVOICED": 4, "CLOSED": 4} as Record<string, number>)[detail.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detail.value
  if (!d) return []
  return [{ label: '价税合计', value: fmtMoney(d.totalAmt), hl: true }, { label: '已收款', value: fmtMoney(d.receiptAmount) }, d.receiptMethod && { label: '收款方式', value: d.receiptMethod }].filter(Boolean) as any[]
})

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a: number, r: any) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const formLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 价税合计: { value: (r: any) => Number(lineAmount(r) || 0), type: 'money' } })
</script>

<style scoped>
.doc-link { background: none; border: none; color: #2465a8; cursor: pointer; padding: 0; font: inherit; }
.muted { color: #8a94a6; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.edit-total { text-align: right; margin-top: 10px; font-size: 14px; }
.edit-total b { font-size: 18px; color: #2465a8; }
.mb-3 { margin-bottom: 12px; }
</style>
