<template>
  <div class="mx-page">
    <header class="mx-top">
      <button class="mx-top__back" aria-label="返回" @click="$router.back()"><ArrowLeft :size="22" /></button>
      <span class="mx-top__title">采购订单</span>
    </header>

    <main class="mx-body mf-body">
      <DocGuardHint class="mx-guard" :active="true" :date="form.docDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <div class="mx-g">
        <div class="mx-g__label"><b>基本信息</b></div>
        <div class="mx-g__box">
          <MobPicker v-model="form.supplierId" label="供应商" required placeholder="选择供应商" :search="searchSupplierOptions" />
          <MobDate v-model="form.docDate" label="单据日期" />
          <MobDate v-model="form.arrivalDate" label="预计到货" clearable placeholder="选填" />
          <MobPicker v-model="form.warehouseId" label="收货仓库" required :options="nameOptions(warehouses)" />
          <label class="mx-row"><span class="mx-row__label">备注</span><input v-model="form.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>

      <div v-for="(line, i) in form.lines" :key="line._k" class="mx-g">
        <div class="mx-g__label">
          <b>订单明细 {{ i + 1 }}</b>
          <button type="button" class="mx-g__more is-danger" @click="form.lines.splice(i, 1)">删除</button>
        </div>
        <div class="mx-g__box">
          <MobPicker v-model="line.materialId" label="物料" required wrap placeholder="选择物料" :search="searchMaterialOptions" @change="loadRefs" />
          <div class="mx-row"><span class="mx-row__label">数量<i>*</i></span><MobNumber v-model="line.qty" stepper /></div>
          <div class="mx-row"><span class="mx-row__label">含税单价<i>*</i></span><MobNumber v-model="line.price" :precision="4" /></div>
          <!-- 2026-10-01 小计直接算给人看：单价录成金额时这里会大得离谱，一眼就能发现（与电脑端含税金额栏同理）；单价精度与电脑端统一为 4 位 -->
          <div v-if="Number(line.qty) > 0 && Number(line.price) > 0" class="mx-note">小计 {{ fmtMoney(roundMoney(Number(line.qty) * Number(line.price))) }} 元</div>
          <div v-if="line.materialId && refs[line.materialId]" class="mx-note" :class="{ 'is-bad': bad(line) }">
            历史采购价约 {{ fmtMoney(refs[line.materialId].refPrice) }}<template v-if="bad(line)">，{{ deviationText(Number(line.price), refs[line.materialId].refPrice) }}，请核对</template>
          </div>
          <label class="mx-row"><span class="mx-row__label">行备注</span><input v-model="line.remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>
      <div class="mx-g">
        <button type="button" class="mx-add" @click="addLine">+ 加一行物料</button>
        <div v-if="orderTotal > 0" class="mx-total">共 {{ form.lines.length }} 行 · 合计 {{ fmtMoney(orderTotal) }} 元</div>
      </div>
    </main>

    <div class="mx-bar">
      <button class="mx-bar__btn mx-bar__btn--ghost" :disabled="guardBlocked || saving" @click="saveDraft">存草稿</button>
      <button class="mx-bar__btn mx-bar__btn--main" :disabled="guardBlocked || saving" @click="submit">{{ saving ? '提交中...' : '提交订单' }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import DocGuardHint from '@/components/DocGuardHint.vue'
import MobPicker from './parts/MobPicker.vue'
import MobNumber from './parts/MobNumber.vue'
import MobDate from './parts/MobDate.vue'
import { nameOptions, searchMaterialOptions, searchSupplierOptions } from './parts/options'
import { ArrowLeft } from '@/icons'
import { useLeaveGuard } from '@/composables/useLeaveGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { masterApi, poApi } from '@/api'
import { fmtMoney, roundMoney, today as todayLocal } from '@/utils/format'
import { fetchPriceRefs, fetchPriceRefsForSave, findPriceIssues, priceDeviation, deviationText, saveWithPriceNote, type PriceRef } from '@/utils/poPriceCheck'

// 把移动端表单映射为后端采购订单合同(桌面端字段:orderDate/deliveryDate/行 amount 含税金额+taxRate)
// 之前直接 {...form} 提交,行只有 price 没有 amount、日期名为 docDate,后端会报"含税金额不能为空"/"下单日期不能为空"
function buildPayload(status?: string) {
  return {
    supplierId: form.supplierId,
    warehouseId: form.warehouseId,
    orderDate: form.docDate,
    deliveryDate: form.arrivalDate || null,
    remark: form.remark,
    ...(status ? { status } : {}),
    lines: form.lines
      .filter((l: any) => l.materialId)
      .map((l: any) => ({
        materialId: l.materialId,
        qty: l.qty,
        amount: roundMoney((Number(l.qty) || 0) * (Number(l.price) || 0)), // 含税金额=数量×含税单价
        taxRate: 13,  // 默认13%(与桌面端物料默认税率一致);财务在桌面端可复核调整
        priceCheckNote: l.priceCheckNote || null,
        remark: l.remark
      }))
  }
}

const router = useRouter()
// 填了没提交就返回会先问；已结账时提交按钮变灰（提示在页面顶部）
const leave = useLeaveGuard()
const guardBlocked = ref(false)
const saving = ref(false)
const warehouses = ref<any[]>([])

const now = todayLocal()
// _k 只给列表渲染当 key 用（删掉中间一行时各行不串），提交时不带（buildPayload 逐字段挑）
let lineSeq = 0
const form = reactive<any>({
  supplierId: null, warehouseId: null,
  docDate: now, arrivalDate: '',
  remark: '', lines: [emptyLine()]
})
const orderTotal = computed(() => form.lines.reduce((t: number, l: any) => t + roundMoney((Number(l.qty) || 0) * (Number(l.price) || 0)), 0))
function emptyLine() { return { _k: ++lineSeq, materialId: null, qty: 0, price: 0, remark: '' } }
function addLine() { form.lines.push(emptyLine()) }

// 单价录成金额防错（2026-09-29）：与桌面端同一套阈值；后端保存时仍会强制把关
const refs = ref<Record<number, PriceRef>>({})
async function loadRefs() {
  try { Object.assign(refs.value, await fetchPriceRefs(form.lines.map((l: any) => l.materialId))) } catch { /* 只是提示 */ }
}
function bad(l: any) { return !!priceDeviation(Number(l.price || 0), refs.value[l.materialId]) }
/** 偏离行要求写理由；返回 false = 用户取消 */
async function priceCheck(): Promise<boolean> {
  const lines = form.lines.filter((l: any) => l.materialId)
  const r = await fetchPriceRefsForSave(lines.map((l: any) => l.materialId))
  Object.assign(refs.value, r)
  const issues = findPriceIssues(lines.map((l: any) => ({ materialId: l.materialId, qty: l.qty, amount: roundMoney((Number(l.qty) || 0) * (Number(l.price) || 0)) })), r)
  lines.forEach((l: any) => { l.priceCheckNote = '' })
  if (!issues.length) return true
  const text = issues.map(x => `第${x.index + 1}行：含税单价 ${fmtMoney(x.unitIncl)}，历史价约 ${fmtMoney(x.refPrice)}（${deviationText(x.unitIncl, x.refPrice)}）`).join('\n')
  try {
    const { value } = await ElMessageBox.prompt(`${text}\n\n确属正常（特价、赠品等）请写一句理由，审批人会看到：`, '单价和历史采购价差得太多', {
      confirmButtonText: '确认无误，继续', cancelButtonText: '返回修改', inputPlaceholder: '例：供应商清库特价',
      inputValidator: (v: string) => !!(v && v.trim()) || '请填写理由', customStyle: { whiteSpace: 'pre-line' } as any
    })
    issues.forEach(x => { lines[x.index].priceCheckNote = String(value).trim().slice(0, 200) })
    return true
  } catch { return false }
}

async function loadWarehouses() {
  try { warehouses.value = await masterApi.warehouses() as any[] } catch { warehouses.value = [] }
}

// 0 元明细一律不许保存（2026-09-29）：0 成本入库会拉低库存单价
function zeroPriceLine(): string {
  const i = form.lines.findIndex((l: any) => l.materialId && roundMoney((Number(l.qty) || 0) * (Number(l.price) || 0)) <= 0)
  return i < 0 ? '' : `第 ${i + 1} 行含税单价必须大于 0`
}
async function saveDraft() {
  if (saving.value) return
  const zero = zeroPriceLine()
  if (zero) { ElMessage.warning(zero); return }
  saving.value = true
  try {
    if (!(await priceCheck())) return
    await saveWithPriceNote(buildPayload('DRAFT'), p => poApi.create(p))
    ElMessage.success('草稿已保存')
    leave.allow()
    router.back()
  } catch {} finally { saving.value = false }
}
async function submit() {
  if (saving.value) return
  if (!form.supplierId || !form.warehouseId || form.lines.some((l: any) => !l.materialId || l.qty <= 0)) {
    ElMessage.warning('请完善供应商、仓库和明细')
    return
  }
  const zero = zeroPriceLine()
  if (zero) { ElMessage.warning(zero); return }
  saving.value = true
  try {
    if (!(await priceCheck())) return
    const res: any = await saveWithPriceNote(buildPayload(), p => poApi.create(p))
    const id = res?.data?.id || res?.id
    if (id) await poApi.submit(id)
    ElMessage.success('已提交')
    leave.allow()
    router.back()
  } catch {} finally { saving.value = false }
}

onMounted(loadWarehouses)
</script>

