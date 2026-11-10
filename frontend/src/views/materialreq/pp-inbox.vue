<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购计划收件箱</div>
        <div class="page-sub">{{ viewMode === 'summary' ? '公司整体计划：期间内多份提报按物料自动合并，配全公司库存与建议采购量，可直接打印汇总计划表。' : '业务提报的采购需求汇总在此；采购结合「当前库存 / 最低库存」参考建议采购量、确定最终采购量后确认打印。仅计划，不动库存。' }}</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-radio-group v-model="viewMode" @change="viewMode === 'summary' && loadSummary()">
          <el-radio-button value="docs">单条提报</el-radio-button>
          <el-radio-button value="summary">公司汇总</el-radio-button>
        </el-radio-group>
        <el-button v-if="viewMode === 'docs'" @click="load">刷新</el-button>
      </el-space>
    </div>

    <!-- 公司整体汇总视图 -->
    <el-card v-if="viewMode === 'summary'" shadow="never">
      <!-- 筛选条件收进抽屉,与供应商/物料/库存等 8 个页面一致:点「查询」弹出抽屉选条件 -->
      <QueryDrawer @search="loadSummary" @reset="resetSummaryQuery" @clear="k => { if (k === 'sumOrgId') sumOrgId = null; if (k === 'sumRange') sumRange = null }">
        <el-form :inline="true" size="default">
          <!-- 只有一家公司时也要显示:让用户看得见"现在在查哪家",出问题时能自己切换排查(原 length>1 隐藏,
               叠加后端用部门org查不到数据,采购既看不到结果也没有补救入口) -->
          <el-form-item data-q="sumOrgId" v-if="sumCompanies.length" label="公司">
            <el-select v-model="sumOrgId" filterable style="width:190px">
              <el-option v-for="c in sumCompanies" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="sumRange" label="提报期间">
            <el-date-picker v-model="sumRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:250px" />
          </el-form-item>
          <el-form-item data-q-fixed label="计入">
            <!-- 用 :label 绑值(Element Plus 复选框组靠 label 做选中比对;只写 value 会导致默认已选中却不回显) -->
            <el-checkbox-group v-model="sumStatuses">
              <el-checkbox :label="'SUBMITTED'">待确认</el-checkbox>
              <el-checkbox :label="'CONFIRMED'">已确认</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </el-form>
      </QueryDrawer>
      <div style="margin-bottom:8px">
        <PrintExportDropdown label="打印汇总计划表" default-orientation="landscape" :disabled="!sumRows.length"
          @print="(paper, orientation) => printSummary('print', paper, orientation)"
          @export="(paper, orientation) => printSummary('export', paper, orientation)" />
      </div>
      <!-- show-summary:底部合计行,让采购一眼看到整张计划总共要买多少(照用全站统一的 makeLineSummary) -->
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="sumRows" v-loading="sumLoading" empty-text="所选期间没有采购提报"
                show-summary :summary-method="sumSummaryMethod">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" label="物料编码" prop="code" width="120" show-overflow-tooltip />
        <el-table-column label="物料名称" prop="name" min-width="170" show-overflow-tooltip />
        <el-table-column label="规格型号" prop="spec" min-width="110" show-overflow-tooltip />
        <el-table-column label="单位" prop="unit" width="64" />
        <el-table-column label="需求合计" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.demand_qty) }}</template></el-table-column>
        <el-table-column label="现有库存" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.stock_qty) }}</template></el-table-column>
        <el-table-column label="最低库存" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.safety_qty) }}</template></el-table-column>
        <el-table-column label="建议采购" width="100" align="right"><template #default="{ row }"><b style="color:#2f8fd0">{{ fmtQty(row.suggest_qty) }}</b></template></el-table-column>
        <el-table-column label="已确认量" width="100" align="right"><template #default="{ row }">{{ fmtQty(row.final_qty) }}</template></el-table-column>
        <el-table-column label="提报单数" prop="req_cnt" width="90" align="right" />
        <el-table-column label="提报人" prop="applicants" min-width="130" show-overflow-tooltip />
      </el-table>
      <el-alert type="info" :closable="false" show-icon style="margin-top:10px"
        title="口径：需求合计=期间内所有提报的同一物料求和（驳回不计）；现有库存=该公司全部仓库合计；建议采购=需求合计+最低库存−现有库存(不小于0)；已确认量=采购在单条提报里确认过的最终量合计。" />
    </el-card>

    <el-card v-if="viewMode === 'docs'" shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="load" />
      <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:150px" @change="load">
            <el-option v-for="(v, k) in STATUS" :key="k" :value="k" :label="v.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="搜索">
          <el-input v-model="query.keyword" placeholder="单号/提报人/部门" clearable style="width:200px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table max-height="calc(100vh - 300px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无采购计划">
        <el-table-column prop="docNo" label="计划单号" width="180">
          <template #default="{ row }"><button type="button" class="doc-link" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column label="公司" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || row.orgId }}</template>
        </el-table-column>
        <el-table-column prop="applicantName" label="提报人" width="110" />
        <el-table-column prop="dept" label="部门" min-width="120" show-overflow-tooltip />
        <el-table-column label="仓库" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="STATUS[row.status]?.type || 'info'" size="small">{{ STATUS[row.status]?.label || row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">{{ row.status === 'SUBMITTED' ? '处理' : '查看' }}</el-button>
            <el-button v-if="row.status === 'REJECTED'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > query.size" v-model:current-page="query.current" v-model:page-size="query.size"
        :total="total" layout="total, prev, pager, next" @current-change="load" style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <DocDrawer v-model="detailVisible" kind="采购计划提报" :doc-no="detail?.docNo" :status="detail?.status" :sub="detailSub" :steps="['提报', '采购确认']" :step-active="detailStep" :step-rejected="detail?.status === 'REJECTED'">
      <template #status><el-tag v-if="detail" :type="STATUS[detail.status]?.type || 'info'" size="small">{{ STATUS[detail.status]?.label }}</el-tag></template>

      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="计划单号">{{ detail.docNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="STATUS[detail.status]?.type || 'info'" size="small">{{ STATUS[detail.status]?.label }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="公司">{{ detail.orgName || detail.orgId }}</el-descriptions-item>
          <el-descriptions-item label="提报人">{{ detail.applicantName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ detail.dept || '-' }}</el-descriptions-item>
          <el-descriptions-item label="参照仓库">{{ detail.warehouseName || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.confirmBy" label="采购确认" :span="3">{{ detail.confirmBy }} {{ fmtT(detail.confirmAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detail.status === 'REJECTED'" type="error" :closable="false" class="mb-3"
          :title="`已驳回 · ${detail.rejectBy || ''} ${fmtT(detail.rejectAt)}　原因：${detail.rejectReason || '-'}`" />

        <el-table max-height="min(56vh, 560px)" :data="detail.lines" border size="small" class="readable-table" empty-text="无明细" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="序号" type="index" width="56" align="center" />
          <el-table-column fixed="left" label="物料编码" prop="materialCode" width="118" />
          <el-table-column fixed="left" label="物料名称" prop="materialName" min-width="140" show-overflow-tooltip />
          <el-table-column label="规格型号" prop="materialSpec" min-width="130" show-overflow-tooltip />
          <el-table-column label="单位" prop="materialUnit" width="64" align="center" />
          <el-table-column label="当前库存" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.stockQty) }}</template></el-table-column>
          <el-table-column label="最低库存" width="90" align="right"><template #default="{ row }">{{ fmtQty(row.safetyQty) }}</template></el-table-column>
          <el-table-column label="需求量" prop="demandQty" width="90" align="right" />
          <el-table-column label="建议采购" width="90" align="right"><template #default="{ row }"><b style="color:#2f8fd0">{{ fmtQty(row.suggestQty) }}</b></template></el-table-column>
          <el-table-column label="最终采购量" width="140" align="right">
            <template #default="{ row }">
              <el-input-number v-if="canConfirm" v-model="row._final" :min="0" :precision="2" size="small" style="width:120px" />
              <span v-else>{{ fmtQty(row.finalQty != null ? row.finalQty : row.suggestQty) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <DocAttachments v-if="detail" biz-type="PP" :biz-id="detail.id" />
      </template>
      <template #footer-left>
        <el-button v-if="canConfirm" type="danger" plain @click="reject">驳回</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown v-if="detail" label="打印采购计划表" :type="canConfirm ? '' : 'primary'" default-orientation="landscape"
          @print="(paper, o) => printPp('print', paper, o)" @export="(paper, o) => printPp('export', paper, o)" />
        <el-button v-if="canConfirm" type="primary" @click="confirm">确认采购计划</el-button>
      </template>

    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { materialReqApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { printDocument, exportPrintDocumentXls, type PrintColumn, type PaperAction, type PaperSize, type PrintOrientation } from '@/utils/printExport'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import { makeLineSummary } from '@/utils/tableSummary'

const user = useUserStore()
const STATUS: Record<string, any> = {
  SUBMITTED: { label: '待采购确认', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  REJECTED:  { label: '已驳回', type: 'danger' }
}

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive<any>({ current: 1, size: 20, status: '', keyword: '' })

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }

// ── 公司整体汇总视图 ──
const viewMode = ref<'docs' | 'summary'>('docs')
const sumLoading = ref(false)
const sumRows = ref<any[]>([])
const sumCompanies = ref<any[]>([])
const sumOrgId = ref<number | null>(null)
const sumOrgName = ref('')
const sumRange = ref<any>(null)
const sumStatuses = ref<string[]>(['SUBMITTED', 'CONFIRMED'])

// 汇总表底部合计行:数量类列纵向加总,让采购一眼看到整张计划总共要买多少。
// 按列 label 匹配(与全站 makeLineSummary 一致);「提报单数」是各物料被提报的次数,纵向相加无业务含义,故不合计。
const sumSummaryMethod = makeLineSummary({
  需求合计: { field: 'demand_qty', type: 'qty' },
  现有库存: { field: 'stock_qty', type: 'qty' },
  最低库存: { field: 'safety_qty', type: 'qty' },
  建议采购: { field: 'suggest_qty', type: 'qty' },
  已确认量: { field: 'final_qty', type: 'qty' }
})

/** 抽屉「重置」:恢复默认筛选(本月 + 待确认/已确认全计),公司保持当前不动 */
function resetSummaryQuery() {
  sumRange.value = null
  sumStatuses.value = ['SUBMITTED', 'CONFIRMED']
  loadSummary()
}
async function loadSummary() {
  sumLoading.value = true
  try {
    const r: any = await materialReqApi.ppSummary({
      orgId: sumOrgId.value || undefined,
      statuses: sumStatuses.value.join(','),
      startDate: sumRange.value?.[0] || undefined,
      endDate: sumRange.value?.[1] || undefined
    })
    sumRows.value = r.rows || []
    sumCompanies.value = r.companies || []
    sumOrgId.value = r.orgId
    sumOrgName.value = r.orgName || ''
  } finally { sumLoading.value = false }
}

function printSummary(action: PaperAction, paper: PaperSize, orientation: PrintOrientation) {
  const columns: PrintColumn[] = [
    { label: '序号', value: (_r: any, i: number) => i + 1, width: '4%', align: 'center' },
    { label: '物料编码', value: (l: any) => l.code || '', width: '10%' },
    { label: '物料名称', value: (l: any) => l.name || '', width: '17%' },
    { label: '规格型号', value: (l: any) => l.spec || '', width: '12%' },
    { label: '单位', value: (l: any) => l.unit || '', width: '5%', align: 'center' },
    { label: '需求合计', value: (l: any) => fmtQty(l.demand_qty), width: '8%', align: 'right' },
    { label: '现有库存', value: (l: any) => fmtQty(l.stock_qty), width: '8%', align: 'right' },
    { label: '建议采购', value: (l: any) => fmtQty(l.suggest_qty), width: '8%', align: 'right' },
    { label: '计划采购量', value: () => '', width: '9%', align: 'right' },
    { label: '提报人', value: (l: any) => l.applicants || '', width: '10%' },
    { label: '备注', value: () => '', width: '9%' }
  ]
  const range = sumRange.value?.length === 2 ? `${sumRange.value[0]} 至 ${sumRange.value[1]}` : '全部期间'
  const options = {
    orgId: sumOrgId.value,
    title: '采购计划汇总表',
    paper,
    orientation,
    fileName: `采购计划汇总表-${sumOrgName.value || ''}`,
    fields: [
      { label: '公司', value: sumOrgName.value || '', span: 2 },
      { label: '编制日期', value: new Date().toISOString().slice(0, 10) },
      { label: '提报期间', value: range, span: 2 },
      { label: '物料种数', value: String(sumRows.value.length) }
    ],
    columns,
    rows: sumRows.value,
    signatures: [
      { label: '编制(采购)', lines: ['签字：', '日期：'] },
      { label: '审核', lines: ['签字：', '日期：'] },
      { label: '批准', lines: ['签字：', '日期：'] }
    ],
    note: '口径：需求合计=期间内全部提报按物料合并（驳回不计）；现有库存=公司全部仓库合计；建议采购=需求合计+最低库存−现有库存(不小于0)；「计划采购量」由采购确定后手填。'
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}
function fmtT(t: any) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...Object.entries(STATUS).map(([k, v]) => ({ label: v.label, value: k }))]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => materialReqApi.ppList(q), Object.keys(STATUS))

async function load() {
  refreshStatusCounts(query)
  loading.value = true
  try { const r: any = await materialReqApi.ppList(query); rows.value = r.records || []; total.value = r.total || 0 }
  finally { loading.value = false }
}

const detailVisible = ref(false)
const detail = ref<any>(null)
const canConfirm = computed(() => detail.value?.status === 'SUBMITTED' && (user.hasRole('purchaser') || user.hasRole('admin')))

async function openDetail(id: number) {
  const d: any = await materialReqApi.ppDetail(id)
  ;(d.lines || []).forEach((l: any) => { l._final = l.finalQty != null ? Number(l.finalQty) : Number(l.suggestQty || 0) })
  detail.value = d
  detailVisible.value = true
}

async function confirm() {
  await ElMessageBox.confirm('确认该采购计划?确认后可打印采购计划表。', '提示', { type: 'warning' })
  const lines = (detail.value.lines || []).map((l: any) => ({ lineId: l.id, finalQty: l._final }))
  await materialReqApi.ppConfirm(detail.value.id, lines)
  ElMessage.success('已确认')
  detailVisible.value = false
  await load()
}
async function reject() {
  const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回采购计划', { inputValidator: v => !!v?.trim() || '请填写原因' })
  await materialReqApi.ppReject(detail.value.id, value)
  ElMessage.success('已驳回')
  detailVisible.value = false
  await load()
}
async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除已驳回的采购计划「${row.docNo}」?`, '提示', { type: 'warning' })
  await materialReqApi.ppDelete(row.id)
  ElMessage.success('已删除')
  await load()
}

function printPp(action: PaperAction, paper: PaperSize, orientation: PrintOrientation) {
  const doc = detail.value
  if (!doc) return
  const columns: PrintColumn[] = [
    { label: '序号', value: (_r, i) => i + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: (l: any) => l.materialCode || '', width: '11%' },
    { label: '物料名称', value: (l: any) => l.materialName || '', width: '16%' },
    { label: '规格型号', value: (l: any) => l.materialSpec || '', width: '13%' },
    { label: '单位', value: (l: any) => l.materialUnit || '', width: '5%', align: 'center' },
    { label: '当前库存', value: (l: any) => fmtQty(l.stockQty), width: '9%', align: 'right' },
    { label: '最低库存', value: (l: any) => fmtQty(l.safetyQty), width: '9%', align: 'right' },
    { label: '需求量', value: (l: any) => fmtQty(l.demandQty), width: '8%', align: 'right' },
    { label: '建议采购', value: (l: any) => fmtQty(l.suggestQty), width: '8%', align: 'right' },
    { label: '最终采购量', value: (l: any) => fmtQty(l.finalQty != null ? l.finalQty : l._final), width: '9%', align: 'right' },
    { label: '备注', value: (l: any) => l.remark || '', width: '7%' }
  ]
  const reporter = doc.applicantName
    ? { label: '提报人', lines: [`${doc.applicantName}（提报）`, fmtT(doc.createdAt)] }
    : { label: '提报人', lines: ['签字：', '日期：'] }
  const options = {
    orgId: doc?.orgId,
    title: '采购计划表',
    paper,
    orientation,
    fileName: `采购计划表-${doc.docNo}`,
    fields: [
      { label: '计划单号', value: doc.docNo },
      { label: '提报人', value: doc.applicantName || '' },
      { label: '部门', value: doc.dept || '' },
      { label: '参照仓库', value: doc.warehouseName || '' },
      { label: '采购确认', value: doc.confirmBy ? `${doc.confirmBy} ${fmtT(doc.confirmAt)}` : '' }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      reporter,
      { label: '采购', lines: [doc.confirmBy ? `${doc.confirmBy}（线上确认）` : '签字：', doc.confirmBy ? fmtT(doc.confirmAt) : '日期：'] },
      { label: '审批', lines: ['签字：', '日期：'] }
    ],
    note: '采购计划表用于结合库存/最低库存制定合理采购量，供采购下单参考。'
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

const route = useRoute()
onMounted(() => {
  if (route.query.keyword) query.keyword = String(route.query.keyword)
  load()
})

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detail.value
  if (!d) return ''
  return [d.orgName, d.applicantName && `提报人 ${d.applicantName}`, d.dept, d.warehouseName && `参照 ${d.warehouseName}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"SUBMITTED": 1, "CONFIRMED": 2, "REJECTED": 1} as Record<string, number>)[detail.value?.status] ?? 0)

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 需求量: { field: 'demandQty', type: 'qty' }, 建议采购: { field: 'suggestQty', type: 'qty' }, 最终采购量: { field: '_final', type: 'qty' } })
</script>

<style scoped>
.mb-3 { margin-bottom: 12px; }
</style>
