<template>
  <div class="page">
    <div class="page-head">
      <div><div class="page-title">用户管理</div></div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-input v-model="query.keyword" placeholder="用户名/姓名" clearable style="width:220px" @change="load" />
        <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-upload :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadUsers">
          <el-button>批量导入</el-button>
        </el-upload>
        <el-button type="primary" @click="openForm(null)">+ 新建用户</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="() => { query.current = 1; load() }" />
      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" empty-text="暂无用户">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="username"    label="用户名" width="120" />
        <el-table-column prop="name"        label="姓名"     width="120" />
        <el-table-column prop="orgName"     label="人员归属" width="160" />
        <el-table-column label="角色" width="180">
          <template #default="{ row }">
            <el-tag v-for="r in row.roleCodes || []" :key="r" size="small" style="margin-right:4px">{{ roleLabel(r) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone"       label="手机"     width="140" />
        <el-table-column prop="email"       label="邮箱"     min-width="180" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status ? 'success' : 'danger'" size="small">{{ row.status ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最近登录" width="160" :formatter="$fmtMinute" />
        <el-table-column label="锁定" width="100">
          <template #default="{ row }">
            <el-tag :type="isLocked(row) ? 'danger' : 'info'" size="small">{{ isLocked(row) ? '已锁定' : '正常' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button link type="primary"  @click="openForm(row)">编辑</el-button>
            <el-button v-if="isLocked(row)" link type="success" @click="unlockUser(row)">解锁</el-button>
            <el-button link type="warning"  @click="resetPwd(row)">重置密码</el-button>
            <el-button link type="danger"   @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     layout="total, prev, pager, next" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <!-- 新建/编辑用户 -->
    <el-dialog draggable v-model="visible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑用户' : '新建用户'" width="640px">
      <el-form label-position="top" :model="form" :rules="rules" ref="formRef" label-width="96px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" :disabled="!!form.id" placeholder="登录账号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="真实姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="人员归属" prop="orgId">
              <el-select v-model="form.orgId" filterable placeholder="请选择人员所属公司/部门" style="width:100%">
                <el-option
                  v-for="o in orgOptions"
                  :key="o.id"
                  :value="o.id"
                  :label="o.label"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色">
              <el-select v-model="form.roleIds" multiple placeholder="请选择岗位角色" style="width:100%">
                <el-option v-for="r in roleOptions" :key="r.id" :value="r.id" :label="r.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机">
              <el-input v-model="form.phone" placeholder="手机号（选填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱">
              <el-input v-model="form.email" placeholder="邮箱（选填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="!form.id">
            <el-form-item label="初始密码" prop="password">
              <el-input v-model="form.password" type="password" show-password placeholder="6 位以上，字母和数字组合" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-switch v-model="form.statusBool" active-text="启用" inactive-text="停用" />
            </el-form-item>
          </el-col>
        </el-row>
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
import { useStatusCounts } from '@/composables/useStatusCounts'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api'
import { saveBlob } from '@/utils/download'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows    = ref<any[]>([])
const total   = ref(0)
const query   = reactive<any>({ current: 1, size: 20, keyword: '', status: '' })
const roleOptions = ref<any[]>([])
const orgOptions = ref<any[]>([])

const roleMap: Record<string, string> = {
  purchaser: '采购',
  warehouse: '仓管',
  finance: '材料会计',
  material_accountant: '材料会计',
  finance_manager: '财务部门负责人',
  account_admin: '账套管理员',
  admin: '系统管理员'
}
function roleLabel(code: string) {
  const role = roleOptions.value.find((r: any) => r.code === code)
  return role?.name || roleMap[code] || code
}

const exportColumns: ExportColumn[] = [
  { label: '登录账号', key: 'username' },
  { label: '姓名', key: 'name' },
  { label: '人员归属', key: 'orgName' },
  { label: '岗位角色', value: row => (row.roleCodes || []).map((code: string) => roleLabel(code)).join('、') },
  { label: '手机', key: 'phone' },
  { label: '邮箱', key: 'email' },
  { label: '状态', value: row => row.status ? '启用' : '停用' },
  { label: '最近登录', key: 'lastLoginAt' },
  { label: '锁定', value: row => isLocked(row) ? '已锁定' : '正常' }
]

const orgTypeMap: Record<string, string> = {
  GROUP: '集团',
  COMPANY: '公司',
  DEPT: '部门',
  TEAM: '班组'
}

function flattenOrgs(nodes: any[], level = 0, parentPath = '', out: any[] = []) {
  for (const node of nodes || []) {
    const currentPath = parentPath ? `${parentPath} / ${node.name}` : node.name
    out.push({
      id: node.id,
      name: node.name,
      type: node.type,
      status: node.status,
      label: `${'　'.repeat(level)}${currentPath}（${node.code}，${orgTypeMap[node.type] || node.type}）`
    })
    flattenOrgs(node.children || [], level + 1, currentPath, out)
  }
  return out
}

// 「全部 / 启用 / 停用」页签(2026-10-17):数字 = 只换状态查出来的总人数
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '启用', value: '1' }, { label: '停用', value: '0' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => sysApi.users(q), ['1', '0'])

async function load() {
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await sysApi.users(query)
    rows.value  = r.records
    total.value = r.total
  } finally { loading.value = false }
}

function exportExcel() {
  exportRowsToExcel('用户账号清单', exportColumns, rows.value, '用户账号清单', { total: total.value, fetch: p => sysApi.users({ ...query, ...p }) })
}

async function uploadUsers(options: any) {
  const result: any = await sysApi.importUsers(options.file)
  await Promise.all([loadOptions(), load()])
  const msg = `导入完成：新增 ${result.inserted || 0}，更新 ${result.updated || 0}，跳过 ${result.skipped || 0}`
  if (result.errors?.length) ElMessage.warning(`${msg}；存在 ${result.errors.length} 条提示`)
  else ElMessage.success(msg)
}

async function downloadTemplate() {
  const blob = await sysApi.userImportTemplate()
  saveBlob(blob, '用户账号导入模板.xlsx')
}

async function loadOptions() {
  const [roles, tree] = await Promise.all([sysApi.roles(), sysApi.orgTree()]) as any[]
  roleOptions.value = roles
  orgOptions.value = flattenOrgs(tree).filter((o: any) => o.status !== 0)
}

const visible = ref(false)
const dialogGuard = useUnsavedGuard(visible)
const saving  = ref(false)
const formRef = ref<any>(null)
const form    = reactive<any>({ id: null, username: '', name: '', orgId: null, roleIds: [], phone: '', email: '', password: '', statusBool: true })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  name:     [{ required: true, message: '请填写完整信息',   trigger: 'blur' }],
  orgId:    [{ required: true, message: '请选择人员归属', trigger: 'change' }],
  password: [
    { required: true, message: '请填写完整信息', trigger: 'blur' },
    { pattern: /^(?!123456$)(?!password$)(?!admin123$)(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d]{6,}$/i, message: '至少 6 位，必须包含字母和数字', trigger: 'blur' }
  ]
}

function openForm(row: any) {
  if (row) {
    Object.assign(form, { id: row.id, username: row.username, name: row.name, orgId: row.orgId, roleIds: row.roleIds || [], phone: row.phone || '', email: row.email || '', password: '', statusBool: !!row.status })
  } else {
    Object.assign(form, { id: null, username: '', name: '', orgId: orgOptions.value[0]?.id, roleIds: [], phone: '', email: '', password: '', statusBool: true })
  }
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload: any = { id: form.id, username: form.username, name: form.name, orgId: form.orgId, roleIds: form.roleIds, phone: form.phone, email: form.email, status: form.statusBool ? 1 : 0 }
    if (!form.id) payload.password = form.password
    await sysApi.saveUser(payload)
    ElMessage.success(form.id ? '已更新' : '已创建')
    visible.value = false
    await load()
  } finally { saving.value = false }
}

