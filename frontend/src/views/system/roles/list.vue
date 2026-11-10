<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">角色与权限</div>
        <div class="page-sub">
          岗位在这里建、权限在这里勾。<b>角色是全系统共用的</b>——改「采购员」的权限，所有公司的采购员都会跟着变。
        </div>
      </div>
      <el-space>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="isAdmin" type="primary" @click="openCreate">+ 新建角色</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="statusTab" :options="STATUS_OPTIONS" :counts="statusCounts" />
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="shownRoles" v-loading="loading" empty-text="暂无角色">
        <el-table-column prop="name" label="角色名" width="120" />
        <el-table-column label="编码" width="140">
          <template #default="{ row }"><code>{{ row.code }}</code></template>
        </el-table-column>
        <el-table-column label="能看哪些数据" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="row.dataScope === 'ALL' ? 'danger' : 'info'">
              {{ scopeLabel(row.dataScope) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在用人数" width="90" align="right">
          <template #default="{ row }">{{ userCount[row.id] ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="权限摘要" min-width="260">
          <template #default="{ row }">{{ summary(row.code) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openPerms(row)">配置权限</el-button>
            <template v-if="isAdmin && row.code !== 'admin'">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRole(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :before-close="editGuard.beforeClose" :title="form.id ? '编辑角色' : '新建角色'" width="520px" @closed="resetForm">
      <el-alert v-if="!form.id" type="warning" :closable="false" show-icon style="margin-bottom:14px">
        角色建好后<b>编码不能再改</b>（它是权限判断的依据）。新角色默认没有任何权限，建完记得点「配置权限」勾一遍。
      </el-alert>
      <el-form label-position="top" :model="form" label-width="96px">
        <el-form-item label="角色名称" required>
          <el-input v-model="form.name" maxlength="40" placeholder="如：行政人事、工程部经理、安全员" />
        </el-form-item>
        <el-form-item label="角色编码" required>
          <el-input v-model="form.code" :disabled="!!form.id" maxlength="40" placeholder="小写字母/数字/下划线，如 hr_admin" />
        </el-form-item>
        <el-form-item label="能看哪些数据">
          <el-select v-model="form.dataScope" style="width:100%">
            <el-option v-for="o in SCOPE_OPTIONS" :key="o.value" :value="o.value" :label="o.label">
              <span>{{ o.label }}</span>
              <span class="scope-hint">{{ o.hint }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" placeholder="这个岗位是干什么的（可空）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable v-model="visible" :title="`配置权限：${currentRole?.name || ''}`" width="680px">
      <div class="perm-toolbar">
        <el-button size="small" @click="checkAll">全选</el-button>
        <el-button size="small" @click="clearAll">清空</el-button>
      </div>
      <el-tree
        ref="treeRef"
        :data="menus"
        :props="{ label: 'name', children: 'children' }"
        show-checkbox
        default-expand-all
        node-key="id"
        class="perm-tree"
      >
        <template #default="{ node, data }">
          <span class="menu-node">
            <span>{{ node.label }}</span>
            <el-tag size="small" type="info">{{ data.type }}</el-tag>
            <span v-if="data.path" class="muted">{{ data.path }}</span>
            <span v-if="data.permCode" class="muted">{{ data.permCode }}</span>
          </span>
        </template>
      </el-tree>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePerms">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import StatusChips from '@/components/StatusChips.vue'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { showError } from '@/utils/notify'

const user = useUserStore()
// 角色是全系统共用的,只有系统管理员能建/改/删;账套管理员只能看
const isAdmin = computed(() => (user.roles || []).includes('admin'))

/** 数据范围:决定这个角色能看到哪些公司/部门的数据。给错等于数据泄露,所以每项都写清楚。 */
const SCOPE_OPTIONS = [
  { value: 'ACCOUNT_SET', label: '本公司(推荐)', hint: '本公司及下属部门的数据' },
  { value: 'DEPT',        label: '本部门',        hint: '只看自己部门及下级' },
  { value: 'SELF',        label: '仅本人',        hint: '只看自己经手的单据' },
  { value: 'GROUP',       label: '整个集团',      hint: '集团下所有公司,慎用' },
  { value: 'ALL',         label: '全部数据',      hint: '不受任何限制,仅系统管理员' }
]
function scopeLabel(v?: string) {
  return SCOPE_OPTIONS.find(o => o.value === v)?.label || v || '—'
}

/* ── 新建 / 编辑角色 ── */
const editVisible = ref(false)
const editGuard = useUnsavedGuard(editVisible)
const userCount = ref<Record<number, number>>({})
const form = reactive<any>({ id: null, code: '', name: '', dataScope: 'ACCOUNT_SET', remark: '', status: 1 })
function resetForm() {
  Object.assign(form, { id: null, code: '', name: '', dataScope: 'ACCOUNT_SET', remark: '', status: 1 })
}
function openCreate() { resetForm(); editVisible.value = true }
function openEdit(row: any) {
  Object.assign(form, { id: row.id, code: row.code, name: row.name, dataScope: row.dataScope || 'ACCOUNT_SET', remark: row.remark || '', status: row.status ?? 1 })
  editVisible.value = true
}
async function saveRole() {
  if (!form.name.trim()) { ElMessage.warning('请填写角色名称'); return }
  if (!form.id && !/^[a-z][a-z0-9_]{1,39}$/.test(form.code.trim())) {
    ElMessage.warning('角色编码只能用小写字母、数字、下划线,以字母开头'); return
  }
  saving.value = true
  try {
    if (form.id) await sysApi.updateRole(form.id, { ...form })
    else await sysApi.createRole({ ...form })
    ElMessage.success(form.id ? '已保存' : '角色已创建,记得点「配置权限」勾一遍它能用什么')
    editVisible.value = false
    await load()
  } catch (e: any) {
    showError(e, '保存失败')
  } finally {
    saving.value = false
  }
}
async function removeRole(row: any) {
  const ok = await ElMessageBox.confirm(`确定删除角色「${row.name}」?`, '提示', { type: 'warning' }).catch(() => null)
  if (!ok) return
  try {
    await sysApi.delRole(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    showError(e, '删除失败')
  }
}

const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const roles = ref<any[]>([])
// 「全部 / 启用 / 已停用」页签(2026-10-17):本页一次取回全部角色,直接在页面上按状态分开看
const statusTab = ref('')
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '启用', value: '1' }, { label: '已停用', value: '0' }]
const statusCounts = computed(() => ({ '': roles.value.length, '1': roles.value.filter((r: any) => Number(r.status) === 1).length, '0': roles.value.filter((r: any) => Number(r.status) !== 1).length }))
const byStatus = (list: any[]) => statusTab.value === '' ? list : list.filter((r: any) => (Number(r.status) === 1) === (statusTab.value === '1'))
const shownRoles = computed(() => byStatus(roles.value))
const menus = ref<any[]>([])
const currentRole = ref<any>(null)
const treeRef = ref<any>(null)

const summaries: Record<string, string> = {
  purchaser: '采购订单录入与跟踪',
  warehouse: '仓管负责入库、出库和库存数量管理',
  finance: '旧财务角色已合并到材料会计，仅保留历史兼容',
  account_admin: '本账套内账号、组织下级、供应商、物料、仓库和工程项目维护，可叠加给材料会计等人员',
  material_accountant: '采购订单、发票、入库、出库申请、材料出库、退料、转库最终审核；维护库存期间和成本参考',
  finance_manager: '采购订单财务部门负责人审批，关注预算、付款和财务合规',
  deputy_general_manager: '采购订单分管副总审批，可按公司分工授予运营、工程或市场副总',
  general_manager: '采购订单总经理审批，作为采购链路最高经营审核岗位',
  dept_manager: '旧部门负责人角色已合并到技术部经理，仅保留历史兼容',
  field_manager: '现场管理员，可发起工程类材料出库申请',
  tech_manager: '技术部经理，审核采购入库、工程类出库申请和材料出库',
  service_staff: '网点客服，可发起运营或增值销售类出库申请',
  operation_repair: '运维人员，可发起运营维修类材料申请',
  branch_manager: '网点负责人，审核本站运营和增值销售类出库申请',
  area_manager: '片区经理，审核下属多个网点的运营和增值销售类申请',
  business_leader: '旧分管领导角色已合并到分管副总，仅保留历史兼容',
  operation_leader: '运营副总，审核运营类业务申请',
  engineering_leader: '工程副总，审核工程类业务申请',
  market_leader: '市场副总，审核增值销售类业务申请',
  accountant: '旧会计角色已合并到材料会计，仅保留历史兼容',
  cashier: '旧出纳角色已合并到材料会计，仅保留历史兼容',
  admin: '账号、角色分配、组织账套、基础资料维护'
}
function summary(code: string) { return summaries[code] || '按角色分配菜单与操作权限' }

function collectIds(nodes: any[], out: number[] = []) {
  for (const n of nodes || []) {
    out.push(n.id)
    collectIds(n.children || [], out)
  }
  return out
}

async function loadUserCount() {
  // 「在用人数」用于提醒"这个角色还有人在用,别乱删";拿不到就显示 —,不影响主流程
  try {
    const page: any = await sysApi.users({ current: 1, size: 500 })
    const rows = page?.records || page?.list || []
    const cnt: Record<number, number> = {}
    for (const u of rows) for (const rid of (u.roleIds || [])) cnt[rid] = (cnt[rid] || 0) + 1
    userCount.value = cnt
  } catch { userCount.value = {} }
}

async function load() {
  loading.value = true
  try {
    const [roleRows, menuRows] = await Promise.all([sysApi.roles(), sysApi.menuTree()])
    loadUserCount()
    roles.value = roleRows as any[]
    menus.value = menuRows as any[]
  } finally {
    loading.value = false
  }
}

/** 收集所有非 MENU 类型（DIR）的节点 ID，加载和保存时过滤掉 */
function collectDirIds(nodes: any[]): Set<number> {
  const out = new Set<number>()
  const walk = (items: any[]) => {
    for (const n of items || []) {
      if (n.type !== 'MENU') out.add(n.id)
      walk(n.children || [])
    }
  }
  walk(nodes)
  return out
}

async function openPerms(row: any) {
  currentRole.value = row
  visible.value = true
  const ids = await sysApi.roleMenus(row.id) as number[]
  const dirIds = collectDirIds(menus.value)
  const leafIds = (ids || []).filter(id => !dirIds.has(id))
  await nextTick()
  treeRef.value?.setCheckedKeys(leafIds)
}

function checkAll() {
  treeRef.value?.setCheckedKeys(collectIds(menus.value))
}

function clearAll() {
  treeRef.value?.setCheckedKeys([])
}

async function savePerms() {
  if (!currentRole.value) return
  saving.value = true
  try {
    const checked = treeRef.value?.getCheckedKeys(false) || []
    const halfChecked = treeRef.value?.getHalfCheckedKeys() || []
    const ids = Array.from(new Set([...checked, ...halfChecked])).map(Number)
    await sysApi.saveRoleMenus(currentRole.value.id, ids)
    ElMessage.success('已保存')
    visible.value = false
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.perm-toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}
.perm-tree {
  max-height: 520px;
  overflow: auto;
  border: 1px solid var(--border-1);
  border-radius: 6px;
  padding: 8px;
}
.menu-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--text-3);
  font-size: 12px;
}
</style>
