<template>
  <span class="mxn" :class="{ 'is-stepper': stepper }">
    <button v-if="stepper" type="button" :disabled="current <= min" @click="bump(-1)">−</button>
    <input :value="text" type="text" inputmode="decimal" autocomplete="off" @focus="onFocus" @input="onInput" @blur="onBlur" />
    <button v-if="stepper" type="button" :disabled="max !== undefined && current >= max" @click="bump(1)">+</button>
  </span>
</template>

<script setup lang="ts">
/**
 * 手机端数字输入（数量、单价）。替代 el-input-number：它的加减按钮在手机上太小，且弹的是全键盘。
 *
 * 口径与 el-input-number 保持一致，页面拿到的始终是数字：
 *   · 输入过程中只留数字和一个小数点，按 precision 位四舍五入
 *   · 离开输入框时按 min / max 夹回范围（超过可入库数量会被拉回上限）
 *   · 清空 = 按 min 算（默认 0），不会把空字符串传给后端
 */
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: number | null | undefined
  min?: number
  max?: number
  precision?: number
  stepper?: boolean
}>(), { min: 0, precision: 2 })
const emit = defineEmits<{ (e: 'update:modelValue', v: number): void }>()

const focused = ref(false)
const text = ref(fmt(props.modelValue))
const current = computed(() => Number(props.modelValue) || 0)

function round(n: number) { const p = 10 ** props.precision; return Math.round(n * p) / p }
function clamp(n: number) {
  if (n < props.min) return props.min
  if (props.max !== undefined && n > props.max) return props.max
  return n
}
/** 显示时去掉多余的 0：12.50 → 12.5，3.00 → 3 */
function fmt(v: number | null | undefined) { return String(round(Number(v) || 0)) }

function onFocus(e: FocusEvent) {
  focused.value = true
  // 点进去就全选：原来是 0 的话直接敲数字就替换掉，不用先删
  ;(e.target as HTMLInputElement).select()
}
function onInput(e: Event) {
  const el = e.target as HTMLInputElement
  let s = el.value.replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot >= 0) s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '').slice(0, props.precision)
  if (s !== el.value) el.value = s
  text.value = s
  emit('update:modelValue', round(Number(s) || 0))
}
function onBlur() {
  focused.value = false
  const n = clamp(round(Number(text.value) || 0))
  text.value = fmt(n)
  emit('update:modelValue', n)
}
function bump(d: number) {
  const n = clamp(round(current.value + d))
  text.value = fmt(n)
  emit('update:modelValue', n)
}

// 外面改了值（带出默认数量、重置表单）时同步显示；正在输入时不打断
watch(() => props.modelValue, v => { if (!focused.value) text.value = fmt(v) })
</script>

<style scoped lang="scss">
.mxn { display: flex; flex: 1; min-width: 0; align-items: center; justify-content: flex-end; }
.mxn input {
  width: 100%; min-width: 0; height: 34px; padding: 0; border: 0; outline: 0; background: transparent;
  font-size: 16px; color: var(--mob-ink); text-align: right; font-variant-numeric: tabular-nums;
}
.mxn.is-stepper {
  flex: 0 0 auto; margin-left: auto; overflow: hidden;
  border: 1px solid var(--mob-line); border-radius: 9px;
  input { width: 68px; text-align: center; }
  button {
    width: 38px; height: 34px; padding: 0; border: 0; background: #f6f2ea;
    font-size: 19px; line-height: 1; color: var(--mob-ink-2); cursor: pointer;
    -webkit-tap-highlight-color: transparent;
    &:active:not(:disabled) { background: var(--mob-line); }
    &:disabled { color: #cfc8b8; }
  }
}
</style>
