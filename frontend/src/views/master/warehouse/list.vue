<template>
  <div class="page">
    <div class="page-head">
      <div><div class="page-title">仓库管理</div></div>
      <el-space>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button type="primary" v-if="user.hasPerm('md.warehouse')" @click="openForm(null)">+ 新建仓库</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="statusTab" :options="STATUS_OPTIONS" :counts="statusCounts" />
      <QueryDrawer :model="query" @search="applySearch" @reset="resetSearch">
        <el-form :inline="true" :model="query" size="default">
          <el-form-item data-q="orgId" label="所属组织">
            <el-select v-model="query.orgId" placeholder="全部组织" clearable filterable>
              <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="warehouseType" label="仓库类型">
            <el-select v-model="query.warehouseType" placeholder="全部类型" clearable style="width:140px">
              <el-option label="总仓" value="MAIN" />
              <el-option label="二级仓" value="SECONDARY" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="keyword" label="仓库信息">
            <el-input v-model="query.keyword" placeholder="仓库编码、名称、地址" clearable @keyup.enter="applySearch" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="shownRows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'">
        <el-table-column prop="code" label="系统编码" width="100" />
        <el-table-column prop="name" label="仓库名称" min-width="200" />
        <el-table-column label="仓库类型" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.warehouseType === 'MAIN' ? 'primary' : 'success'">{{ warehouseTypeLabel(row.warehouseType) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="所属组织" min-width="180">
          <template #default="{ row }">{{ orgName(row.orgId) }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="170" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog draggable v-model="visible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑仓库' : '新建仓库'" width="540px">
      <el-form label-position="top" :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="系统编码" prop="code">
          <el-input v-model="form.code" readonly placeholder="系统自动生成，如 01、02" />
        </el-form-item>
        <el-form-item label="仓库名称" prop="name">
          <el-input v-model="form.name" placeholder="仓库名称" />
        </el-form-item>
        <el-form-item label="仓库类型" prop="warehouseType">
          <el-segmented v-model="form.warehouseType" :options="warehouseTypeOptions" />
        </el-form-item>
        <el-form-item label="所属组织" prop="orgId">
          <el-select v-model="form.orgId" filterable placeholder="请选择组织账套" style="width:100%">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="仓库地址（选填）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.statusBool" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChips from '@/components/StatusChips.vue'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { masterApi, sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import QueryDrawer from '@/components/QueryDrawer.vue'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'

const user = useUserStore()
const loading = ref(false)
const rows = ref<any[]>([])
const orgOptions = ref<any[]>([])
const searched = ref(false)
const query = reactive<any>({ orgId: null, warehouseType: '', keyword: '' })
const warehouseTypeOptions = [
  { label: '总仓', value: 'MAIN' },
  { label: '二级仓', value: 'SECONDARY' }
]
const exportColumns: ExportColumn[] = [
  { label: '系统编码', key: 'code' },
  { label: '仓库名称', key: 'name' },
  { label: '仓库类型', value: row => warehouseTypeLabel(row.warehouseType) },
  { label: '所属组织', value: row => orgName(row.orgId) },
  { label: '地址', key: 'address' },
  { label: '状态', value: row => row.status ? '启用' : '停用' }
]

function flattenOrgs(nodes: any[], level = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, label: `${'　'.repeat(level)}${node.name}（${node.code}）`, name: node.name, type: node.type, status: node.status })
    flattenOrgs(node.children || [], level + 1, out)
  }
  return out
}

function warehouseTypeLabel(type: string) {
  return type === 'MAIN' ? '总仓' : '二级仓'
}

function orgName(id: number) {
  return orgOptions.value.find(o => o.id === id)?.name || id || '-'
}

// 「全部 / 启用 / 已停用」页签(2026-10-17):本页一次取回全部仓库,直接在页面上按状态分开看
const statusTab = ref('')
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '启用', value: '1' }, { label: '已停用', value: '0' }]
const statusCounts = computed(() => ({ '': rows.value.length, '1': rows.value.filter((r: any) => Number(r.status) === 1).length, '0': rows.value.filter((r: any) => Number(r.status) !== 1).length }))
const byStatus = (list: any[]) => statusTab.value === '' ? list : list.filter((r: any) => (Number(r.status) === 1) === (statusTab.value === '1'))
const shownRows = computed(() => byStatus(rows.value))

async function load() {
  searched.value = true
  loading.value = true
  try {
    rows.value = await masterApi.warehouses({
      includeDisabled: true,
      orgId: query.orgId || undefined,
      warehouseType: query.warehouseType || undefined,
      keyword: query.keyword || undefined
    }) as any
  } finally {
    loading.value = false
  }
}

function applySearch() {
  return load()
}

function resetSearch() {
  Object.assign(query, { orgId: null, warehouseType: '', keyword: '' })
  load()
}

function exportExcel() {
  exportRowsToExcel('仓库管理', exportColumns, shownRows.value, '仓库管理')
}

async function loadOptions() {
  orgOptions.value = flattenOrgs(await sysApi.orgTree() as any[])
    .filter(o => o.status !== 0 && ['GROUP', 'COMPANY'].includes(o.type))
}

const visible = ref(false)
const dialogGuard = useUnsavedGuard(visible)
const saving = ref(false)
const formRef = ref<any>(null)
const form = reactive<any>({ id: null, code: '', name: '', warehouseType: 'SECONDARY', orgId: null, address: '', statusBool: true })
const rules = {
  name: [{ required: true, message: '请填写仓库名称', trigger: 'blur' }],
  warehouseType: [{ required: true, message: '请选择仓库类型', trigger: 'change' }],
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }]
}

function openForm(row: any) {
  if (row) {
    Object.assign(form, {
      id: row.id,
      code: row.code,
      name: row.name,
      warehouseType: row.warehouseType || 'SECONDARY',
      orgId: row.orgId,
      address: row.address || '',
      statusBool: !!row.status
    })
  } else {
    Object.assign(form, {
      id: null,
      code: '',
      name: '',
      warehouseType: 'SECONDARY',
      orgId: orgOptions.value[0]?.id,
      address: '',
      statusBool: true
    })
  }
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload = {
      id: form.id,
      code: form.code,
      name: form.name,
      warehouseType: form.warehouseType,
      orgId: form.orgId,
      address: form.address,
      status: form.statusBool ? 1 : 0
    }
    await masterApi.saveWarehouse(payload)
    ElMessage.success(form.id ? '已更新' : '已创建')
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除 ${row.name}？`, '删除确认', { type: 'warning' })
  await masterApi.delWarehouse(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await loadOptions()
  load()
})
</script>
