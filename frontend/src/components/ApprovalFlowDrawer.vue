<template>
  <el-button
    v-if="docNo"
    class="afd-btn"
    :class="{ 'afd-float-left': floatLeft }"
    :size="size"
    type="primary"
    plain
    :icon="Connection"
    @click="open"
  >
    <slot>审批流程</slot>
  </el-button>
  <el-drawer v-if="docNo" v-model="visible" :title="`审批流程 · ${docNo}`" size="440px" append-to-body>
      <div v-loading="loading">
        <template v-if="flow">
          <div class="afd-head">
            <el-tag :type="stateColor(flow.state)" effect="dark" size="small">{{ stateLabel(flow.state) }}</el-tag>
            <span v-if="flow.applicant" class="afd-sub">{{ flow.applicant }} 提交<template v-if="flow.submittedAt"> · {{ fmtMinute(flow.submittedAt) }}</template></span>
          </div>

          <!-- 审批进度：共几步 / 已完成几步 / 当前第几步，让单节点也能一眼看出是否走完 -->
          <div v-if="totalSteps" class="afd-progress">
            <div class="afd-progress-text">
              <span>审批进度</span>
              <strong>{{ doneSteps }}/{{ totalSteps }}</strong>
              <span class="afd-progress-tip">{{ progressTip }}</span>
            </div>
            <div class="afd-progress-bar"><i :class="'pb-' + flow.state.toLowerCase()" :style="{ width: progressPct + '%' }"></i></div>
          </div>

          <div v-if="flow.state === 'PROCESSING'" class="afd-pending">⏳ 当前待「{{ roleLabel(flow.nextApproverRole) }}」审批，可及时跟进催办</div>

          <div v-if="flow.steps && flow.steps.length" class="afd-steps">
            <div v-for="(s, i) in flow.steps" :key="i" class="afd-step" :class="'st-' + (s.status || 'pending').toLowerCase()">
              <div class="afd-dot"><i v-if="s.status === 'DONE'">✓</i><i v-else-if="s.status === 'REJECTED'">✕</i><i v-else>{{ i + 1 }}</i></div>
              <div class="afd-body">
                <div class="afd-title">
                  <span class="afd-seq">第 {{ i + 1 }} 步</span>{{ s.name || nodeLabel(s.node) }}
                  <el-tag size="small" :type="stepTagType(s.status)">{{ stepTagLabel(s.status) }}</el-tag>
                </div>
                <div class="afd-desc">
                  <template v-if="s.status === 'DONE'">{{ s.approver || '-' }} · 通过<template v-if="s.time"> · {{ fmtMinute(s.time) }}</template></template>
                  <template v-else-if="s.status === 'REJECTED'">{{ s.approver || '-' }} · 驳回<template v-if="s.time"> · {{ fmtMinute(s.time) }}</template></template>
                  <template v-else>待「{{ roleLabel(s.role) }}」审批</template>
                  <div v-if="s.comment" class="afd-cmt">意见：{{ s.comment }}</div>
                </div>
              </div>
            </div>
            <!-- 终点：流程是否走完，明确收口 -->
            <div class="afd-step afd-terminal" :class="'tm-' + flow.state.toLowerCase()">
              <div class="afd-dot"><i>{{ flow.state === 'APPROVED' ? '✓' : flow.state === 'REJECTED' ? '✕' : '…' }}</i></div>
              <div class="afd-body"><div class="afd-title afd-terminal-title">{{ terminalText }}</div></div>
            </div>
          </div>
          <el-empty v-else :description="flow.state === 'NONE' ? '该单据尚未进入审批流（草稿/未提交）' : '暂无审批节点'" :image-size="60" />

          <!-- 完整操作记录：提交/审批/驳回/撤回/取消退回，永久留痕可追溯 -->
          <div v-if="flow.history && flow.history.length" class="afd-history">
            <div class="afd-history-title">操作记录</div>
            <div v-for="(h, i) in flow.history" :key="'h' + i" class="afd-hrow">
              <span class="afd-haction" :class="'ha-' + String(h.action || '').toLowerCase()">{{ actionLabel(h.action) }}</span>
              <span class="afd-hwho">{{ h.approver || '-' }}</span>
              <span class="afd-htime">{{ h.time ? fmtMinute(h.time) : '' }}</span>
              <div v-if="h.comment" class="afd-hcmt">{{ h.comment }}</div>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection } from '@/icons'
