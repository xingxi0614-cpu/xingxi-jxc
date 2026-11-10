<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">工程项目档案</div>
        <div class="page-sub">维护出库申请、材料出库归属的工程项目</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !filteredRows.length" :title="!searched ? '请先选择条件并点击查询' : (!filteredRows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button @click="downloadTemplate" v-if="user.hasPerm('md.project')">下载模板</el-button>
        <el-upload :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadProjects" v-if="user.hasPerm('md.project')">
          <el-button>批量导入</el-button>
        </el-upload>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" v-if="user.hasPerm('md.project')" @click="openCreate">新增项目</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="statusTab" :options="STATUS_OPTIONS" :counts="statusCounts" />
      <QueryDrawer :model="filterDraft" @search="applyProjectSearch" @reset="resetProjectSearch">
        <el-form :inline="true" :model="filterDraft" size="default">
          <el-form-item data-q="keyword" label="编码/名称">
            <el-input v-model="filterDraft.keyword" placeholder="工程项目编码、名称、简称" clearable @keyup.enter="applyProjectSearch" />
          </el-form-item>
          <el-form-item data-q="orgId" label="所属组织">
            <el-select v-model="filterDraft.orgId" placeholder="全部组织" clearable filterable>
              <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="category" label="工程项目类别">
            <el-select v-model="filterDraft.category" placeholder="全部类别" clearable>
              <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="status" label="状态">
            <el-select v-model="filterDraft.status" placeholder="全部状态" clearable>
              <el-option label="已启用" :value="1" />
              <el-option label="已停用" :value="0" />
            </el-select>
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="filteredRows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="code" label="工程项目编码" width="130" />
        <el-table-column prop="name" label="工程项目名称" min-width="240" show-overflow-tooltip />
        <el-table-column label="所属组织" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ orgName(row.orgId) }}</template>
        </el-table-column>
        <el-table-column prop="category" label="工程项目类别" width="130" />
        <el-table-column prop="shortName" label="简称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="mnemonicCode" label="助记码" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '已启用' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" v-if="user.hasPerm('md.project')" @click="openEdit(row)">修改</el-button>
            <el-button link type="danger" v-if="user.hasPerm('md.project')" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog draggable v-model="visible" :before-close="dialogGuard.beforeClose" :title="form.id ? '修改工程项目' : '新增工程项目'" width="680px">
      <el-form label-position="top" ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="所属组织" prop="orgId"><el-select v-model="form.orgId" style="width:100%" @change="applyCodePrefix">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.name" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="上级项目"><el-select v-model="form.parentId" clearable style="width:100%">
            <el-option :value="0" label="顶级项目" />
            <el-option v-for="p in rows" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="工程项目编码" prop="code"><el-input v-model="form.code" placeholder="如 ABC-40001（前缀已按公司自动填好）" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="工程项目名称" prop="name"><el-input v-model="form.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="简称"><el-input v-model="form.shortName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="助记码"><el-input v-model="form.mnemonicCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="工程项目类别" prop="category"><el-select v-model="form.category" placeholder="请选择工程项目类别" style="width:100%">
            <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
          </el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogGuard.cancel">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
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
import { saveBlob } from '@/utils/download'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'

const user = useUserStore()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const filterDraft = reactive<any>({ keyword: '', orgId: null, category: '', status: null })
const searched = ref(false)
const visible = ref(false)
const dialogGuard = useUnsavedGuard(visible)
const orgOptions = ref<any[]>([])
const categories = ['生产', '工程', '维修', '改造', '研发', '其他']
const form = reactive<any>({ id: null, parentId: 0, orgId: 1, code: '', name: '', shortName: '', mnemonicCode: '', category: '工程', status: 1, remark: '' })
const formRef = ref<any>(null)
const rules = {
  orgId: [{ required: true, message: '请填写完整信息', trigger: 'change' }],
  code: [{ required: true, message: '请填写完整信息', trigger: 'blur' }],
  name: [{ required: true, message: '请填写完整信息', trigger: 'blur' }],
  category: [{ required: true, message: '请填写完整信息', trigger: 'change' }]
}
// 「全部 / 启用 / 已停用」页签(2026-10-17):本页一次取回全部项目,直接在页面上按状态分开看
const statusTab = ref('')
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '启用', value: '1' }, { label: '已停用', value: '0' }]
const statusCounts = computed(() => ({ '': rows.value.length, '1': rows.value.filter((r: any) => Number(r.status) === 1).length, '0': rows.value.filter((r: any) => Number(r.status) !== 1).length }))
const byStatus = (list: any[]) => statusTab.value === '' ? list : list.filter((r: any) => (Number(r.status) === 1) === (statusTab.value === '1'))
const filteredRows = computed(() => byStatus(rows.value))
const exportColumns: ExportColumn[] = [
  { label: '工程项目编码', key: 'code' },
  { label: '工程项目名称', key: 'name' },
  { label: '所属组织', value: row => orgName(row.orgId) },
  { label: '工程项目类别', key: 'category' },
  { label: '简称', key: 'shortName' },
  { label: '助记码', key: 'mnemonicCode' },
  { label: '状态', value: row => row.status === 1 ? '已启用' : '已停用' },
  { label: '备注', key: 'remark' }
]

