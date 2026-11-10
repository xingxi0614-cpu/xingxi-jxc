<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">正式启用初始化</div>
        <div class="page-sub">清空测试账套数据，只保留 admin 管理员账号和系统配置骨架</div>
      </div>
      <el-button @click="load">刷新状态</el-button>
    </div>

    <el-alert
      type="warning"
      show-icon
      :closable="false"
      title="这是正式启用前的清空操作，执行后业务数据和基础资料不可从系统内恢复。"
      class="mb-3"
    />
    <el-alert
      v-if="preview.enabled === false"
      type="info"
      show-icon
      :closable="false"
      title="当前未开启清空账套开关。仅在计划清空账套时，由运维设置 SCM_INITIALIZATION_ENABLED=true 后才能执行。"
      class="mb-3"
    />

    <KpiCards :items="countCards.map(c => ({ label: c.label, value: counts[c.key] ?? 0 }))" :cols="3" />

    <el-row :gutter="14">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="init-card">
          <template #header>将会保留</template>
          <ul class="check-list keep">
            <li v-for="item in preview.willKeep || []" :key="item">{{ item }}</li>
          </ul>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="init-card">
          <template #header>将会清空</template>
          <ul class="check-list danger">
            <li v-for="item in preview.willClear || []" :key="item">{{ item }}</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="execute-card">
      <div class="execute-row">
        <div>
          <div class="execute-title">执行初始化</div>
          <div class="execute-sub">请输入确认语句：<code>{{ confirmText }}</code></div>
        </div>
        <div class="execute-actions">
          <el-input v-model="inputText" :placeholder="confirmText" :disabled="preview.enabled === false || running" style="width:220px" />
          <el-button type="danger" :disabled="preview.enabled === false || inputText !== confirmText" :loading="running" @click="runReset">
            {{ preview.enabled === false ? '初始化未开启' : '清空并进入正式启用' }}
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api'

const loading = ref(false)
const running = ref(false)
const preview = ref<any>({})
const inputText = ref('')
const confirmText = computed(() => preview.value.confirmText || '确认正式启用')
const counts = computed(() => preview.value.counts || {})

const countCards = [
  { key: 'users', label: '当前用户' },
  { key: 'businessDocs', label: '业务单据' },
  { key: 'suppliers', label: '供应商' },
  { key: 'materials', label: '物料' },
  { key: 'warehouses', label: '仓库' },
  { key: 'projects', label: '工程项目' }
]

async function load() {
  loading.value = true
  try {
    preview.value = await sysApi.initializationPreview() as any
  } finally {
    loading.value = false
  }
}

async function runReset() {
  if (preview.value.enabled === false) {
    ElMessage.warning('正式启用初始化当前未开启。请仅在计划清空账套时设置 SCM_INITIALIZATION_ENABLED=true。')
    return
  }
  const confirmed = await ElMessageBox.confirm(
    '确认清空测试账套数据并进入正式启用状态？该操作会保留 admin 当前密码，但会清空其他用户、组织下级、基础资料、业务单据、库存和待办。',
    '正式启用初始化',
    { type: 'warning', confirmButtonText: '确认初始化', cancelButtonText: '取消' }
  ).catch(() => null)
  if (!confirmed) return
  running.value = true
  try {
    await sysApi.formalReset(inputText.value)
    ElMessage.success('初始化完成，请从组织账套、角色权限和用户管理开始配置')
    inputText.value = ''
    await load()
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.metric {
  min-height: 92px;
  border: 1px solid var(--border-2);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 14px 16px;
}

.metric-label {
  color: var(--text-3);
  font-size: 12px;
}

.metric-value {
  margin-top: 8px;
  color: var(--text-1);
  font-size: 26px;
  font-weight: 700;
}

.init-card {
  min-height: 250px;
}

.check-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text-2);
  line-height: 1.9;
}

.check-list.keep li::marker {
  color: var(--color-primary);
}

.check-list.danger li::marker {
  color: var(--el-color-danger);
}

.execute-card {
  margin-top: 14px;
}

.execute-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.execute-title {
  color: var(--text-1);
  font-weight: 650;
}

.execute-sub {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 12px;
}

.execute-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

@media (max-width: 760px) {
  .execute-row,
  .execute-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .execute-actions .el-input,
  .execute-actions .el-button {
    width: 100% !important;
  }
}
</style>
