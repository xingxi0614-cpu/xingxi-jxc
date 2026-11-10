<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">组织架构</div>
        <div class="page-sub">维护公司、部门、片区、网点和人员层级；人员归属决定其账套数据范围</div>
      </div>
      <el-space>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openForm(null, null)">新建组织</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-tree
        :data="tree"
        :props="{ label: 'name', children: 'children' }"
        default-expand-all
        node-key="nodeKey"
        empty-text="暂无组织"
        class="org-tree"
      >
        <template #default="{ node, data }">
          <div v-if="data.kind === 'person'" class="org-node person-node">
            <div class="org-main">
              <el-tag size="small" type="info">人员</el-tag>
              <span class="org-name">{{ data.name }}</span>
              <span class="org-code">{{ data.username }}</span>
              <el-tag v-for="r in data.roleCodes || []" :key="r" size="small">{{ roleLabel(r) }}</el-tag>
              <el-tag size="small" :type="data.status ? 'success' : 'info'">
                {{ data.status ? '启用' : '停用' }}
              </el-tag>
            </div>
            <el-space class="org-actions">
              <el-button link type="primary" @click.stop="openPersonForm(data, null)">编辑人员</el-button>
              <el-button link type="danger" @click.stop="removePerson(data)">删除人员</el-button>
            </el-space>
          </div>
          <div v-else class="org-node">
            <div class="org-main">
              <el-tag size="small" :type="typeTag(data.type)">{{ typeLabel(data.type) }}</el-tag>
              <span class="org-name">{{ node.label }}</span>
              <span class="org-code">{{ data.code }}</span>
              <span class="org-id">#{{ data.id }}</span>
              <el-tag size="small" :type="data.status ? 'success' : 'info'">
                {{ data.status ? '启用' : '停用' }}
              </el-tag>
              <el-tag v-if="data.type === 'COMPANY'" size="small" :type="approvalModeTag(data.approvalMode)">
                {{ approvalModeLabel(data.approvalMode) }}
              </el-tag>
              <span v-if="data.remark" class="org-remark">{{ data.remark }}</span>
            </div>
            <el-space class="org-actions">
              <el-switch
                v-if="data.type === 'COMPANY'"
                :model-value="isStandardApprovalMode(data.approvalMode)"
                inline-prompt
                active-text="完整"
                inactive-text="简化"
                @click.stop
                @change="(value: any) => changeApprovalMode(data, value)"
              />
              <el-button link type="primary" @click.stop="openForm(data, null)">编辑</el-button>
              <el-button v-if="data.status !== 0" link type="primary" @click.stop="openForm(null, data)">新增下级</el-button>
              <el-button v-if="data.status !== 0" link type="success" @click.stop="openPersonForm(null, data)">新增人员</el-button>
              <el-button link type="danger" @click.stop="remove(data)">删除</el-button>
            </el-space>
          </div>
        </template>
      </el-tree>
    </el-card>

    <el-dialog draggable v-model="visible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑组织' : '新建组织'" width="560px">
      <el-form label-position="top" ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="上级组织">
          <el-select v-model="form.parentId" filterable style="width:100%">
            <el-option :value="0" label="无上级（根组织）" />
            <el-option
              v-for="item in flatOrgs"
              :key="item.id"
              :value="item.id"
              :label="item.label"
              :disabled="form.id === item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="组织编码" prop="code">
          <el-input v-model="form.code" placeholder="如：COMP-001 或 FIN-DEPT" maxlength="40" />
        </el-form-item>
        <el-form-item label="组织名称" prop="name">
          <el-input v-model="form.name" placeholder="如：财务部" maxlength="80" />
        </el-form-item>
        <el-form-item label="组织类型" prop="type">
          <el-select v-model="form.type" style="width:100%">
            <el-option label="集团" value="GROUP" />
            <el-option label="公司/账套" value="COMPANY" />
            <el-option label="部门" value="DEPT" />
            <el-option label="片区" value="AREA" />
            <el-option label="网点" value="BRANCH" />
            <el-option label="班组" value="TEAM" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type === 'COMPANY'" label="审批模式">
          <el-switch
            v-model="form.standardApproval"
            active-text="完整审批链"
            inactive-text="简化审批"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.statusBool" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable v-model="personVisible" :before-close="personGuard.beforeClose" :title="personForm.id ? '编辑人员' : '新增人员'" width="640px">
      <el-form label-position="top" ref="personFormRef" :model="personForm" :rules="personRules" label-width="96px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="登录账号" prop="username">
              <el-input v-model="personForm.username" :disabled="!!personForm.id" placeholder="登录账号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="personForm.name" placeholder="人员姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="人员归属" prop="orgId">
              <el-select v-model="personForm.orgId" filterable style="width:100%">
                <el-option
                  v-for="item in activeOrgOptions"
                  :key="item.id"
                  :value="item.id"
                  :label="item.label"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色">
              <el-select v-model="personForm.roleIds" multiple placeholder="请选择岗位角色" style="width:100%">
                <el-option v-for="r in roleOptions" :key="r.id" :value="r.id" :label="r.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="!personForm.id">
            <el-form-item label="初始密码" prop="password">
              <el-input v-model="personForm.password" type="password" show-password placeholder="6 位以上，字母和数字组合" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机">
              <el-input v-model="personForm.phone" placeholder="手机号（选填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱">
              <el-input v-model="personForm.email" placeholder="邮箱（选填）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-switch v-model="personForm.statusBool" active-text="启用" inactive-text="停用" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="personGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="personSaving" @click="savePerson">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api'

