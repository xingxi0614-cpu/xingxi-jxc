<template>
  <!-- 单据步骤条：永远回答同一个问题——现在走到哪一步、轮到谁、要做什么 -->
  <div v-if="guide" class="doc-step-guide">
    <el-steps :active="guide.active" :process-status="guide.bad ? 'error' : 'process'" finish-status="success" align-center>
      <el-step v-for="s in guide.steps" :key="s" :title="s" />
    </el-steps>
    <el-alert :type="guide.tone || 'info'" :closable="false" show-icon class="doc-step-next">
      <template #title>
        <span class="doc-step-msg"><b>{{ guide.lead }}</b>{{ guide.text }}</span>
      </template>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import type { StepGuide } from '@/utils/docSteps'
defineProps<{ guide: StepGuide | null }>()
</script>

<style scoped>
.doc-step-guide { margin-bottom: 12px; }
.doc-step-next { margin-top: 10px; }
.doc-step-msg { font-weight: 400; line-height: 1.7; white-space: normal; }
.doc-step-msg b { font-weight: 600; }
:deep(.el-step__title) { font-size: 13px; line-height: 1.5; }
@media (max-width: 640px) {
  :deep(.el-step__title) { font-size: 12px; }
}
</style>
