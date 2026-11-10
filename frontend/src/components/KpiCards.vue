<template>
  <!--
    页面顶部的汇总数字卡（2026-10-17 全站界面改版，原型 Rcpt_List / R_OutSum）。
    一排等宽卡片，列数 = 卡片数（最多 5）；下方并排的面板应按同一套列宽对齐（见 .kpi-aligned-cols）。
    tone：amber=需要留意、red=要处理、blue=重点结果；不传为普通。
  -->
  <div class="kpi-cards" :style="{ gridTemplateColumns: `repeat(${cols || Math.min(items.length, 5) || 1}, minmax(0, 1fr))` }">
    <div v-for="k in items" :key="k.label" class="kpi-card" :class="k.tone ? `tone-${k.tone}` : ''">
      <span class="kpi-l">{{ k.label }}</span>
      <span class="kpi-v">{{ k.value }}</span>
      <span v-if="k.hint" class="kpi-h">{{ k.hint }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
export interface KpiItem { label: string; value: string | number; hint?: string; tone?: 'amber' | 'red' | 'blue' | 'green' }
/** cols：每行几张；不传 = 卡片数（最多 5）。6 张时传 3，排成整齐的两行 */
defineProps<{ items: KpiItem[]; cols?: number }>()
</script>

<style scoped>
.kpi-cards { display: grid; gap: 12px; margin-bottom: 12px; }
.kpi-card {
  background: var(--bg-panel); border: 1px solid var(--table-grid); border-radius: 12px;
  padding: 14px 18px; display: flex; flex-direction: column; gap: 4px; min-width: 0;
}
.kpi-l { font-size: 13px; color: var(--text-2); }
.kpi-v { font-size: 22px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--text-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kpi-h { font-size: 12px; color: var(--text-2); }
.tone-amber { border-color: #f1d9b5; background: #fffbf4; }
.tone-amber .kpi-l, .tone-amber .kpi-v { color: #8a4b05; }
.tone-red { border-color: #f0c9c4; background: #fff8f7; }
.tone-red .kpi-l, .tone-red .kpi-v { color: #b42318; }
.tone-blue { border-color: #d2e0f2; background: #f7fafe; }
.tone-blue .kpi-l, .tone-blue .kpi-v { color: #174a88; }
.tone-green .kpi-v { color: #17633d; }
</style>