const loading = ref(false)
const saving = ref(false)
const personSaving = ref(false)
const visible = ref(false)
const dialogGuard = useUnsavedGuard(visible)
const personVisible = ref(false)
const personGuard = useUnsavedGuard(personVisible)
const tree = ref<any[]>([])
const orgTree = ref<any[]>([])
const roleOptions = ref<any[]>([])
const formRef = ref<any>(null)
const personFormRef = ref<any>(null)
const form = reactive<any>({
  id: null,
  parentId: 0,
  code: '',
  name: '',
  type: 'DEPT',
  standardApproval: false,
  sort: 0,
  statusBool: true,
  remark: ''
})
const personForm = reactive<any>({
  id: null,
  username: '',
  name: '',
  orgId: null,
  roleIds: [],
  phone: '',
  email: '',
  password: '',
  statusBool: true
})

const rules = {
  code: [{ required: true, message: '请填写完整信息', trigger: 'blur' }],
  name: [{ required: true, message: '请填写完整信息', trigger: 'blur' }],
  type: [{ required: true, message: '请选择组织类型', trigger: 'change' }]
}
const personRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  name: [{ required: true, message: '请填写完整信息', trigger: 'blur' }],
  orgId: [{ required: true, message: '请选择人员归属', trigger: 'change' }],
  password: [
    { required: true, message: '请填写完整信息', trigger: 'blur' },
    { pattern: /^(?!123456$)(?!password$)(?!admin123$)(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d]{6,}$/i, message: '至少 6 位，必须包含字母和数字', trigger: 'blur' }
  ]
}
const roleMap: Record<string, string> = {
  purchaser: '采购',
  warehouse: '仓管',
  finance: '材料会计',
  account_admin: '账套管理员',
  material_accountant: '材料会计',
  finance_manager: '财务部门负责人',
  deputy_general_manager: '分管副总',
  general_manager: '总经理',
  dept_manager: '技术部经理',
  field_manager: '现场管理员',
  tech_manager: '技术部经理',
  service_staff: '网点客服',
  operation_repair: '运维人员',
  branch_manager: '网点负责人',
  area_manager: '片区经理',
  business_leader: '分管副总',
  operation_leader: '运营副总',
  engineering_leader: '工程副总',
  market_leader: '市场副总',
  accountant: '材料会计',
  cashier: '材料会计',
  admin: '系统管理员'
}

function typeLabel(type: string) {
  return ({
    GROUP: '集团',
    COMPANY: '公司',
    DEPT: '部门',
    AREA: '片区',
    BRANCH: '网点',
    TEAM: '班组'
  } as Record<string, string>)[type] || type
}

function typeTag(type: string) {
  if (type === 'GROUP') return 'danger'
  if (type === 'COMPANY') return 'warning'
  if (type === 'DEPT') return 'success'
  if (type === 'AREA') return 'primary'
  return 'info'
}
function roleLabel(code: string) { return roleMap[code] || code }

function isStandardApprovalMode(mode?: string) {
  return String(mode || 'STANDARD').toUpperCase() !== 'SIMPLE'
}

function approvalModeLabel(mode?: string) {
  return isStandardApprovalMode(mode) ? '完整审批链' : '简化审批'
}

function approvalModeTag(mode?: string) {
  return isStandardApprovalMode(mode) ? 'success' : 'info'
}

function defaultChildType(parent?: any) {
  if (!parent) return 'GROUP'
  if (parent.type === 'GROUP') return 'COMPANY'
  if (parent.type === 'AREA') return 'BRANCH'
  if (parent.type === 'BRANCH') return 'TEAM'
  return 'DEPT'
}

function flatten(nodes: any[], level = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, label: `${'　'.repeat(level)}${node.name}（${node.code}）` })
    flatten(node.children || [], level + 1, out)
  }
  return out
}

const flatOrgs = computed(() => flatten(orgTree.value))
const activeOrgOptions = computed(() => flatOrgs.value.filter((item: any) => {
  const org = findOrg(orgTree.value, item.id)
  return org && org.status !== 0
}))

async function load() {
  loading.value = true
  try {
    const [orgs, users, roles] = await Promise.all([
      sysApi.orgTree(),
      sysApi.users({ current: 1, size: 1000, keyword: '' }),
      sysApi.roles()
    ]) as any[]
    orgTree.value = orgs || []
    roleOptions.value = roles || []
    tree.value = buildOrgUserTree(orgTree.value, users?.records || [])
  } finally {
    loading.value = false
  }
}

function findOrg(nodes: any[], id: number): any {
  for (const node of nodes || []) {
    if (node.id === id) return node
    const found = findOrg(node.children || [], id)
    if (found) return found
  }
  return null
}

