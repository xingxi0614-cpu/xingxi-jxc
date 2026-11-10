<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">供应商档案</div>
        <div class="page-sub">按供应商基本信息模板维护主数据，支持后期批量导入</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-upload :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadSuppliers">
          <el-button>批量导入</el-button>
        </el-upload>
        <el-button type="primary" v-if="user.hasPerm('md.supplier')" @click="openForm()">新建</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="statusTab" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'poolAll') poolAll = false }">
        <el-form :inline="true" :model="query" class="supplier-query-form">
          <el-form-item data-q="orgId" label="所属组织">
            <el-select v-model="query.orgId" clearable filterable placeholder="全部组织" :disabled="!isSysAdmin && poolAll">
              <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="poolAll" v-if="!isSysAdmin" label="供应商池">
            <el-switch v-model="poolAll" active-text="查看全部供应商池" @change="onPoolChange" />
          </el-form-item>
          <el-form-item data-q="keyword" label="关键字">
            <el-input v-model="query.keyword" placeholder="编码/名称/简称/分类/类型" clearable @keyup.enter="doSearch" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'">
        <!-- 集团池模型：档案全集团可见，归属公司对所有人展示；仅归属公司在可见范围内的行可维护 -->
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="orgName" label="归属公司" width="130" show-overflow-tooltip />
        <el-table-column prop="code" label="供应商编码" width="120" />
        <el-table-column prop="name" label="供应商名称" min-width="240" show-overflow-tooltip />
        <el-table-column prop="shortName" label="供应商简称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="mnemonicCode" label="助记码" width="110" />
        <el-table-column prop="supplierCategory" label="供应商基本分类" min-width="170" show-overflow-tooltip />
        <el-table-column prop="regionCategory" label="地区分类" min-width="120" show-overflow-tooltip />
        <el-table-column label="供应商状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '核准' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="是否客户" width="100">
          <template #default="{ row }">{{ row.isCustomer ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="supplierType" label="供应商类型" width="120" show-overflow-tooltip />
        <el-table-column prop="financeOrg" label="对应财务组织" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="canMaintain(row)">
              <el-button link type="primary" @click="openForm(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
            <el-tooltip v-else :content="`归属 ${row.orgName || '其他'} 维护，仅可查看`" placement="left">
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

    <el-dialog draggable v-model="formVisible" :before-close="formGuard.beforeClose" :title="form.id ? '编辑供应商' : '新建供应商'" width="760px" align-center>
      <el-form label-position="top" ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-row :gutter="18">
          <el-col :span="12"><el-form-item label="所属组织" prop="orgId"><el-select v-model="form.orgId" filterable placeholder="请选择组织单位" style="width:100%">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商编码"><el-input v-model="form.code" readonly placeholder="系统自动生成" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商名称" prop="name"><el-input v-model="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商简称"><el-input v-model="form.shortName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="助记码"><el-input v-model="form.mnemonicCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="基本分类" prop="supplierCategory"><el-select v-model="form.supplierCategory" filterable allow-create default-first-option placeholder="请选择基本分类" style="width:100%">
            <el-option v-for="item in supplierCategoryOptions" :key="item" :value="item" :label="item" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="地区分类"><el-input v-model="form.regionCategory" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="供应商状态">
              <el-select v-model="form.status" style="width:100%">
                <el-option :value="1" label="核准" />
                <el-option :value="0" label="停用" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="是否客户">
              <el-switch v-model="form.isCustomer" :active-value="1" :inactive-value="0" active-text="是" inactive-text="否" />
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="供应商类型" prop="supplierType"><el-select v-model="form.supplierType" filterable allow-create default-first-option placeholder="请选择供应商类型" style="width:100%">
            <el-option v-for="item in supplierTypeOptions" :key="item" :value="item" :label="item" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="对应财务组织"><el-input v-model="form.financeOrg" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.contactPhone" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="地址"><el-input v-model="form.address" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item></el-col>
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
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ current: 1, size: 20, keyword: '', orgId: null })
const orgOptions = ref<any[]>([])
const supplierCategoryOptions = ['内部单位', '外部供应商', '材料供应商', '施工单位', '服务供应商', '其他']
const supplierTypeOptions = ['内部单位', '外部单位', '个人供应商', '集团内单位', '其他']
// 集团池模型：归属公司对所有人展示；编辑/删除仅限归属公司在本人可见组织树内的行
const isSysAdmin = computed(() => user.hasRole('admin'))
const poolAll = ref(false)   // 非 admin 可开关：查看全部供应商池
const visibleOrgIdSet = ref<Set<number>>(new Set())
const exportColumns = computed<ExportColumn[]>(() => ([
  { label: '归属公司', key: 'orgName' },
  { label: '供应商编码', key: 'code' },
  { label: '供应商名称', key: 'name' },
  { label: '供应商简称', key: 'shortName' },
  { label: '助记码', key: 'mnemonicCode' },
  { label: '供应商基本分类', key: 'supplierCategory' },
  { label: '地区分类', key: 'regionCategory' },
  { label: '供应商状态', value: row => row.status ? '核准' : '停用' },
  { label: '是否客户', value: row => row.isCustomer ? '是' : '否' },
  { label: '供应商类型', key: 'supplierType' },
  { label: '对应财务组织', key: 'financeOrg' },
  { label: '联系人', key: 'contactName' },
  { label: '联系电话', key: 'contactPhone' },
  { label: '地址', key: 'address' },
  { label: '备注', key: 'remark' }
]))