function applyProjectSearch() {
  return load()
}

function resetProjectSearch() {
  Object.assign(filterDraft, { keyword: '', orgId: null, category: '', status: null })
  rows.value = []
  searched.value = false
}

function exportExcel() {
  exportRowsToExcel('工程项目档案', exportColumns, filteredRows.value, '工程项目档案')
}

async function load() {
  searched.value = true
  loading.value = true
  try {
    rows.value = await masterApi.projects({
      includeDisabled: true,
      keyword: filterDraft.keyword || undefined,
      orgId: filterDraft.orgId || undefined,
      category: filterDraft.category || undefined,
      status: filterDraft.status === '' || filterDraft.status === undefined ? undefined : filterDraft.status
    }) as any
  } finally {
    loading.value = false
  }
}

function orgName(id: number) {
  return orgOptions.value.find(o => o.id === id)?.name || id || '-'
}

async function loadOrgs() {
  const tree: any[] = await sysApi.orgTree() as any
  const out: any[] = []
  const walk = (items: any[]) => {
    for (const item of items || []) {
      out.push({ id: item.id, name: item.name, type: item.type, status: item.status })
      walk(item.children || [])
    }
  }
  walk(tree)
  orgOptions.value = out.filter(o => o.status !== 0 && ['GROUP', 'COMPANY'].includes(o.type))
}

// 新增时按所属公司预填编码前缀(如 ABC-)。
// 各公司编码原来是全集团一个号池、谁先占谁得,撞了车一线只能猜。
// 约定成「公司代号-自编号」就天然不会撞;代号沿用单据号已经在用的那一套,一线不用另学。
// 只预填、不强制:后面的自编号可能有业务含义(4开头/6开头),而且存量项目都不带前缀,
// 硬校验会把编辑旧项目卡死。
const codePrefix = ref('')

async function applyCodePrefix() {
  if (form.id) return                       // 编辑既有项目不动编码
  const old = codePrefix.value
  try {
    const next = (await masterApi.projectCodePrefix(form.orgId)) || ''
    codePrefix.value = next
    // 三种情况分开处理,核心原则:绝不冲掉人已经敲进去的编号
    if (!form.code || form.code === old) {
      form.code = next                                   // 还空着 / 只有旧前缀 → 直接换
    } else if (old && form.code.startsWith(old)) {
      form.code = next + form.code.slice(old.length)     // 已输了编号 → 只换前缀,编号原样保留
    }
    // 人自己完全手打的编码(不以旧前缀开头)一律不动
  } catch {
    // 拿不到前缀不该挡住建档,静默放过,人自己填
  }
}

function openCreate() {
  Object.assign(form, { id: null, parentId: 0, orgId: orgOptions.value[0]?.id || 1, code: '', name: '', shortName: '', mnemonicCode: '', category: '工程', status: 1, remark: '' })
  codePrefix.value = ''
  visible.value = true
  applyCodePrefix()
}

function openEdit(row: any) {
  Object.assign(form, { ...row })
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  await masterApi.saveProject(form)
  ElMessage.success('已保存')
  visible.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除 ${row.code} ${row.name}？`, '删除确认', { type: 'warning' })
  await masterApi.delProject(row.id)
  ElMessage.success('已保存')
  await load()
}

async function uploadProjects(options: any) {
  const result: any = await masterApi.importProjects(options.file)
  await load()
  const msg = `导入完成：新增 ${result.inserted || 0}，更新 ${result.updated || 0}，跳过 ${result.skipped || 0}`
  if (result.errors?.length) ElMessage.warning(`${msg}；存在 ${result.errors.length} 条提示`)
  else ElMessage.success(msg)
}

async function downloadTemplate() {
  const blob = await masterApi.projectImportTemplate()
  saveBlob(blob, '工程项目档案导入模板.xlsx')
}

onMounted(async () => {
  await loadOrgs()
})
</script>
