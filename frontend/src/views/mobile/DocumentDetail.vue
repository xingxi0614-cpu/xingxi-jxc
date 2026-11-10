<template>
  <div class="mdoc">
    <header class="mdoc-head">
      <button class="mdoc-back" @click="goBack">‹ 返回</button>
      <span class="mdoc-title">{{ title }}</span>
      <span class="mdoc-spacer"></span>
    </header>

    <main class="mdoc-body">
      <div v-if="loading" class="mdoc-empty">加载中...</div>
      <div v-else-if="error" class="mdoc-empty error">{{ error }}</div>
      <template v-else-if="doc">
        <section class="mdoc-card">
          <div class="mdoc-card-head">
            <div>
              <div class="mdoc-docno">{{ doc.docNo || doc.id }}</div>
              <div class="mdoc-type">{{ title }}</div>
            </div>
            <el-tag size="small" :type="statusType(doc.status)">{{ statusText(doc.status) }}</el-tag>
          </div>
          <div class="mdoc-fields">
            <div v-for="field in fields" :key="field.label" class="mdoc-field" :class="{ wide: field.wide }">
              <span>{{ field.label }}</span>
              <strong>{{ field.value }}</strong>
            </div>
          </div>
        </section>

        <section class="mdoc-card">
          <div class="mdoc-section-title">明细</div>
          <div v-if="!lines.length" class="mdoc-line-empty">暂无明细</div>
          <div v-for="(line, index) in lines" :key="line.id || index" class="mdoc-line">
            <div class="mdoc-line-top">
              <span>#{{ line.lineNo || index + 1 }}</span>
              <strong>{{ materialName(line.materialId) }}</strong>
            </div>
            <div class="mdoc-line-grid">
              <div v-for="col in lineColumns" :key="col.label">
                <span>{{ col.label }}</span>
                <strong>{{ col.value(line, index) }}</strong>
              </div>
            </div>
          </div>
        </section>
        <PickupPanel v-if="docType === 'OREQ' && ['APPROVED','PARTIAL_OUT','CLOSED'].includes(doc.status)" :request-id="doc.id" :source-warehouse="doc.warehouseId" @changed="loadDoc" />
        <PickupPanel v-if="docType === 'TRANSFER' && doc.sourceReqId" :request-id="doc.sourceReqId" :source-warehouse="doc.fromWarehouseId" @changed="loadDoc" />
      </template>
    </main>

    <!-- 操作栏：审批（通过/退回） · 撤回 · 取消审批 · 删除（草稿/已驳回） -->
    <footer v-if="canApproveDoc || canWithdraw || canCancelApproval || canDelete" class="mdoc-footer">
      <!-- 轮到我审时，审批是这一屏的主操作，排在最前 -->
      <template v-if="canApproveDoc">
        <button class="mdoc-act reject" @click="doReject">退回</button>
        <button class="mdoc-act approve" @click="doApprove">通过</button>
      </template>
      <button v-if="canWithdraw" class="mdoc-act warn" @click="doWithdraw">撤回（取消提交）</button>
      <button v-if="canCancelApproval" class="mdoc-act danger" @click="doCancelApproval">取消审批</button>
      <button v-if="canDelete" class="mdoc-act danger" @click="doDelete">删除</button>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import PickupPanel from '@/components/PickupPanel.vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inventoryApi, invoiceApi, masterApi, outApi, poApi, recvApi, wfApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { DOC_TYPE_LABELS, normalizeDocQrType, type DocQrType } from '@/utils/docQr'
import { fmtMoney, fmtPrice } from '@/utils/format'
import { grossOutQty, netOutQty } from '@/utils/outboundProgress'

const user = useUserStore()

type Field = { label: string; value: string; wide?: boolean }
type LineColumn = { label: string; value: (line: any, index: number) => string }

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const doc = ref<any>(null)
const suppliers = ref<Record<number, any>>({})
const warehouses = ref<Record<number, any>>({})
const materials = ref<Record<number, any>>({})
const projects = ref<Record<number, any>>({})

const docType = computed(() => normalizeDocQrType(String(route.params.type || '')))
const title = computed(() => docType.value ? DOC_TYPE_LABELS[docType.value] : '单据详情')
const lines = computed(() => doc.value?.lines || [])

