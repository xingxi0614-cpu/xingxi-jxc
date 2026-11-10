<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">单据链查询</div>
        <div class="page-sub">输入任意单据号，查看上下游关联路径</div>
      </div>
    </div>

    <el-card shadow="never" class="cd-p" style="padding:16px">
      <el-input
        v-model="docNo"
        placeholder="如：PO-2026-0001 / RECV-2026-0001 / OUT-2026-0001"
        style="width:340px"
        @keyup.enter="query"
      >
        <template #append>
          <el-button @click="query" :icon="Search">查询</el-button>
        </template>
      </el-input>

      <div v-if="chain" class="chain-canvas">
        <div class="legend">
          <span>图例：</span>
          <span class="lg lg-po">采购订单</span>
          <span class="lg lg-recv">入库</span>
          <span class="lg lg-inv">发票</span>
          <span class="lg lg-oreq">出库申请</span>
          <span class="lg lg-out">材料出库</span>
        </div>

        <div class="nodes">
          <div v-for="g in groupedNodes" :key="g.type" class="node-group">
            <div class="group-title">{{ groupLabel(g.type) }}</div>
            <button
              v-for="n in g.list"
              :key="n.id"
              type="button"
              class="node"
              :class="['n-' + g.type.toLowerCase(), { active: n.id === docNo }]"
              :aria-label="`查看单据链 ${n.id}`"
              :title="`查看单据链 ${n.id}`"
              @click="docNo = n.id; query()"
            >
              <div class="no">{{ n.id }}</div>
              <el-tag v-if="n.status" size="small" :type="statusColor(n.status)" class="node-status">{{ statusLabel(n.status) }}</el-tag>
            </button>
          </div>
        </div>

        <div class="edges">
          <div class="section-title">关系（{{ chain.edges.length }} 条）</div>
          <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="chain.edges" size="small">
            <el-table-column prop="from" label="源单据" width="170" show-overflow-tooltip />
            <el-table-column prop="to" label="目标单据" width="170" show-overflow-tooltip />
            <el-table-column prop="type" label="关系" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="edgeColor(row.type)">{{ edgeLabel(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" align="right" width="100" :formatter="$qtyFmt" />
            <el-table-column v-if="canSeeAmount" prop="amount" label="金额" align="right" width="140">
              <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="centerNode" class="approval">
          <div class="section-title">
            审批流程 · {{ centerNode.id }}
            <el-tag v-if="centerNode.status" size="small" :type="statusColor(centerNode.status)">{{ statusLabel(centerNode.status) }}</el-tag>
          </div>
          <div v-if="centerNode.approval && centerNode.approval.state === 'PROCESSING'" class="pending-hint">
            当前待「{{ roleLabel(centerNode.approval.nextApproverRole) }}」审批
          </div>
          <el-timeline v-if="approvalRecords.length" style="margin-top:10px">
            <el-timeline-item
              v-for="(r, i) in approvalRecords"
              :key="i"
              :timestamp="$fmtMin(r.time)"
              :type="r.action === 'REJECT' ? 'danger' : 'success'"
            >
              <b>{{ nodeLabel(r.node) }}</b>
              <span class="who"> · {{ r.approver || '-' }} · {{ actionLabel(r.action) }}</span>
              <span v-if="r.comment" class="cmt">意见：{{ r.comment }}</span>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="该单据暂无审批记录" :image-size="56" />
        </div>
      </div>

      <el-empty v-else description="请输入单据号查询" style="margin-top:30px" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Search } from '@/icons'
import { ElMessage } from 'element-plus'
import { docChainApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtMoney } from '@/utils/format'

const userStore = useUserStore()
const route = useRoute()
const docNo = ref('')
const chain = ref<any>(null)
const canSeeAmount = computed(() => userStore.hasFinanceAccessOrAdmin())

async function query() {
  if (!docNo.value) {
    ElMessage.warning('请输入单据号')
    return
  }
  try {
    chain.value = await docChainApi.chain(docNo.value)
  } catch {
    chain.value = null
  }
}

const groupedNodes = computed(() => {
  if (!chain.value) return []
  const order = ['PO', 'RECV', 'INV', 'OREQ', 'OREQ_REV', 'OUT', 'RETURN', 'LOAN', 'TRANSFER']
  const map: Record<string, any[]> = {}
  for (const n of chain.value.nodes) {
    if (!map[n.type]) map[n.type] = []
    map[n.type].push(n)
  }
  return order.filter(t => map[t]).map(t => ({ type: t, list: map[t] }))
})

function groupLabel(t: string) {
  return ({ PO: '采购订单', RECV: '入库', INV: '发票', OREQ: '出库申请', OREQ_REV: '申请修订', OUT: '材料出库', RETURN: '退料单', LOAN: '借料', TRANSFER: '库存转库' } as any)[t] || t
}
function edgeLabel(t: string) {
  return ({ DOWN_PUSH: '下推', MATCH: '核销', OFFSET: '冲销', CANCEL: '取消' } as any)[t] || t
}
function edgeColor(t: string) {
  return ({ DOWN_PUSH: 'primary', MATCH: 'success', OFFSET: 'warning', CANCEL: 'danger' } as any)[t] || 'info'
}

const centerNode = computed<any>(() => chain.value?.nodes?.find((n: any) => n.id === chain.value.center) || null)
const approvalRecords = computed<any[]>(() => centerNode.value?.approval?.records || [])

function statusLabel(s: string) {
  return ({
    DRAFT: '草稿', PENDING: '待审批', PROCESSING: '审批中', APPROVED: '已审批', REJECTED: '已驳回',
    RECEIVED: '已入库', PARTIAL_RECEIVED: '部分入库', MATCHED: '已核销', PARTIAL_MATCHED: '部分核销',
    OUTBOUND: '已出库', PARTIAL_OUT: '部分出库', CLOSED: '已关闭', CANCELLED: '已取消'
  } as any)[s] || s || '-'
}
function statusColor(s: string) {
  if (['APPROVED', 'RECEIVED', 'MATCHED', 'OUTBOUND'].includes(s)) return 'success'
  if (['PENDING', 'PROCESSING', 'PARTIAL_RECEIVED', 'PARTIAL_MATCHED', 'PARTIAL_OUT'].includes(s)) return 'warning'
  if (['REJECTED', 'CANCELLED'].includes(s)) return 'danger'
  return 'info'
}
function roleLabel(r: string) {
  return ({
    material_accountant: '材料会计', finance_manager: '财务负责人', deputy_general_manager: '分管副总',
    general_manager: '总经理', tech_manager: '技术部经理', engineering_leader: '工程副总',
    operation_leader: '运营副总', market_leader: '市场副总', branch_manager: '网点负责人',
    area_manager: '片区经理', dept_manager: '部门负责人', finance: '财务'
  } as any)[r] || r || '-'
}
function nodeLabel(n: string) {
  return ({
    MATERIAL_ACCOUNTANT: '材料会计', MATERIAL_ACCOUNTANT_REVIEW: '材料会计', FINANCE_MANAGER: '财务负责人',
    DEPUTY_GENERAL_MANAGER: '分管副总', GENERAL_MANAGER: '总经理', TECH_MANAGER: '技术部经理',
    ENGINEERING_LEADER: '工程副总', OPERATION_LEADER: '运营副总', MARKET_LEADER: '市场副总',
    BRANCH_MANAGER: '网点负责人', AREA_MANAGER: '片区经理', SUBMIT: '提交'
  } as any)[n] || n || '-'
}
function actionLabel(a: string) {
  return ({ SUBMIT: '提交', APPROVE: '通过', REJECT: '驳回', WITHDRAW: '撤回' } as any)[a] || a || '-'
}

function applyRouteDocNo() {
  const q = route.query.docNo
  const next = Array.isArray(q) ? q[0] : q
  if (next && next !== docNo.value) {
    docNo.value = next
    query()
  }
}

onMounted(applyRouteDocNo)
watch(() => route.query.docNo, applyRouteDocNo)
</script>

<style scoped lang="scss">
.chain-canvas {
  margin-top: 14px;
  background: var(--panel-bg);
  border-radius: 8px;
  padding: 16px;
}
.legend {
  display: flex; gap: 10px; flex-wrap: wrap; align-items: center;
  font-size: 12px; color: var(--text-3);
  margin-bottom: 14px;
  .lg {
    padding: 2px 8px;
    border-radius: 4px;
    border: 1px solid #ddd;
    background: var(--el-bg-color);
    color: var(--text-2);
    &.lg-pr   { border-color: #91D5FF; }
    &.lg-po   { border-color: #1677FF; }
    &.lg-recv { border-color: #52C41A; }
    &.lg-inv  { border-color: #FAAD14; }
    &.lg-oreq { border-color: #722ED1; }
    &.lg-out  { border-color: #EB2F96; }
  }
}
.nodes {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  align-items: flex-start;
}
.node-group {
  background: var(--el-bg-color);
  border-radius: 8px;
  padding: 10px 12px;
  min-width: 180px;
  border: 1px dashed #D9D9D9;
}
.group-title {
  font-size: 11px; font-weight: 600; color: var(--text-3);
  margin-bottom: 8px; text-transform: uppercase;
}
.node {
  display: block;
  width: 100%;
  background: var(--el-bg-color);
  border: 2px solid var(--border-1);
  border-radius: 6px;
  padding: 6px 10px;
  margin-bottom: 6px;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: .15s;
  &:hover { transform: translateY(-1px); box-shadow: 0 2px 8px rgba(0,0,0,.08); }
  &.active { background: #E6F4FF; border-color: var(--color-primary); }
  .no { font-size: 13px; font-weight: 600; color: var(--color-primary); font-variant-numeric: tabular-nums; }
}
.n-pr   { border-color: #91D5FF; }
.n-po   { border-color: #1677FF; }
.n-recv { border-color: #52C41A; }
.n-inv  { border-color: #FAAD14; }
.n-oreq { border-color: #722ED1; }
.n-out  { border-color: #EB2F96; }

.edges { margin-top: 16px; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: var(--text-2); display: flex; align-items: center; gap: 8px; }
.node-status { margin-top: 4px; }
.approval { margin-top: 18px; padding-top: 14px; border-top: 1px dashed var(--border-2, #e4e7ed); }
.pending-hint { font-size: 12px; color: var(--el-color-warning, #e6a23c); margin-top: 4px; }
.who { color: var(--text-2); font-size: 13px; }
.cmt { display: block; font-size: 12px; color: var(--text-3); margin-top: 2px; }
</style>
