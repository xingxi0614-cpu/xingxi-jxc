<template>
  <!--
    单据详情弹窗（2026-10-17 全站界面改版，原型 Out_Detail / Po_Detail）。
    🔴 起初做成右侧抽屉（880 宽），用户实际用下来发现：明细列多（单价/税率/金额/税额…）时要横向拖，
       一拖物料名称就滚出去，看到的数字对不上是哪个物料。定改回居中弹窗、宽 1180（与原详情弹窗同宽），
       并让各页明细表冻结左边三列（行号/物料编码/物料名称）。组件名沿用 DocDrawer，16 个页面不用跟着改。
    结构固定为：顶部（单据类型 + 单号 + 状态 + 一行摘要）→ 步骤条 → 关键数字 → 正文（页面自己放信息表、明细、附件等）→ 底栏。
    底栏左边放删除/驳回这类"往回走"的按钮，右边放关闭和主操作，主按钮永远在最右。
    标题和底栏固定，内容多时只滚中间（全站 .el-dialog 规则）。
  -->
  <el-dialog
    :model-value="modelValue"
    :width="size"
    :before-close="beforeClose"
    :show-close="false"
    draggable
    append-to-body
    class="doc-dialog"
    :aria-labelledby="titleId"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #header>
      <div class="dd-head">
        <div class="dd-title">
          <div v-if="kind" class="dd-kind">{{ kind }}</div>
          <h2 :id="titleId">
            <span class="dd-no">{{ docNo || '-' }}</span>
            <slot name="status"><StatusTag v-if="status" :status="status" /></slot>
          </h2>
          <div v-if="$slots.sub || sub" class="dd-sub"><slot name="sub">{{ sub }}</slot></div>
        </div>
        <div class="dd-head-acts">
          <slot name="head-actions" />
          <button type="button" class="dd-x" aria-label="关闭" @click="requestClose">×</button>
        </div>
      </div>
    </template>

    <div class="dd-body" v-loading="loading">
      <ol v-if="steps && steps.length" class="dd-steps" aria-label="单据进度">
        <template v-for="(s, i) in steps" :key="s">
          <li v-if="i > 0" class="dd-sl" aria-hidden="true"></li>
          <li class="dd-st" :class="stepClass(i)">
            <b>{{ stepMark(i) }}</b>{{ s }}
          </li>
        </template>
      </ol>
      <div v-if="nums && nums.length" class="dd-nums">
        <div v-for="n in nums" :key="n.label" :class="{ hl: n.hl }">
          <span>{{ n.label }}</span><strong>{{ n.value }}</strong>
        </div>
      </div>
      <slot />
    </div>

    <template v-if="$slots.footer || $slots['footer-left']" #footer>
      <div class="dd-foot">
        <slot name="footer-left" />
        <span class="dd-sp"></span>
        <slot name="footer" />
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { provide, ref, watch } from 'vue'
import StatusTag from '@/components/StatusTag.vue'

export interface DocNum { label: string; value: string | number; hl?: boolean }

const props = withDefaults(defineProps<{
  modelValue: boolean
  kind?: string
  docNo?: string | null
  status?: string | null
  sub?: string
  /** 步骤名称，如 ['制单', '审批', '扣减库存'] */
  steps?: string[]
  /** 当前走到第几步（从 0 数）；等于 steps.length 表示全部完成 */
  stepActive?: number
  /** 被驳回时当前步显示为红色 ✕ */
  stepRejected?: boolean
  nums?: DocNum[]
  loading?: boolean
  /** 弹窗宽度，默认与原详情弹窗同宽 1180 */
  size?: string
  /** 有未保存内容时拦一下（与 el-dialog 的 before-close 同用法），点右上角 × 也走它 */
  beforeClose?: (done: () => void) => void
}>(), { stepActive: 0, stepRejected: false, loading: false, size: 'min(1180px, calc(100vw - 56px))' })
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const titleId = `dd-t-${Math.random().toString(36).slice(2, 8)}`

