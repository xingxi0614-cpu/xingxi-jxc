<template>
  <!--
    单据号查询框:
    ① 点进输入框就列出最近 50 张,打字在全部单据里按单号模糊找,点一下选上;也可以只打一段单号直接查;
    ② 右边放大镜打开选择表,带日期/供应商/仓库/状态,看清楚再选,multiple 时可勾多张(逗号分隔,列表接口认)。
  -->
  <div class="doc-lookup" :style="{ width }">
    <el-autocomplete
      :model-value="modelValue"
      :fetch-suggestions="suggest"
      :placeholder="placeholder"
      :debounce="250"
      clearable
      class="doc-lookup__input"
      popper-class="doc-lookup-popper"
      @update:model-value="v => emit('update:modelValue', String(v ?? ''))"
      @select="(row: any) => emit('update:modelValue', row.docNo)"
      @keyup.enter="emit('enter')"
    >
      <template #default="{ item }">
        <div class="doc-lookup__opt">
          <span class="doc-lookup__no">{{ item.docNo }}</span>
          <small class="doc-lookup__sub">{{ subLine(item) }}</small>
        </div>
      </template>
    </el-autocomplete>
    <el-button :icon="Search" class="doc-lookup__mag" :title="src.title" @click="openPicker" />

    <el-dialog v-model="pickerVisible" :title="src.title" width="1000px" align-center append-to-body class="doc-lookup-dialog">
      <div class="doc-lookup__bar">
        <el-input v-model="pickerKeyword" placeholder="输入单号的一段缩小范围(在全部单据里找)" clearable @keyup.enter="loadPicker" @clear="loadPicker" />
        <el-button type="primary" @click="loadPicker">搜索</el-button>
      </div>
      <el-table
        ref="tableRef"
        v-loading="pickerLoading"
        class="readable-table"
        border
        height="380"
        :data="pickerRows"
        highlight-current-row
        empty-text="没有可选的单据"
        @row-click="onRowClick"
        @row-dblclick="pickOne"
        @selection-change="(rows: any[]) => (picked = rows)"
      >
        <el-table-column v-if="multiple" type="selection" width="44" />
        <el-table-column v-for="c in src.columns" :key="c.prop" :prop="c.prop" :label="c.label" :width="c.width" :min-width="c.width ? undefined : 140" show-overflow-tooltip :formatter="c.prop === 'materialSummary' ? (r: any) => fmtMaterials(r.materialSummary) : undefined" />
      </el-table>
      <div class="doc-lookup__tip">默认列出最近 50 张；{{ multiple ? '可勾选多张，' : '' }}双击一行直接选中</div>
      <template #footer>
        <el-button @click="pickerVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmPicker">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { fmtMaterials } from '@/utils/format'
import { computed, ref } from 'vue'
import { Search } from '@/icons'
import { DOC_SOURCES, fetchDocs } from '@/utils/doc-lookup'

const props = withDefaults(defineProps<{
  modelValue?: string | null
  /** 单据类型:PO / RECV / PRET / STOCKTAKE,见 utils/doc-lookup.ts */
  type: string
  placeholder?: string
  width?: string
  /** 允许勾选多张(该列表的单号关键字支持逗号分隔多张时才开) */
  multiple?: boolean
  /** 附加给列表接口的筛选,如暂估页只列已审批的暂估入库单 { recvType: 'ESTIMATE', status: 'APPROVED' } */
  params?: Record<string, unknown>
}>(), { modelValue: '', placeholder: '点一下列出最近单据，也可输入单号', width: '100%', multiple: false, params: () => ({}) })

const emit = defineEmits<{ 'update:modelValue': [value: string]; enter: [] }>()

const src = computed(() => {
  const s = DOC_SOURCES[props.type]
  if (!s) throw new Error(`DocNoLookup: 未登记的单据类型 ${props.type}`)
  return s
})

function subLine(row: any) {
  return [row[src.value.dateProp], row.supplierName, row.warehouseName, row.statusText].filter(Boolean).join(' · ')
}

async function suggest(keyword: string, cb: (rows: any[]) => void) {
  // 已选多张(逗号分隔)时不再拿整串去搜
  cb(keyword && keyword.includes(',') ? [] : await fetchDocs(src.value, keyword || '', props.params))
}

const pickerVisible = ref(false)
const pickerKeyword = ref('')
const pickerRows = ref<any[]>([])
const pickerLoading = ref(false)
const picked = ref<any[]>([])
const current = ref<any | null>(null)
const tableRef = ref<any>()

async function loadPicker() {
  pickerLoading.value = true
  try { pickerRows.value = await fetchDocs(src.value, pickerKeyword.value, props.params) } finally { pickerLoading.value = false }
}

function openPicker() {
  pickerKeyword.value = props.modelValue && !props.modelValue.includes(',') ? props.modelValue : ''
  picked.value = []
  current.value = null
  pickerVisible.value = true
  loadPicker()
}

function onRowClick(row: any) {
  current.value = row
  if (props.multiple) tableRef.value?.toggleRowSelection(row)
}

function pickOne(row: any) {
  emit('update:modelValue', row.docNo)
  pickerVisible.value = false
}

function confirmPicker() {
  const rows = props.multiple && picked.value.length ? picked.value : current.value ? [current.value] : []
  if (rows.length) emit('update:modelValue', rows.map(r => r.docNo).join(','))
  pickerVisible.value = false
}
</script>

<style scoped>
.doc-lookup {
  display: flex;
  min-width: 0;
}

.doc-lookup__input {
  flex: 1;
  min-width: 0;
}

.doc-lookup__input :deep(.el-input__wrapper) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

.doc-lookup__mag {
  margin-left: -1px;
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}

.doc-lookup__opt {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  padding: 4px 0;
}

.doc-lookup__no {
  white-space: nowrap;
}

.doc-lookup__sub {
  color: var(--text-3);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-lookup__bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.doc-lookup__tip {
  margin-top: 8px;
  color: var(--text-3);
  font-size: 12px;
}
</style>

<style>
/* 下拉每项两行(单号 + 日期/供应商/仓库/状态),放宽一点免得截断 */
.doc-lookup-popper {
  min-width: 360px;
}

.doc-lookup-popper li {
  line-height: normal !important;
}
</style>
