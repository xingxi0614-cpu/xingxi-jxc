<template>
  <div class="mx-page">
    <header class="mx-top">
      <button class="mx-top__back" aria-label="返回" @click="$router.back()"><ArrowLeft :size="22" /></button>
      <span class="mx-top__title">采购入库</span>
    </header>

    <main class="mx-body mf-body">
      <DocGuardHint class="mx-guard" :active="true" :source-org-id="pos.find(p => Number(p.id) === Number(poId))?.orgId" v-model:blocked="guardBlocked" />
      <div class="mx-g">
        <div class="mx-g__label"><b>采购订单</b>只列出已审批、还没收完货的</div>
        <div class="mx-g__box">
          <MobPicker v-model="poId" label="采购订单" required placeholder="选择采购订单"
            :options="pos.map(p => ({ value: p.id, label: p.docNo, sub: p.supplierName || '' }))" @change="onPoChange" />
          <label class="mx-row"><span class="mx-row__label">备注</span><input v-model="remark" class="mx-row__input" placeholder="选填" /></label>
        </div>
      </div>

      <div class="mx-g">
        <div class="mx-g__label"><b>入库类型</b></div>
        <div class="mx-g__box">
          <button v-for="t in RECV_TYPES" :key="t.value" type="button" class="mx-row mx-row--app" @click="recvType = t.value">
            <span class="mx-row__tt"><b>{{ t.label }}</b><span>{{ t.desc }}</span></span>
            <Check v-if="recvType === t.value" class="mx-row__check" :size="18" />
          </button>
        </div>
      </div>

      <div v-if="!lines.length" class="mx-g">
        <div class="mx-g__label"><b>入库明细</b></div>
        <div class="mx-g__box"><div class="mx-tip">{{ poLoading ? '正在加载采购订单…' : '选了采购订单，这里会列出可以入库的物料' }}</div></div>
      </div>
      <div v-for="(line, i) in lines" :key="line.id" class="mx-g">
        <div class="mx-g__label"><b>入库明细 {{ i + 1 }}</b>可入 {{ fmt(remain(line)) }}</div>
        <div class="mx-g__box">
          <div class="mx-row mx-row--app">
            <span class="mx-row__tt mx-row__tt--wrap"><b>{{ matName(line.materialId) }}</b><span>订货 {{ fmt(line.qty) }} · 已入 {{ fmt(line.receivedQty) }}</span></span>
          </div>
          <div class="mx-row"><span class="mx-row__label">本次入库</span><MobNumber v-model="line.recvQty" stepper :max="remain(line)" /></div>
        </div>
      </div>
    </main>

    <div class="mx-bar">
      <button class="mx-bar__btn mx-bar__btn--main" :disabled="guardBlocked || (saving || !poId)" @click="submit">{{ saving ? '提交中...' : '生成并提交入库' }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import DocGuardHint from '@/components/DocGuardHint.vue'
import MobPicker from './parts/MobPicker.vue'
import MobNumber from './parts/MobNumber.vue'
import { ArrowLeft, CircleCheck as Check } from '@/icons'
import { useLeaveGuard } from '@/composables/useLeaveGuard'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { masterApi, poApi, recvApi } from '@/api'
import { showError } from '@/utils/notify'

const router = useRouter()
// 填了没提交就返回会先问；已结账时提交按钮变灰（提示在页面顶部）
const leave = useLeaveGuard('.mf-body', () => recvType.value)
const guardBlocked = ref(false)
const saving = ref(false)
const poLoading = ref(false)
const pos = ref<any[]>([])
const poId = ref<number | null>(null)
const recvType = ref('ESTIMATE')
const RECV_TYPES = [
  { value: 'ESTIMATE', label: '暂估入库', desc: '货到了，发票还没到' },
  { value: 'INVOICE', label: '发票入库', desc: '货和发票一起到' }
]
const remark = ref('')
const lines = ref<any[]>([])
const matMap = ref<Record<number, any>>({})

function fmt(v: any) { const n = Number(v || 0); return Number.isInteger(n) ? n : n.toFixed(2) }
function remain(line: any) { return Math.max(0, Number(line.qty || 0) - Number(line.receivedQty || 0)) }
function matName(id: number) { const m = matMap.value[id]; return m ? `${m.code || ''} ${m.name || ''}`.trim() : String(id) }

async function loadPos() {
  poLoading.value = true
  try {
    const [a, b]: any = await Promise.all([
      poApi.page({ status: 'APPROVED', size: 100 }),
      poApi.page({ status: 'PARTIAL_RECEIVED', size: 100 })
    ])
    pos.value = [...(a?.records || []), ...(b?.records || [])]
  } catch { pos.value = [] } finally { poLoading.value = false }
}

async function onPoChange(v: string | number | null) {
  const id = v === null ? null : Number(v)
  lines.value = []
  if (!id) return
  if (!Object.keys(matMap.value).length) {
    try { const ms = await masterApi.allMaterials({ includeDisabled: true }) as any[]; matMap.value = Object.fromEntries(ms.map(m => [m.id, m])) } catch {}
  }
  try {
    const po: any = await poApi.detail(id)
    lines.value = (po.lines || []).map((l: any) => ({ ...l, recvQty: Math.max(0, Number(l.qty || 0) - Number(l.receivedQty || 0)) }))
  } catch (e: any) { showError(e, '加载订单明细失败') }
}

async function submit() {
  if (!poId.value) { ElMessage.warning('请选择采购订单'); return }
  const payloadLines = lines.value.filter((l: any) => Number(l.recvQty) > 0).map((l: any) => ({ poLineId: l.id, qty: Number(l.recvQty) }))
  if (!payloadLines.length) { ElMessage.warning('请至少填写一行入库数量'); return }
  saving.value = true
  try {
    const recv: any = await recvApi.fromPo({ poId: poId.value, recvType: recvType.value, lines: payloadLines, remark: remark.value })
    const id = recv?.id || recv?.data?.id
    if (id) await recvApi.submit(id)
    ElMessage.success('入库单已生成并提交')
    leave.allow()
    router.back()
  } catch { /* 拦截器统一提示 */ } finally { saving.value = false }
}

onMounted(loadPos)
</script>

