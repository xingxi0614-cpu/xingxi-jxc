<template>
  <div class="material-lookup">
    <div class="lookup-select-row">
      <el-select
        :model-value="modelValue"
        :placeholder="placeholder"
        filterable
        remote
        clearable
        remote-show-suffix
        :remote-method="remoteSearch"
        :loading="optLoading"
        class="lookup-select"
        @change="onSelect"
        @visible-change="onVisible"
      >
        <el-option
          v-for="opt in options"
          :key="opt.id"
          :label="opt.code"
          :value="opt.id"
        >
          <span class="lk-opt-code">{{ opt.code }}</span>
          <span class="lk-opt-name">{{ opt.name }}</span>
        </el-option>
      </el-select>
      <el-button :icon="Search" class="lookup-more-btn" title="浏览 / 高级搜索物料" @click="open" />
    </div>

    <el-dialog
      v-model="visible"
      title="选择物料"
      width="920px"
      align-center
      append-to-body
      class="material-lookup-dialog"
    >
      <div class="material-lookup-body">
        <section class="material-lookup-list">
          <div class="material-search-row">
            <el-input
              v-model="materialKeyword"
              placeholder="输入编码、名称或规格进行全局搜索"
              clearable
              @keyup.enter="loadMaterials"
              @clear="loadMaterials"
            />
            <el-button type="primary" @click="loadMaterials">搜索</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </div>
          <el-table class="readable-table" border
            :data="materials"
            v-loading="loading"
            height="390"
            highlight-current-row
            @row-click="pending = $event"
            @row-dblclick="selectMaterial"
          >
            <el-table-column type="index" label="序号" width="60" align="center" />
            <el-table-column prop="code" label="物料编码" width="150">
              <template #default="{ row }">
                <button type="button" class="material-code-link" @click.stop="selectMaterial(row)">{{ row.code }}</button>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="物料名称" min-width="220" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格型号" min-width="200" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="90" align="center" />
          </el-table>
          <el-pagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            :total="total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadMaterials"
            @current-change="loadMaterials"
          />
        </section>
      </div>

      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button @click="clear">清空</el-button>
        <el-button type="primary" :disabled="!pending" @click="selectMaterial(pending)">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Search } from '@/icons'
import { masterApi } from '@/api'

const props = withDefaults(defineProps<{
  modelValue?: number | null
  placeholder?: string
}>(), {
  modelValue: null,
  placeholder: '选择或搜索物料'
})

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  change: [row: any | null]
}>()

const visible = ref(false)
const loading = ref(false)
const materialKeyword = ref('')
const materials = ref<any[]>([])
const total = ref(0)
const page = reactive({ current: 1, size: 20 })
const pending = ref<any | null>(null)
const selected = ref<any | null>(null)

/* ===== 内联下拉：可下拉浏览 + 输入即搜 ===== */
const options = ref<any[]>([])
const optLoading = ref(false)

function ensureOption(row: any | null) {
  if (row && !options.value.some(o => o.id === row.id)) options.value = [row, ...options.value]
}

async function remoteSearch(q: string) {
  optLoading.value = true
  try {
    const r: any = await masterApi.materials({ current: 1, size: 50, keyword: q || '', pool: 1 })
    options.value = r.records || []
    ensureOption(selected.value)
  } finally {
    optLoading.value = false
  }
}

function onVisible(v: boolean) {
  if (v && !options.value.length) remoteSearch('')
}

function onSelect(id: number | null) {
  if (!id) { clear(); return }
  const row = options.value.find(o => o.id === id) || selected.value
  selected.value = row || null
  emit('update:modelValue', id)
  emit('change', row || null)
}

/* ===== 预选解析（编辑已有单据时回显标签）===== */
async function resolveSelected() {
  const list = await masterApi.allMaterials({ includeDisabled: true }) as any[]
  selected.value = list.find(row => row.id === props.modelValue) || null
  ensureOption(selected.value)
}

watch(() => props.modelValue, value => {
  if (!value) { selected.value = null; return }
  if (!selected.value || selected.value.id !== value) resolveSelected()
})

onMounted(() => { if (props.modelValue) resolveSelected() })

/* ===== 弹窗：浏览 / 高级搜索（大列表用）===== */
async function open() {
  visible.value = true
  pending.value = selected.value
  await nextTick()
  if (props.modelValue && !selected.value) await resolveSelected()
  if (!materials.value.length) await loadMaterials()
  pending.value = selected.value
}

async function loadMaterials() {
  loading.value = true
  try {
    const r: any = await masterApi.materials({
      current: page.current,
      size: page.size,
      keyword: materialKeyword.value,
      pool: 1
    })
    materials.value = r.records || []
    total.value = r.total || 0
  } finally {
    loading.value = false
  }
}

function selectMaterial(row: any) {
  if (!row) return
  selected.value = row
  ensureOption(row)
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
  materialKeyword.value = ''
  page.current = 1
  loadMaterials()
}
</script>

<style>
.material-lookup,
.supplier-lookup {
  width: 100%;
}
/* 物料下拉选项：编码 + 灰色名称（选中后输入框只显示编码）*/
.lk-opt-code { font-weight: 500; }
.lk-opt-name { margin-left: 10px; color: var(--text-3); }
.lookup-select-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.lookup-select-row .lookup-select {
  flex: 1 1 auto;
  min-width: 0;
}
.lookup-select-row .lookup-more-btn {
  flex: 0 0 auto;
  padding: 8px 11px;
}

.material-lookup-dialog .el-dialog__body {
  padding-top: 14px;
}

.material-lookup-dialog.el-dialog {
  max-width: 94vw;
}

.material-lookup-body {
  min-height: 470px;
}

.material-lookup-list {
  min-width: 0;
}

.material-code-link {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  cursor: pointer;
}

.material-code-link:hover {
  text-decoration: underline;
}

.material-search-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 10px;
  margin-bottom: 10px;
}

.material-lookup-list .el-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
