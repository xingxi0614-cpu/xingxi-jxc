<template>
  <!--
    手机首页「需要你留意」：和电脑端工作台同一份数据（后端 /work/alerts 按角色与账套算好，不含金额）。
    没有要紧事时整块不显示；接口失败也不显示，不能挡住审批列表。
  -->
  <div v-if="items.length" class="ma">
    <div class="ma-head"><span class="ma-title">需要你留意</span><span class="ma-cnt">{{ items.length }}</span><span class="ma-sub">处理完自动消失</span></div>
    <div v-for="(a, i) in items" :key="i" class="ma-row" :class="a.level" @click="go(a)">
      <div class="ma-t">{{ a.title }}</div>
      <div class="ma-d">{{ a.detail }}</div>
      <div class="ma-w">{{ a.who }}<span class="ma-go">{{ mobileTarget(a) ? `${a.actionText} ›` : '请在电脑上处理' }}</span></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { workApi } from '@/api'

interface AlertItem { level: 'red' | 'yellow' | 'blue'; title: string; detail: string; who: string; actionText: string; path: string; query?: Record<string, string> }

/** 电脑端页面 → 手机单据浏览里的单据类型。没有对应手机页面的（盘点、月末结账、报表）只提示去电脑上处理。 */
const MOBILE_DOC: Record<string, string> = { '/inbound/recv': 'RECV', '/outbound/out': 'OUT' }

const emit = defineEmits<{ (e: 'open-docs', type: string, query: Record<string, string>): void; (e: 'open-approval'): void }>()
const items = ref<AlertItem[]>([])

function mobileTarget(a: AlertItem) { return a.path === '/work/todo' ? 'APPROVAL' : MOBILE_DOC[a.path] || null }
function go(a: AlertItem) {
  const type = mobileTarget(a)
  if (type === 'APPROVAL') emit('open-approval')
  else if (type) emit('open-docs', type, a.query || {})
}
async function load() {
  try { items.value = (await workApi.alerts()) as AlertItem[] || [] }
  catch { items.value = [] }
}
onMounted(load)
defineExpose({ load })
</script>

<style scoped>
.ma { background: #fff; border-radius: 12px; padding: 12px; margin-bottom: 12px; }
.ma-head { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.ma-title { font-weight: 600; font-size: 15px; }
.ma-cnt { background: var(--el-color-danger); color: #fff; border-radius: 9px; font-size: 12px; line-height: 18px; padding: 0 7px; }
.ma-sub { color: var(--el-text-color-placeholder); font-size: 12px; margin-left: auto; }
.ma-row { border-left: 4px solid var(--el-color-primary); background: var(--el-fill-color-lighter); border-radius: 6px; padding: 8px 10px; margin-top: 8px; }
.ma-row.red { border-left-color: var(--el-color-danger); }
.ma-row.yellow { border-left-color: var(--el-color-warning); }
.ma-t { font-weight: 600; font-size: 14px; line-height: 1.5; }
.ma-d { color: var(--el-text-color-regular); font-size: 13px; line-height: 1.5; margin-top: 2px; }
.ma-w { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; display: flex; gap: 8px; justify-content: space-between; flex-wrap: wrap; }
.ma-go { color: var(--el-color-primary); white-space: nowrap; }
</style>
