<template>
  <!-- 采购单价防错弹窗 -->
  <el-dialog v-model="visible" title="这些行的单价和历史采购价差得太多，请核对" width="min(760px, calc(100vw - 32px))"
             append-to-body :close-on-click-modal="false" @closed="onClosed">
    <div class="pc-lead">最常见的原因是<b>把单价填进了"含税金额"栏</b>。请逐行看一下：</div>
    <div v-for="it in issues" :key="it.index" class="pc-item">
      <div class="pc-title">第 {{ it.index + 1 }} 行　{{ label(it.materialId) }}</div>
      <div class="pc-row">
        <span>数量 <b>{{ it.qty }} {{ unit(it.materialId) }}</b></span>
        <span>你填的含税单价 <b>{{ fmtMoney(it.unitIncl) }}</b></span>
        <span>历史采购价约 <b>{{ fmtMoney(it.refPrice) }}</b></span>
        <el-tag type="danger" size="small">{{ deviationText(it.unitIncl, it.refPrice) }}</el-tag>
      </div>
      <div v-if="it.looksLikeUnit" class="pc-guess">
        <span>看起来 <b>{{ fmtMoney(it.amount) }}</b> 像是一个单价。若按单价算，本行金额应为 <b>{{ fmtMoney(roundMoney(it.amount * it.qty)) }}</b></span>
        <el-button size="small" type="primary" plain @click="fixOne(it)">改成：单价 {{ fmtMoney(it.amount) }}，金额 {{ fmtMoney(roundMoney(it.amount * it.qty)) }}</el-button>
      </div>
    </div>
    <div class="pc-reason">
      <div class="pc-reason-label">如果确实没错（降价、特价、赠品等），请写一句理由（会显示给审批人）：</div>
      <el-input ref="noteRef" v-model="note" type="textarea" :rows="2" maxlength="200" show-word-limit
                placeholder="例：供应商清库特价，已附报价单" />
    </div>
    <template #footer>
      <el-button @click="finish(null)">返回修改</el-button>
      <el-button type="warning" @click="confirmAnyway">确认无误，继续保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fmtMoney, roundMoney } from '@/utils/format'
import { deviationText, type PriceIssue } from '@/utils/poPriceCheck'

/** 结果：fix=按单价改正某一行后需重新检查；confirm=带理由继续；null=返回修改 */
export type PriceCheckResult = { action: 'fix'; index: number } | { action: 'confirm'; note: string } | null

const props = defineProps<{ materials: Record<number, any> }>()
const visible = ref(false)
const issues = ref<PriceIssue[]>([])
const note = ref('')
const noteRef = ref()
let resolver: ((r: PriceCheckResult) => void) | null = null
let pending: PriceCheckResult = null

function open(list: PriceIssue[], lastNote = ''): Promise<PriceCheckResult> {
  issues.value = list
  note.value = lastNote
  pending = null
  visible.value = true
  return new Promise(resolve => { resolver = resolve })
}
function label(id: number) {
  const m = props.materials[id] || {}
  return `${m.code || ''} ${m.name || ''}${m.spec ? '（' + m.spec + '）' : ''}`
}
function unit(id: number) { return props.materials[id]?.unit || '' }
function fixOne(it: PriceIssue) { finish({ action: 'fix', index: it.index }) }
function confirmAnyway() {
  const text = note.value.trim()
  if (!text) { ElMessage.warning('请先写一句理由，审批人会看到'); noteRef.value?.focus(); return }
  finish({ action: 'confirm', note: text })
}
function finish(r: PriceCheckResult) { pending = r; visible.value = false }
// 关闭动画结束后才回调，避免连续弹两次时对话框状态打架；点右上角 × 视同"返回修改"
function onClosed() { const r = resolver; resolver = null; r?.(pending) }

defineExpose({ open })
</script>

<style scoped>
.pc-lead { color: var(--el-text-color-regular); }
.pc-item { border: 1px solid var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); border-radius: 6px; padding: 10px 12px; margin-top: 10px; }
.pc-title { font-weight: 600; color: var(--el-text-color-primary); }
.pc-row { display: flex; flex-wrap: wrap; gap: 4px 18px; margin-top: 4px; font-size: 13px; align-items: center; }
.pc-row b, .pc-guess b { font-variant-numeric: tabular-nums; color: var(--el-text-color-primary); }
.pc-guess { margin-top: 8px; padding: 8px 10px; background: var(--el-bg-color); border: 1px dashed var(--el-color-warning); border-radius: 4px; font-size: 13px; display: flex; gap: 10px; align-items: center; justify-content: space-between; flex-wrap: wrap; }
.pc-reason { margin-top: 14px; }
.pc-reason-label { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 4px; }
</style>
