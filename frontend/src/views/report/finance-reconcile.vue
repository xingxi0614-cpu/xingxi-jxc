<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">财务核对台账</div>
        <div class="page-sub">发票口径：按供应商汇总应付发生额的不含税(原材料)/进项税/含税(应付)，含①已核销发票 ②发票入库(货票同到)两类，供你与财务软件手工核对。另附「暂估未开票」与「期末原材料库存」两个对照数。</div>
      </div>
      <el-space>
        <el-button @click="openPayList">付款记录</el-button>
        <el-button :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
        <el-button type="primary" @click="openPayDialog">+ 登记付款</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" size="default" style="margin-bottom:8px">
        <el-form-item v-if="companies.length > 1" label="公司">
          <el-select v-model="orgId" placeholder="全部（汇总）" clearable filterable style="width:200px" @change="load">
            <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票期间">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:260px" :shortcuts="dateShortcuts" @change="load" />
        </el-form-item>
        <el-form-item label="供应商">
          <SupplierLookup v-model="supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="load">查询</el-button></el-form-item>
      </el-form>

      <!-- 汇总卡：上排发票口径（记账用），下排付款与对照数（2026-10-17 界面改版，统一卡片样式） -->
      <KpiCards :items="kpiInvoice" />
      <KpiCards :items="kpiPay" />

      <div class="tbl-title">按供应商明细（发票口径）</div>
      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="所选期间没有已审核发票（发票在系统里录入/核销后才会计入）" show-summary :summary-method="summaryRow">
        <el-table-column label="供应商" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplier_name || '（未知供应商）' }}</template>
        </el-table-column>
        <el-table-column label="发票数" prop="inv_cnt" width="100" align="right" />
        <el-table-column label="不含税（原材料）" width="140" align="right"><template #default="{ row }">{{ fmtMoney(row.net_amt) }}</template></el-table-column>
        <el-table-column label="进项税额" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.tax_amt) }}</template></el-table-column>
        <el-table-column label="含税（应付）" width="130" align="right"><template #default="{ row }">{{ fmtMoney(row.total_amt) }}</template></el-table-column>
        <el-table-column label="已付款（期间）" width="130" align="right"><template #default="{ row }">{{ fmtMoney(row.pay_amt) }}</template></el-table-column>
        <el-table-column label="应付余额（累计）" width="140" align="right">
          <template #default="{ row }"><span :class="{ 'bal-pos': Number(row.balance_amt) > 0 }">{{ fmtMoney(row.balance_amt) }}</span></template>
        </el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" show-icon class="mt-3"
        title="口径说明：原材料=不含税；进项税=税额；应付账款-物资款=含税应付发生额，含①暂估后已核销的发票 ②发票入库(货票同到)。已付款来自「登记付款」(含税口径)；应付余额=累计应付发生额-累计付款，是截至今天的欠款快照、不随期间筛选变。暂估未开票=已入库尚未收到发票的部分。跟财务软件核对时以发生额对科目、以应付余额对供应商往来。" />
    </el-card>

    <!-- 登记付款 -->
    <el-dialog v-model="payDialogVisible" :before-close="payDialogGuard.beforeClose" title="登记付款（应付-物资款核销）" width="min(480px, 94vw)" align-center>
      <el-form label-position="top" :model="payForm" label-width="92px">
        <el-form-item v-if="companies.length > 1" label="付款公司" required>
          <el-select v-model="payForm.orgId" placeholder="选择公司" filterable style="width:100%">
            <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" required>
          <SupplierLookup v-model="payForm.supplierId" placeholder="选择收款供应商" width="100%" />
        </el-form-item>
        <el-form-item label="付款日期" required>
          <el-date-picker v-model="payForm.payDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="金额(含税)" required>
          <el-input-number v-model="payForm.amount" :min="0.01" :precision="2" :controls="false" style="width:100%" placeholder="对应应付-物资款的含税金额" />
          <!-- 2026-10-01 付款防错：把这家供应商现欠多少摆在旁边，多打一个零/付错供应商当场能看出来 -->
          <div v-if="payBalance !== null" class="pay-hint" :class="{ over: payOver > 0 }">
            该供应商当前应付余额 {{ fmtMoney(payBalance) }} 元<template v-if="payOver > 0">；本次付款超出 {{ fmtMoney(payOver) }} 元，请核对金额和供应商</template>
          </div>
        </el-form-item>
        <el-form-item label="付款方式">
          <el-select v-model="payForm.method" placeholder="可选" clearable style="width:100%">
            <el-option v-for="m in ['转账', '银行承兑', '商业承兑', '现金', '其他']" :key="m" :value="m" :label="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证号">
          <el-input v-model="payForm.refNo" maxlength="60" placeholder="银行回单号/记账凭证号，可选" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payForm.remark" maxlength="255" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payDialogGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="paySaving" @click="savePayment">保存</el-button>
      </template>
    </el-dialog>

    <!-- 付款记录 -->
    <el-dialog v-model="payListVisible" title="付款记录（跟随上方公司/期间/供应商筛选）" width="min(860px, 96vw)" align-center>
      <el-table class="readable-table" border :data="payRows" v-loading="payListLoading" max-height="480" empty-text="所选范围内没有付款记录" show-summary :summary-method="sumPayRows">
        <el-table-column label="付款日期" prop="payDate" width="110" />
        <el-table-column label="供应商" prop="supplierName" min-width="180" show-overflow-tooltip />
        <el-table-column label="公司" prop="orgName" width="150" show-overflow-tooltip />
        <el-table-column label="金额(含税)" width="130" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" prop="method" width="90" />
        <el-table-column label="凭证号" prop="refNo" width="130" show-overflow-tooltip />
        <el-table-column label="登记人" prop="createdByName" width="90" />
        <el-table-column label="备注" prop="remark" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="removePayment(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { paymentApi, reportApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import SupplierLookup from '@/components/SupplierLookup.vue'
