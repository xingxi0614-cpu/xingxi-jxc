<template>
  <!-- 详情面板里直接显示的审批记录（最新在上）；完整的分步进度仍可点「审批流程」看 ApprovalFlowDrawer -->
  <!--
    10-20 用户截图：以前只是白底上几行字，"做得太白"，等待审批那行右边还挂个「—」。
    改成与信息表、明细表同一套框线的记录表：每条一行、左边色点、右边时间；正在等的那一步整行淡黄底，右边标「审批中」。
  -->
  <div class="atl" v-loading="loading">
    <ol v-if="items.length" class="atl-list">
      <li v-for="(it, i) in items" :key="i" :class="{ cur: it.current }">
        <i :class="it.color" aria-hidden="true"></i>
        <span class="atl-main"><b>{{ it.title }}</b><span v-if="it.who" class="atl-who">{{ it.who }}</span><span v-if="it.comment" class="atl-cmt">「{{ it.comment }}」</span></span>
        <span v-if="it.current" class="atl-tag">审批中</span>
        <span v-else class="atl-time">{{ it.time }}</span>
      </li>
    </ol>
    <div v-else-if="!loading" class="atl-empty">{{ emptyText }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref, watch, type Ref } from 'vue'
import { wfApi } from '@/api'
import { fmtMinute } from '@/utils/format'

const props = defineProps<{ docNo?: string | null }>()
const flow = ref<any>(null)
const loading = ref(false)
const failed = ref(false)

const ACTION: Record<string, string> = {
  SUBMIT: '提交', APPROVE: '审批通过', REJECT: '驳回',
  RECALL: '撤回（取消提交）', CANCEL: '取消审批 · 退回草稿', WITHDRAW: '撤回'
}

const items = computed(() => {
  const f = flow.value
  if (!f) return []
  const list: { title: string; who?: string; time: string; comment?: string; color: string; current?: boolean }[] = []
  if (f.state === 'PROCESSING') {
    const cur = (f.steps || []).find((s: any) => s.status === 'CURRENT')
    list.push({ title: '等待审批', who: cur?.name || '', time: '', color: 'y', current: true })
  }
  const hist = [...(f.history || [])].reverse()
  for (const h of hist) {
    const a = String(h.action || '')
    list.push({
      title: ACTION[a] || a || '操作',
      who: h.approver || '',
      time: h.time ? fmtMinute(h.time) : '',
      comment: h.comment || '',
      color: a === 'REJECT' ? 'r' : a === 'APPROVE' ? 'g' : 'n'
    })
  }
  return list
})
const emptyText = computed(() => failed.value ? '审批记录暂时读取不到，可点「审批流程」查看' : '还没有提交审批')

// 所在详情弹窗每打开一次就重读(同一张单审批后再打开也是最新记录);不在弹窗里时为 undefined
const openSeq = inject<Ref<number> | undefined>('docDrawerOpenSeq', undefined)
// 请求世代号:只认最后一次请求的结果。先看 A 单(慢)再切到 B 单(快),晚到的 A 不能覆盖 B(计算审查 U18)
let seq = 0
watch(() => [props.docNo, openSeq?.value], async () => {
  const no = props.docNo
  const my = ++seq
  flow.value = null
  failed.value = false
  if (!no) { loading.value = false; return }
  loading.value = true
  try {
    const res = await wfApi.flow(no)
    if (my === seq) flow.value = res
  } catch {
    // 读不到审批记录不影响看单据，这里静默，空态文字会提示去「审批流程」看
    if (my === seq) failed.value = true
  } finally {
    if (my === seq) loading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.atl { min-height: 24px; }
.atl-list {
  margin: 0; padding: 0; list-style: none; font-size: 13px;
  border: 1px solid var(--table-grid); border-radius: 8px; overflow: hidden; background: var(--bg-panel);
}
.atl-list li {
  display: grid; grid-template-columns: 10px minmax(0, 1fr) auto; gap: 12px; align-items: center;
  padding: 10px 16px; min-height: 42px; box-sizing: border-box;
}
.atl-list li + li { border-top: 1px solid var(--border-1); }
.atl-list li.cur { background: color-mix(in srgb, #c77700 9%, var(--bg-panel)); }
.atl-list i { width: 10px; height: 10px; border-radius: 50%; background: #9aa5b1; box-shadow: 0 0 0 3px color-mix(in srgb, #9aa5b1 22%, transparent); }
.atl-list i.g { background: #1e7a4c; box-shadow: 0 0 0 3px color-mix(in srgb, #1e7a4c 18%, transparent); }
.atl-list i.y { background: #c77700; box-shadow: 0 0 0 3px color-mix(in srgb, #c77700 22%, transparent); }
.atl-list i.r { background: #b42318; box-shadow: 0 0 0 3px color-mix(in srgb, #b42318 18%, transparent); }
.atl-main { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 10px; min-width: 0; color: var(--text-1); }
.atl-main b { font-weight: 600; }
.atl-who { color: var(--text-2); }
.atl-cmt { color: var(--text-2); overflow-wrap: anywhere; }
.atl-time { color: var(--text-2); font-variant-numeric: tabular-nums; white-space: nowrap; }
.atl-tag {
  font-size: 12px; line-height: 20px; padding: 0 10px; border-radius: 10px; white-space: nowrap;
  color: color-mix(in srgb, #c77700 75%, var(--text-1)); background: color-mix(in srgb, #c77700 16%, var(--bg-panel)); border: 1px solid color-mix(in srgb, #c77700 35%, transparent);
}
.atl-empty { font-size: 13px; color: var(--text-3); padding: 10px 16px; border: 1px dashed var(--table-grid); border-radius: 8px; }
</style>
