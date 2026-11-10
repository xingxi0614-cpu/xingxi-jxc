<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">收款登记</div>
        <div class="page-sub">登记向客户收到的货款（含税口径，对应应收）。应收余额 = 累计已审批销售发票价税合计 − 累计收款（按客户单位）。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button @click="reload">刷新</el-button>
        <el-button v-if="canEdit" type="primary" @click="openCreate">+ 登记收款</el-button>
      </el-space>
    </div>

    <KpiCards :items="kpis" />
    <!-- 两块面板各占一半，竖缝与上方 4 张数字卡的中缝对齐 -->
    <el-row :gutter="12" class="rcpt-cols">
      <el-col :md="12" :sm="24">
        <el-card shadow="never">
          <div class="card-title">收款记录</div>
          <el-form :inline="true" size="default" style="margin-bottom:8px">
            <el-form-item label="日期"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:230px" @change="loadReceipts" /></el-form-item>
          </el-form>
          <el-table class="readable-table" border :data="receipts" v-loading="loading" empty-text="暂无收款记录" max-height="480" show-summary :summary-method="receiptSummary">
            <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="receiptDate" label="日期" width="110" />
            <el-table-column label="客户" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.customerUnit || row.customerName || '-' }}</template></el-table-column>
            <el-table-column label="收款金额" prop="amount" width="120" align="right"><template #default="{ row }">{{ fmtMoney(row.amount) }}</template></el-table-column>
            <el-table-column prop="method" label="方式" width="90" />
            <el-table-column prop="refNo" label="单号" width="120" show-overflow-tooltip />
            <el-table-column v-if="canEdit" label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="doDelete(row.id)">删除</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="12" :sm="24">
        <el-card shadow="never">
          <div class="card-title">应收余额（按客户单位）</div>
          <el-table class="readable-table" border :data="arRows" v-loading="arLoading" empty-text="暂无应收往来" max-height="530" show-summary :summary-method="arSummary">
            <el-table-column v-if="arMultiOrg" prop="orgName" label="公司" min-width="110" show-overflow-tooltip />
            <el-table-column prop="customerName" label="客户单位" min-width="130" show-overflow-tooltip />
            <el-table-column label="应收(发票)" width="100" align="right"><template #default="{ row }">{{ fmtMoney(row.invoiceAmt) }}</template></el-table-column>
            <el-table-column label="退货冲减" width="90" align="right"><template #default="{ row }">{{ fmtMoney(row.returnAmt) }}</template></el-table-column>
            <el-table-column label="已收" width="90" align="right"><template #default="{ row }">{{ fmtMoney(row.receiptAmt) }}</template></el-table-column>
            <el-table-column label="余额" width="100" align="right"><template #default="{ row }"><span :class="{ owe: Number(row.balance) > 0 }">{{ fmtMoney(row.balance) }}</span></template></el-table-column>
          </el-table>
          <el-alert type="info" :closable="false" show-icon class="mt-2" title="散户(个人)按订单预收，多为收付实现，不在此往来余额中体现。" />
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose" title="登记收款" width="480px" align-center>
      <el-form label-position="top" :model="form" label-width="92px">
        <el-form-item label="客户单位">
          <el-select v-model="form.customerId" filterable clearable remote :remote-method="searchCustomers" :loading="cusLoading" placeholder="单位客户可选" style="width:100%" @change="onCus">
            <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户名称"><el-input v-model="form.customerName" placeholder="散户个人姓名（未选单位时填）" /></el-form-item>
        <el-form-item label="收款日期"><el-date-picker v-model="form.receiptDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="收款金额" required><el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="收款方式"><el-select v-model="form.method" clearable style="width:100%"><el-option v-for="m in payMethods" :key="m" :value="m" :label="m" /></el-select></el-form-item>
        <el-form-item label="回单号"><el-input v-model="form.refNo" placeholder="银行回单号/凭证号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { salesApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtMoney, today } from '@/utils/format'
import { showError } from '@/utils/notify'
import { makeLineSummary } from '@/utils/tableSummary'
import KpiCards from '@/components/KpiCards.vue'

const user = useUserStore()
const canEdit = computed(() => user.hasFinanceAccessOrAdmin())
const payMethods = ['转账', '现金', '微信', '支付宝', '银行承兑', '其他']

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const receipts = ref<any[]>([])
const dateRange = ref<any>(null)
const arLoading = ref(false)
const arRows = ref<any[]>([])
// 应收按「公司 + 客户」分别算(同一客户在两家公司的欠款和预收不互相抵,2026-10-18 定);能看多家公司时多一列「公司」
const arMultiOrg = computed(() => new Set(arRows.value.map(r => r.orgId)).size > 1)