import { showError } from '@/utils/notify'
import { makeLineSummary } from '@/utils/tableSummary'

const loading = ref(false)
const rows = ref<any[]>([])
const summary = reactive<any>({ materialAmt: 0, inputTax: 0, payableAmt: 0, estimateUninvoiced: 0, inventoryValue: 0, invoiceCount: 0 })
const supplierId = ref<number | null>(null)
const dateRange = ref<any>(null)
const companies = ref<any[]>([])
const orgId = ref<number | null>(null)

function fmt(d: Date) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
function firstOfYear() { const d = new Date(); return `${d.getFullYear()}-01-01` }
function today() { return fmt(new Date()) }

// 期间快捷选项：默认「本年至今」，财务软件 按月对账时可一键切到本月/上月/本季度
const dateShortcuts = [
  { text: '本月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth(), 1), d] } },
  { text: '上月', value: () => { const d = new Date(); const s = new Date(d.getFullYear(), d.getMonth() - 1, 1); const e = new Date(d.getFullYear(), d.getMonth(), 0); return [s, e] } },
  { text: '本季度', value: () => { const d = new Date(); const q = Math.floor(d.getMonth() / 3) * 3; return [new Date(d.getFullYear(), q, 1), d] } },
  { text: '本年至今', value: () => { const d = new Date(); return [new Date(d.getFullYear(), 0, 1), d] } }
]

async function load() {
  loading.value = true
  try {
    const r: any = await reportApi.financeReconcile({
      orgId: orgId.value,
      supplierId: supplierId.value,
      startDate: dateRange.value?.[0] || null,
      endDate: dateRange.value?.[1] || null
    })
    rows.value = r.bySupplier || []
    Object.assign(summary, r.summary || {})
    companies.value = r.companies || []
  } finally { loading.value = false }
}

// 按列名合计：表格会自动插「序号」列、固定列排最前，按列序号写会错位（2026-10-01 修）
const summaryRow = makeLineSummary({
  发票数: { field: 'inv_cnt', type: 'count' },
  '不含税（原材料）': { field: 'net_amt', type: 'money' },
  进项税额: { field: 'tax_amt', type: 'money' },
  '含税（应付）': { field: 'total_amt', type: 'money' },
  '已付款（期间）': { field: 'pay_amt', type: 'money' },
  '应付余额（累计）': { field: 'balance_amt', type: 'money' }
})

const exportColumns: ExportColumn[] = [
  { label: '供应商', value: (r: any) => r.supplier_name || '' },
  { label: '发票数', key: 'inv_cnt' },
  { label: '不含税(原材料)', money: true, value: (r: any) => fmtMoney(r.net_amt) },
  { label: '进项税额', money: true, value: (r: any) => fmtMoney(r.tax_amt) },
  { label: '含税(应付)', money: true, value: (r: any) => fmtMoney(r.total_amt) },
  { label: '已付款(期间)', money: true, value: (r: any) => fmtMoney(r.pay_amt) },
  { label: '应付余额(累计)', money: true, value: (r: any) => fmtMoney(r.balance_amt) }
]
function exportExcel() { exportRowsToExcel('财务核对台账', exportColumns, rows.value, '财务核对台账') }

// ── 付款登记 ──
const payDialogVisible = ref(false)
const payDialogGuard = useUnsavedGuard(payDialogVisible)
const paySaving = ref(false)
const payForm = reactive<any>({ orgId: null, supplierId: null, payDate: today(), amount: null, method: '转账', refNo: '', remark: '' })
function openPayDialog() {
  Object.assign(payForm, { orgId: orgId.value || companies.value[0]?.id || null, supplierId: supplierId.value, payDate: today(), amount: null, method: '转账', refNo: '', remark: '' })
  payDialogVisible.value = true
}
/**
 * 这家供应商的累计应付余额（取自当前台账，口径同「应付余额（累计）」列）。
 * 只有台账的公司范围与付款公司一致时才给（多公司合看时余额是几家加总，拿来比会误导）；拿不到就不提示。
 */
const payBalance = computed<number | null>(() => {
  if (!payForm.supplierId) return null
  if (companies.value.length > 1 && !(orgId.value && payForm.orgId === orgId.value)) return null
  const r = rows.value.find((x: any) => Number(x.supplier_id) === Number(payForm.supplierId))
  return r && r.balance_amt != null ? Number(r.balance_amt) : null
})
const payOver = computed(() => payBalance.value === null ? 0 : Math.round((Number(payForm.amount || 0) - payBalance.value) * 100) / 100)
async function savePayment() {
  if (companies.value.length > 1 && !payForm.orgId) { ElMessage.warning('请选择付款公司'); return }
  if (!payForm.supplierId) { ElMessage.warning('请选择供应商'); return }
  if (!payForm.amount || payForm.amount <= 0) { ElMessage.warning('请填写付款金额'); return }
  if (payOver.value > 0) {
    // 只提醒不拦：预付款、跨期对账时付款大于当前余额是可能的
    try {
      await ElMessageBox.confirm(`本次付款 ${fmtMoney(payForm.amount)} 元，比该供应商当前应付余额 ${fmtMoney(payBalance.value)} 元多 ${fmtMoney(payOver.value)} 元。确认是预付款或金额无误？`,
        '付款超过应付余额', { type: 'warning', confirmButtonText: '确认无误，登记', cancelButtonText: '返回修改' })
    } catch { return }
  }
  paySaving.value = true
  try {
    await paymentApi.create({ ...payForm })
    ElMessage.success('付款已登记')
    payDialogVisible.value = false
    await load()
  } catch (e: any) { showError(e, '登记失败') }
  finally { paySaving.value = false }
}

// ── 付款记录 ──
const payListVisible = ref(false)
const payListLoading = ref(false)
const payRows = ref<any[]>([])
async function openPayList() {
  payListVisible.value = true
  payListLoading.value = true
  try {
    payRows.value = await paymentApi.list({
      orgId: orgId.value || undefined,
      supplierId: supplierId.value || undefined,
      startDate: dateRange.value?.[0] || undefined,
      endDate: dateRange.value?.[1] || undefined
    }) as any
  } finally { payListLoading.value = false }
}
async function removePayment(row: any) {
  await ElMessageBox.confirm(`删除 ${row.payDate} 付给「${row.supplierName || row.supplierId}」的 ${fmtMoney(row.amount)} 这笔付款登记?删除会计入操作日志。`, '提示', { type: 'warning' })
  await paymentApi.remove(row.id)
  ElMessage.success('已删除')
  await Promise.all([openPayList(), load()])
}

onMounted(() => { dateRange.value = [firstOfYear(), today()]; load() })

const kpiInvoice = computed(() => [
  { label: '原材料（发票不含税）', value: fmtMoney(summary.materialAmt) },
  { label: '当期进项税', value: fmtMoney(summary.inputTax) },
  { label: '应付账款-物资款（含税）', value: fmtMoney(summary.payableAmt), tone: 'blue' as const },
  { label: '发票张数', value: `${summary.invoiceCount || 0} 张` }
])
const kpiPay = computed(() => [
  { label: '期间已付款', value: fmtMoney(summary.paidAmt) },
  { label: '应付余额（累计）', value: fmtMoney(summary.payableBalance), hint: '含以前期间，不随所选期间变', tone: Number(summary.payableBalance) > 0 ? 'red' as const : undefined },
  { label: '对照：当前暂估未开票', value: fmtMoney(summary.estimateUninvoiced), hint: '不含税，不进应付' },
  { label: '对照：期末原材料库存', value: fmtMoney(summary.inventoryValue), hint: '成本口径' }
])

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const sumPayRows = makeLineSummary({ '金额(含税)': { field: 'amount', type: 'money' } })
</script>

<style scoped>
.pay-hint { font-size: 12px; line-height: 1.5; margin-top: 4px; color: var(--text-3, #909399); }
.pay-hint.over { color: var(--el-color-danger); font-weight: 600; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin: 6px 0 16px; }
.rc-card { border-radius: 12px; padding: 14px 16px; border: 1px solid #eef1f5; }
.rc-l { font-size: 12px; color: #6b7280; margin-bottom: 6px; }
.rc-v { font-size: 20px; font-weight: 800; color: #1f2937; }
.rc-card.blue { background: #eff5ff; } .rc-card.blue .rc-v { color: #1d63b0; }
.rc-card.amber { background: #fff7ea; } .rc-card.amber .rc-v { color: #b7791f; }
.rc-card.rose { background: #fdeef0; } .rc-card.rose .rc-v { color: #c0392b; }
.rc-card.green { background: #eefaf3; } .rc-card.green .rc-v { color: #2f9e6f; }
.rc-card.violet { background: #f3f0fb; } .rc-card.violet .rc-v { color: #6d4fc4; }
.rc-card.teal { background: #e9f7f7; } .rc-card.teal .rc-v { color: #16797c; }
.rc-card.gray { background: #f4f6f8; }
.rc-card.plain { background: #fafbfc; }
.bal-pos { color: #c0392b; font-weight: 700; }
.tbl-title { font-weight: 700; color: #374151; margin: 8px 0; }
.mt-3 { margin-top: 12px; }
</style>
