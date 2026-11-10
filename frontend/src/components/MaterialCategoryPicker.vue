<template>
  <div class="category-picker">
    <el-input
      :model-value="displayText"
      :placeholder="placeholder"
      readonly
      clearable
      @click="open"
      @clear="clear"
    >
      <template #append>
        <el-button @click.stop="open">选择</el-button>
      </template>
    </el-input>

    <el-dialog
      v-model="visible"
      title="物料基本分类"
      width="720px"
      align-center
      append-to-body
      class="material-category-dialog"
    >
      <div class="category-tools">
        <el-input v-model="keyword" placeholder="搜索分类编码或名称" clearable @input="filterTree" />
        <el-checkbox v-model="showDisabled" @change="loadCats">显示停用数据</el-checkbox>
      </div>

      <div class="category-tree-panel">
        <el-tree
          ref="treeRef"
          :data="treeData"
          node-key="id"
          default-expand-all
          show-checkbox
          check-strictly
          highlight-current
          :expand-on-click-node="false"
          :filter-node-method="filterNode"
          @node-click="selectNode"
          @check="checkNode"
        >
          <template #default="{ data }">
            <span class="category-node">
              <span class="category-code">{{ data.code }}</span>
              <span>{{ data.name }}</span>
            </span>
          </template>
        </el-tree>
      </div>

      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button @click="clear">清空</el-button>
        <el-button type="primary" :disabled="!pendingId" @click="confirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { masterApi } from '@/api'

const props = withDefaults(defineProps<{
  modelValue?: number | null
  placeholder?: string
}>(), {
  modelValue: null,
  placeholder: '请选择物料分类'
})

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  change: [value: any | null]
}>()

const visible = ref(false)
const keyword = ref('')
const showDisabled = ref(false)
const cats = ref<any[]>([])
const treeData = ref<any[]>([])
const pendingId = ref<number | null>(props.modelValue)
const treeRef = ref<any>()

const catMap = computed(() => Object.fromEntries(cats.value.map((cat: any) => [cat.id, cat])))
const displayText = computed(() => {
  if (!props.modelValue) return ''
  const cat = catMap.value[props.modelValue]
  return cat ? `${cat.code} ${cat.name}` : `分类 ${props.modelValue}`
})

watch(() => props.modelValue, value => {
  pendingId.value = value ?? null
})

async function loadCats() {
  cats.value = await masterApi.materialCats({ includeDisabled: showDisabled.value }) as any
  treeData.value = buildTree(cats.value)
  await nextTick()
  syncTreeSelection()
  filterTree()
}

function buildTree(list: any[]) {
  const map: Record<string, any> = {}
  const roots: any[] = []
  list.forEach((item: any) => {
    map[item.id] = { ...item, children: [] }
  })
  list.forEach((item: any) => {
    const node = map[item.id]
    const parent = map[item.parentId]
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
}

function open() {
  visible.value = true
  if (!cats.value.length) loadCats()
}

function selectNode(node: any) {
  pendingId.value = node.id
  syncTreeSelection()
}

function checkNode(node: any, checked: any) {
  if (checked.checkedKeys?.includes(node.id)) {
    pendingId.value = node.id
    syncTreeSelection()
    return
  }
  if (pendingId.value === node.id) {
    pendingId.value = null
    syncTreeSelection()
  }
}

function confirm() {
  const cat = pendingId.value ? catMap.value[pendingId.value] : null
  emit('update:modelValue', pendingId.value)
  emit('change', cat || null)
  visible.value = false
}

function clear() {
  pendingId.value = null
  emit('update:modelValue', null)
  emit('change', null)
  syncTreeSelection()
  visible.value = false
}

function filterTree() {
  treeRef.value?.filter(keyword.value)
}

function filterNode(value: string, data: any) {
  if (!value) return true
  const text = `${data.code || ''} ${data.name || ''}`.toLowerCase()
  return text.includes(value.toLowerCase())
}

function syncTreeSelection() {
  nextTick(() => {
    treeRef.value?.setCurrentKey(pendingId.value || null)
    treeRef.value?.setCheckedKeys(pendingId.value ? [pendingId.value] : [])
  })
}

onMounted(loadCats)
</script>

<style>
.material-category-dialog .el-dialog__body {
  padding-top: 14px;
}

.material-category-dialog.el-dialog {
  max-width: 92vw;
}

.category-tools {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.category-tree-panel {
  height: 420px;
  overflow: auto;
  border: 1px solid var(--border-2);
  border-radius: 8px;
  padding: 8px;
  background: var(--el-bg-color);
}

.category-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.category-code {
  min-width: 42px;
  color: var(--color-primary);
  font-weight: 650;
}
</style>
