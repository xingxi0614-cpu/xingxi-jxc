<template>
  <div class="mx-page">
    <header class="mx-top">
      <button class="mx-top__back" aria-label="返回" @click="onBack"><ArrowLeft :size="22" /></button>
      <span class="mx-top__title">材料出库</span>
    </header>

    <main class="mx-body mf-body">
      <DocGuardHint class="mx-guard" :active="true" :warehouse-ids="[picked?.warehouseId]" v-model:blocked="guardBlocked" />
      <!-- ── 第一步：挑一张已审批的出库申请 ── -->
      <template v-if="step === 0">
        <input v-model="keyword" class="mx-search" placeholder="搜单号 / 项目 / 申请人" />
        <div v-if="loading" class="mx-tip">加载中…</div>
        <div v-else-if="shownReqs.length === 0" class="mx-tip">
          <b>没有可出库的申请</b>
          只有「已审批」且还没发完料的申请才会出现在这里
        </div>
        <button v-for="r in shownReqs" :key="r.id" type="button" class="mx-card" @click="pickReq(r)">
          <div class="mx-card__top">
            <span class="mx-badge" :class="{ 'is-warn': r.status === 'PARTIAL_OUT' }">{{ r.status === 'PARTIAL_OUT' ? '部分已出' : '待出库' }}</span>
            <span class="mx-card__no">{{ r.docNo }}</span>
          </div>
          <div class="mx-card__title">{{ projectName(r.projectId) || '未填工程项目' }}</div>
          <div class="mx-card__meta">
            <span v-if="r.applyDate">{{ r.applyDate }}</span>
            <span v-if="r.materialApplicant">申请人 {{ r.materialApplicant }}</span>
            <span v-if="r.purpose">{{ r.purpose }}</span>
          </div>
        </button>
      </template>

      <!-- ── 第二步：填本次出库数量 ── -->
      <template v-else>
        <div class="mx-g">
          <div class="mx-g__label"><b>出库申请</b><button type="button" class="mx-g__more" @click="onBack">重选</button></div>
          <div class="mx-g__box">
            <div class="mx-row mx-row--app">
              <span class="mx-row__tt"><b>{{ projectName(picked?.projectId) || picked?.docNo }}</b><span>{{ picked?.docNo }}<template v-if="picked?.materialApplicant"> · 领料 {{ picked.materialApplicant }}</template></span></span>
            </div>
            <MobPicker v-model="outType" label="出库类型" required placeholder="决定成本进哪个科目"
              :options="outTypes.map(t => ({ value: t, label: t }))" />
          </div>
        </div>

        <div v-for="(l, i) in lines" :key="l.id" class="mx-g">
          <div class="mx-g__label"><b>出库明细 {{ i + 1 }}</b>可出 {{ l.remain }}</div>
          <div class="mx-g__box">
            <div class="mx-row mx-row--app"><span class="mx-row__tt mx-row__tt--wrap"><b>{{ materialLabel(l.materialId) }}</b></span></div>
            <label class="mx-row">
              <span class="mx-row__label">本次出库</span>
              <input v-model="l.qtyInput" class="mx-row__input" type="number" inputmode="decimal" min="0" :max="l.remain" />
            </label>
            <!-- 当前库存:这里只提醒;2026-10-20 起提交时后端按「账面 − 其他待审单占用」拦,审批时库存引擎再拦一次(见 SubmitStockGuard) -->
            <div class="mx-row">
              <span class="mx-row__label">当前库存</span>
              <span class="mx-row__r mo-stock">
                <template v-if="stockOf(l.materialId) === null">{{ stockLoading ? '查询中…' : '暂不可用' }}</template>
                <b v-else-if="stockOf(l.materialId)! <= 0" class="mo-zero">无库存</b>
                <b v-else>{{ $fmtQty(stockOf(l.materialId)) }}</b>
              </span>
            </div>
            <div v-if="over(l)" class="mx-note is-bad">超过可出数量（最多 {{ l.remain }}）</div>
            <div v-else-if="stockTip(l)" class="mx-note is-bad">{{ stockTip(l) }}</div>
          </div>
        </div>
      </template>
    </main>

    <div v-if="step === 1" class="mx-bar">
      <button class="mx-bar__btn mx-bar__btn--main" :disabled="guardBlocked || (!canSubmit || submitting)" @click="submit">
        {{ submitting ? '提交中…' : '生成出库单并提交审批' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import DocGuardHint from '@/components/DocGuardHint.vue'
import MobPicker from './parts/MobPicker.vue'
import { ArrowLeft } from '@/icons'
import { useLeaveGuard } from '@/composables/useLeaveGuard'
import { useWarehouseStock, sumQty, subQty, exceedsStock } from '@/composables/useWarehouseStock'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { masterApi, outApi } from '@/api'
import { fmtQty } from '@/utils/format'
import { useBizOptions } from '@/composables/useBizOptions'

const router = useRouter()
// 填了没提交就返回会先问；已结账时提交按钮变灰（提示在页面顶部）
const leave = useLeaveGuard()
const guardBlocked = ref(false)
const { outTypes, loadBizOptions } = useBizOptions()

const step = ref(0)
const loading = ref(false)
const submitting = ref(false)
const keyword = ref('')
const reqs = ref<any[]>([])
const picked = ref<any>(null)
const lines = ref<any[]>([])
const outType = ref('')
const materials = ref<Record<number, any>>({})
const projects = ref<Record<number, any>>({})

/** 可出库 = 已审批 + 部分已出（与电脑版同口径） */
async function loadReqs() {
  loading.value = true
  try {
    const [approved, partial] = await Promise.all([
      outApi.oreqPage({ current: 1, size: 100, status: 'APPROVED' }).catch(() => ({ records: [] })),
      outApi.oreqPage({ current: 1, size: 100, status: 'PARTIAL_OUT' }).catch(() => ({ records: [] }))
    ])
    reqs.value = [...((approved as any).records || []), ...((partial as any).records || [])]
  } finally { loading.value = false }
}

const shownReqs = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return reqs.value
  return reqs.value.filter((r: any) =>
    [r.docNo, r.materialApplicant, r.purpose, projectName(r.projectId)]
      .some(v => String(v || '').toLowerCase().includes(k)))
})

