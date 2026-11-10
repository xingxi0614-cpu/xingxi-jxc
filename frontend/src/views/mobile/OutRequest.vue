<template>
  <div class="mx-page">
    <header class="mx-top">
      <button class="mx-top__back" aria-label="返回" @click="$router.back()"><ArrowLeft :size="22" /></button>
      <span class="mx-top__title">出库申请</span>
    </header>

    <main class="mx-body mf-body">
      <DocGuardHint class="mx-guard" :active="true" :date="form.applyDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <div class="mx-g">
        <div class="mx-g__label"><b>基本信息</b></div>
        <div class="mx-g__box">
          <MobPicker v-model="form.businessCategory" label="业务分类" required :searchable="false"
            :options="businessCategories.map(c => ({ value: c, label: c }))" @change="onCategoryChange" />
          <MobPicker v-model="form.warehouseId" label="仓库" required :options="nameOptions(warehouses)" />
          <MobPicker v-model="form.projectId" label="工程项目" required clearable :options="nameOptions(projects)" />
          <label class="mx-row"><span class="mx-row__label">施工单位</span><input v-model="form.constructionUnit" class="mx-row__input" placeholder="选填" /></label>
          <label class="mx-row"><span class="mx-row__label">材料申请人<i>*</i></span><input v-model="form.materialApplicant" class="mx-row__input" placeholder="必填" /></label>
          <MobDate v-model="form.applyDate" label="申请日期" />
          <label class="mx-row"><span class="mx-row__label">用途</span><input v-model="form.purpose" class="mx-row__input" placeholder="选填" /></label>
          <label class="mx-row"><span class="mx-row__label">审批单据号</span><input v-model="form.approvalDocNo" class="mx-row__input" placeholder="如 TSSX-202609280025" /></label>
          <label class="mx-row"><span class="mx-row__label">备注</span><input v-model="form.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>

      <div v-for="(line, i) in form.lines" :key="line._k" class="mx-g">
        <div class="mx-g__label">
          <b>领用明细 {{ i + 1 }}</b>
          <button type="button" class="mx-g__more is-danger" @click="form.lines.splice(i, 1)">删除</button>
        </div>
        <div class="mx-g__box">
          <MobPicker v-model="line.materialId" label="物料" required wrap placeholder="选择物料" :search="searchMaterialOptions" />
          <div class="mx-row"><span class="mx-row__label">申请数量<i>*</i></span><MobNumber v-model="line.qty" stepper /></div>
          <label class="mx-row"><span class="mx-row__label">行备注</span><input v-model="line.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>
      <div class="mx-g"><button type="button" class="mx-add" @click="addLine">+ 加一行物料</button></div>
    </main>

    <div class="mx-bar">
      <button class="mx-bar__btn mx-bar__btn--ghost" :disabled="guardBlocked || saving" @click="save(false)">存草稿</button>
      <button class="mx-bar__btn mx-bar__btn--main" :disabled="guardBlocked || saving" @click="save(true)">{{ saving ? '提交中...' : '提交申请' }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import DocGuardHint from '@/components/DocGuardHint.vue'
import MobPicker from './parts/MobPicker.vue'
import MobNumber from './parts/MobNumber.vue'
import MobDate from './parts/MobDate.vue'
import { nameOptions, searchMaterialOptions } from './parts/options'
import { ArrowLeft } from '@/icons'
import { useLeaveGuard } from '@/composables/useLeaveGuard'
import { onMounted, reactive, ref } from 'vue'
import { today as todayLocal } from '@/utils/format'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { masterApi, outApi } from '@/api'
import { checkDuplicateOreq } from '@/utils/oreqDuplicate'
import { useUserStore } from '@/stores/user'
import { useBizOptions } from '@/composables/useBizOptions'

const router = useRouter()
// 填了没提交就返回会先问；已结账时提交按钮变灰（提示在页面顶部）
const leave = useLeaveGuard()
const guardBlocked = ref(false)
const user = useUserStore()
const saving = ref(false)
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])
const { businessCategories, loadBizOptions } = useBizOptions()

const today = todayLocal()
// _k 只给列表渲染当 key 用（删掉中间一行时各行不串），提交时不带
let lineSeq = 0
const form = reactive<any>({
  warehouseId: null, projectId: null, constructionUnit: '',
  businessCategory: '', materialApplicant: user.userInfo?.name || '',
  purpose: '', approvalDocNo: '', applyDate: today, remark: '', lines: [emptyLine()]
})
function emptyLine() { return { _k: ++lineSeq, materialId: null, qty: 0, remark: '' } }
function addLine() { form.lines.push(emptyLine()) }

function isProjectCategory(c: string) { return ['工程业务', '工程类', 'PROJECT'].includes(String(c || '').trim()) }
function onCategoryChange(c: string) {
  if (isProjectCategory(c) && !String(form.purpose).trim()) form.purpose = '工程领料'
}

async function save(submit: boolean) {
  if (!form.warehouseId) { ElMessage.warning('请选择仓库'); return }
  if (!form.projectId) { ElMessage.warning('请选择工程项目'); return }
  if (!form.businessCategory) { ElMessage.warning('请选择业务分类'); return }
  if (!String(form.materialApplicant).trim()) { ElMessage.warning('请填写材料申请人'); return }
  if (form.lines.some((l: any) => !l.materialId || Number(l.qty) <= 0)) { ElMessage.warning('请完善领用明细（物料和数量）'); return }
  // 2026-10-16:工程类申请,同一工程已有未完成的申请 → 保存前提醒一次(只提醒,可"仍新建")
  const choice = await checkDuplicateOreq({
    category: form.businessCategory, projectId: form.projectId,
    projectName: projects.value.find((p: any) => p.id === form.projectId)?.name, canRevise: false
  })
  if (choice !== 'continue') return
  saving.value = true
  try {
    const payload = {
      warehouseId: form.warehouseId, projectId: form.projectId, constructionUnit: form.constructionUnit,
      businessCategory: form.businessCategory, materialApplicant: form.materialApplicant,
      purpose: form.purpose, approvalDocNo: form.approvalDocNo, applyDate: form.applyDate, remark: form.remark,
      lines: form.lines.map((l: any) => ({ materialId: l.materialId, qty: l.qty, remark: l.remark }))
    }
    const r: any = await outApi.oreqCreate(payload)
    const id = r?.id || r?.data?.id
    if (submit && id) await outApi.oreqSubmit(id)
    ElMessage.success(submit ? '已提交' : '草稿已保存')
    leave.allow()
    router.back()
  } catch { /* 拦截器统一提示 */ } finally { saving.value = false }
}

onMounted(async () => {
  await loadBizOptions()
  form.businessCategory = businessCategories.value[0] || ''
  onCategoryChange(form.businessCategory)
  try { warehouses.value = await masterApi.warehouses() as any[]; form.warehouseId = warehouses.value[0]?.id || null } catch {}
  try { projects.value = await masterApi.projects() as any[] } catch {}
})
</script>