const fields = computed<Field[]>(() => {
  if (!doc.value || !docType.value) return []
  const d = doc.value
  const common = (items: Field[]) => items.filter(item => item.value !== '')
  switch (docType.value) {
    case 'PO':
      return common([
        field('供应商', supplierName(d.supplierId)),
        field('仓库', warehouseName(d.warehouseId)),
        field('下单日期', d.orderDate),
        field('交货日期', d.deliveryDate),
        field('数量', d.totalQty),
        moneyField('金额', d.totalAmt),
        field('备注', d.remark, true)
      ])
    case 'RECV':
      return common([
        field('供应商', supplierName(d.supplierId)),
        field('仓库', warehouseName(d.warehouseId)),
        field('关联订单', d.sourcePoNo),
        field('入库类型', d.recvType === 'ESTIMATE' ? '暂估入库' : '发票入库'),
        field('入库日期', d.recvDate),
        field('关联发票', d.invoiceNo),
        field('数量', d.totalQty),
        moneyField('金额', d.totalAmt),
        field('备注', d.remark, true)
      ])
    case 'INV':
      return common([
        field('供应商发票号', d.invoiceNo),
        field('供应商', supplierName(d.supplierId)),
        field('开票日期', d.invoiceDate),
        moneyField('净额', d.netAmt),
        moneyField('税额', d.taxAmt),
        moneyField('价税合计', d.totalAmt),
        moneyField('库存差额调整', d.adjustAmt),
        field('备注', d.remark, true)
      ])
    case 'OREQ':
      return common([
        field('申请日期', d.applyDate),
        field('仓库', warehouseName(d.warehouseId)),
        field('工程项目', d.projectName || projectName(d.projectId), true),
        field('施工单位', d.constructionUnit, true),
        field('出库类型', d.outType),
        field('业务分类', d.businessCategory),
        field('材料申请人', d.materialApplicant),
        field('用途', d.purpose),
        field('特殊事项审批单据号', d.approvalDocNo),
        field('申请数量', d.totalQty),
        field('累计出库', grossOutQty(d)),
        field('已退料', d.returnQty || 0),
        field('净出库', netOutQty(d)),
        field('备注', d.remark, true)
      ])
    case 'OREQ_REV':
      return common([
        field('来源申请', d.sourceReqNo),
        field('修订日期', d.revisionDate),
        field('仓库', warehouseName(d.warehouseId)),
        field('工程项目', d.projectName || projectName(d.projectId), true),
        field('修订数量', d.totalDeltaQty),
        field('原因', d.reason, true),
        field('备注', d.remark, true)
      ])
    case 'OUT':
      return common([
        field('来源申请', d.sourceReqNo),
        field('出库日期', d.outDate),
        field('工程项目', d.projectName || projectName(d.projectId), true),
        field('施工单位', d.constructionUnit, true),
        field('出库类型', d.outType),
        field('业务分类', d.businessCategory),
        field('领料人', d.materialReceiver),
        field('数量', d.totalQty),
        moneyField('金额', d.totalAmt),
        // 用途/备注来自来源出库申请(后端读出),出库单自己的备注一直为空
        field('用途', d.purpose),
        field('备注', d.remark || d.reqRemark, true)
      ])
    case 'RETURN':
      return common([
        field('来源出库', d.sourceOutNo),
        field('来源申请', d.sourceReqNo),
        field('退料日期', d.returnDate),
        field('仓库', warehouseName(d.warehouseId)),
        field('工程项目', d.projectName || projectName(d.projectId), true),
        field('退料人', d.returner),
        field('数量', d.totalQty),
        moneyField('金额', d.totalAmt),
        field('备注', d.remark, true)
      ])
    case 'LOAN':
      return common([
        field('借料日期', d.borrowDate),
        field('仓库', warehouseName(d.warehouseId)),
        field('工程项目', d.projectName || projectName(d.projectId), true),
        field('预计归还', d.expectedReturnDate),
        field('借料人', d.borrower),
        field('借料部门', d.borrowerDept),
        field('用途', d.purpose),
        field('借出数量', d.totalQty),
        field('已归还', d.returnedQty),
        field('备注', d.remark, true)
      ])
    case 'TRANSFER':
      return common([
        field('转库日期', d.transferDate),
        field('转出仓库', warehouseName(d.fromWarehouseId)),
        field('转入仓库', warehouseName(d.toWarehouseId)),
        field('经办人', d.transferer),
        field('数量', d.totalQty),
        moneyField('金额', d.totalAmt),
        field('备注', d.remark, true)
      ])
  }
})

