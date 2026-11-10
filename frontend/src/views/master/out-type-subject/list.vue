<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">出库类型科目映射</div>
        <div class="page-sub">每个出库类型对应一个总账借方科目，直接决定材料出库成本归到哪个科目。新建/修改出库单时按此显示科目，月末「出库成本科目汇总」按此归集。此处维护的出库类型即全站出库类型下拉。</div>
      </div>
      <el-space>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openForm()">+ 新增映射</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无映射">
        <el-table-column type="index" label="序号" width="64" />
        <el-table-column label="适用范围" prop="category" width="120">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column label="出库类型" prop="outType" min-width="180" show-overflow-tooltip />
        <el-table-column label="对应总账借方科目" prop="subject" min-width="280" show-overflow-tooltip>
          <template #default="{ row }"><span class="subj">{{ row.subject }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" show-icon class="mt-3"
        title="停用某出库类型后，它会从全站出库类型下拉中移除（老单据已记的类型不受影响）。删除同理。科目支持用反斜杠“\”分层，如 工程施工\材料费。" />
    </el-card>

    <el-dialog v-model="dialogVisible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑映射' : '新增映射'" width="480px" align-center>
      <el-form label-position="top" :model="form" label-width="100px">
        <el-form-item label="适用范围">
          <el-select v-model="form.category" placeholder="选填" clearable filterable allow-create style="width:100%">
            <el-option v-for="c in ['通用','企业自定义']" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="出库类型" required>
          <el-input v-model="form.outType" maxlength="60" placeholder="如 生产领料 / 工程领料" />
        </el-form-item>
        <el-form-item label="借方科目" required>
          <el-input v-model="form.subject" maxlength="120" placeholder="如 工程施工\材料费（反斜杠分层）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :controls="false" style="width:120px" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { outTypeSubjectApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { showError } from '@/utils/notify'

const user = useUserStore()
const canEdit = computed(() => user.hasRole('account_admin') || user.hasRole('admin'))
const loading = ref(false)
const rows = ref<any[]>([])

async function load() {
  loading.value = true
  try { rows.value = await outTypeSubjectApi.list() as any }
  finally { loading.value = false }
}

const dialogVisible = ref(false)
const dialogGuard = useUnsavedGuard(dialogVisible)
const saving = ref(false)
const form = reactive<any>({ id: null, category: '', outType: '', subject: '', sort: 0, status: 1 })
function openForm(row?: any) {
  if (row) Object.assign(form, { id: row.id, category: row.category, outType: row.outType, subject: row.subject, sort: row.sort, status: row.status })
  else Object.assign(form, { id: null, category: '', outType: '', subject: '', sort: (rows.value.length + 1) * 10, status: 1 })
  dialogVisible.value = true
}
async function save() {
  if (!form.outType?.trim()) { ElMessage.warning('请填写出库类型'); return }
  if (!form.subject?.trim()) { ElMessage.warning('请填写借方科目'); return }
  saving.value = true
  try {
    await outTypeSubjectApi.save({ ...form })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e: any) { showError(e, '保存失败') }
  finally { saving.value = false }
}
async function remove(row: any) {
  await ElMessageBox.confirm(`删除出库类型「${row.outType}」的科目映射?删除后它从出库类型下拉中移除。`, '提示', { type: 'warning' })
  await outTypeSubjectApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.subj { font-family: ui-monospace, Menlo, monospace; color: #1f2937; }
.mt-3 { margin-top: 12px; }
</style>
