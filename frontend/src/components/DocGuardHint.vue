<template>
  <!-- 建单提前提示：已结账 / 仓库盘点中。填的当下就说，不等点保存 -->
  <div v-if="issues.length" class="doc-guard-hint" data-guard-ignore>
    <el-alert v-for="(it, i) in issues" :key="i" :type="it.blocking ? 'error' : 'warning'" :closable="false" show-icon>
      <template #title><span class="doc-guard-text">{{ it.text }}</span></template>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
/**
 * 用法（放在建单弹窗顶部）：
 *   <DocGuardHint :active="createVisible" :date="form.transferDate" :warehouse-ids="[form.fromWarehouseId]" v-model:blocked="guardBlocked" />
 *   <el-button :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''">保存</el-button>
 *
 * - 不传 date = 按今天（采购入库等单据日期由系统写死当天）。
 * - blocked 只在「确定存不了」（该月已结账）时为 true；盘点中只提醒，单据仍可保存。
 * - 这里只是提示。接口出错就什么都不显示——保存时后端照常校验，不会因此放过任何一张单。
 */
import { ref, watch } from 'vue'
import { workApi } from '@/api'

const props = defineProps<{
  active: boolean
  date?: string | Date | null
  warehouseIds?: Array<number | string | null | undefined>
  sourceOrgId?: number | string | null
  operation?: string
}>()
const emit = defineEmits<{ (e: 'update:blocked', v: boolean): void }>()

type Issue = { type: string; blocking: boolean; text: string }
const issues = ref<Issue[]>([])
let seq = 0

function dateText(d: string | Date | null | undefined): string | undefined {
  if (!d) return undefined
  if (typeof d === 'string') return /^\d{4}-\d{2}-\d{2}/.test(d) ? d.slice(0, 10) : undefined
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function set(list: Issue[]) {
  issues.value = list
  emit('update:blocked', list.some(i => i.blocking))
}

async function check() {
  const mine = ++seq
  if (!props.active) return set([])
  const ids = (props.warehouseIds || []).map(v => Number(v)).filter(v => Number.isFinite(v) && v > 0)
  try {
    const list = await workApi.docGuard({ date: dateText(props.date), warehouseIds: ids.length ? ids.join(',') : undefined,
      sourceOrgId: props.sourceOrgId ? Number(props.sourceOrgId) : undefined, operation: props.operation }) as any
    // 用户连着改日期/仓库时，只认最后一次的结果
    if (mine === seq) set(Array.isArray(list) ? list : [])
  } catch {
    if (mine === seq) set([])
  }
}

watch(() => [props.active, dateText(props.date), (props.warehouseIds || []).join(','), props.sourceOrgId, props.operation], check, { immediate: true })
</script>

<style scoped>
.doc-guard-hint { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.doc-guard-text { font-weight: 400; line-height: 1.7; white-space: normal; }
</style>