const lineColumns = computed<LineColumn[]>(() => {
  const cols: LineColumn[] = [
    { label: '规格型号', value: line => materials.value[line.materialId]?.spec || '-' },
    { label: '单位', value: line => materials.value[line.materialId]?.unit || '-' },
    { label: '数量', value: line => valueText(line.qty) }
  ]
  if (hasLineValue('oldQty')) cols.push({ label: '原数量', value: line => valueText(line.oldQty) })
  if (hasLineValue('newQty')) cols.push({ label: '新数量', value: line => valueText(line.newQty) })
  if (hasLineValue('deltaQty')) cols.push({ label: '变更量', value: line => valueText(line.deltaQty) })
  if (hasLineValue('receivedQty')) cols.push({ label: '已入库', value: line => valueText(line.receivedQty) })
  if (docType.value === 'OREQ') {
    cols.push({ label: '累计出库', value: line => valueText(grossOutQty(line)) })
    cols.push({ label: '已退料', value: line => valueText(line.returnQty || 0) })
    cols.push({ label: '净出库', value: line => valueText(netOutQty(line)) })
  } else if (hasLineValue('outQty')) cols.push({ label: '已出库', value: line => valueText(line.outQty) })
  if (hasLineValue('returnedQty')) cols.push({ label: '已归还', value: line => valueText(line.returnedQty) })
  if (hasLineValue('unitPrice')) cols.push({ label: '单价', value: line => fmtPrice(line.unitPrice) })
  if (hasLineValue('amount')) cols.push({ label: '金额', value: line => fmtMoney(line.amount) })
  // 采购单价防错：制单人确认过的价格偏离，审批人在手机上也要看得到
  if (hasLineValue('priceCheckNote')) cols.push({ label: '⚠ 价格偏离', value: line => line.priceCheckNote ? `历史价约 ${fmtMoney(line.priceCheckRef)}，理由：${line.priceCheckNote}` : '正常' })
  if (hasLineValue('remark') || hasLineValue('reqRemark')) cols.push({ label: '备注', value: line => valueText(line.remark || line.reqRemark) })
  return cols
})

function field(label: string, value: unknown, wide = false): Field {
  return { label, value: valueText(value), wide }
}

function moneyField(label: string, value: unknown): Field {
  return value == null || value === '' ? { label, value: '' } : { label, value: fmtMoney(value as string | number) }
}

function valueText(value: unknown) {
  return value == null || value === '' ? '-' : String(value)
}

function hasLineValue(key: string) {
  return lines.value.some((line: any) => line[key] !== null && line[key] !== undefined && line[key] !== '')
}

function supplierName(id: number) {
  return suppliers.value[id]?.name || valueText(id)
}

function warehouseName(id: number) {
  return warehouses.value[id]?.name || valueText(id)
}

function projectName(id: number) {
  return projects.value[id]?.name || valueText(id)
}

function materialName(id: number) {
  const mat = materials.value[id]
  return mat ? `${mat.code || ''} ${mat.name || ''}`.trim() : valueText(id)
}

function statusText(status: string) {
  return ({ READY: '待实际交接', CLOSED: '已结束领料', DRAFT: '草稿', PENDING: '待审批', APPROVED: '已审批', REJECTED: '已驳回' } as Record<string, string>)[status] || status || '-'
}

