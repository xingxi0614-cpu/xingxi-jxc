<template>
  <div class="page period-page">
    <div class="page-head period-head">
      <div>
        <div class="page-title">月末结账</div>
        <div class="page-sub">结账流程：① 选月份「一键对账检查」→ ② 处理完所有异常项（单据须全部审批完成、无未处理差异）→ ③ 满足条件后「结账」锁定，当月单据不可再改，除非「反结账」。</div>
      </div>
      <el-space wrap>
        <el-date-picker
          v-model="period"
          type="month"
          format="YYYY-MM"
          value-format="YYYY-MM"
          :clearable="false"
          :disabled="closing || reopening"
          placeholder="选择期间"
          style="width: 140px"
        />
        <el-button :loading="recordLoading" @click="refreshCheck">刷新</el-button>
        <el-button v-if="user.hasFinanceAccessOrAdmin() && isClosed" :loading="reopening" type="danger" plain @click="reopenPeriod">反结账</el-button>
        <el-button :loading="loading" @click="runCheck()">一键对账检查</el-button>
        <el-tooltip :disabled="canClose || !checked" :content="`尚有 ${blockerItems.length} 项拦截待处理，处理完才能结账`" placement="bottom">
          <span>
            <el-button :loading="closing" type="primary" :disabled="!canClose" @click="closePeriod">结账锁定</el-button>
          </span>
        </el-tooltip>
      </el-space>
    </div>

    <!-- 进度：检查 → 处理拦截项 → 结账锁定（2026-10-17 界面改版，原型 Close_Index） -->
    <el-card shadow="never" class="close-steps-card">
      <ol class="close-steps" aria-label="结账进度">
        <li :class="stepCls(0)"><b>{{ stepMark(0) }}</b>对账检查</li>
        <li class="sl" aria-hidden="true"></li>
        <li :class="stepCls(1)"><b>{{ stepMark(1) }}</b>处理拦截项<template v-if="checked && blockerItems.length">（还剩 {{ blockerItems.length }} 项）</template></li>
        <li class="sl" aria-hidden="true"></li>
        <li :class="stepCls(2)"><b>{{ stepMark(2) }}</b>结账锁定</li>
      </ol>
    </el-card>
    <KpiCards :items="kpis" />

    <el-card shadow="never" class="check-panel">
      <template #header>
        <div class="panel-title">
          <span>对账检查明细</span>
          <small>拦截项必须处理完才能结账；提醒项不拦，建议处理。</small>
        </div>
      </template>
      <StatusChips v-model="levelFilter" :options="LEVEL_OPTIONS" :counts="levelCounts" />

      <!-- row-key 必须给：el-table 默认按行下标复用行，行数不变时不重建单元格，
           于是「收起」只把链接文字改了、标签却收不回去（2026-09-02 实测） -->
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false"
        :data="filteredItems"
        :row-key="(row: any) => `${row.docType}|${row.message}|${row._expanded}`"
        v-loading="loading"
        :empty-text="checked ? '未发现异常，可执行结账' : '点击一键检查后显示结果'"
      >
        <el-table-column label="级别" width="110">
          <template #default="{ row }">
            <el-tag :type="row.level === 'BLOCKER' ? 'danger' : 'warning'">
              {{ row.level === 'BLOCKER' ? '拦截' : '提醒' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="module" label="检查对象" width="140" />
        <el-table-column prop="message" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="count" label="数量" width="90" align="right" />
        <!-- 表格是 :fit="false"，min-width 不会随屏幕变宽而伸展，所以这里要给足。
             实测：单号标签 196px，el-table 的 .cell 左右各 12px 内边距，
             420 时可用宽仅 396，比两个标签+间距(398)差 2px，于是又竖成一列。460 才排得开。 -->
        <el-table-column label="问题单据（点单号直接去处理）" min-width="460">
          <template #default="{ row }">
            <!-- 不用 el-space：它是 inline-flex，宽度会收缩成 396px，比两个单号标签(196×2+间距)差几像素，
                 结果每行只能放一个、6 条单号竖成一列。用占满单元格的 flex 容器才排得开。 -->
            <div class="doc-tag-wrap">
              <el-tag
                v-for="sample in row._visibleSamples"
                :key="sample"
                size="small"
                :effect="canOpen(row) ? 'light' : 'plain'"
                :type="canOpen(row) ? 'primary' : 'info'"
                :class="{ 'doc-tag-clickable': canOpen(row) }"
                @click="canOpen(row) && openDoc(row, sample)"
              >{{ sample }}</el-tag>
              <el-link
                v-if="row._foldable"
                type="primary"
                :underline="false"
                class="sample-toggle"
                @click="toggleExpand(row)"
              >{{ row._expanded ? '收起' : `展开全部 ${row._sampleTotal} 条` }}</el-link>
              <span v-if="row.count > row._sampleTotal" class="muted-text sample-more">
                共 {{ row.count }} 条，此处列出前 {{ row._sampleTotal }} 条
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="DOC_ROUTE[row.docType]" link type="primary" @click="goFix(row)">去处理 →</el-button>
            <span v-else class="muted-text">对应模块核对</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="records-panel">
      <template #header>
        <div class="panel-title">
          <span>结账记录</span>
          <small>显示当前权限范围内的期间结账状态。</small>
        </div>
      </template>
      <el-table class="readable-table" border :fit="false" :data="records" v-loading="recordLoading" empty-text="暂无结账记录">
        <el-table-column prop="period_month" label="期间" width="110" />
        <el-table-column prop="orgName" label="组织" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag type="success" effect="plain">{{ row.status === 'CLOSED' ? '已结账' : row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issue_count" label="异常" align="right" width="90" />
        <el-table-column prop="warning_count" label="提醒" align="right" width="90" />
        <el-table-column prop="closedByName" label="结账人" width="130" show-overflow-tooltip />
        <el-table-column prop="closed_at" label="结账时间" width="180" />
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import KpiCards from '@/components/KpiCards.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { periodCloseApi } from '@/api'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const router = useRouter()
const loading = ref(false)
const closing = ref(false)
const reopening = ref(false)
const recordLoading = ref(false)
const checked = ref(false)
const result = ref<any>(null)
const records = ref<any[]>([])
const period = ref(currentMonth())

// 检查项的单据类型 → 对应单据列表路由，用于“去处理”一键跳转
const DOC_ROUTE: Record<string, string> = {
  PO: '/purchase/po', RECV: '/inbound/recv', PRET: '/inbound/purchase-return', INV: '/invoice/list',
  OREQ: '/outbound/req', OREQ_REV: '/outbound/req', OUT: '/outbound/out',
  RETURN: '/outbound/return', TRANSFER: '/inventory/transfer',
  LOAN: '/outbound/loan', STOCKTAKE: '/inventory/stocktake',
  // 销售三张单后端一直在查（addStatusIssue 里有 SALE_OUT/SALE_INV/SALE_RETURN），
  // 但这张表以前没登记，于是它们只能显示「对应模块核对」，点不动也跳不走（2026-09-02 补）
  SALE_OUT: '/sale/out', SALE_INV: '/sale/invoice', SALE_RETURN: '/sale/return',
  CANCEL_REQ: '/work/todo', EST_DIFF: '/report/estimate-diff', BALANCE: '/inventory/balance',
  // 2026-10-16:暂估挂账超 90 天 → 暂估清单只看超期
  EST_AGING: '/report/estimate'
}
function targetQuery(row: any, keyword: string) {
  if (row.docType === 'EST_DIFF') return { periodMonth: period.value }
  if (row.docType === 'EST_AGING') return { overdue: '1' }
  if (row.docType === 'BALANCE') return { auto: '1', abnormal: '1' }
  if (row.docType === 'CANCEL_REQ') return { auto: '1', cancel: '1', keyword }
  return { auto: '1', keyword, ...(row.docType === 'OREQ_REV' ? { revision: '1' } : {}) }
}
function goFix(row: any) {
  const path = DOC_ROUTE[row?.docType]
  if (!path) { ElMessage.info('该项请在对应模块核对处理'); return }
  if (row.count > (row.samples || []).length && canOpen(row)) {
    ElMessage.info(`本次定位已列出的${row.samples.length}个单号；处理后返回结账页重新检查可继续处理其余单据`)
  }
  router.push({ path, query: { ...targetQuery(row, (row.samples || []).join(',')), check: String(result.value?.checkedAt || Date.now()) } })
}

// 问题单据：默认只铺开 6 个，避免 16 条挤爆表格行；点「展开全部」看完整清单
const SAMPLE_FOLD = 6
const expanded = ref<Record<string, boolean>>({})
function rowKey(row: any) { return String(row?.docType) + String(row?.message) }
function toggleExpand(row: any) {
  const k = rowKey(row)
  expanded.value = { ...expanded.value, [k]: !expanded.value[k] }
}
/** 只有能定位到具体单据列表的检查项才让点：暂估差异、库存余额这类的“单号”其实是描述文字，点了没意义 */
function canOpen(row: any) { return !!DOC_ROUTE[row?.docType] && !['EST_DIFF', 'BALANCE', 'EST_AGING'].includes(row.docType) }
/** 点单号 → 跳到对应单据列表并用单号搜索，直接定位到这一张单 */
function openDoc(row: any, docNo: string) {
  const path = DOC_ROUTE[row?.docType]
  if (!path || !docNo) return
  router.push({ path, query: { ...targetQuery(row, docNo), check: String(result.value?.checkedAt || Date.now()) } })
}

// 异常(BLOCKER)排在提醒(WARNING)前面，先处理硬性阻断项
const items = computed(() => {
  const list = [...(result.value?.items || [])]
  return list.sort((a, b) => (a.level === 'BLOCKER' ? 0 : 1) - (b.level === 'BLOCKER' ? 0 : 1))
})

/**
 * 表格真正渲染用的数据：把「展开/收起」的结果直接算进每一行。
 *
 * <p>不能在模板里调 visibleSamples(row) —— el-table 只在 :data 变化时才重渲染单元格，
 * 展开状态是表格外部的 ref，驱动不了它：实测点「收起」时链接文字会变、标签却收不回去
 * （2026-09-02 本地浏览器实测复现）。把状态并进 data，表格才会跟着刷新。</p>
 */
const displayItems = computed(() => items.value.map((row: any) => {
  const samples: string[] = row.samples || []
  const open = !!expanded.value[rowKey(row)]
  return {
    ...row,
    _expanded: open,
    _visibleSamples: open ? samples : samples.slice(0, SAMPLE_FOLD),
    _foldable: samples.length > SAMPLE_FOLD,
    _sampleTotal: samples.length
  }
}))
const blockerItems = computed(() => items.value.filter((i: any) => i.level === 'BLOCKER'))

// 级别页签：全部 / 拦截 / 提醒（数字 = 条数）
const levelFilter = ref('')
const LEVEL_OPTIONS = [{ label: '全部', value: '' }, { label: '拦截', value: 'BLOCKER' }, { label: '提醒', value: 'WARNING' }]
const levelCounts = computed(() => checked.value
  ? { '': items.value.length, BLOCKER: blockerItems.value.length, WARNING: items.value.length - blockerItems.value.length }
  : {})
const filteredItems = computed(() => levelFilter.value ? displayItems.value.filter((r: any) => (levelFilter.value === 'BLOCKER') === (r.level === 'BLOCKER')) : displayItems.value)

// 步骤：没检查=第 1 步；检查了有拦截=第 2 步；无拦截=可以结账（第 3 步）；已结账=全部完成
const stepIndex = computed(() => isClosed.value ? 3 : !checked.value ? 0 : blockerItems.value.length ? 1 : 2)
function stepCls(i: number) { return i < stepIndex.value ? 'done' : i === stepIndex.value ? 'cur' : '' }
function stepMark(i: number) { return i < stepIndex.value ? '✓' : String(i + 1) }

const kpis = computed(() => [
  { label: '期间状态', value: isClosed.value ? '已结账' : '未结账', hint: `${period.value} · 结账后当月单据禁止修改`, tone: isClosed.value ? 'green' as const : undefined },
  { label: '拦截（必须处理）', value: checked.value ? `${blockerItems.value.length} 项` : '—', hint: checked.value ? (blockerItems.value.length ? '清零后「结账锁定」才可点' : '没有拦截项，可以结账') : '先点「一键对账检查」', tone: checked.value && blockerItems.value.length ? 'red' as const : undefined },
  { label: '提醒（不拦）', value: checked.value ? `${items.value.length - blockerItems.value.length} 项` : '—', hint: '建议处理，不影响结账', tone: checked.value && items.value.length > blockerItems.value.length ? 'amber' as const : undefined },
  { label: '结账范围', value: `${result.value?.orgCount ?? '-'} 个组织`, hint: `已结账 ${result.value?.closedOrgCount ?? closedCount.value} 个` }
])
const canClose = computed(() => checked.value && !loading.value && result.value?.periodMonth === period.value && result.value?.issueCount === 0)
const isClosed = computed(() => closedCount.value > 0)
const closedCount = computed(() => records.value.filter(row => row.period_month === period.value && row.status === 'CLOSED').length)

function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

let checkRequest = 0
async function runCheck(notify = true) {
  const requestId = ++checkRequest
  const requestedPeriod = period.value
  checked.value = false
  result.value = null
  loading.value = true
  try {
    const response = await periodCloseApi.check({ periodMonth: requestedPeriod })
    if (period.value !== requestedPeriod || requestId !== checkRequest) return
    result.value = response
    checked.value = true
    if (!notify) { /* 返回页面时只更新结果 */ } else if (result.value.issueCount === 0) {
      ElMessage.success('检查完成，当前期间可结账')
    } else {
      ElMessage.warning(`检查完成，发现 ${result.value.issueCount} 项异常`)
    }
    await loadRecords()
  } finally {
    if (requestId === checkRequest) loading.value = false
  }
}

async function closePeriod() {
  const actionPeriod = period.value
  await ElMessageBox.confirm(
    `${actionPeriod} 结账后，当月采购、入库、出库、退料等单据将禁止新增、修改、提交、审核和删除（确保账实相符、不可再变动）。如需调整须先「反结账」。是否确认结账？`,
    '确认月末结账',
    { type: 'warning', confirmButtonText: '确认结账锁定', cancelButtonText: '取消' }
  )
  closing.value = true
  try {
    result.value = await periodCloseApi.close({ periodMonth: actionPeriod, remark: '月末结账' })
    checked.value = true
    ElMessage.success('结账完成，当月单据已锁定')
    await loadRecords()
  } finally {
    closing.value = false
  }
}

async function reopenPeriod() {
  const actionPeriod = period.value
  await ElMessageBox.confirm(
    `取消结账后 ${actionPeriod} 的单据将允许继续处理，请确认这是经过授权的调整。`,
    '确认取消结账',
    { type: 'warning', confirmButtonText: '确认取消结账', cancelButtonText: '取消' }
  )
  reopening.value = true
  try {
    await periodCloseApi.reopen({ periodMonth: actionPeriod })
    ElMessage.success('已取消结账，当前期间可继续调整')
    checked.value = false
    result.value = null
    await loadRecords()
  } finally {
    reopening.value = false
  }
}

let recordRequest = 0
async function loadRecords() {
  const requestId = ++recordRequest
  const requestedPeriod = period.value
  recordLoading.value = true
  try {
    const response = await periodCloseApi.records({ periodMonth: requestedPeriod }) as any[]
    if (requestId === recordRequest && requestedPeriod === period.value) records.value = response
  } finally {
    if (requestId === recordRequest) recordLoading.value = false
  }
}

async function refreshCheck() {
  if (checked.value) await runCheck(false)
  else await loadRecords()
}
watch(period, () => {
  ++checkRequest
  loading.value = false
  records.value = []
  checked.value = false
  result.value = null
  expanded.value = {}
  void loadRecords()
})
onActivated(refreshCheck)
</script>

<style scoped>
.period-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.period-head {
  align-items: flex-start;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.summary-card {
  min-height: 112px;
  padding: 18px 20px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: linear-gradient(145deg, rgba(255,255,255,.96), rgba(247,250,255,.88));
  box-shadow: 0 14px 34px rgba(38, 61, 92, .08);
}

.summary-card span,
.summary-card small {
  display: block;
  color: var(--el-text-color-secondary);
}

.summary-card strong {
  display: block;
  margin: 10px 0 6px;
  font-size: 30px;
  line-height: 1;
  color: #0f2450;
}

.summary-card.ok {
  border-color: rgba(103, 194, 58, .34);
  background: linear-gradient(145deg, rgba(245,255,241,.98), rgba(255,255,255,.92));
}

.summary-card.danger {
  border-color: rgba(245, 108, 108, .34);
  background: linear-gradient(145deg, rgba(255,246,246,.98), rgba(255,255,255,.92));
}

.check-panel,
.records-panel {
  border-radius: 14px;
}

.panel-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.panel-title span {
  font-weight: 700;
  color: var(--el-text-color-primary);
}

.panel-title small {
  color: var(--el-text-color-secondary);
}

.muted-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 占满单元格才排得开：el-space 的 inline-flex 会收缩宽度，导致单号一行只放得下一个 */
.doc-tag-wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 2px 0;
}

/* 可点的单号：给出明确的“这是个链接”信号，避免像以前那样看着能点其实是死文字 */
.doc-tag-clickable {
  cursor: pointer;
  transition: opacity .15s, transform .15s;
}
.doc-tag-clickable:hover {
  opacity: .78;
  transform: translateY(-1px);
}
.sample-toggle {
  font-size: 12px;
}
.sample-more {
  margin-left: 2px;
}

@media (max-width: 1080px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .period-page { padding: 12px; }
  .period-head { flex-direction: column; align-items: stretch; padding: 14px; }
  .period-head > div { min-width: 0; }
  .panel-title { flex-direction: column; align-items: flex-start; gap: 6px; }
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
  .period-page :deep(.el-button) { min-height: 44px; }
  .period-page :deep(.el-card__body) { padding: 12px; }
}

.close-steps-card :deep(.el-card__body) { padding: 12px 18px; }
.close-steps { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 0; padding: 0; list-style: none; font-size: 13px; }
.close-steps li { display: flex; align-items: center; gap: 8px; color: var(--text-2); }
.close-steps li b { width: 22px; height: 22px; border-radius: 50%; border: 1px solid var(--table-grid); display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 500; background: var(--bg-panel); }
.close-steps li.done { color: #17633d; }
.close-steps li.done b { background: #e6f3ec; border-color: #bfe0cc; }
.close-steps li.cur { color: #174a88; font-weight: 600; }
.close-steps li.cur b { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.close-steps .sl { width: 32px; height: 1px; background: var(--table-grid); }
</style>