import { wfApi } from '@/api'
import { fmtMinute } from '@/utils/format'
import { showError } from '@/utils/notify'

const props = withDefaults(defineProps<{
  docNo?: string | null
  size?: 'small' | 'default' | 'large'
  floatLeft?: boolean
}>(), { size: 'default', floatLeft: true })

const visible = ref(false)
const loading = ref(false)
const flow = ref<any>(null)

const totalSteps = computed(() => flow.value?.steps?.length || 0)
const doneSteps = computed(() => (flow.value?.steps || []).filter((s: any) => s.status === 'DONE').length)
// 进度条：通过时拉满，驳回时按已完成占比（红），审批中按已完成占比（蓝）
const progressPct = computed(() => {
  if (!totalSteps.value) return 0
  if (flow.value?.state === 'APPROVED') return 100
  return Math.round((doneSteps.value / totalSteps.value) * 100)
})
const progressTip = computed(() => {
  const st = flow.value?.state
  if (st === 'APPROVED') return '已全部审批完成'
  if (st === 'REJECTED') return '已驳回，流程终止'
  if (st === 'PROCESSING') return `当前第 ${doneSteps.value + 1} 步`
  return ''
})
const terminalText = computed(() => {
  const st = flow.value?.state
  if (st === 'APPROVED') return '审批完成，全部节点已通过'
  if (st === 'REJECTED') return '已驳回，审批流程结束'
  return '流程尚未结束，审批进行中'
})

async function open() {
  if (!props.docNo) return
  visible.value = true
  loading.value = true
  flow.value = null
  try {
    flow.value = await wfApi.flow(props.docNo)
  } catch (e: any) {
    showError(e, '获取审批流程失败')
    visible.value = false
  } finally {
    loading.value = false
  }
}

function stateLabel(s: string) {
  return ({ NONE: '未提交', DRAFT: '草稿', PROCESSING: '审批中', APPROVED: '已通过', REJECTED: '已驳回' } as any)[s] || s || '-'
}
function stateColor(s: string) {
  if (s === 'APPROVED') return 'success'
  if (s === 'PROCESSING') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}
function stepTagType(s: string) {
  return ({ DONE: 'success', CURRENT: 'warning', REJECTED: 'danger' } as any)[s] || 'info'
}
function stepTagLabel(s: string) {
  return ({ DONE: '已通过', CURRENT: '当前待审', REJECTED: '已驳回', PENDING: '待审批' } as any)[s] || '待审批'
}
function roleLabel(r: string) {
  return ({
    material_accountant: '材料会计', finance_manager: '财务负责人', deputy_general_manager: '分管副总',
    general_manager: '总经理', tech_manager: '技术部经理', engineering_leader: '工程副总',
    operation_leader: '运营副总', market_leader: '市场副总', branch_manager: '网点负责人',
    area_manager: '片区经理', dept_manager: '部门负责人', finance: '财务'
  } as any)[r] || r || '审批人'
}
function actionLabel(a: string) {
  return ({
    SUBMIT: '提交', APPROVE: '审批通过', REJECT: '驳回',
    RECALL: '撤回（取消提交）', CANCEL: '取消审批·退回草稿', WITHDRAW: '撤回'
  } as any)[a] || a || '操作'
}
function nodeLabel(n: string) {
  return ({
    MATERIAL_ACCOUNTANT: '材料会计', MATERIAL_ACCOUNTANT_REVIEW: '材料会计', FINANCE_MANAGER: '财务负责人',
    DEPUTY_GENERAL_MANAGER: '分管副总', GENERAL_MANAGER: '总经理', TECH_MANAGER: '技术部经理',
    ENGINEERING_LEADER: '工程副总', OPERATION_LEADER: '运营副总', MARKET_LEADER: '市场副总',
    BRANCH_MANAGER: '网点负责人', AREA_MANAGER: '片区经理'
  } as any)[n] || n || '审批'
}
</script>

