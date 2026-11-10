<template>
  <div class="supplier-lookup" :style="{ width }">
    <div class="lookup-select-row">
      <el-select
        :model-value="modelValue"
        :placeholder="placeholder"
        filterable
        clearable
        class="lookup-select"
        @change="onSelect"
        @visible-change="onVisible"
      >
        <el-option
          v-for="opt in rows"
          :key="opt.id"
          :label="`${opt.code || ''}  ${opt.name || ''}`.trim()"
          :value="opt.id"
        />
      </el-select>
      <el-button :icon="Search" class="lookup-more-btn" title="浏览 / 高级搜索供应商" @click="open" />
    </div>

    <el-dialog
      v-model="visible"
      title="选择供应商"
      width="1080px"
      align-center
      append-to-body
      class="supplier-lookup-dialog"
    >
      <div class="supplier-lookup-body">
        <aside class="supplier-category">
          <div class="lookup-section-title">供应商基本分类</div>
          <el-input v-model="categoryKeyword" placeholder="搜索分类" clearable />
          <el-tree
            :data="categoryTree"
            node-key="id"
            default-expand-all
            highlight-current
            :expand-on-click-node="false"
            :filter-node-method="filterCategoryNode"
            ref="treeRef"
            @node-click="selectCategory"
          />
        </aside>

        <section class="supplier-list">
          <div class="supplier-search-row">
            <el-input
              v-model="keyword"
              placeholder="输入编码、名称、简称、助记码进行全局搜索"
              clearable
              @keyup.enter="page.current = 1"
            />
            <el-button type="primary" @click="page.current = 1">搜索</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </div>

          <el-table class="readable-table" border
            :data="pagedRows"
            height="390"
            highlight-current-row
            empty-text="暂无供应商"
            @row-click="pending = $event"
            @row-dblclick="selectSupplier"
          >
            <el-table-column type="index" label="序号" width="60" align="center" />
            <el-table-column prop="code" label="供应商编码" width="118" />
            <el-table-column prop="name" label="供应商名称" min-width="200" show-overflow-tooltip />
            <el-table-column prop="supplierCategory" label="基本分类" width="118" show-overflow-tooltip />
            <el-table-column prop="supplierType" label="供应商类型" width="106" show-overflow-tooltip />
            <el-table-column prop="orgName" label="所属组织" min-width="140" show-overflow-tooltip />
          </el-table>

          <el-pagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            :total="filteredRows.length"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
          />
        </section>
      </div>

      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button @click="clear">清空</el-button>
        <el-button type="primary" :disabled="!pending" @click="selectSupplier(pending)">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Search } from '@/icons'
import { masterApi } from '@/api'

const props = withDefaults(defineProps<{
  modelValue?: number | null
  placeholder?: string
  width?: string
}>(), {
  modelValue: null,
  placeholder: '选择或搜索供应商',
  width: '100%'
})

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  change: [row: any | null]
}>()

const visible = ref(false)
const rows = ref<any[]>([])
const selected = ref<any | null>(null)
const pending = ref<any | null>(null)
const keyword = ref('')
const categoryKeyword = ref('')
const selectedCategory = ref('')
const treeRef = ref<any>()
const page = reactive({ current: 1, size: 20 })

/* ===== 内联下拉：可下拉浏览 + 输入即搜（全量已加载，客户端筛选）===== */
async function ensureLoaded() {
  if (!rows.value.length) rows.value = await masterApi.allSuppliers() as any[]
}

function onVisible(v: boolean) {
  if (v) ensureLoaded()
}

function onSelect(id: number | null) {
  if (!id) { clear(); return }
  const row = rows.value.find(r => r.id === id) || selected.value
  selected.value = row || null
  emit('update:modelValue', id)
  emit('change', row || null)
}

const categories = computed(() => {
  const set = new Set<string>()
  rows.value.forEach(row => {
    if (row.supplierCategory) set.add(row.supplierCategory)
  })
  return Array.from(set).sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const categoryTree = computed(() => [
  {
    id: '__all',
    label: '全部供应商',
    children: categories.value.map(name => ({ id: name, label: name }))
  }
])

const filteredRows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    const categoryOk = !selectedCategory.value || row.supplierCategory === selectedCategory.value
    if (!categoryOk) return false
    if (!k) return true
    return [
      row.code,
      row.name,
      row.shortName,
      row.mnemonicCode,
      row.supplierCategory,
      row.supplierType,
      row.orgName
    ].some(value => String(value || '').toLowerCase().includes(k))
  })
})

const pagedRows = computed(() => {
  const start = (page.current - 1) * page.size
  return filteredRows.value.slice(start, start + page.size)
})

watch(categoryKeyword, value => treeRef.value?.filter(value))

watch(() => props.modelValue, value => {
  if (!value) {
    selected.value = null
    return
  }
  if (!selected.value || selected.value.id !== value) {
    resolveSelected()
  }
})

onMounted(() => { if (props.modelValue) resolveSelected() })

async function open() {
  visible.value = true
  pending.value = selected.value
  await nextTick()
  await ensureLoaded()
  if (props.modelValue && !selected.value) await resolveSelected()
  pending.value = selected.value
  await nextTick()
  treeRef.value?.filter(categoryKeyword.value)
}

async function resolveSelected() {
  if (!props.modelValue) return
  await ensureLoaded()
  selected.value = rows.value.find(row => row.id === props.modelValue) || null
}

function selectCategory(node: any) {
  selectedCategory.value = node?.id === '__all' ? '' : node?.id || ''
  page.current = 1
}

function selectSupplier(row: any) {
  if (!row) return
  selected.value = row
  emit('update:modelValue', row.id)
  emit('change', row)
  visible.value = false
}

function clear() {
  selected.value = null
  pending.value = null
  emit('update:modelValue', null)
  emit('change', null)
  visible.value = false
}

function resetFilter() {
  keyword.value = ''
  categoryKeyword.value = ''
  selectedCategory.value = ''
  pending.value = null
  page.current = 1
  treeRef.value?.setCurrentKey('__all')
}

function filterCategoryNode(value: string, data: any) {
  if (!value) return true
  return String(data.label || '').toLowerCase().includes(value.toLowerCase())
}
</script>

<style>
/* 内联触发器：占满给定宽度，下拉自适应、浏览按钮并排（保证和相邻查询字段宽度一致） */
.supplier-lookup { display: inline-block; vertical-align: middle; max-width: 100%; }
.supplier-lookup .lookup-select-row { display: flex; align-items: center; gap: 6px; }
.supplier-lookup .lookup-select { flex: 1 1 auto; min-width: 0; }
.supplier-lookup .lookup-more-btn { flex: 0 0 auto; }

.supplier-lookup-dialog .el-dialog__body {
  padding-top: 14px;
}

.supplier-lookup-dialog.el-dialog {
  max-width: 94vw;
}

.supplier-lookup-body {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
  min-height: 470px;
}

.supplier-category {
  border: 1px solid var(--border-2);
  border-radius: 8px;
  padding: 12px;
  overflow: auto;
  background: var(--el-bg-color);
}

.lookup-section-title {
  margin-bottom: 10px;
  color: var(--text-1);
  font-weight: 650;
}

.supplier-search-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 10px;
  margin-bottom: 10px;
}

.supplier-list .el-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .supplier-lookup-body {
    grid-template-columns: 1fr;
  }

  .supplier-category {
    max-height: 240px;
  }
}
</style>
