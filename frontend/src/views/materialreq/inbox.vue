<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">领料申请收件箱</div>
        <div class="page-sub">一线提交的领料申请由采购→仓管→财务依次线上确认；三方通过后自动生成「已审批/可发料」的出库申请。仅计划，不动库存。</div>
      </div>
      <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="load" />
      <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:150px" @change="load">
            <el-option v-for="(v, k) in STATUS" :key="k" :value="k" :label="v.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="搜索">
          <el-input v-model="query.keyword" placeholder="单号/领料人/工程" clearable style="width:200px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无领料申请">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="申请单号" width="180">
          <template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column label="公司" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || row.orgId }}</template>
        </el-table-column>
        <el-table-column prop="pickerName" label="领料人" width="110" />
        <el-table-column prop="projectName" label="工程名称" min-width="170" show-overflow-tooltip />
        <el-table-column label="仓库" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="applyDate" label="申请日期" width="110" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="STATUS[row.status]?.type || 'info'" size="small">{{ STATUS[row.status]?.label || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button v-if="canConfirm(row)" link type="success" @click="confirm(row)">{{ STATUS[row.status].action }}</el-button>
            <el-button v-if="canConfirm(row)" link type="danger" @click="reject(row)">驳回</el-button>
            <el-button v-if="row.status === 'REJECTED'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > query.size" v-model:current-page="query.current" v-model:page-size="query.size"
        :total="total" layout="total, prev, pager, next" @current-change="load" style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <DocDrawer v-model="detailVisible" kind="领料申请" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['一线提交', '采购确认', '仓管确认', '财务确认', '生成出库申请']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'">
      <template #status><el-tag v-if="detail" :type="STATUS[detail.status]?.type || 'info'" size="small">{{ STATUS[detail.status]?.label }}</el-tag></template>

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="申请单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="STATUS[detail.status]?.type || 'info'" size="small">{{ STATUS[detail.status]?.label }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="公司">{{ detail.orgName || detail.orgId }}</el-descriptions-item>
          <el-descriptions-item label="领料人">{{ detail.pickerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请日期">{{ detail.applyDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ detail.projectName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务分类">{{ detail.bizType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="施工地点">{{ detail.site || '-' }}</el-descriptions-item>
          <el-descriptions-item label="填表/经办人">{{ detail.applicantName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="生成出库申请" :span="2">{{ detail.outReqNo || '（未生成）' }}</el-descriptions-item>
        </el-descriptions>

        <div class="trail">
          <span :class="{ done: detail.purchaseBy }">采购：{{ detail.purchaseBy ? `${detail.purchaseBy} ${fmtT(detail.purchaseAt)}` : '待确认' }}</span>
          <el-icon><Right /></el-icon>
          <span :class="{ done: detail.warehouseBy }">仓管：{{ detail.warehouseBy ? `${detail.warehouseBy} ${fmtT(detail.warehouseAt)}` : '待确认' }}</span>
          <el-icon><Right /></el-icon>
          <span :class="{ done: detail.financeBy }">财务：{{ detail.financeBy ? `${detail.financeBy} ${fmtT(detail.financeAt)}` : '待确认' }}</span>
        </div>
        <el-alert v-if="detail.status === 'REJECTED'" type="error" :closable="false" class="mb-3"
          :title="`已驳回 · ${detail.rejectBy || ''} ${fmtT(detail.rejectAt)}　原因：${detail.rejectReason || '-'}`" />

        <el-table max-height="min(56vh, 560px)" :data="detail.lines" border size="small" class="readable-table" empty-text="无明细" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="序号" type="index" width="60" align="center" />
          <el-table-column fixed="left" label="物料编码" prop="materialCode" width="120" />
          <el-table-column fixed="left" label="物料名称" prop="materialName" min-width="150" show-overflow-tooltip />
          <el-table-column label="规格型号" prop="materialSpec" min-width="140" show-overflow-tooltip />
          <el-table-column label="单位" prop="materialUnit" width="70" align="center" />
          <el-table-column label="申领数量" prop="qty" width="100" align="right" />
          <el-table-column label="当前库存" width="100" align="right">
            <template #default="{ row }">
              <span :class="{ over: Number(row.qty) > Number(row.stockQty || 0) }">{{ fmtQty(row.stockQty) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" prop="remark" min-width="120" show-overflow-tooltip />
        </el-table>
        <DocAttachments v-if="detail" biz-type="LR" :biz-id="detail.id" />
      </template>
      <template #footer-left>
        <el-button v-if="detail && canConfirm(detail)" type="danger" plain @click="reject(detail, true)">驳回</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown v-if="detail" label="打印工程材料计划表" :type="detail && canConfirm(detail) ? '' : 'primary'" default-orientation="landscape"
          @print="(paper, o) => printLr('print', paper, o)" @export="(paper, o) => printLr('export', paper, o)" />
        <el-button v-if="detail && canConfirm(detail)" type="primary" @click="confirm(detail, true)">{{ STATUS[detail.status].action }}</el-button>
      </template>

    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { materialReqApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { printDocument, exportPrintDocumentXls, type PrintColumn, type PaperAction, type PaperSize, type PrintOrientation } from '@/utils/printExport'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'

const user = useUserStore()

const STATUS: Record<string, any> = {
  SUBMITTED:    { label: '待采购确认', type: 'warning', role: 'purchaser', action: '采购确认' },
  PURCHASE_OK:  { label: '待仓管确认', type: 'warning', role: 'warehouse', action: '仓管确认' },
  WAREHOUSE_OK: { label: '待财务确认', type: 'warning', role: 'material_accountant', action: '财务确认' },
  APPROVED:     { label: '已通过', type: 'success' },
  REJECTED:     { label: '已驳回', type: 'danger' }
}

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive<any>({ current: 1, size: 20, status: '', keyword: '' })

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }
function fmtT(t: any) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function canConfirm(row: any) {
  const s = STATUS[row.status]
  return !!s?.role && (user.hasRole(s.role) || user.hasRole('admin'))
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...Object.entries(STATUS).map(([k, v]) => ({ label: v.label, value: k }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => materialReqApi.requestList(q), Object.keys(STATUS))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try {
    const r: any = await materialReqApi.requestList(query)
    rows.value = r.records || []
    total.value = r.total || 0
  } finally { loading.value = false }
}

const detailVisible = ref(false)
const detail = ref<any>(null)
async function openDetail(id: number) {
  detail.value = await materialReqApi.requestDetail(id) as any
  detailVisible.value = true
}

async function confirm(row: any, fromDetail = false) {
  const action = STATUS[row.status]?.action || '确认'
  await ElMessageBox.confirm(`确定${action}该领料申请「${row.docNo}」?${row.status === 'WAREHOUSE_OK' ? '\n财务确认后将自动生成正式出库申请（已审批/可发料）。' : ''}`, '提示', { type: 'warning' })
  await materialReqApi.requestConfirm(row.id)
  ElMessage.success('已确认')
  if (fromDetail) detailVisible.value = false
  await load()
}

async function reject(row: any, fromDetail = false) {
  const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回领料申请', { inputPlaceholder: '驳回原因', inputValidator: v => !!v?.trim() || '请填写原因' })
  await materialReqApi.requestReject(row.id, value)
  ElMessage.success('已驳回')
  if (fromDetail) detailVisible.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除已驳回的领料申请「${row.docNo}」?删除后不可恢复（一线如需重报，重新提交即可）。`, '提示', { type: 'warning' })
  await materialReqApi.requestDelete(row.id)
  ElMessage.success('已删除')
  await load()
}

function printLr(action: PaperAction, paper: PaperSize, orientation: PrintOrientation) {
  const doc = detail.value
  if (!doc) return
  const columns: PrintColumn[] = [
    { label: '序号', value: (_r, i) => i + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: (l: any) => l.materialCode || '', width: '12%' },
    { label: '物料名称', value: (l: any) => l.materialName || '', width: '18%' },
    { label: '物料型号', value: (l: any) => l.materialSpec || '', width: '14%' },
    { label: '单位', value: (l: any) => l.materialUnit || '', width: '6%', align: 'center' },
    { label: '申领数量', value: (l: any) => fmtQty(l.qty), width: '9%', align: 'right' },
    { label: '实领数量', value: () => '', width: '9%', align: 'right' },
    { label: '未领数量', value: () => '', width: '9%', align: 'right' },
    { label: '备注', value: (l: any) => l.remark || '', width: '18%' }
  ]
  // 采购员/材料会计/仓管 线上确认 → 名字自动印；网点负责人默认取提报人(发起人)+提交时间；其余手签
  const sign = (label: string, by: string, at: string) =>
    ({ label, lines: [by ? `${by}（线上确认）` : '签字：', by ? fmtT(at) : '日期：'] })
  const requester = doc.applicantName
    ? { label: '网点负责人', lines: [`${doc.applicantName}（提报）`, fmtT(doc.createdAt || doc.applyDate)] }
    : { label: '网点负责人', lines: ['签字：', '日期：'] }
  const options = {
    orgId: doc?.orgId,
    title: '工程材料计划表',
    paper,
    orientation,
    fileName: `工程材料计划表-${doc.docNo}`,
    fields: [
      { label: '工程名称', value: doc.projectName || '', span: 2 },
      { label: '业务类型', value: doc.bizType || '' },
      { label: '施工地点', value: doc.site || '', span: 2 },
      { label: '申请日期', value: doc.applyDate || '' },
      { label: '领料人', value: doc.pickerName || '' },
      { label: '出库申请单号', value: doc.outReqNo || '（待财务确认后生成）' },
      { label: '填表/经办人', value: doc.applicantName || '' },
      { label: '仓库', value: doc.warehouseName || '' }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      requester,
      { label: '片区经理', lines: ['签字：', '日期：'] },
      { label: '主管副总', lines: ['签字：', '日期：'] },
      { label: '总经理', lines: ['签字：', '日期：'] },
      sign('采购员', doc.purchaseBy, doc.purchaseAt),
      sign('材料会计', doc.financeBy, doc.financeAt),
      sign('仓管', doc.warehouseBy, doc.warehouseAt)
    ],
    note: '1、申请人依据签批完整的工程材料计划表至仓库申领物资，同时将此单复印后交至财务部门。\n'
      + '2、补充、退库材料需单独填写。\n'
      + '3、申领数量由申请人填写，实领、未领数量由仓库管理员填写确认。'
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

const route = useRoute()
onMounted(() => {
  // 从「待处理事项」点进来：按单号过滤
  if (route.query.keyword) query.keyword = String(route.query.keyword)
  load()
})

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.orgName, d.pickerName && `领料人 ${d.pickerName}`, d.projectName, d.applyDate && `申请 ${d.applyDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"SUBMITTED": 1, "PURCHASE_OK": 2, "WAREHOUSE_OK": 3, "APPROVED": 5, "REJECTED": 1} as Record<string, number>)[detail.value?.status] ?? 0)

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 申领数量: { field: 'qty', type: 'qty' } })
</script>

<style scoped>
.mb-3 { margin-bottom: 12px; }
.trail { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; background: #f6f8fb; border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; font-size: 13px; color: #9aa7b8; }
.trail .done { color: #2f9e6f; font-weight: 600; }
.over { color: #d9542b; }
</style>
