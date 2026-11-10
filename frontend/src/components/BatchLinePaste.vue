<template>
  <el-dialog v-model="visible" title="批量导入明细" width="min(860px, calc(100vw - 48px))" align-center>
    <el-alert type="info" :closable="false" show-icon class="mb-2">
      <template #title>
        两种用法：① 在 Excel 里选中「{{ colDesc }}」两/三列直接 <b>复制</b>，粘贴到下面文本框；② 下载模板填好后选择文件导入。每行一条，列之间用 Tab 或逗号分隔。
      </template>
    </el-alert>
    <div class="tool-row">
      <el-button size="small" @click="downloadTemplate">下载CSV模板</el-button>
      <el-button size="small" @click="filePick?.click()">选择CSV文件</el-button>
      <input ref="filePick" type="file" accept=".csv,.txt" style="display:none" @change="onFile" />
      <span class="muted">粘贴或导入后点「解析预览」</span>
    </div>
    <el-input v-model="raw" type="textarea" :rows="7" :placeholder="`例如(从Excel复制两列即是此格式):\nM001\t10\nM002\t5\t${priceLabel ? '3.5' : ''}`" />
    <div class="tool-row">
      <el-button type="primary" size="small" :loading="parsing" @click="parse">解析预览</el-button>
      <span v-if="parsed.length" class="muted">共 {{ parsed.length }} 行:有效 {{ validRows.length }},问题 {{ parsed.length - validRows.length }}</span>
    </div>
    <el-table v-if="parsed.length" class="readable-table" :data="parsed" border size="small" max-height="300">
      <el-table-column type="index" label="#" width="46" />
      <el-table-column label="物料编码" prop="code" width="140" />
      <el-table-column label="匹配物料" min-width="200">
        <template #default="{ row }">
          <span v-if="row.material">{{ row.material.name }}<span class="muted">（{{ row.material.spec || '-' }} / {{ row.material.unit || '-' }}）</span></span>
          <span v-else class="err">未找到该编码</span>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="100" align="right">
        <template #default="{ row }"><span :class="{ err: !row.qtyOk }">{{ row.qty ?? '-' }}</span></template>
      </el-table-column>
      <el-table-column v-if="priceLabel" :label="priceLabel" width="130" align="right">
        <template #default="{ row }">{{ row.price ?? '-' }}</template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!validRows.length" @click="apply">填充 {{ validRows.length }} 行</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { masterApi } from '@/api'

const props = withDefaults(defineProps<{
  /** 第三列的名称,如 "含税净价" / "含税单价";不传则只解析 编码+数量 两列 */
  priceLabel?: string
}>(), { priceLabel: '' })

const emit = defineEmits<{ apply: [rows: { material: any; qty: number; price: number | null }[]] }>()

const visible = ref(false)
const raw = ref('')
const parsing = ref(false)
const parsed = ref<any[]>([])
const filePick = ref<HTMLInputElement>()
const matByCode = ref<Record<string, any>>({})

const colDesc = computed(() => props.priceLabel ? `物料编码、数量、${props.priceLabel}(选填)` : '物料编码、数量')
const validRows = computed(() => parsed.value.filter(r => r.material && r.qtyOk))

function open() { raw.value = ''; parsed.value = []; visible.value = true }
defineExpose({ open })

async function ensureMats() {
  if (Object.keys(matByCode.value).length) return
  const list: any = await masterApi.allMaterials({})
  for (const m of (list || [])) matByCode.value[String(m.code).trim().toUpperCase()] = m
}

async function parse() {
  if (!raw.value.trim()) { ElMessage.warning('请先粘贴数据或导入文件'); return }
  parsing.value = true
  try {
    await ensureMats()
    const rows: any[] = []
    for (const line of raw.value.split(/\r?\n/)) {
      if (!line.trim()) continue
      const cols = line.split(/\t|,|，/).map(c => c.trim())
      // 跳过表头行(第2列不是数字视为表头)
      if (cols.length >= 2 && cols[1] && isNaN(Number(cols[1]))) continue
      const code = (cols[0] || '').toUpperCase()
      if (!code) continue
      const qty = cols[1] ? Number(cols[1]) : NaN
      const price = props.priceLabel && cols[2] !== undefined && cols[2] !== '' ? Number(cols[2]) : null
      rows.push({
        code: cols[0],
        material: matByCode.value[code] || null,
        qty: isNaN(qty) ? null : qty,
        qtyOk: !isNaN(qty) && qty > 0,
        price: price !== null && !isNaN(price) ? price : null
      })
    }
    parsed.value = rows
    if (!rows.length) ElMessage.warning('没有解析到有效数据行')
  } finally { parsing.value = false }
}

function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => { raw.value = String(reader.result || ''); parse() }
  reader.readAsText(f)
  ;(e.target as HTMLInputElement).value = ''
}

function downloadTemplate() {
  const head = props.priceLabel ? `物料编码,数量,${props.priceLabel}(选填)` : '物料编码,数量'
  const blob = new Blob(['﻿' + head + '\nM001,10\n'], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '批量导入模板.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

function apply() {
  emit('apply', validRows.value.map(r => ({ material: r.material, qty: r.qty, price: r.price })))
  const skipped = parsed.value.length - validRows.value.length
  ElMessage.success(`已填充 ${validRows.value.length} 行` + (skipped ? `,跳过 ${skipped} 行问题数据` : ''))
  visible.value = false
}
</script>

<style scoped>
.mb-2 { margin-bottom: 10px; }
.tool-row { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
.muted { color: #8a94a6; font-size: 12px; }
.err { color: #d64b46; font-weight: 600; }
</style>
