<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">物料档案</div>
        <div class="page-sub">维护集团统一的物料主数据，支持模板批量导入</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-upload :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadMaterials">
          <el-button>批量导入</el-button>
        </el-upload>
        <el-button :disabled="selectedRows.length === 0" type="danger" @click="batchDelete">批量删除</el-button>
        <el-button type="primary" v-if="user.hasPerm('md.material')" @click="openForm()">新建</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="statusTab" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'poolAll') poolAll = false }">
        <el-form :inline="true" :model="query" class="material-query-form">
          <el-form-item data-q="orgId" label="所属组织">
            <el-select v-model="query.orgId" clearable filterable placeholder="全部组织" :disabled="!isSysAdmin && poolAll">
              <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="poolAll" v-if="!isSysAdmin" label="物料池">
            <el-switch v-model="poolAll" active-text="查看全部物料池" @change="onPoolChange" />
          </el-form-item>
          <el-form-item data-q="code" label="物料编码">
            <el-input v-model="query.code" placeholder="输入编码" clearable @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="name" label="物料名称">
            <el-input v-model="query.name" placeholder="输入名称" clearable @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="spec" label="规格型号">
            <el-input v-model="query.spec" placeholder="输入规格或型号" clearable @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="materialType" label="物料类型">
            <el-input v-model="query.materialType" placeholder="输入物料类型" clearable @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="keyword" label="全局搜索">
            <el-input v-model="query.keyword" placeholder="编码/名称/规格/类型" clearable @keyup.enter="doSearch" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @selection-change="onSelectionChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" type="selection" width="45" />
        <!-- 集团池模型：档案全集团可见，归属公司对所有人展示；仅归属公司在可见范围内的行可维护 -->
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" label="归属公司" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ orgName(row.orgId) }}</template>
        </el-table-column>
        <el-table-column prop="code" label="物料编码" width="130" />
        <el-table-column prop="name" label="物料名称" min-width="240" show-overflow-tooltip />
        <el-table-column prop="materialType" label="物料类型" min-width="110" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格型号" min-width="170" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="默认税率" width="100" align="right">
          <template #default="{ row }">{{ Number(row.defaultTax || 0).toFixed(2) }}%</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="canMaintain(row)">
              <el-button link type="primary" @click="openForm(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
            <el-tooltip v-else :content="`归属 ${orgName(row.orgId)} 维护，仅可查看`" placement="left">
              <span style="color:var(--text-3);font-size:12px">仅查看</span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="load"
        @current-change="load"
        style="margin-top:12px;justify-content:flex-end"
      />
    </el-card>

    <el-dialog draggable v-model="formVisible" :before-close="formGuard.beforeClose" :title="form.id ? '编辑物料' : '新建物料'" width="680px" align-center>
      <el-form label-position="top" ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-row :gutter="18">
          <el-col :span="12"><el-form-item label="所属组织" prop="orgId"><el-select v-model="form.orgId" filterable placeholder="请选择组织单位" style="width:100%">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="物料编码" prop="code"><el-input v-model="form.code" placeholder="手动填写物料编码" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="物料名称" prop="name"><el-input v-model="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="物料类型"><el-input v-model="form.materialType" placeholder="选填，如主材、辅材" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="规格型号"><el-input v-model="form.spec" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="单位" prop="unit"><el-input v-model="form.unit" placeholder="个/台/kg" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="默认税率%"><el-input-number v-model="form.defaultTax" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="批次管理"><el-switch v-model="form.isBatch" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formGuard.cancel">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { masterApi, sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import QueryDrawer from '@/components/QueryDrawer.vue'
import { saveBlob } from '@/utils/download'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'

const user = useUserStore()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const orgOptions = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ current: 1, size: 20, keyword: '', code: '', name: '', spec: '', materialType: '', orgId: null })

const orgMap = computed<Record<number, any>>(() => Object.fromEntries(orgOptions.value.map((org: any) => [org.id, org])))
// 集团池模型：归属公司对所有人展示；编辑/删除仅限归属公司在本人可见组织树内的行
const isSysAdmin = computed(() => user.hasRole('admin'))
const poolAll = ref(false)   // 非 admin 可开关：查看全部物料池
const visibleOrgIdSet = ref<Set<number>>(new Set())
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '归属公司', value: row => orgName(row.orgId) },
  { label: '物料编码', key: 'code' },
  { label: '物料名称', key: 'name' },
  { label: '物料类型', key: 'materialType' },
  { label: '规格型号', key: 'spec' },
  { label: '单位', key: 'unit' },
  { label: '默认税率', value: row => `${Number(row.defaultTax || 0).toFixed(2)}%` },
  { label: '状态', value: row => row.status ? '启用' : '停用' }
])

function canMaintain(row: any) {
  return isSysAdmin.value || visibleOrgIdSet.value.has(row.orgId)
}

function onPoolChange(v: boolean) {
  if (!v && !query.orgId) query.orgId = defaultMaterialOrgId()
  doSearch()
}

function orgName(id: number | null) {
  if (!id) return '-'
  return orgMap.value[id]?.name || `组织 ${id}`
}

function collectOrgIds(nodes: any[], out: number[] = []) {
  for (const node of nodes || []) {
    out.push(node.id)
    collectOrgIds(node.children || [], out)
  }
  return out
}

