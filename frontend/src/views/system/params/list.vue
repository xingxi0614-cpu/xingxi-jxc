<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">系统参数</div>
        <div class="page-sub">当前账套运行参数</div>
      </div>
      <el-space>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openAdd">新增参数</el-button>
      </el-space>
    </div>
    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading">
        <el-table-column prop="param_key" label="参数" width="240" />
        <el-table-column prop="param_value" label="值" min-width="240" />
        <el-table-column prop="remark" label="说明" min-width="170" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog draggable v-model="editVisible" :before-close="editGuard.beforeClose" :title="isNew ? '新增系统参数' : '编辑系统参数'" width="520px">
      <el-form label-position="top" :model="form" label-width="90px">
        <el-form-item label="参数">
          <el-input v-model="form.param_key" :disabled="!isNew" placeholder="新参数的唯一标识" />
        </el-form-item>
        <el-form-item label="参数值" required>
          <el-input
            v-model="form.param_value"
            type="textarea"
            :rows="4"
            placeholder="多个选项可用逗号、中文逗号或换行分隔"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editGuard.cancel">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sysApi } from '@/api'

const loading = ref(false)
const rows = ref<any[]>([])
const editVisible = ref(false)
const editGuard = useUnsavedGuard(editVisible)
const isNew = ref(false)
const form = reactive<any>({ param_key: '', param_value: '', remark: '' })

async function load() {
  loading.value = true
  try { rows.value = await sysApi.params() as any } finally { loading.value = false }
}

function openAdd() {
  isNew.value = true
  Object.assign(form, { param_key: '', param_value: '', remark: '' })
  editVisible.value = true
}

function openEdit(row: any) {
  isNew.value = false
  Object.assign(form, row)
  editVisible.value = true
}

async function save() {
  if (!form.param_key || !String(form.param_key).trim()) {
    ElMessage.error('请填写参数键')
    return
  }
  if (!String(form.param_value || '').trim()) {
    ElMessage.error('请填写参数值')
    return
  }
  await sysApi.saveParam(form.param_key.trim(), { paramValue: form.param_value.trim(), remark: form.remark })
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
}

onMounted(load)
</script>