/**
 * 这一行还能出多少：申请量 − 已出/已锁定（与电脑版 lineRemain 同算法）。
 * 相减走 subQty:直接减会得到 0.19999999999999998,既显示成一长串,又让"填 0.2"被判超量、按钮置灰。
 */
function remainOf(line: any) {
  const used = Math.max(Number(line.lockedOutQty || 0), Number(line.outQty || 0))
  return Math.max(0, subQty(line.qty, used))
}

async function pickReq(r: any) {
  const detail: any = await outApi.oreqDetail(r.id)
  const avail = (detail.lines || [])
    .map((l: any) => ({ ...l, remain: remainOf(l) }))
    .filter((l: any) => l.remain > 0)
  if (!avail.length) { ElMessage.warning('这张申请已经发完料了，换一张'); return }
  picked.value = detail
  // 默认按剩余量全出，仓管少发时自己改小
  lines.value = avail.map((l: any) => ({ ...l, qtyInput: String(l.remain) }))
  // 只在申请上的出库类型确实是当前可选值时才预填。
  // 老单据可能留着已经被管理员改掉的旧类型，直接带出来会让仓管看着"已经选好了"，
  // 点提交却被后端顶回来（「出库类型不在可选范围内」），且下拉里根本找不到那一项。
  if (detail.outType && outTypes.value.includes(detail.outType)) outType.value = detail.outType
  void loadStock(detail.warehouseId)   // 不等它回来,先显示"查询中…"
  step.value = 1
}