function flattenOrgs(nodes: any[], level = 0, parentId = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, name: node.name, label: `${'　'.repeat(level)}${node.name}（${node.code}）`, type: node.type, status: node.status, parentId })
    flattenOrgs(node.children || [], level + 1, node.id, out)
  }
  return out
}

async function loadOrgs() {
  const tree = await sysApi.orgTree() as any[]
  visibleOrgIdSet.value = new Set(collectOrgIds(tree))
  orgOptions.value = flattenOrgs(tree)
    .filter(o => o.status !== 0 && ['GROUP', 'COMPANY'].includes(o.type))
}

function defaultMaterialOrgId() {
  const currentOrgId = user.userInfo?.orgId
  if (currentOrgId && orgOptions.value.some(o => o.id === currentOrgId)) return currentOrgId
  const company = orgOptions.value.find(o => o.type === 'COMPANY')
  if (company) return company.id
  return orgOptions.value[0]?.id || null
}

// 「启用 / 已停用 / 全部」页签(2026-10-17):默认只看启用的,与原来列表一致;-1 = 全部
const statusTab = ref('1')
const STATUS_OPTIONS = [{ label: '启用', value: '1' }, { label: '已停用', value: '0' }, { label: '全部', value: '-1' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => masterApi.materials(q), ['1', '0', '-1'])

/** 列表查询条件(导出全部时用同一份,见 exportRowsToExcel 的 all) */
function listParams() {
  const params: any = { ...query }
  if (!isSysAdmin.value && poolAll.value) {
    params.pool = 1
    params.orgId = null
  }
  return params
}

async function load() {
  searched.value = true
  loading.value = true
  try {
    const params: any = listParams()
    refreshStatusCounts(params)
    params.status = statusTab.value
    const r: any = await masterApi.materials(params)
    rows.value = r.records
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function doSearch() {
  query.current = 1
  return load()
}

function resetQuery() {
  poolAll.value = false
  Object.assign(query, { current: 1, size: query.size, keyword: '', code: '', name: '', spec: '', materialType: '', orgId: isSysAdmin.value ? null : defaultMaterialOrgId() })
  load()
}

function exportExcel() {
  exportRowsToExcel('物料档案', exportColumns.value, rows.value, '物料档案', { total: total.value, fetch: p => masterApi.materials({ ...listParams(), status: statusTab.value, ...p }) })
}

const formVisible = ref(false)
const formGuard = useUnsavedGuard(formVisible)
const formRef = ref<any>(null)
const form = reactive<any>({ id: null, code: '', name: '', materialType: '', spec: '', unit: '个', catId: null, orgId: null, defaultTax: 13, isBatch: 0, status: 1 })
const selectedRows = ref<any[]>([])

const rules = {
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  code: [{ required: true, message: '请填写物料编码', trigger: 'blur' }],
  name: [{ required: true, message: '请填写物料名称', trigger: 'blur' }],
  unit: [{ required: true, message: '请填写单位', trigger: 'blur' }]
}

function onSelectionChange(rows: any[]) { selectedRows.value = rows }

async function batchDelete() {
  if (selectedRows.value.length === 0) return
  const names = selectedRows.value.map((r: any) => r.code).slice(0, 5).join('、')
  const more = selectedRows.value.length > 5 ? ` 等 ${selectedRows.value.length} 条` : ''
  const confirmed = await ElMessageBox.confirm(
    `确认删除以下物料？${names}${more}。已引用的物料将被跳过。`,
    '批量删除确认',
    { type: 'warning', confirmButtonText: '确认删除' }
  ).catch(() => null)
  if (!confirmed) return
  const ids = selectedRows.value.map((r: any) => r.id)
  const res: any = await masterApi.batchDeleteMaterials(ids)
  let msg = `已删除 ${res.deleted} 条物料`
  if (res.blocked > 0) msg += `，${res.blocked} 条因已被引用而跳过`
  ElMessage.success(msg)
  await load()
}

function openForm(row?: any) {
  Object.assign(form, { id: null, code: '', name: '', materialType: '', spec: '', unit: '个', catId: null, orgId: defaultMaterialOrgId(), defaultTax: 13, isBatch: 0, status: 1 })
  if (row) {
    Object.assign(form, row)
    delete form.isShared
    delete form.scopeOrgs
  }
  form.catId = null
  formVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  await masterApi.saveMaterial({ ...form, catId: null })
  ElMessage.success('已保存')
  formVisible.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除 ${row.name}？如已有历史单据或库存流水，系统会归档隐藏该物料，历史记录仍保留。`, '删除确认', { type: 'warning' })
  await masterApi.delMaterial(row.id)
  ElMessage.success('已删除')
  await load()
}

async function uploadMaterials(options: any) {
  const result: any = await masterApi.importMaterials(options.file)
  await load()
  const msg = `导入完成：新增 ${result.inserted || 0} 条，已存在直接选用 ${result.updated || 0} 条，跳过 ${result.skipped || 0} 条`
  if (result.errors?.length) ElMessage.warning(`${msg}；存在 ${result.errors.length} 条提示`)
  else ElMessage.success(msg)
}

async function downloadTemplate() {
  const blob = await masterApi.materialImportTemplate()
  saveBlob(blob, '物料档案导入模板.xlsx')
}

onMounted(async () => {
  await loadOrgs()
  // 非 admin 默认只看本账套归属的档案，可用「物料池」开关查看全池
  if (!isSysAdmin.value) query.orgId = defaultMaterialOrgId()
  load()
})
</script>
