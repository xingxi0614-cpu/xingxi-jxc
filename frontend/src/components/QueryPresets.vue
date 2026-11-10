<template>
  <!--
    查询面板「常用方案」(2026-10-20 用户立项):把这一页现在的查询条件存成一个名字,下次一点就按它查。
    跟着账号走(存在后端用户偏好里,换电脑也在),每页最多 10 个。
    只保存「清空值」能清掉的条件(页面查询对象里的字段);单独的日期区间这类不在查询对象里的,暂不保存。
  -->
  <el-dropdown trigger="click" :disabled="!model" @command="onCommand" @visible-change="v => v && load()">
    <el-button :link="link" :type="link ? 'primary' : undefined">常用方案<span v-if="list.length">（{{ list.length }}）</span> ▾</el-button>
    <template #dropdown>
      <el-dropdown-menu class="qp-presets">
        <el-dropdown-item v-for="(p, i) in list" :key="p.name" :command="'use:' + i">
          <span class="qp-preset-name">{{ p.name }}</span>
          <span class="qp-preset-x" title="删除这个方案" @click.stop="remove(i)">×</span>
        </el-dropdown-item>
        <el-dropdown-item v-if="!list.length" disabled>还没有保存过方案</el-dropdown-item>
        <el-dropdown-item divided command="save">把当前条件存为方案…</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { prefApi } from '@/api'
import { emptyLike } from '@/utils/query-panel'

interface Preset { name: string; values: Record<string, unknown> }

const props = defineProps<{
  /** 页面的查询对象(QueryDrawer 的 model) */
  model?: Record<string, any>
  /** 本页查询条件对应的字段名(表单项上的 data-q) */
  keys: string[]
  link?: boolean
}>()
const emit = defineEmits<{ apply: [] }>()

const MAX_PER_PAGE = 10
const route = useRoute()
/** 后端键:query.presets: + 页面路径(后端白名单只认小写字母/数字/斜杠/短横线,总长 ≤ 48) */
const prefKey = computed(() => 'query.presets:' + route.path.toLowerCase().replace(/[^a-z0-9/-]/g, '').slice(0, 33))
const list = ref<Preset[]>([])
let loaded = false

async function load(force = false) {
  if (loaded && !force) return
  try {
    const all: any = await prefApi.all()
    const raw = all?.[prefKey.value]
    list.value = raw ? (JSON.parse(raw) as Preset[]).filter(p => p && p.name && p.values) : []
    loaded = true
  } catch { list.value = [] }
}
load()

/** 不保存「空」的值:空串、空数组、null —— 套用时这些字段一律清空 */
function isEmpty(v: unknown) {
  return v == null || v === '' || (Array.isArray(v) && !v.length)
}

async function persist(next: Preset[]) {
  await prefApi.put(prefKey.value, next)
  list.value = next
}

async function save() {
  const model = props.model
  if (!model) return
  const values: Record<string, unknown> = {}
  for (const k of props.keys) if (k in model && !isEmpty(model[k])) values[k] = JSON.parse(JSON.stringify(model[k]))
  if (!Object.keys(values).length) { ElMessage.warning('现在没有设任何条件，先设好条件再保存'); return }
  await load(true)
  let name = ''
  try {
    name = (await ElMessageBox.prompt(`给这组条件起个名字（共 ${Object.keys(values).length} 个条件），下次在「常用方案」里一点就按它查。`, '存为常用方案', {
      inputPlaceholder: '如：本月总仓',
      inputValidator: v => (!!v?.trim() && v.trim().length <= 20) || '请填 1～20 个字',
      confirmButtonText: '保存', cancelButtonText: '取消'
    })).value.trim()
  } catch { return }
  const others = list.value.filter(p => p.name !== name)
  if (others.length >= MAX_PER_PAGE) { ElMessage.warning(`每页最多存 ${MAX_PER_PAGE} 个方案，请先删掉不用的`); return }
  await persist([...others, { name, values }])
  ElMessage.success(`已保存方案「${name}」`)
}

function use(i: number) {
  const p = list.value[i]
  const model = props.model
  if (!p || !model) return
  // 先把本页能清的条件都清掉,再填方案里的值——不然上次的条件会和方案混在一起
  for (const k of props.keys) if (k in model) model[k] = k in p.values ? JSON.parse(JSON.stringify(p.values[k])) : emptyLike(model[k])
  if ('current' in model) model.current = 1
  emit('apply')
}

async function remove(i: number) {
  const p = list.value[i]
  if (!p) return
  try {
    await ElMessageBox.confirm(`删除常用方案「${p.name}」？`, '删除方案', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  await persist(list.value.filter((_, j) => j !== i))
}

function onCommand(cmd: string) {
  if (cmd === 'save') save()
  else if (cmd.startsWith('use:')) use(Number(cmd.slice(4)))
}
</script>

<style>
.qp-presets .el-dropdown-menu__item { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-width: 200px; }
.qp-preset-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; }
.qp-preset-x { color: var(--text-3); font-size: 16px; line-height: 1; padding: 0 2px; border-radius: 4px; }
.qp-preset-x:hover { color: var(--el-color-danger); background: var(--bg-panel-hover); }
</style>