// 每打开一次详情就 +1:弹窗里的组件(审批记录)据此重新读数据。
// el-dialog 关掉后不销毁里面的组件,同一张单审批后再打开,单号没变,不靠这个就会一直显示旧记录(计算审查 U18)
const openSeq = ref(0)
watch(() => props.modelValue, v => { if (v) openSeq.value++ }, { immediate: true })
provide('docDrawerOpenSeq', openSeq)

function requestClose() {
  const done = () => emit('update:modelValue', false)
  if (props.beforeClose) props.beforeClose(done)
  else done()
}

function stepClass(i: number) {
  if (i < props.stepActive) return 'done'
  if (i === props.stepActive) return props.stepRejected ? 'rej' : 'cur'
  return ''
}
function stepMark(i: number) {
  if (i < props.stepActive) return '✓'
  if (i === props.stepActive && props.stepRejected) return '✕'
  return String(i + 1)
}
</script>

<style scoped>
.dd-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; color: var(--text-1); }
.dd-title { min-width: 0; }
.dd-kind { font-size: 12px; color: var(--text-2); }
.dd-title h2 { margin: 2px 0 0; font-size: 18px; font-weight: 600; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dd-no { font-variant-numeric: tabular-nums; }
.dd-sub { margin-top: 4px; font-size: 13px; color: var(--text-2); line-height: 1.6; }
.dd-head-acts { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.dd-x {
  width: 34px; height: 34px; border: 0; border-radius: 8px; background: transparent;
  color: var(--text-2); font-size: 22px; line-height: 1; cursor: pointer;
}
.dd-x:hover { background: var(--bg-panel-hover); color: var(--text-1); }
.dd-body { display: flex; flex-direction: column; gap: 16px; color: var(--text-1); font-size: 14px; }
/* 正文里的块一律不压缩：内容多时整个正文滚动，而不是把表格、数字条挤扁 */
.dd-body > :deep(*) { flex-shrink: 0; }
.dd-foot { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dd-foot :deep(.el-button + .el-button) { margin-left: 0; }
.dd-sp { flex: 1; }

.dd-steps { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 0; padding: 0; list-style: none; font-size: 13px; }
.dd-st { display: flex; align-items: center; gap: 8px; color: var(--text-2); }
.dd-st b {
  width: 22px; height: 22px; border-radius: 50%; border: 1px solid var(--table-grid);
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 500; background: var(--bg-panel);
}
.dd-st.done { color: #17633d; }
.dd-st.done b { background: #e6f3ec; border-color: #bfe0cc; }
.dd-st.cur { color: #174a88; font-weight: 600; }
.dd-st.cur b { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.dd-st.rej { color: #8f1c13; font-weight: 600; }
.dd-st.rej b { background: #b42318; border-color: #b42318; color: #fff; }
.dd-sl { width: 32px; height: 1px; background: var(--table-grid); }

.dd-nums {
  display: grid; grid-auto-flow: column; grid-auto-columns: minmax(0, 1fr);
  border: 1px solid var(--table-grid); border-radius: 10px; overflow: hidden;
}
.dd-nums > div { padding: 10px 14px; display: flex; flex-direction: column; gap: 2px; border-right: 1px solid var(--border-1); min-width: 0; }
.dd-nums > div:last-child { border-right: 0; }
.dd-nums span { font-size: 12px; color: var(--text-2); }
.dd-nums strong { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dd-nums .hl { background: #f2f7fd; }
.dd-nums .hl span, .dd-nums .hl strong { color: #174a88; }

/* 正文里的小标题：页面用 <div class="dd-sec">明细</div> */
.dd-body :deep(.dd-sec) {
  font-size: 14px; font-weight: 600; color: var(--table-header-text);
  display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: -6px;
}
.dd-body :deep(.dd-sec small) { font-weight: 400; color: var(--text-2); font-size: 12px; }
</style>

<style>
/* 详情弹窗：头部用自定义标题区（不要 el-dialog 自带标题的右侧留白），正文内边距与标题对齐 */
.doc-dialog .el-dialog__header { padding: 16px 24px 14px; margin-right: 0; }
.doc-dialog .el-dialog__header.show-close { padding-right: 24px; }
.doc-dialog .el-dialog__body { padding: 16px 24px; }
.doc-dialog .el-dialog__footer { padding: 12px 24px; }
</style>
