<template>
  <div class="mx-page">
    <header class="mx-top">
      <button class="mx-top__back" aria-label="返回" @click="$router.back()"><ArrowLeft :size="22" /></button>
      <span class="mx-top__title">借料登记</span>
    </header>

    <main class="mx-body mf-body">
      <DocGuardHint class="mx-guard" :active="true" :date="form.borrowDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <div class="mx-g">
        <div class="mx-g__label"><b>基本信息</b></div>
        <div class="mx-g__box">
          <MobPicker v-model="form.warehouseId" label="仓库" required :options="nameOptions(warehouses)" />
          <MobPicker v-model="form.projectId" label="工程项目" clearable placeholder="选填" :options="nameOptions(projects)" />
          <label class="mx-row"><span class="mx-row__label">借料人<i>*</i></span><input v-model="form.borrower" class="mx-row__input" placeholder="必填" /></label>
          <label class="mx-row"><span class="mx-row__label">借料部门</span><input v-model="form.borrowerDept" class="mx-row__input" placeholder="选填" /></label>
          <MobDate v-model="form.borrowDate" label="借料日期" />
          <MobDate v-model="form.expectedReturnDate" label="预计归还" clearable placeholder="选填" />
          <label class="mx-row"><span class="mx-row__label">用途<i>*</i></span><input v-model="form.purpose" class="mx-row__input" placeholder="如：抢修临借" /></label>
          <label class="mx-row"><span class="mx-row__label">备注</span><input v-model="form.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>

      <div v-for="(line, i) in form.lines" :key="line._k" class="mx-g">
        <div class="mx-g__label">
          <b>借料明细 {{ i + 1 }}</b>
          <button type="button" class="mx-g__more is-danger" @click="form.lines.splice(i, 1)">删除</button>
        </div>
        <div class="mx-g__box">
          <MobPicker v-model="line.materialId" label="物料" required wrap placeholder="选择物料" :search="searchMaterialOptions" />
          <div class="mx-row"><span class="mx-row__label">借出数量<i>*</i></span><MobNumber v-model="line.qty" stepper /></div>
          <label class="mx-row"><span class="mx-row__label">行备注</span><input v-model="line.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>
      <div class="mx-g"><button type="button" class="mx-add" @click="addLine">+ 加一行物料</button></div>
    </main>

    <div class="mx-bar">
      <button class="mx-bar__btn mx-bar__btn--main" :disabled="guardBlocked || saving" @click="save">{{ saving ? '保存中...' : '登记借料（扣减库存）' }}</button>
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

const router = useRouter()
// 填了没提交就返回会先问；已结账时提交按钮变灰（提示在页面顶部）
const leave = useLeaveGuard()
const guardBlocked = ref(false)
const saving = ref(false)
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])

const today = todayLocal()
// _k 只给列表渲染当 key 用（删掉中间一行时各行不串），提交时不带
let lineSeq = 0
const form = reactive<any>({
  warehouseId: null, projectId: null, borrower: '', borrowerDept: '',
  borrowDate: today, expectedReturnDate: '', purpose: '', remark: '', lines: [emptyLine()]
})
function emptyLine() { return { _k: ++lineSeq, materialId: null, qty: 0, remark: '' } }
function addLine() { form.lines.push(emptyLine()) }

async function save() {
  if (!form.warehouseId) { ElMessage.warning('请选择仓库'); return }
  if (!String(form.borrower).trim()) { ElMessage.warning('请填写借料人'); return }
  if (!String(form.purpose).trim()) { ElMessage.warning('请填写用途'); return }
  const lines = form.lines.filter((l: any) => l.materialId && Number(l.qty) > 0)
  if (!lines.length) { ElMessage.warning('请至少添加一行有效借料明细'); return }
  saving.value = true
  try {
    await outApi.loanCreate({
      warehouseId: form.warehouseId, projectId: form.projectId,
      borrower: String(form.borrower).trim(), borrowerDept: String(form.borrowerDept || '').trim(),
      borrowDate: form.borrowDate, expectedReturnDate: form.expectedReturnDate || null,
      purpose: String(form.purpose).trim(), remark: form.remark,
      lines: lines.map((l: any) => ({ materialId: l.materialId, qty: Number(l.qty), remark: l.remark }))
    })
    ElMessage.success('借料登记已保存为草稿；在电脑端「借料登记」提交、材料会计审核通过后才扣减库存')
    leave.allow()
    router.back()
  } catch { /* 拦截器统一提示 */ } finally { saving.value = false }
}

onMounted(async () => {
  try { warehouses.value = await masterApi.warehouses() as any[]; form.warehouseId = warehouses.value[0]?.id || null } catch {}
  try { projects.value = await masterApi.projects() as any[] } catch {}
})
</script>

