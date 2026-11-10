<template>
  <!-- 列表顶部的状态页签：一键按状态筛选，并显示每种状态有几张（2026-10-17 全站界面改版，原型 Out_List） -->
  <div class="status-chips" role="tablist" aria-label="按状态筛选">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      role="tab"
      class="status-chip"
      :class="{ on: modelValue === opt.value }"
      :aria-selected="modelValue === opt.value"
      @click="pick(opt.value)"
    >
      {{ opt.label }}<span v-if="counts[opt.value] != null" class="status-chip__n">{{ fmtCount(counts[opt.value]) }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
export interface StatusChipOption { label: string; value: string }

const props = defineProps<{
  options: StatusChipOption[]
  modelValue: string
  /** 每个状态值对应的条数；还没查到的不显示数字（不显示 0，免得误以为"没有"） */
  counts: Record<string, number | undefined>
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void; (e: 'change', v: string): void }>()

function pick(v: string) {
  if (v === props.modelValue) return
  emit('update:modelValue', v)
  emit('change', v)
}
function fmtCount(n: number | undefined) {
  return n == null ? '' : Number(n).toLocaleString('en-US')
}
</script>

<style scoped>
.status-chips { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 12px; }
.status-chip {
  height: 28px; padding: 0 12px; border-radius: 999px; border: 1px solid var(--table-grid, #d8dde6);
  background: var(--bg-panel, #fff); color: var(--text-2); font: inherit; font-size: 13px;
  display: inline-flex; align-items: center; gap: 5px; cursor: pointer; white-space: nowrap;
  transition: background-color .15s, border-color .15s, color .15s;
}
.status-chip:hover { border-color: var(--color-primary); color: var(--color-primary); }
.status-chip.on { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.status-chip__n { opacity: .75; font-variant-numeric: tabular-nums; }
.status-chip:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
</style>
