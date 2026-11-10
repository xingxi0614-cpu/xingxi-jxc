<template>
  <!--
    工作台「需要你留意」：只显示和当前角色有关、能动手处理的异常（后端 /work/alerts 按角色与账套算好）。
    2026-10-17 起固定占工作台左上一格（与右边「待处理事项」上下边对齐）：没有异常时显示空状态，
    卡片里只列前 4 项，多出来的点「更多」在弹窗里看全。接口失败也只显示空状态，不挡工作台。
  -->
  <el-card shadow="never" class="alerts-card" data-tour="work-alerts">
    <template #header>
      <div class="alerts-head">
        <span class="alerts-title">需要你留意</span>
        <el-tag v-if="items.length" type="danger" size="small" round>{{ items.length }} 项</el-tag>
        <span class="alerts-sub">处理完会自动消失</span>
        <span class="alerts-sp"></span>
        <el-button v-if="items.length > MAX" link type="primary" @click="allVisible = true">更多（{{ items.length }}）</el-button>
      </div>
    </template>
    <div v-if="!items.length" class="alerts-empty">
      <el-icon><component :is="'CircleCheck'" /></el-icon>
      <span>没有需要留意的异常</span>
    </div>
    <div v-for="(a, i) in items.slice(0, MAX)" :key="i" class="alert-row" :class="a.level">
      <div class="alert-body">
        <div class="alert-title">{{ a.title }}</div>
        <div class="alert-detail">{{ a.detail }}</div>
        <div class="alert-who">{{ a.who }}</div>
      </div>
      <el-button type="primary" size="small" @click="go(a)">{{ a.actionText }}</el-button>
    </div>

    <!-- 弹窗挂到 body 上，放在卡片里只是为了组件只有一个根节点（父页面给的 class 才能落到卡片上） -->
    <el-dialog v-model="allVisible" title="需要你留意（全部）" width="min(860px, 94vw)" append-to-body>
      <div v-for="(a, i) in items" :key="i" class="alert-row" :class="a.level">
        <div class="alert-body">
          <div class="alert-title">{{ a.title }}</div>
          <div class="alert-detail">{{ a.detail }}</div>
          <div class="alert-who">{{ a.who }}</div>
        </div>
        <el-button type="primary" size="small" @click="allVisible = false; go(a)">{{ a.actionText }}</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { workApi } from '@/api'

interface AlertItem { level: 'red' | 'yellow' | 'blue'; title: string; detail: string; who: string; actionText: string; path: string; query?: Record<string, string> }

const router = useRouter()
const items = ref<AlertItem[]>([])
// 卡片高度固定，只放得下 4 项
const MAX = 4
const allVisible = ref(false)
let request = 0

async function load() {
  const mine = ++request
  try { const next = (await workApi.alerts()) as AlertItem[] || []; if (mine === request) items.value = next }
  catch { if (mine === request) items.value = [] }
}
function go(a: AlertItem) {
  // auto=1：目标列表页进来就自动查询，不用再点一次"查询"
  router.push({ path: a.path, query: { ...(a.query || {}), auto: '1' } })
}
onMounted(load)
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  load()
})
</script>

<style scoped>
.alerts-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.alerts-sp { flex: 1; }
.alerts-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; height: 100%; min-height: 160px; color: var(--el-text-color-placeholder); font-size: 13px; }
.alerts-empty .el-icon { font-size: 30px; color: var(--el-color-success); }
.alerts-title { font-weight: 600; font-size: 15px; }
.alerts-sub { color: var(--el-text-color-placeholder); font-size: 12px; }
.alert-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px 10px 14px; margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter); border-left-width: 4px; border-radius: 4px;
}
.alert-row:last-child { margin-bottom: 0; }
.alert-row.red { border-left-color: var(--el-color-danger); }
.alert-row.yellow { border-left-color: var(--el-color-warning); }
.alert-row.blue { border-left-color: var(--el-color-primary); }
.alert-body { flex: 1; min-width: 0; }
.alert-title { font-weight: 600; }
.alert-detail { color: var(--el-text-color-regular); font-size: 13px; margin-top: 2px; }
.alert-who { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
@media (max-width: 640px) {
  .alert-row { flex-direction: column; align-items: flex-start; }
}
</style>
