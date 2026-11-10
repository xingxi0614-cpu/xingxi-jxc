<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">滚动通知</div>
        <div class="page-sub">发布后在所选公司的 ERP 顶栏滚动显示；到结束日期自动下线，可随时停用。只有系统管理员能发布。</div>
      </div>
      <el-space>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openForm()">+ 发布通知</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-table :data="rows" border class="readable-table" v-loading="loading" empty-text="还没有发布过滚动通知">
        <el-table-column prop="title" label="标题（滚动显示的那一行）" min-width="300" show-overflow-tooltip />
        <el-table-column label="重要程度" width="100" align="center">
          <template #default="{ row }"><el-tag :type="row.level === 'IMPORTANT' ? 'warning' : 'primary'" effect="light">{{ row.level === 'IMPORTANT' ? '重要' : '普通' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="orgNames" label="显示到" min-width="180" show-overflow-tooltip />
        <el-table-column prop="startDate" label="开始" width="110" />
        <el-table-column prop="endDate" label="结束" width="110" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><el-tag :type="stateOf(row).type" effect="plain">{{ stateOf(row).label }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="createdByName" label="发布人" width="110" />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button v-if="row.status === 1" link type="warning" @click="toggle(row, false)">停用</el-button>
            <el-button v-else link type="primary" @click="toggle(row, true)">恢复</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑滚动通知' : '发布滚动通知'" width="min(760px, calc(100vw - 32px))" draggable>
      <el-form label-position="top" :model="form">
        <el-form-item label="标题（滚动条上显示这一行）" required>
          <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="建议 30 字以内，一眼能看完" />
        </el-form-item>
        <el-form-item label="正文（点开后看的全文，选填）">
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :xs="24" :sm="10">
            <el-form-item label="重要程度" required>
              <el-radio-group v-model="form.level">
                <el-radio-button value="NORMAL">普通</el-radio-button>
                <el-radio-button value="IMPORTANT">重要（橙色、排最前）</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="14">
            <el-form-item label="显示时间" required>
              <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:100%" />
              <div class="tk-tip">到结束日期当天过完后自动不再显示</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="显示到哪些公司" required>
          <div class="tk-cos">
            <el-checkbox v-model="form.allOrgs" class="tk-all">全部公司（含以后新开的公司）</el-checkbox>
            <el-checkbox-group v-if="!form.allOrgs" v-model="form.orgIds">
              <el-checkbox v-for="c in companies" :key="c.id" :value="c.id">{{ c.name }}</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="tk-tip">默认全部公司；只发给某几家就取消「全部公司」再勾选。每家公司只看到发给自己的通知。</div>
        </el-form-item>
        <div class="tk-preview">
          <span class="k">预览</span>
          <span class="tk-pv" :class="{ imp: form.level === 'IMPORTANT' }">
            <span class="tag">{{ form.level === 'IMPORTANT' ? '重要' : '通知' }}</span>{{ form.title || '（标题）' }}
          </span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ form.id ? '保存' : '发布' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tickerApi } from '@/api'
import { today } from '@/utils/format'

const rows = ref<any[]>([])
const companies = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const formVisible = ref(false)
const form = reactive<any>({ id: null, title: '', content: '', level: 'NORMAL', range: null, allOrgs: true, orgIds: [] })

function stateOf(row: any): { label: string; type: any } {
  const d = today()
  if (row.status !== 1) return { label: '已停用', type: 'info' }
  if (row.startDate > d) return { label: '未开始', type: 'primary' }
  if (row.endDate < d) return { label: '已过期', type: 'info' }
  return { label: '显示中', type: 'success' }
}

async function load() {
  loading.value = true
  try { rows.value = (await tickerApi.list() as any) || [] } finally { loading.value = false }
}

function openForm(row?: any) {
  Object.assign(form, row
    ? { id: row.id, title: row.title, content: row.content || '', level: row.level, range: [row.startDate, row.endDate], allOrgs: row.allOrgs === 1, orgIds: row.orgIds || [] }
    : { id: null, title: '', content: '', level: 'NORMAL', range: [today(), today()], allOrgs: true, orgIds: [] })
  formVisible.value = true
}

async function save() {
  if (!form.title.trim()) { ElMessage.warning('请填写标题'); return }
  if (!form.range?.[0] || !form.range?.[1]) { ElMessage.warning('请选择显示时间'); return }
  if (!form.allOrgs && !form.orgIds.length) { ElMessage.warning('请至少选一家公司，或勾选「全部公司」'); return }
  const payload = {
    title: form.title, content: form.content, level: form.level,
    startDate: form.range[0], endDate: form.range[1],
    allOrgs: form.allOrgs ? 1 : 0, orgIds: form.allOrgs ? [] : form.orgIds
  }
  saving.value = true
  try {
    if (form.id) await tickerApi.update(form.id, payload)
    else await tickerApi.create(payload)
    ElMessage.success(form.id ? '已保存' : '已发布，各公司顶栏最迟 5 分钟内出现')
    formVisible.value = false
    await load()
  } finally { saving.value = false }
}

async function toggle(row: any, enabled: boolean) {
  await tickerApi.setStatus(row.id, enabled)
  ElMessage.success(enabled ? '已恢复' : '已停用，各公司顶栏最迟 5 分钟内消失')
  await load()
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？删除后各公司不再显示，也不会出现在这个列表里。`, '删除滚动通知', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  await tickerApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await load()
  companies.value = (await tickerApi.companies() as any) || []
})
</script>

<style scoped>
.tk-tip { font-size: 12px; color: var(--text-3); line-height: 1.5; margin-top: 4px; }
.tk-cos { width: 100%; border: 1px solid var(--border-1); border-radius: 6px; padding: 6px 12px; }
.tk-all { font-weight: 600; }
.tk-preview { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px dashed var(--border-1); border-radius: 8px; background: var(--surface-soft, #f5f7fa); }
.tk-preview .k { font-size: 12px; color: var(--text-3); }
.tk-pv { display: inline-flex; align-items: center; gap: 6px; font-size: 13.5px; padding: 4px 10px; border-radius: 6px; background: var(--color-primary-soft, #e8f1fb); }
.tk-pv.imp { background: #fff6f0; }
.tk-pv .tag { font-size: 11px; padding: 0 6px; border-radius: 4px; font-weight: 600; background: rgba(29, 99, 176, .12); color: var(--color-primary); }
.tk-pv.imp .tag { background: #fde4d6; color: #b4471a; }
</style>