function isLocked(row: any) {
  if (!row?.lockedUntil) return false
  return new Date(row.lockedUntil).getTime() > Date.now()
}

async function unlockUser(row: any) {
  const confirmed = await ElMessageBox.confirm(`确认解锁 ${row.name}？`, '解锁确认', { type: 'warning' }).catch(() => null)
  if (!confirmed) return
  await sysApi.unlockUser(row.id)
  ElMessage.success(`已解锁 ${row.name}`)
  await load()
}

async function resetPwd(row: any) {
  const result = await ElMessageBox.prompt(
    `请输入 ${row.name} 的新密码`,
    '重置密码',
    {
      type: 'warning',
      inputType: 'password',
      inputPlaceholder: '6 位以上，字母和数字组合',
      inputPattern: /^(?!123456$)(?!password$)(?!admin123$)(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d]{6,}$/i,
      inputErrorMessage: '至少 6 位，必须包含字母和数字'
    }
  ).catch(() => null)
  if (!result) return
  const { value } = result
  await sysApi.resetPwd(row.id, value)
  ElMessage.success(`已重置 ${row.name} 的密码`)
}

async function remove(row: any) {
  const confirmed = await ElMessageBox.confirm(`确认删除 ${row.name}？`, '删除确认', { type: 'warning' }).catch(() => null)
  if (!confirmed) return
  await sysApi.delUser(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(() => Promise.all([loadOptions(), load()]))
</script>
