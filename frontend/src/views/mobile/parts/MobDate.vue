<template>
  <div class="mx-row mxd">
    <span class="mx-row__label">{{ label }}<i v-if="required">*</i></span>
    <span class="mx-row__val" :class="{ 'is-ph': !modelValue }">{{ modelValue || placeholder }}</span>
    <button v-if="clearable && modelValue" type="button" class="mxd-clear" @click="emit('update:modelValue', '')">清除</button>
    <ArrowRight v-else class="mx-row__chev" :size="16" />
    <!-- 透明的系统日期框盖住整行：点哪儿都弹出手机自带的日期选择 -->
    <input ref="native" class="mxd-native" type="date" :value="modelValue || ''" :aria-label="label" @click="openPicker" @input="onInput" />
  </div>
</template>

<script setup lang="ts">
/**
 * 手机端日期行。用系统自带的日期选择（安卓/苹果各自的滚轮或日历），不用 el-date-picker 的弹出日历——
 * 那个日历是按鼠标设计的，手机上格子小、容易点错。
 *
 * 值固定是 'YYYY-MM-DD' 字符串（或空字符串）。
 * el-date-picker 不设 value-format 时给的是 Date 对象，转成 JSON 会按 UTC 算，东八区的当天 0 点会变成前一天 16 点，
 * 所以这里统一用字符串，页面里今天的默认值本来也是这个格式。
 */
import { ref } from 'vue'
import { ArrowRight } from '@/icons'

withDefaults(defineProps<{
  modelValue: string | null | undefined
  label: string
  placeholder?: string
  required?: boolean
  clearable?: boolean
}>(), { placeholder: '请选择' })
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const native = ref<HTMLInputElement | null>(null)
function openPicker() {
  // 电脑浏览器里透明输入框只有点到右侧小图标才弹日历，这里主动叫出来；不支持的浏览器照常走原生点击
  try { native.value?.showPicker?.() } catch { /* 有的浏览器要求必须是用户手势，失败就算了 */ }
}
function onInput(e: Event) { emit('update:modelValue', (e.target as HTMLInputElement).value) }
</script>

<style scoped lang="scss">
.mxd-native { position: absolute; inset: 0; width: 100%; height: 100%; margin: 0; padding: 0; border: 0; opacity: 0; cursor: pointer; }
/* 「清除」要压在透明日期框上面才点得到 */
.mxd-clear { position: relative; z-index: 1; flex: 0 0 auto; padding: 4px 0 4px 6px; border: 0; background: none; font-size: 13px; color: var(--mob-ink-2); cursor: pointer; }
</style>