<style scoped lang="scss">
// 放在弹框 footer 左侧，与右对齐的审批/打印/关闭按钮分开，避免拥挤重叠
.afd-float-left { float: left; }
.afd-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.afd-sub { font-size: 12px; color: var(--text-3); }
.afd-pending {
  font-size: 13px; color: var(--el-color-warning, #e6a23c);
  background: var(--el-color-warning-light-9, #fdf6ec); border-radius: 6px;
  padding: 8px 10px; margin-bottom: 12px;
}
.afd-progress { margin-bottom: 12px; }
.afd-progress-text {
  display: flex; align-items: baseline; gap: 6px; font-size: 12px; color: var(--text-3); margin-bottom: 6px;
  strong { font-size: 14px; color: var(--text-1); font-variant-numeric: tabular-nums; }
  .afd-progress-tip { margin-left: auto; color: var(--text-2); }
}
.afd-progress-bar {
  height: 6px; border-radius: 99px; background: var(--border-2, #e4e7ed); overflow: hidden;
  i {
    display: block; height: 100%; border-radius: 99px; transition: width .3s ease;
    background: var(--el-color-primary, #1d63b0);
    &.pb-approved { background: var(--el-color-success, #2e9e6b); }
    &.pb-rejected { background: var(--el-color-danger, #dc4d4d); }
    &.pb-processing { background: var(--el-color-warning, #d6881f); }
  }
}
.afd-seq {
  font-size: 11px; color: var(--text-3); font-weight: 400;
  background: var(--surface-soft, #f5f7fa); border-radius: 4px; padding: 1px 5px; margin-right: 2px;
}
.afd-dot i { font-style: normal; font-size: 11px; }
/* 终点收口节点：明确"是否走完" */
.afd-terminal {
  padding-bottom: 0;
  .afd-dot { background: var(--text-3, #98a1b0); }
  &.tm-approved .afd-dot { background: var(--el-color-success, #2e9e6b); }
  &.tm-rejected .afd-dot { background: var(--el-color-danger, #dc4d4d); }
  &.tm-processing .afd-dot { background: var(--el-color-warning, #d6881f); }
}
.afd-terminal-title { font-weight: 600; }
.tm-approved .afd-terminal-title { color: var(--el-color-success, #2e9e6b); }
.tm-rejected .afd-terminal-title { color: var(--el-color-danger, #dc4d4d); }
.tm-processing .afd-terminal-title { color: var(--text-2); }
.afd-steps { padding-left: 4px; }
.afd-step {
  display: flex; gap: 10px; padding-bottom: 14px; position: relative;
  &:not(:last-child)::before {
    content: ''; position: absolute; left: 10px; top: 22px; bottom: 0; width: 2px; background: var(--border-2, #e4e7ed);
  }
}
.afd-dot {
  flex: 0 0 22px; width: 22px; height: 22px; border-radius: 50%;
  background: var(--border-2, #e4e7ed); color: #fff; font-size: 12px;
  display: flex; align-items: center; justify-content: center; z-index: 1;
}
.st-done   .afd-dot { background: var(--el-color-success, #67c23a); }
.st-current .afd-dot { background: var(--el-color-warning, #e6a23c); box-shadow: 0 0 0 4px var(--el-color-warning-light-8, #faecd8); }
.st-rejected .afd-dot { background: var(--el-color-danger, #f56c6c); }
/* 操作记录（完整留痕） */
.afd-history { margin-top: 18px; padding-top: 12px; border-top: 1px dashed var(--border-1, #e6e9ee); }
.afd-history-title { font-size: 12px; color: var(--text-3); margin-bottom: 8px; }
.afd-hrow { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--border-2, #eef1f5); font-size: 12px; }
.afd-haction {
  flex: 0 0 auto; font-weight: 600; color: var(--text-2);
  &.ha-submit { color: var(--el-color-primary, #1d63b0); }
  &.ha-approve { color: var(--el-color-success, #2e9e6b); }
  &.ha-reject { color: var(--el-color-danger, #dc4d4d); }
  &.ha-cancel, &.ha-recall, &.ha-withdraw { color: var(--el-color-warning, #d6881f); }
}
.afd-hwho { color: var(--text-1); }
.afd-htime { margin-left: auto; color: var(--text-3); font-variant-numeric: tabular-nums; }
.afd-hcmt { flex: 0 0 100%; color: var(--text-3); margin-top: 2px; }
.afd-title { font-size: 13px; font-weight: 600; color: var(--text-1); display: flex; align-items: center; gap: 8px; }
.afd-desc { font-size: 12px; color: var(--text-2); margin-top: 3px; }
.afd-cmt { color: var(--text-3); margin-top: 2px; }
</style>