function buildOrgUserTree(nodes: any[], users: any[]) {
  const usersByOrg = new Map<number, any[]>()
  for (const user of users || []) {
    const orgId = Number(user.orgId || 0)
    if (!usersByOrg.has(orgId)) usersByOrg.set(orgId, [])
    usersByOrg.get(orgId)!.push({
      ...user,
      kind: 'person',
      nodeKey: `person-${user.id}`
    })
  }
  const walk = (items: any[]): any[] => (items || []).map(item => {
    const orgChildren = walk(item.children || [])
    const people = (usersByOrg.get(Number(item.id)) || []).sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
    return {
      ...item,
      kind: 'org',
      nodeKey: `org-${item.id}`,
      children: [...orgChildren, ...people]
    }
  })
  return walk(nodes)
}

function openForm(row: any, parent: any) {
  if (row?.kind === 'person') return
  if (!row && parent?.status === 0) {
    ElMessage.warning('请先启用上级组织')
    return
  }
  if (row) {
    Object.assign(form, {
      id: row.id,
      parentId: row.parentId || 0,
      code: row.code,
      name: row.name,
      type: row.type,
      standardApproval: isStandardApprovalMode(row.approvalMode),
      sort: row.sort ?? 0,
      statusBool: row.status !== 0,
      remark: row.remark || ''
    })
  } else {
    Object.assign(form, {
      id: null,
      parentId: parent?.id || 0,
      code: '',
      name: '',
      type: defaultChildType(parent),
      standardApproval: false,
      sort: 0,
      statusBool: true,
      remark: ''
    })
  }
  visible.value = true
}

function openPersonForm(row: any, org: any) {
  if (!row && org?.status === 0) {
    ElMessage.warning('请先启用组织后再新增人员')
    return
  }
  if (row) {
    Object.assign(personForm, {
      id: row.id,
      username: row.username,
      name: row.name,
      orgId: row.orgId,
      roleIds: row.roleIds || [],
      phone: row.phone || '',
      email: row.email || '',
      password: '',
      statusBool: !!row.status
    })
  } else {
    Object.assign(personForm, {
      id: null,
      username: '',
      name: '',
      orgId: org?.id || activeOrgOptions.value[0]?.id,
      roleIds: [],
      phone: '',
      email: '',
      password: '',
      statusBool: true
    })
  }
  personVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    await sysApi.saveOrg({
      id: form.id,
      parentId: form.parentId,
      code: form.code,
      name: form.name,
      type: form.type,
      approvalMode: form.type === 'COMPANY' && form.standardApproval ? 'STANDARD' : 'SIMPLE',
      sort: form.sort,
      status: form.statusBool ? 1 : 0,
      remark: form.remark
    })
    ElMessage.success(form.id ? '已更新' : '已创建')
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function changeApprovalMode(row: any, value: any) {
  const approvalMode = value ? 'STANDARD' : 'SIMPLE'
  const previous = row.approvalMode || 'STANDARD'
  row.approvalMode = approvalMode
  try {
    await sysApi.saveOrgApprovalMode(row.id, { approvalMode })
    ElMessage.success(`已切换为${approvalModeLabel(approvalMode)}`)
    await load()
  } catch (e) {
    row.approvalMode = previous
    throw e
  }
}

async function remove(row: any) {
  if (row?.kind === 'person') return removePerson(row)
  await ElMessageBox.confirm(`确认删除 ${row.name}？下级组织也会被删除。`, '删除确认', { type: 'warning' })
  await sysApi.delOrg(row.id)
  ElMessage.success('已删除')
  await load()
}

async function savePerson() {
  await personFormRef.value?.validate()
  personSaving.value = true
  try {
    const payload: any = {
      id: personForm.id,
      username: personForm.username,
      name: personForm.name,
      orgId: personForm.orgId,
      roleIds: personForm.roleIds,
      phone: personForm.phone,
      email: personForm.email,
      status: personForm.statusBool ? 1 : 0
    }
    if (!personForm.id) payload.password = personForm.password
    await sysApi.saveUser(payload)
    ElMessage.success(personForm.id ? '已更新' : '已创建')
    personVisible.value = false
    await load()
  } finally {
    personSaving.value = false
  }
}

async function removePerson(row: any) {
  const confirmed = await ElMessageBox.confirm(`确认删除人员 ${row.name}？`, '删除确认', { type: 'warning' }).catch(() => null)
  if (!confirmed) return
  await sysApi.delUser(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped lang="scss">
.org-tree {
  min-height: 360px;
}
.org-node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-right: 8px;
}
.org-main {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.org-name {
  font-weight: 600;
  color: var(--text-1);
}
.org-code,
.org-remark {
  color: var(--text-3);
  font-size: 12px;
}
.org-actions {
  opacity: 0;
  transition: opacity 0.16s ease;
}
.org-node:hover .org-actions {
  opacity: 1;
}
.person-node {
  color: var(--text-2);
}
.person-node .org-name {
  font-weight: 500;
}
</style>
