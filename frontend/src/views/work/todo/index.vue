<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">我的待办</div>
        <div class="page-sub">共 {{ list.length }} 条 · 未读 {{ unread }} 条</div>
      </div>
      <el-button @click="readAll" :disabled="unread === 0">全部标为已读</el-button>
    </div>

    <el-card v-if="route.query.cancel === '1'" shadow="never" style="margin-bottom:16px">
      <template #header>结账待处理的取消审批申请</template>
      <el-table :data="cancelRows" v-loading="loading" empty-text="没有当前可见的待处理申请，可能已处理，请返回结账页重新检查">
        <el-table-column prop="bizNo" label="单号" min-width="220" />
        <el-table-column prop="reason" label="申请原因" min-width="200" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.canProcess">
              <el-button link type="primary" @click="processCancel({ bizId: row.id }, true)">同意取消</el-button>
              <el-button link type="danger" @click="processCancel({ bizId: row.id }, false)">驳回</el-button>
            </template>
            <span v-else>请原审批人处理</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="list" v-loading="loading" empty-text="暂无待办">
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isRead ? 'info' : 'warning'">
              {{ row.isRead ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="事项" width="360" show-overflow-tooltip />
        <el-table-column label="关联单据" width="210">
          <template #default="{ row }">
            <button
              v-if="row.bizNo && row.bizType !== 'CANCEL_REQ'"
              type="button"
              class="doc-link"
              :aria-label="`查看单据 ${row.bizNo}`"
              :title="`查看单据 ${row.bizNo}`"
              @click="openDoc(row)"
            >
              {{ row.bizNo }}
            </button>
            <span v-else>{{ row.bizNo || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">
            <span class="todo-time">{{ fmtTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <div class="todo-actions">
              <template v-if="row.bizType === 'CANCEL_REQ'">
                <el-button link type="primary" @click="processCancel(row, true)">同意取消</el-button>
                <el-button link type="danger" @click="processCancel(row, false)">驳回</el-button>
              </template>
              <template v-else>
                <el-button v-if="row.bizNo" link type="primary" @click="openDoc(row)">打开</el-button>
                <el-button v-if="!row.isRead" link type="primary" @click="markRead(row.id)">标为已读</el-button>
                <span v-if="!row.bizNo && row.isRead" class="muted">-</span>
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { wfApi } from '@/api'
import { useNotifStore } from '@/stores/notif'
import { useUserStore } from '@/stores/user'

const notifStore = useNotifStore()
const user = useUserStore()
const router = useRouter()
const route = useRoute()
const cancelRows = ref<any[]>([])
const loading = ref(false)
const list = computed(() => notifStore.list)
const unread = computed(() => notifStore.unread)

function fmtTime(v: string) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  try {
    await notifStore.loadList()
    if (route.query.cancel === '1') cancelRows.value = await wfApi.pendingCancelRequests(String(route.query.keyword || '')) as any[]
  } finally { loading.value = false }
}

async function markRead(id: number) {
  await notifStore.read(id)
  await load()
}

async function readAll() {
  await notifStore.readAll()
  await load()
}

async function refreshRouteMenus() {
  try { await user.loadUserInfo() } catch {}
}

async function openDoc(row: any) {
  const map: Record<string, string> = {
    PO: '/purchase/po',
    RECV: '/inbound/recv',
    PRET: '/inbound/purchase-return',
    INV: '/invoice/list',
    OREQ: '/outbound/req',
    OREQ_REV: '/outbound/req',
    OUT: '/outbound/out',
    RETURN: '/outbound/return',
    TRANSFER: '/inventory/transfer',
    LOAN: '/outbound/loan',
    LR: '/material-req/inbox',
    PP: '/material-req/pp-inbox',
    STOCKTAKE: '/inventory/stocktake',
    // 🔴 2026-08-02 补:原来缺这 4 类销售单据,点开会静默弹回首页(曾积压不少这样的待办)。
    //    这张表必须与首页 views/work/home/index.vue 的同名 map 保持一致 ——
    //    两处各维护一份是这个 bug 的根因,以后新增单据类型【两处都要加】。
    SO: '/sale/order',
    SALE_OUT: '/sale/out',
    SALE_INV: '/sale/invoice',
    SALE_RETURN: '/sale/return'
  }
  await refreshRouteMenus()
  await router.push({
    path: map[row.bizType] || '/work/home',
    // 按单号定位(不锁定状态)：待审批单据是 PENDING、被驳回的是 REJECTED，都能查到
    query: row.bizNo ? { keyword: row.bizNo, auto: '1', ...(row.bizType === 'OREQ_REV' ? { revision: '1' } : {}) } : {}
  })
}

async function processCancel(row: any, approve: boolean) {
  const actionText = approve ? '同意取消审批' : '驳回取消审批'
  const result = await ElMessageBox.prompt(`请输入${actionText}说明`, actionText, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: approve ? '同意取消审批' : '请输入驳回原因',
    inputValue: approve ? '同意取消审批' : ''
  }).catch(() => null)
  if (!result) return
  const { value } = result
  await wfApi.processCancel(row.bizId, { approve, comment: value || actionText })
  ElMessage.success('已处理')
  await load()
}

onMounted(load)
</script>

<style scoped lang="scss">
.todo-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;

  :deep(.el-button) {
    margin-left: 0;
    padding: 0;
    height: 24px;
  }
}

.todo-time {
  white-space: nowrap;
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--text-3);
}
</style>