// 收款合计由后台对全部数据求和(列表最多 500 笔,不能拿列表加总当合计,计算审查 U15)
const receiptTotal = ref<{ count: number; amount: number } | null>(null)
async function loadReceipts() {
  loading.value = true
  const q = { startDate: dateRange.value?.[0] || null, endDate: dateRange.value?.[1] || null }
  try {
    const [list, total] = await Promise.all([salesApi.receiptList(q), salesApi.receiptSummary(q)])
    receipts.value = list as any[]
    receiptTotal.value = total as any
  }
  finally { loading.value = false }
}
async function loadAr() {
  arLoading.value = true
  try { arRows.value = await salesApi.arBalance() as any[] } finally { arLoading.value = false }
}
function reload() { loadReceipts(); loadAr() }
// 按列名合计：表格会自动插「序号」列、固定列排最前，按列序号写会错位（2026-10-01 修）
const arSummary = makeLineSummary({
  '应收(发票)': { field: 'invoiceAmt', type: 'money' }, 退货冲减: { field: 'returnAmt', type: 'money' },
  已收: { field: 'receiptAmt', type: 'money' }, 余额: { field: 'balance', type: 'money' }
})

const receiptSummary = makeLineSummary({ 收款金额: { field: 'amount', type: 'money' } })

// 顶部汇总：收款按上面选的日期范围；应收余额是累计口径（不随日期变）
// 余额为正 = 客户欠款；为负 = 多收或预收（还没开票），开票后自动抵减
const kpis = computed(() => {
  const sum = (list: any[], f: (r: any) => number) => list.reduce((a, r) => a + f(r), 0)
  const owe = arRows.value.filter(r => Number(r.balance) > 0)
  const over = arRows.value.filter(r => Number(r.balance) < 0)
  return [
    { label: dateRange.value ? '期间收款' : '全部收款', value: receiptTotal.value ? fmtMoney(receiptTotal.value.amount) : '—',
      hint: receiptTotal.value ? `${receiptTotal.value.count} 笔${receiptTotal.value.count > receipts.value.length ? `（下表显示最近 ${receipts.value.length} 笔）` : ''}` : '' },
    { label: '应收余额合计', value: fmtMoney(sum(arRows.value, r => Number(r.balance || 0))), hint: '= 客户欠款 − 多收/预收', tone: 'blue' as const },
    { label: '客户欠款', value: fmtMoney(sum(owe, r => Number(r.balance))), hint: `${owe.length} 户`, tone: owe.length ? 'red' as const : undefined },
    { label: '多收 / 预收（还没开票）', value: fmtMoney(Math.abs(sum(over, r => Number(r.balance)))), hint: `${over.length} 户，开票后自动抵减` }
  ]
})

/* 客户下拉 */
const customers = ref<any[]>([])
const cusLoading = ref(false)
async function searchCustomers(kw: string) { cusLoading.value = true; try { customers.value = await salesApi.customers(kw) as any[] } finally { cusLoading.value = false } }
function onCus(id: number) { const c = customers.value.find(x => x.id === id); if (c && !form.customerName) form.customerName = '' }

/* 新增 */
const createVisible = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const saving = ref(false)
const form = reactive<any>({ customerId: null, customerName: '', receiptDate: today(), amount: null, method: '转账', refNo: '', remark: '' })
function openCreate() { Object.assign(form, { customerId: null, customerName: '', receiptDate: today(), amount: null, method: '转账', refNo: '', remark: '' }); customers.value = []; createVisible.value = true }
async function save() {
  if (!form.customerId && !form.customerName?.trim()) { ElMessage.warning('请选择客户单位或填写客户名称'); return }
  if (!form.amount || Number(form.amount) <= 0) { ElMessage.warning('收款金额须大于 0'); return }
  saving.value = true
  try { await salesApi.receiptCreate({ ...form }); ElMessage.success('已登记'); createVisible.value = false; reload() }
  catch (e: any) { showError(e, '保存失败') }
  finally { saving.value = false }
}
async function doDelete(id: number) { await ElMessageBox.confirm('确认删除该收款记录？', '提示', { type: 'warning' }); await salesApi.receiptDelete(id); ElMessage.success('已删除'); reload() }

onMounted(reload)
</script>

<style scoped>
.card-title { font-weight: 700; color: #374151; margin-bottom: 10px; }
.owe { color: #d64b46; font-weight: 600; }
.mt-2 { margin-top: 10px; }
/* 两块面板等高，底边对齐 */
.rcpt-cols { row-gap: 12px; }
.rcpt-cols :deep(.el-col) { display: flex; }
.rcpt-cols :deep(.el-col > .el-card) { flex: 1; }
</style>