function over(l: any) { return exceedsStock([l.qtyInput], Number(l.remain || 0)) }

const { stockLoading, loadStock, stockOf } = useWarehouseStock()
/** 本次出库数超过库存时的提醒文字(空串=不提醒;只提醒,不影响提交)。同一物料占多行时按合计比 */
function stockTip(l: any): string {
  const stock = stockOf(l.materialId)
  if (stock === null || !(Number(l.qtyInput) > 0)) return ''
  // 合计与比较都走 sumQty/exceedsStock:小数直接相加会把"刚好够"误报成不足
  const qtys = lines.value.filter(x => x.materialId === l.materialId).map(x => x.qtyInput)
  if (!exceedsStock(qtys, stock)) return ''
  return exceedsStock([l.qtyInput], stock) ? '库存不足，提交时会被拦' : `同一物料合计 ${fmtQty(sumQty(qtys))}，超过库存`
}

const canSubmit = computed(() =>
  !!outType.value && lines.value.some(l => Number(l.qtyInput) > 0) && !lines.value.some(over))

async function submit() {
  if (!picked.value || !canSubmit.value) return
  const payload = lines.value
    .filter(l => Number(l.qtyInput) > 0)
    .map(l => ({ reqLineId: l.id, qty: Number(l.qtyInput) }))
  submitting.value = true
  let out: any = null
  try {
    out = await outApi.outFromOreq({ oreqId: picked.value.id, lines: payload, outType: outType.value })
    // 手机端一步到位:生成即提交审批——仓管在库房不会再回电脑上点一次"提交"
    await outApi.outSubmit(out.id)
    ElMessage.success(`出库单 ${out.docNo} 已提交审批`)
    leave.allow()
    router.replace('/mobile')
  } catch (e: any) {
    // 出库单已生成、只是没提交上(如 2026-10-20 起提交时库存不足就拦):告诉他草稿单号并离开本页,
    // 留在这里再点一次会再生成一张草稿。原因写进弹框里——顶部红字会被弹框遮罩挡住看不清(本地实测截图)
    if (out?.id) {
      ElMessage.closeAll()
      await ElMessageBox.alert(`${e?.message ? e.message + '\n\n' : ''}出库单 ${out.docNo} 已保存为草稿，没有提交审批。库存补足后，在电脑端「材料出库」里找到这张草稿点「提交」。`,
        '没有提交', { confirmButtonText: '知道了', customStyle: { whiteSpace: 'pre-line' } }).catch(() => {})
      leave.allow()
      router.replace('/mobile')
    }
    // 失败提示由请求拦截器统一弹
  } finally { submitting.value = false }
}

function onBack() {
  if (step.value === 1) { step.value = 0; picked.value = null; lines.value = []; return }
  router.back()
}

function materialLabel(id: number) {
  const m = materials.value[id]
  if (!m) return `物料 #${id}`
  // 全站统一"编码 名称(规格)"
  return `${m.code || ''} ${m.name || ''}${m.spec ? `(${m.spec})` : ''}`.trim()
}
function projectName(id: number) { return projects.value[id]?.name || '' }

onMounted(async () => {
  loadBizOptions()
  const [mats, projs] = await Promise.allSettled([
    masterApi.allMaterials({ includeDisabled: true }),
    masterApi.projects(true)
  ])
  if (mats.status === 'fulfilled') materials.value = Object.fromEntries((mats.value as any[]).map((m: any) => [m.id, m]))
  if (projs.status === 'fulfilled') projects.value = Object.fromEntries((projs.value as any[]).map((p: any) => [p.id, p]))
  loadReqs()
})
</script>


<style scoped>
/* 库存数字靠右，与上一行"本次出库"的数字对齐 */
.mo-stock { margin-left: auto; }
.mo-stock b { font-size: 16px; font-weight: 500; }
.mo-stock b.mo-zero { color: var(--biz-danger); font-weight: 600; }
</style>
