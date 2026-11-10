<template>
  <el-dialog
    v-model="state.visible"
    :title="`打印预览 · ${state.paper}`"
    width="min(1180px, 96vw)"
    top="3vh"
    append-to-body
    class="print-preview-dialog"
    @opened="onOpened"
  >
    <div class="pp-toolbar">
      <span class="pp-tip">以下为 <b>{{ state.paper }}</b> 版面效果，确认无误后点「打印」即可直接出单</span>
      <span class="pp-spacer" />
      <el-button @click="state.visible = false">关闭</el-button>
      <el-button type="primary" :icon="Printer" @click="doPrint">打印</el-button>
    </div>
    <iframe ref="frame" class="pp-frame" :srcdoc="state.html" title="打印预览" />
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Printer } from '@/icons'
import { printPreviewState } from '@/composables/usePrintPreview'

// 弹框宽 1180：按 A4 横版(277mm≈1047px)放得下来定，否则横版报表在预览里右侧被裁，用户会以为打印出格
const state = printPreviewState
const frame = ref<HTMLIFrameElement | null>(null)

function onOpened() {
  frame.value?.focus()
}

function doPrint() {
  const win = frame.value?.contentWindow
  if (win) {
    win.focus()
    win.print()
  }
}
</script>

<style scoped lang="scss">
.pp-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.pp-tip { font-size: 12px; color: var(--text-3, #7a8799); }
.pp-spacer { flex: 1; }
.pp-frame {
  width: 100%;
  height: 78vh;
  border: 1px solid var(--border-2, #e4e7ed);
  border-radius: 6px;
  background: #eef2f7;
}
:global(.print-preview-dialog .el-dialog__body) { padding-top: 10px; }
</style>
