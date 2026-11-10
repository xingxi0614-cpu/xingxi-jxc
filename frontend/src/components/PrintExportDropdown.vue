<template>
  <el-popover
    v-model:visible="visible"
    trigger="click"
    placement="bottom-end"
    popper-class="print-export-popper"
    :width="352"
    :disabled="disabled"
  >
    <template #reference>
      <el-button class="print-export-trigger" :type="type" :disabled="disabled">
        <el-icon><Printer /></el-icon>
        <span>{{ label }}</span>
        <el-icon class="trigger-arrow"><ArrowDown /></el-icon>
      </el-button>
    </template>

    <div class="print-export-panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">打印与导出</div>
          <div class="panel-subtitle">先选纸张，再执行对应操作</div>
        </div>
      </div>

      <div class="paper-grid" role="radiogroup" aria-label="纸张尺寸">
        <button
          v-for="paper in papers"
          :key="paper.value"
          type="button"
          class="paper-card"
          :class="{ active: selectedPaper === paper.value }"
          role="radio"
          :aria-checked="selectedPaper === paper.value"
          @click="selectedPaper = paper.value"
        >
          <span class="paper-name">{{ paper.value }}</span>
          <span class="paper-desc">{{ paper.desc }}</span>
        </button>
      </div>

      <div class="orient-row" role="radiogroup" aria-label="打印方向">
        <button
          v-for="o in orientations"
          :key="o.value"
          type="button"
          class="orient-btn"
          :class="{ active: selectedOrientation === o.value }"
          role="radio"
          :aria-checked="selectedOrientation === o.value"
          @click="selectedOrientation = o.value"
        >{{ o.label }}</button>
      </div>

      <div class="action-row">
        <el-button class="action-btn primary" type="primary" @click="run('print')">
          <el-icon><Printer /></el-icon>
          打印 / 存PDF
        </el-button>
        <el-button class="action-btn" @click="run('export')">
          <el-icon><Download /></el-icon>
          导出Excel
        </el-button>
      </div>
      <div class="panel-foot">打印预览里点「打印」，在系统打印框中选「另存为 PDF」即可导出 PDF</div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ArrowDown, Download, Printer } from '@/icons'
import type { PaperAction, PaperSize, PrintOrientation } from '@/utils/printExport'

const props = withDefaults(defineProps<{
  label?: string
  disabled?: boolean
  type?: '' | 'primary' | 'success' | 'warning' | 'danger' | 'info'
  defaultOrientation?: PrintOrientation
}>(), {
  label: '打印/导出',
  disabled: false,
  type: '',
  defaultOrientation: 'portrait'
})

const emit = defineEmits<{
  print: [paper: PaperSize, orientation: PrintOrientation]
  export: [paper: PaperSize, orientation: PrintOrientation]
}>()

const visible = ref(false)
const selectedPaper = ref<PaperSize>('A4')
const papers: Array<{ value: PaperSize; desc: string }> = [
  { value: 'A4', desc: '常用签字单' },
  { value: 'B5', desc: '紧凑归档' }
]
const selectedOrientation = ref<PrintOrientation>(props.defaultOrientation)
const orientations: Array<{ value: PrintOrientation; label: string }> = [
  { value: 'portrait', label: '纵向' },
  { value: 'landscape', label: '横向（宽表更全）' }
]

function run(action: PaperAction) {
  if (action === 'print') emit('print', selectedPaper.value, selectedOrientation.value)
  else emit('export', selectedPaper.value, selectedOrientation.value)
  visible.value = false
}
</script>

<style scoped lang="scss">
.print-export-trigger {
  gap: 7px;
}

.trigger-arrow {
  margin-left: 2px;
  font-size: 12px;
  opacity: .76;
}

.print-export-panel {
  padding: 4px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 12px;
}

.panel-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1, #0f1f3a);
}

.panel-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3, #7a8799);
}

.paper-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.paper-card {
  min-height: 72px;
  padding: 11px 8px;
  border: 1px solid var(--border-1, rgba(232, 227, 218, .92));
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(255, 253, 248, .96), rgba(250, 247, 242, .92));
  color: var(--text-2, #46566d);
  cursor: pointer;
  text-align: left;
  box-shadow: 0 8px 18px rgba(44, 36, 22, .06);
  transition: transform .16s ease, border-color .16s ease, box-shadow .16s ease, background .16s ease;
}

.paper-card:hover {
  transform: translateY(-1px);
  border-color: rgba(200, 149, 74, .34);
  box-shadow: 0 12px 24px rgba(44, 36, 22, .10);
}

.paper-card.active {
  border-color: rgba(200, 149, 74, .52);
  background: linear-gradient(135deg, rgba(255, 253, 248, .96), rgba(244, 239, 230, .92));
  box-shadow: 0 14px 28px rgba(44, 36, 22, .12), inset 0 0 0 1px rgba(255, 255, 255, .72);
}

.paper-name,
.paper-desc {
  display: block;
}

.paper-name {
  font-size: 18px;
  font-weight: 800;
  color: var(--color-primary, #2d3b4f);
  line-height: 1;
}

.paper-desc {
  margin-top: 9px;
  font-size: 12px;
  color: var(--text-3, #7a8799);
  white-space: nowrap;
}

.orient-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}

.orient-btn {
  height: 38px;
  border: 1px solid var(--border-1, rgba(232, 227, 218, .92));
  border-radius: 11px;
  background: linear-gradient(180deg, rgba(255, 253, 248, .96), rgba(250, 247, 242, .92));
  color: var(--text-2, #46566d);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease;
}

.orient-btn:hover {
  border-color: rgba(200, 149, 74, .34);
}

.orient-btn.active {
  border-color: rgba(200, 149, 74, .52);
  color: var(--color-primary, #2d3b4f);
  background: linear-gradient(135deg, rgba(255, 253, 248, .96), rgba(244, 239, 230, .92));
  box-shadow: 0 8px 18px rgba(44, 36, 22, .10), inset 0 0 0 1px rgba(255, 255, 255, .72);
}

:global(html[data-theme='dark']) .orient-btn {
  background: linear-gradient(180deg, rgba(28, 40, 58, .96), rgba(19, 29, 45, .94));
  border-color: rgba(200, 149, 74, .18);
}

:global(html[data-theme='dark']) .orient-btn.active {
  background: linear-gradient(135deg, rgba(45, 59, 79, .78), rgba(154, 107, 44, .58));
  border-color: rgba(200, 149, 74, .38);
  color: #fff;
}

.action-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding-top: 14px;
}

.action-btn {
  width: 100%;
  height: 40px;
  border-radius: 13px;
}

.action-btn.primary {
  box-shadow: 0 12px 22px rgba(45, 59, 79, .18);
}

.panel-foot {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-3, #7a8799);
}

:global(.print-export-popper) {
  border-radius: 18px !important;
  padding: 14px !important;
  border: 1px solid var(--border-1, rgba(232, 227, 218, .88)) !important;
  box-shadow: 0 22px 44px rgba(44, 36, 22, .14) !important;
}

:global(html[data-theme='dark'] .print-export-popper) {
  background: #142033 !important;
  border-color: rgba(200, 149, 74, .18) !important;
}

:global(html[data-theme='dark']) {
  .paper-card {
    background: linear-gradient(180deg, rgba(28, 40, 58, .96), rgba(19, 29, 45, .94));
    border-color: rgba(200, 149, 74, .18);
  }

  .paper-card.active {
    background: linear-gradient(135deg, rgba(45, 59, 79, .78), rgba(154, 107, 44, .58));
    border-color: rgba(200, 149, 74, .38);
  }
}
</style>
