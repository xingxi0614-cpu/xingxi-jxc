<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">领料人档案</div>
        <div class="page-sub">维护各公司的领料人。一线提交领料申请时从本公司清单里选领料人（不能手填）。</div>
      </div>
      <el-space>
        <el-button type="primary" @click="openCreate">+ 新增领料人</el-button>
        <el-button @click="load">刷新</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="公司">
          <el-select v-model="query.orgId" placeholder="全部公司" clearable filterable style="width:240px" @change="load">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="query.keyword" placeholder="领料人姓名" clearable style="width:180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无领料人">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column label="姓名" min-width="160">
          <template #default="{ row }"><b>{{ row.name }}</b></template>
        </el-table-column>
        <el-table-column label="所属公司" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || orgName(row.orgId) || row.orgId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑领料人' : '新增领料人'" width="440px">
      <el-form label-position="top" :model="form" label-width="80px">
        <el-form-item label="公司" required>
          <el-select v-model="form.orgId" placeholder="选择公司" filterable style="width:100%" :disabled="!!form.id">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="领料人姓名" maxlength="64" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
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
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { materialReqApi, sysApi } from '@/api'
import { showError } from '@/utils/notify'

const loading = ref(false)
const rows = ref<any[]>([])
const orgOptions = ref<any[]>([])
const query = reactive<any>({ orgId: null, keyword: '' })

function flattenOrgs(nodes: any[], level = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, name: node.name, label: `${'　'.repeat(level)}${node.name}（${node.code}）`, type: node.type, status: node.status })
    flattenOrgs(node.children || [], level + 1, out)
  }
  return out
}
function orgName(id: number) {
  return orgOptions.value.find(o => o.id === id)?.name || ''
}

async function loadOptions() {
  orgOptions.value = flattenOrgs(await sysApi.orgTree() as any[])
    .filter(o => o.status !== 0 && o.type === 'COMPANY')
}

async function load() {
  loading.value = true
  try {
    rows.value = await materialReqApi.pickerList({ orgId: query.orgId, keyword: query.keyword }) as any
  } finally { loading.value = false }
}

const dialogVisible = ref(false)
const dialogGuard = useUnsavedGuard(dialogVisible)
const saving = ref(false)
const form = reactive<any>({ id: null, orgId: null, name: '', status: 1 })

function openCreate() {
  Object.assign(form, { id: null, orgId: query.orgId || orgOptions.value[0]?.id || null, name: '', status: 1 })
  dialogVisible.value = true
}
function openEdit(row: any) {
  Object.assign(form, { id: row.id, orgId: row.orgId, name: row.name, status: row.status })
  dialogVisible.value = true
}

async function save() {
  if (!form.orgId) { ElMessage.warning('请选择公司'); return }
  if (!form.name?.trim()) { ElMessage.warning('请填写姓名'); return }
  saving.value = true
  try {
    if (form.id) await materialReqApi.pickerUpdate(form.id, { name: form.name, status: form.status })
    else await materialReqApi.pickerCreate({ orgId: form.orgId, name: form.name, status: form.status })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e: any) { showError(e, '保存失败') }
  finally { saving.value = false }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除领料人「${row.name}」?`, '提示', { type: 'warning' })
  await materialReqApi.pickerDelete(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => { await loadOptions(); await load() })
</script>