function collectOrgIds(nodes: any[], out: number[] = []) {
  for (const node of nodes || []) {
    out.push(node.id)
    collectOrgIds(node.children || [], out)
  }
  return out
}

function flattenOrgs(nodes: any[], level = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, name: node.name, label: `${'　'.repeat(level)}${node.name}（${node.code}）`, type: node.type, status: node.status })
    flattenOrgs(node.children || [], level + 1, out)
  }
  return out
}

async function loadOptions() {
  const tree = await sysApi.orgTree() as any[]
  visibleOrgIdSet.value = new Set(collectOrgIds(tree))
  orgOptions.value = flattenOrgs(tree)
    .filter(o => o.status !== 0 && ['GROUP', 'COMPANY'].includes(o.type))
}

function canMaintain(row: any) {
  return isSysAdmin.value || visibleOrgIdSet.value.has(row.orgId)
}

function onPoolChange(v: boolean) {
  if (!v && !query.orgId) query.orgId = defaultSupplierOrgId()
  doSearch()
}

function defaultSupplierOrgId() {
  const currentOrgId = user.userInfo?.orgId
  if (currentOrgId && orgOptions.value.some(o => o.id === currentOrgId)) return currentOrgId
  const company = orgOptions.value.find(o => o.type === 'COMPANY')
  if (company) return company.id
  return orgOptions.value[0]?.id || null
}

// 「核准 / 已停用 / 全部」页签(2026-10-17):默认只看核准的,与原来列表一致;-1 = 全部
const statusTab = ref('1')
const STATUS_OPTIONS = [{ label: '核准', value: '1' }, { label: '已停用', value: '0' }, { label: '全部', value: '-1' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => masterApi.suppliers(q), ['1', '0', '-1'])

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
    const r: any = await masterApi.suppliers(params)
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
  Object.assign(query, { current: 1, size: query.size, keyword: '', orgId: isSysAdmin.value ? null : defaultSupplierOrgId() })
  load()
}

function exportExcel() {
  exportRowsToExcel('供应商档案', exportColumns.value, rows.value, '供应商档案', { total: total.value, fetch: p => masterApi.suppliers({ ...listParams(), status: statusTab.value, ...p }) })
}

const formVisible = ref(false)
const formGuard = useUnsavedGuard(formVisible)
const formRef = ref<any>(null)
const form = reactive<any>({})
const rules = {
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  name: [{ required: true, message: '请填写供应商名称', trigger: 'blur' }],
  supplierCategory: [{ required: true, message: '请选择基本分类', trigger: 'change' }],
  supplierType: [{ required: true, message: '请选择供应商类型', trigger: 'change' }]
}

function resetForm() {
  const orgId = defaultSupplierOrgId()
  const org = orgOptions.value.find(o => o.id === orgId)
  Object.assign(form, {
    id: null,
    orgId,
    orgName: org?.name || '',
    code: '',
    name: '',
    shortName: '',
    mnemonicCode: '',
    supplierCategory: '',
    regionCategory: '',
    status: 1,
    isCustomer: 0,
    supplierType: '',
    financeOrg: '',
    contactName: '',
    contactPhone: '',
    address: '',
    remark: ''
  })
}

async function openForm(row?: any) {
  resetForm()
  if (row) {
    Object.assign(form, row)
    delete form.isShared
    delete form.scopeOrgs
    if (!form.orgId && form.orgName) {
      form.orgId = orgOptions.value.find(o => o.name === form.orgName)?.id || null
    }
  } else {
    try {
      form.code = await masterApi.nextSupplierCode() as any
    } catch (e: any) {
      form.code = ''
      ElMessage.warning(e?.message || '供应商编码自动生成失败')
    }
  }
  formVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  await masterApi.saveSupplier(form)
  ElMessage.success('已保存')
  formVisible.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除 ${row.name}？如已有历史单据引用，系统会归档隐藏该供应商，历史记录仍保留。`, '删除确认', { type: 'warning' })
  await masterApi.delSupplier(row.id)
  ElMessage.success('已删除')
  await load()
}

async function uploadSuppliers(options: any) {
  const result: any = await masterApi.importSuppliers(options.file)
  await load()
  const msg = `导入完成：新增 ${result.inserted || 0} 条，已存在直接选用 ${result.updated || 0} 条，跳过 ${result.skipped || 0} 条`
  if (result.errors?.length) ElMessage.warning(`${msg}；存在 ${result.errors.length} 条提示`)
  else ElMessage.success(msg)
}

async function downloadTemplate() {
  const blob = await masterApi.supplierImportTemplate()
  saveBlob(blob, '供应商档案导入模板.xlsx')
}

onMounted(async () => {
  await loadOptions()
  // 非 admin 默认只看本账套归属的档案，可用「供应商池」开关查看全池
  if (!isSysAdmin.value) query.orgId = defaultSupplierOrgId()
  load()
})
</script>

<style scoped>
.supplier-query-form :deep(.el-input) {
  width: 280px;
}
</style>