function statusType(status: string): any {
  return ({ DRAFT: 'info', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' } as Record<string, string>)[status] || 'info'
}

function goBack() {
  if (history.length > 1) router.back()
  else router.replace('/mobile')
}

async function loadMasters() {
  const [supplierRes, warehouseRes, materialRes, projectRes] = await Promise.allSettled([
    masterApi.allSuppliers({ includeDisabled: true }),
    masterApi.warehouses(true),
    masterApi.allMaterials({ includeDisabled: true }),
    masterApi.projects(true)
  ])
  if (supplierRes.status === 'fulfilled') suppliers.value = Object.fromEntries((supplierRes.value as any[]).map((s: any) => [s.id, s]))
  if (warehouseRes.status === 'fulfilled') warehouses.value = Object.fromEntries((warehouseRes.value as any[]).map((w: any) => [w.id, w]))
  if (materialRes.status === 'fulfilled') materials.value = Object.fromEntries((materialRes.value as any[]).map((m: any) => [m.id, m]))
  if (projectRes.status === 'fulfilled') projects.value = Object.fromEntries((projectRes.value as any[]).map((p: any) => [p.id, p]))
}

async function loadDoc() {
  const type = docType.value
  const rawId = route.params.id
  const id = Number(Array.isArray(rawId) ? rawId[0] : rawId)
  if (!type || !Number.isFinite(id)) {
    error.value = '单据链接无效'
    doc.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (!Object.keys(materials.value).length) await loadMasters()
    doc.value = await detailApi(type, id)
  } catch (e: any) {
    doc.value = null
    error.value = e?.message || '无法打开该单据'
  } finally {
    loading.value = false
  }
}

function detailApi(type: DocQrType, id: number) {
  const apis: Record<DocQrType, (id: number) => Promise<any>> = {
    PO: poApi.detail,
    RECV: recvApi.detail,
    INV: invoiceApi.detail,
    OREQ: outApi.oreqDetail,
    OREQ_REV: outApi.oreqRevisionDetail,
    OUT: outApi.outDetail,
    RETURN: outApi.returnDetail,
    LOAN: outApi.loanDetail,
    TRANSFER: inventoryApi.transferDetail
  }
  return apis[type](id)
}

// ===== 在详情页直接审批 =====
// 2026-09-15 加：原先手机上只能从「待我审」卡片弹窗审批，
// 从「单据查询」点进来的详情页没有任何审批入口 —— 用户反馈"只能查不能办"。
const approveActions: Partial<Record<DocQrType, { approve: (id: number, c: string) => Promise<any>; reject: (id: number, c: string) => Promise<any> }>> = {
  PO: { approve: poApi.approve, reject: poApi.reject },
  RECV: { approve: recvApi.approve, reject: recvApi.reject },
  INV: { approve: invoiceApi.approve, reject: invoiceApi.reject },
  OREQ: { approve: outApi.oreqApprove, reject: outApi.oreqReject },
  OUT: { approve: outApi.outApprove, reject: outApi.outReject },
  RETURN: { approve: outApi.returnApprove, reject: outApi.returnReject },
  LOAN: { approve: outApi.loanApprove, reject: outApi.loanReject },
  TRANSFER: { approve: inventoryApi.transferApprove, reject: inventoryApi.transferReject }
}
/** 能不能审：以后端回填的 canApprove 为准（同一把尺子，不在前端另猜一套角色规则） */
const canApproveDoc = computed(() =>
  !!doc.value && doc.value.status === 'PENDING' && doc.value.canApprove === true
  && !!approveActions[docType.value as DocQrType])

async function doApprove() {
  const a = approveActions[docType.value as DocQrType]
  if (!a || !doc.value) return
  let comment = '同意'
  try {
    const r = await ElMessageBox.prompt('审批意见（可留空）', `通过 ${doc.value.docNo || ''}`, {
      confirmButtonText: '确认通过', cancelButtonText: '取消', inputPlaceholder: '同意', inputValue: ''
    })
    comment = (r.value || '').trim() || '同意'
  } catch { return }
  try {
    await a.approve(doc.value.id, comment)
    ElMessage.success('已通过')
    loadDoc()
  } catch { /* 失败提示由拦截器统一弹 */ }
}

async function doReject() {
  const a = approveActions[docType.value as DocQrType]
  if (!a || !doc.value) return
  let comment = ''
  try {
    // 退回必须说明理由：不写原因的退回，提单人只能猜，来回折腾
    const r = await ElMessageBox.prompt('退回原因（必填）', `退回 ${doc.value.docNo || ''}`, {
      confirmButtonText: '确认退回', cancelButtonText: '取消', inputPlaceholder: '请说明哪里需要改',
      inputValidator: (v: string) => (v && v.trim().length >= 2) || '请写清楚退回原因'
    })
    comment = (r.value || '').trim()
  } catch { return }
  try {
    await a.reject(doc.value.id, comment)
    ElMessage.success('已退回')
    loadDoc()
  } catch { /* 失败提示由拦截器统一弹 */ }
}

// ===== P0-3 撤回 / 取消审批 =====
const withdrawApis: Partial<Record<DocQrType, (id: number) => Promise<any>>> = {
  PO: poApi.withdraw, RECV: recvApi.withdraw, INV: invoiceApi.withdraw,
  OREQ: outApi.oreqWithdraw, OUT: outApi.outWithdraw, RETURN: outApi.returnWithdraw,
  LOAN: outApi.loanWithdraw, TRANSFER: inventoryApi.transferWithdraw
}
// 撤回(取消提交)：待审批中 + 我是发起人；取消审批本次不接（OREQ_REV 无取消提交）
const canWithdraw = computed(() =>
  !!doc.value && doc.value.status === 'PENDING' && docType.value !== 'OREQ_REV'
  && !!withdrawApis[docType.value as DocQrType] && user.ownsDoc(doc.value))
// 取消审批：已审批 + 我是发起人或财务/管理员；排除无通用取消审批路径的单据——
// OREQ_REV 无取消提交；调拨类出库申请走领料「剩余不领」；关联领料生成的转库单（sourceReqId 非空）
// 状态为 READY/走「取消本次领料」，普通转库单（无 sourceReqId）才有通用取消审批
const canCancelApproval = computed(() =>
  !!doc.value && doc.value.status === 'APPROVED' && docType.value !== 'OREQ_REV' && doc.value.fulfillmentMode !== 'TRANSFER' && !doc.value.sourceReqId
  && (user.ownsDoc(doc.value) || user.hasFinanceAccess() || user.roles.includes('admin')))

async function doWithdraw() {
  const t = docType.value as DocQrType
  const api = withdrawApis[t]
  if (!api || !doc.value) return
  try {
    await ElMessageBox.confirm(`确定撤回 ${doc.value.docNo}？撤回后退回草稿，可修改后重新提交。`, '取消提交', { confirmButtonText: '撤回', cancelButtonText: '再想想', type: 'warning' })
  } catch { return }
  try { await api(doc.value.id); ElMessage.success('已撤回，退回草稿'); loadDoc() }
  catch { /* 拦截提示已由响应拦截器给出 */ }
}
async function doCancelApproval() {
  const t = docType.value as DocQrType
  if (!doc.value) return
  let reason = ''
  try {
    const r = await ElMessageBox.prompt('取消审批将退回草稿并回退库存/成本，请填写原因：', '取消审批', {
      confirmButtonText: '提交', cancelButtonText: '再想想', inputPlaceholder: '取消原因（必填）',
      inputValidator: (v) => (v && v.trim() ? true : '请填写取消原因')
    })
    reason = (r.value || '').trim()
  } catch { return }
  try {
    const res: any = await wfApi.cancelRequest({ bizType: t, bizId: doc.value.id, bizNo: doc.value.docNo, reason })
    // 与桌面端一致：发起人就是原审批人时后端一步到位，直接退回草稿
    if (res && (res.status === 'APPROVED' || res.status === 'CANCELLED')) {
      ElMessage.success('已取消审批，单据退回草稿，库存/成本已自动回退')
    } else {
      ElMessage.success('取消审批已提交，等待原审批人处理')
    }
    loadDoc()
  } catch { /* 拦截提示已给出 */ }
}

// ===== 删除（草稿/已驳回）：与桌面端列表行同口径，删完返回上一页 =====
const deleteApis: Partial<Record<DocQrType, (id: number) => Promise<any>>> = {
  PO: poApi.delete, RECV: recvApi.delete, INV: invoiceApi.delete,
  OREQ: outApi.oreqDelete, OUT: outApi.outDelete, RETURN: outApi.returnDelete,
  LOAN: outApi.loanDelete, TRANSFER: inventoryApi.transferDelete
}
// 采购订单要采购员角色；借料单有归还记录不删（已登记删除会冲回库存）；其余按「谁提交谁删除」
const canDelete = computed(() => {
  if (!doc.value || !docType.value || docType.value === 'OREQ_REV') return false
  if (!deleteApis[docType.value as DocQrType]) return false
  if (docType.value === 'PO') return ['DRAFT', 'REJECTED'].includes(doc.value.status) && user.hasRole('purchaser')
  if (docType.value === 'LOAN') {
    if (Number(doc.value.returnedQty || 0) > 0) return false
    return ['DRAFT', 'REJECTED', 'REGISTERED'].includes(doc.value.status) && user.ownsDoc(doc.value)
  }
  return user.canDeleteDoc(doc.value)
})

async function doDelete() {
  const t = docType.value as DocQrType
  const api = deleteApis[t]
  if (!api || !doc.value) return
  try {
    await ElMessageBox.confirm(`确定删除 ${title.value} ${doc.value.docNo}？删除后不可恢复。`, '删除确认', { confirmButtonText: '删除', cancelButtonText: '再想想', type: 'warning' })
  } catch { return }
  try {
    await api(doc.value.id)
    ElMessage.success('已删除')
    // 从列表/待办进来就返回上一页；扫二维码/外链直达的没有上一页，回手机端首页
    if (window.history.state?.back) router.back()
    else router.replace('/mobile')
  } catch { /* 拦截提示已由响应拦截器给出 */ }
}

onMounted(loadDoc)
watch(() => [route.params.type, route.params.id], loadDoc)
</script>

<style scoped lang="scss">
.mdoc {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--mob-bg);
  padding-bottom: calc(72px + env(safe-area-inset-bottom));
}

.mdoc-footer {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  display: flex;
  gap: 10px;
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid var(--mob-line);
  z-index: 20;
}
.mdoc-act {
  flex: 1;
  height: 44px;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  /* 按压反馈统一走 mobile.scss 全局规则 */
  /* 审批是主操作：通过用实心，退回用描边，避免误触 */
  &.approve { background: var(--mob-brand); color: #fff; }
  &.reject { background: #fff; color: var(--biz-danger); box-shadow: inset 0 0 0 1px rgba(192, 86, 79, .35); }
  &.warn { background: rgba(217, 160, 46, .15); color: #a8791f; }
  &.danger { background: rgba(192, 86, 79, .12); color: var(--biz-danger); }
}

.mdoc-head {
  height: 48px;
  padding: 0 12px;
  display: grid;
  grid-template-columns: 72px 1fr 72px;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid var(--mob-line);
  flex-shrink: 0;
}

.mdoc-back {
  border: 0;
  background: none;
  color: var(--mob-brand);
  font-size: 16px;
  text-align: left;
}

.mdoc-title {
  text-align: center;
  font-weight: 700;
  color: var(--mob-ink);
}

.mdoc-body {
  flex: 1;
  padding: 12px;
  overflow-y: auto;
}

.mdoc-card {
  background: #fff;
  border: 1px solid var(--mob-line);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  box-shadow: 0 6px 18px rgba(44, 36, 22, .05);
}

.mdoc-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}

.mdoc-docno {
  font-size: 17px;
  line-height: 1.35;
  font-weight: 800;
  color: #111827;
  overflow-wrap: anywhere;
}

.mdoc-type {
  margin-top: 2px;
  color: #9b9288;
  font-size: 12px;
}

.mdoc-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.mdoc-field,
.mdoc-line-grid > div {
  min-width: 0;
}

.mdoc-field.wide {
  grid-column: 1 / -1;
}

.mdoc-field span,
.mdoc-line-grid span {
  display: block;
  color: #9b9288;
  font-size: 12px;
  line-height: 1.4;
}

.mdoc-field strong,
.mdoc-line-grid strong {
  display: block;
  margin-top: 2px;
  color: var(--mob-ink);
  font-size: 14px;
  line-height: 1.45;
  font-weight: 650;
  overflow-wrap: anywhere;
}

.mdoc-section-title {
  font-size: 15px;
  font-weight: 800;
  color: var(--mob-ink);
  margin-bottom: 10px;
}

.mdoc-line {
  border: 1px solid #f0ece5;
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 10px;
}

.mdoc-line:last-child {
  margin-bottom: 0;
}

.mdoc-line-top {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.mdoc-line-top span {
  flex: 0 0 auto;
  color: #9b9288;
  font-size: 12px;
}

.mdoc-line-top strong {
  min-width: 0;
  color: #111827;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.mdoc-line-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 10px;
}

.mdoc-empty,
.mdoc-line-empty {
  padding: 42px 12px;
  text-align: center;
  color: #9b9288;
}

.mdoc-empty.error {
  color: #c4554a;
}

@media (min-width: 720px) {
  .mdoc-body {
    max-width: 720px;
    width: 100%;
    margin: 0 auto;
  }
}
</style>
