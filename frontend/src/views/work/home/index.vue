<template>
  <div class="page home-page">
    <section class="workspace-panel">
      <div class="overview">
        <div class="overview-greet">
          <div class="eyebrow">
            <el-icon><component :is="'Calendar'" /></el-icon>{{ nowText }}
          </div>
          <h1>{{ user.userInfo?.name }}，{{ timeGreet }}</h1>
        </div>
        <!-- 滚动通知:铺满问候语和「刷新」之间的空白(用户 10-21 截图指定) -->
        <NoticeTicker class="home-ticker" :class="{ 'nt-off': !tickerRoom }" @room="ok => (tickerRoom = ok)" />
        <div class="overview-tools">
          <el-tooltip content="刷新工作台" placement="bottom">
            <button type="button" class="refresh-btn" aria-label="刷新工作台" title="刷新工作台" @click="refresh">
              <el-icon><Refresh /></el-icon>
            </button>
          </el-tooltip>
          <Header class="home-account" :show-ticker="false" />
        </div>
      </div>

      <!-- 窄屏兜底:问候语右边放不下整条滚动时,滚动条挪到这里单独一行(有通知才出现) -->
      <NoticeTicker v-if="!tickerRoom" class="home-ticker-row" />

      <div class="stats-grid" :style="{ '--n': statCols }">
        <template v-if="loading && !stats.length">
          <div v-for="i in 4" :key="i" class="stat stat-skeleton">
            <div class="sk-line sk-label"></div>
            <div class="sk-line sk-value"></div>
            <div class="sk-line sk-delta"></div>
          </div>
        </template>
        <button v-else v-for="s in stats" :key="s.label" type="button" class="stat" :aria-label="`查看${s.label}`" :title="`查看${s.label}`" @click="openStat(s)">
          <div class="lb"><span class="stat-dot" :style="{ background: s.color || '#5a6373' }"></span>{{ s.label }}</div>
          <div class="vl">{{ s.value }}</div>
          <div class="dt">{{ s.delta }}</div>
        </button>
      </div>
    </section>


    <!-- 下方面板与上方数字卡同一套列网格：左栏占（列数−2）放「需要你留意」「库存预警」，
         右栏占 2 放「待处理事项」「快捷入口」，竖缝与数字卡对齐（2026-10-17 全站界面改版，原型 Home_Index） -->
    <!--
      四张卡固定排成两行两列（2026-10-17 反馈：左右卡片上下边要对齐、固定高度，多出来的点「查看全部 / 更多」看）：
        第一行：需要你留意 | 待处理事项      第二行：库存预警 | 快捷入口
      每行高度写死，卡片里放不下的不撑高卡片，收进对应的「查看全部 / 更多」。
    -->
    <div class="work-main" :style="{ '--n': statCols }">
        <AlertsCard :key="alertTick" class="work-card wk-alerts" />
        <el-card shadow="never" class="work-card wk-low lowstock-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title">
                <el-icon><component :is="'Warning'" /></el-icon> 库存预警
                <span v-if="shortageCount" class="head-count" style="background:var(--el-color-danger)">缺货 {{ shortageCount }}</span>
                <span v-if="overstockCount" class="head-count" style="background:var(--el-color-warning)">超储 {{ overstockCount }}</span>
              </span>
              <el-button v-if="stockAlerts.length > 3" link type="primary" @click="lowStockVisible = true">查看全部</el-button>
            </div>
          </template>
          <div v-loading="lowStockLoading" class="lowstock-list">
            <div v-if="!stockAlerts.length" class="todo-empty lowstock-empty">
              <el-icon><component :is="'CircleCheck'" /></el-icon>
              <span>暂无库存预警。在「库存预警设置」里给物料设置最低 / 最高库存，现存量超出范围就会在这里提示</span>
            </div>
            <div v-for="r in stockAlerts.slice(0, 3)" :key="`${r.org_name}-${r.material_id}`" class="ls-row">
              <span class="ls-main">
                <span class="ls-name">
                  <el-tag size="small" round effect="light" :type="r.alert_type === 'SHORTAGE' ? 'danger' : 'warning'" style="margin-right:6px">{{ r.alert_type === 'SHORTAGE' ? '缺货' : '超储' }}</el-tag>
                  {{ r.name }}
                </span>
                <span class="ls-sub">{{ r.spec || '-' }}<template v-if="lowStockShowOrg"> · {{ r.org_name }}</template></span>
              </span>
              <span class="ls-qty"><b>{{ Number(r.qty) }}</b> / {{ r.alert_type === 'SHORTAGE' ? '≥' + Number(r.safety_qty) : '≤' + Number(r.max_qty) }} {{ r.unit || '' }}</span>
            </div>
          </div>
        </el-card>
        <el-card shadow="never" class="work-card wk-todo todo-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title">
                <el-icon><component :is="'Bell'" /></el-icon> 待处理事项
                <span v-if="notif.list.length" class="head-count">{{ notif.list.length }}</span>
              </span>
              <el-button link type="primary" @click="navigatePath('/work/todo')">查看全部</el-button>
            </div>
          </template>
          <div v-loading="loading" class="todo-list">
            <div v-if="!todos.length" class="todo-empty">
              <el-icon><component :is="'CircleCheck'" /></el-icon>
              <span>暂无待处理事项，工作已清空</span>
            </div>
            <button v-for="row in todos" :key="row.id" type="button" class="todo-row" @click="openDoc(row)">
              <span class="todo-ic" :class="bizMeta(row.bizType).cls">
                <el-icon><component :is="bizMeta(row.bizType).icon" /></el-icon>
              </span>
              <span class="todo-main">
                <span class="todo-title">{{ row.title }}</span>
                <span class="todo-sub">{{ bizMeta(row.bizType).label }} · {{ row.bizNo || '-' }}</span>
              </span>
              <span class="todo-go">处理 <el-icon><component :is="'ArrowRight'" /></el-icon></span>
            </button>
            <button v-if="notif.list.length > todos.length" type="button" class="card-more" @click="navigatePath('/work/todo')">
              还有 {{ notif.list.length - todos.length }} 条，查看全部
            </button>
          </div>
        </el-card>
        <el-card shadow="never" class="work-card wk-quick">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><component :is="'Grid'" /></el-icon> 快捷入口</span>
              <el-button v-if="quickActions.length > QUICK_MAX" link type="primary" @click="quickAllVisible = true">更多（{{ quickActions.length }}）</el-button>
            </div>
          </template>
          <div class="action-grid">
            <div
              v-for="a in quickActions.slice(0, QUICK_MAX)"
              :key="a.path"
              class="action-tile"
              :class="{ 'is-fav': a.fav }"
              role="button"
              tabindex="0"
              @click="navigatePath(a.path)"
              @keyup.enter="navigatePath(a.path)"
            >
              <span class="action-ic" :class="toneOf(a.path)"><el-icon><component :is="a.icon" /></el-icon></span>
              <span class="action-label">{{ a.label }}</span>
              <button v-if="a.fav" type="button" class="action-unfav" title="取消收藏" @click.stop="favorites.remove(a.path)">
                <el-icon><component :is="'Close'" /></el-icon>
              </button>
            </div>
          </div>
          <div class="action-hint">
            <el-icon><component :is="'Star'" /></el-icon>
            在任意功能页右上角点 <el-icon><component :is="'Star'" /></el-icon> 即可收藏到这里
          </div>
        </el-card>
    </div>

    <el-dialog v-model="quickAllVisible" title="全部快捷入口" width="min(640px, 94vw)" align-center>
      <div class="action-grid">
        <div v-for="a in quickActions" :key="a.path" class="action-tile" :class="{ 'is-fav': a.fav }" role="button" tabindex="0"
             @click="quickAllVisible = false; navigatePath(a.path)" @keyup.enter="quickAllVisible = false; navigatePath(a.path)">
          <span class="action-ic" :class="toneOf(a.path)"><el-icon><component :is="a.icon" /></el-icon></span>
          <span class="action-label">{{ a.label }}</span>
          <button v-if="a.fav" type="button" class="action-unfav" title="取消收藏" @click.stop="favorites.remove(a.path)">
            <el-icon><component :is="'Close'" /></el-icon>
          </button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="lowStockVisible" title="库存预警清单（缺货 / 超储）" width="min(840px, 94vw)" align-center>
      <el-table class="readable-table" border :data="stockAlerts" max-height="480">
        <el-table-column label="状态" width="82" align="center">
          <template #default="{ row }"><el-tag size="small" round effect="light" :type="row.alert_type === 'SHORTAGE' ? 'danger' : 'warning'">{{ row.alert_type === 'SHORTAGE' ? '缺货' : '超储' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="物料编码" prop="code" width="120" />
        <el-table-column label="物料名称" prop="name" min-width="150" show-overflow-tooltip />
        <el-table-column label="规格型号" prop="spec" min-width="110" show-overflow-tooltip />
        <el-table-column v-if="lowStockShowOrg" label="公司" prop="org_name" width="130" show-overflow-tooltip />
        <el-table-column label="现存量" width="92" align="right"><template #default="{ row }">{{ Number(row.qty) }}</template></el-table-column>
        <el-table-column label="最低" width="80" align="right"><template #default="{ row }">{{ Number(row.safety_qty) > 0 ? Number(row.safety_qty) : '—' }}</template></el-table-column>
        <el-table-column label="最高" width="80" align="right"><template #default="{ row }">{{ Number(row.max_qty) > 0 ? Number(row.max_qty) : '—' }}</template></el-table-column>
        <el-table-column label="单位" prop="unit" width="64" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import AlertsCard from './AlertsCard.vue'
import { computed, onActivated, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@/icons'
import { useUserStore } from '@/stores/user'
import { useNotifStore } from '@/stores/notif'
import { useFavoritesStore } from '@/stores/favorites'
import { invoiceApi, masterApi, outApi, periodCloseApi, poApi, recvApi, reportApi, sysApi } from '@/api'
import Header from '@/layout/components/Header.vue'
import NoticeTicker from '@/components/NoticeTicker.vue'
/** 首页问候语右边能不能放下整条滚动(≥200 像素);放不下就在问候语下面单独放一行,不挤任何按钮(10-21 实测) */
const tickerRoom = ref(true)

const router = useRouter()
const user = useUserStore()
const notif = useNotifStore()
const favorites = useFavoritesStore()
const loading = ref(false)
const businessStats = ref<Record<string, number>>({})
type RouteQuery = Record<string, string | number | undefined>

// 工作台当前时间（每 30 秒刷新一次，下方时钟/问候语都依赖它）
const now = ref(new Date())

const timeGreet = computed(() => {
  const h = now.value.getHours()
  if (h < 5) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 11) return '上午好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  if (h < 23) return '晚上好'
  return '夜深了'
})

const primaryRoleOrder = [
  'admin',
  'material_accountant',
  'finance',
  'warehouse',
  'purchaser',
  'account_admin',
  'tech_manager',
  'finance_manager'
]
const role = computed(() => {
  const roles = user.userInfo?.roles || []
  return primaryRoleOrder.find(code => roles.includes(code)) || roles[0] || ''
})
function isFinanceRole(code: string) {
  return code === 'material_accountant' || code === 'finance'
}
// 待处理事项卡片高度固定，只列前 5 条（正好放满不出滚动条），其余点「查看全部」
const todos = computed(() => notif.list.slice(0, 5))
// 快捷入口卡片只放 4 个（两行），多的点「更多」
const QUICK_MAX = 4
const quickAllVisible = ref(false)

// 待办按单据类型映射图标 / 名称 / 配色
const BIZ_META: Record<string, { label: string; icon: string; cls: string }> = {
  PR: { label: '采购申请', icon: 'EditPen', cls: 'b-blue' },
  PO: { label: '采购订单', icon: 'ShoppingCart', cls: 'b-blue' },
  RECV: { label: '采购入库', icon: 'Box', cls: 'b-green' },
  INV: { label: '采购发票', icon: 'Tickets', cls: 'b-purple' },
  OREQ: { label: '出库申请', icon: 'DocumentChecked', cls: 'b-amber' },
  OUT: { label: '材料出库', icon: 'Van', cls: 'b-rose' },
  LR: { label: '领料申请', icon: 'Tickets', cls: 'b-amber' },
  PP: { label: '采购计划', icon: 'ShoppingCart', cls: 'b-green' },
  STOCKTAKE: { label: '盘点单', icon: 'DocumentChecked', cls: 'b-blue' },
  SO: { label: '销售订单', icon: 'ShoppingCart', cls: 'b-blue' },
  SALE_OUT: { label: '销售出库', icon: 'Van', cls: 'b-rose' },
  SALE_INV: { label: '销售发票', icon: 'Tickets', cls: 'b-purple' },
  SALE_RETURN: { label: '销售退货', icon: 'RefreshRight', cls: 'b-amber' }
}
function bizMeta(t: string) {
  return BIZ_META[t] || { label: '系统通知', icon: 'Bell', cls: 'b-gray' }
}
// 数字卡几张就排几列（财务 5 张，原来写死 4 列，第 5 张会单独掉到第二行）
const statCols = computed(() => Math.min(Math.max(stats.value.length, 4), 5))
const stats = computed(() => {
  const cards = [...roleCards.value]
  if (cards.length <= 3) {
    cards.push({ label: '待办未读', value: notif.unread, delta: '当前用户未读业务提醒', path: '/work/todo', color: '#2f8fd0' })
  }
  return cards
})

const roleCards = computed(() => {
  const s = businessStats.value
  const isPur = role.value === 'purchaser', isWh = role.value === 'warehouse'
  const isFin = isFinanceRole(role.value), isAdm = role.value === 'admin'
  const isAcc = role.value === 'account_admin'
  const C = (color: string) => ({ color })
  if (isPur) {
    return [
      { label: '订单草稿', value: s.poDraft || 0, delta: '尚未提交的采购订单', path: '/purchase/po', query: { status: 'DRAFT', auto: '1' }, ...C('#d6881f') },
      { label: '审批中订单', value: s.poPending || 0, delta: '采购订单等待岗位审批', path: '/purchase/po', query: { status: 'PENDING', auto: '1' }, ...C('#d6881f') },
      { label: '已审批订单', value: s.poApproved || 0, delta: '已完成审批，可安排入库', path: '/purchase/po', query: { status: 'APPROVED', auto: '1' }, ...C('#d6881f') }
    ]
  }
  if (isWh) {
    return [
      { label: '待入库订单', value: s.poApproved || 0, delta: '已审批，可办理入库', path: '/purchase/po', query: { status: 'APPROVED', auto: '1' }, ...C('#c2772a') },
      { label: '待审入库单', value: s.recvPending || 0, delta: '已提交材料会计确认', path: '/inbound/recv', query: { status: 'PENDING', auto: '1' }, ...C('#2e9e6b') },
      { label: '待出库申请', value: s.oreqApproved || 0, delta: '已审批，可关联出库', path: '/outbound/req', query: { status: 'APPROVED', auto: '1' }, ...C('#c2772a') },
      { label: '未完成出库', value: s.unfinishedOutReq || 0, delta: '已审批但尚未发料完毕，含部分出库', path: '/outbound/req', query: { status: 'APPROVED', auto: '1' }, ...C('#c0453b') }
    ]
  }
  if (isFin) {
    return [
      { label: '待审采购订单', value: s.poPending || 0, delta: '采购订单待终审', path: '/purchase/po', query: { status: 'PENDING', auto: '1' }, ...C('#1d63b0') },
      { label: '待审入库单', value: s.recvPending || 0, delta: '入库金额待确认', path: '/inbound/recv', query: { status: 'PENDING', auto: '1' }, ...C('#1d63b0') },
      { label: '待审发票', value: s.invoicePending || 0, delta: '采购发票待审核', path: '/invoice/list', query: { status: 'PENDING', auto: '1' }, ...C('#7a5db0') },
      { label: '未完成出库', value: s.unfinishedOutReq || 0, delta: '已审批但尚未发料完毕，影响月末结账', path: '/outbound/req', query: { status: 'APPROVED', auto: '1' }, ...C('#c0453b') },
      { label: '暂估待开票', value: s.estimateUnmatched || 0, delta: '货到票未到，需取得发票并完成核销', path: '/inbound/est', query: { status: 'APPROVED', auto: '1' }, ...C('#c0453b') }
    ]
  }
  if (isAdm) {
    return [
      { label: '用户账号', value: s.userCount || 0, delta: '已配置系统登录账号', path: '/system/users', ...C('#5a6373') },
      { label: '角色权限', value: s.roleCount || 0, delta: '采购、仓管、材料会计、管理员权限', path: '/system/roles', ...C('#5a6373') },
      { label: '公司账套', value: s.orgCount || 0, delta: '当前公司级组织账套', path: '/system/orgs', ...C('#5a6373') },
      { label: '基础资料', value: s.masterDataCount || 0, delta: '供应商、物料、仓库、项目档案', path: '/master/material', ...C('#5a6373') }
    ]
  }
  if (isAcc) {
    return [
      { label: '本账套用户', value: s.userCount || 0, delta: '账套内可管理的登录账号', path: '/system/users', ...C('#5a6373') },
      { label: '公司账套', value: s.orgCount || 0, delta: '可维护的公司级账套', path: '/system/orgs', ...C('#5a6373') },
      { label: '基础资料', value: s.masterDataCount || 0, delta: '供应商、物料、仓库、项目档案', path: '/master/material', ...C('#2e9e6b') },
      { label: '工程项目', value: s.projectCount || 0, delta: '工程项目档案维护', path: '/master/project', ...C('#d6881f') }
    ]
  }
  return [
    { label: '待处理事项', value: notif.unread, delta: '当前用户未读业务提醒', path: '/work/todo', color: '#2f8fd0' },
    { label: '可用菜单', value: s.menu || 0, delta: '按授权开放的功能入口', path: '/work/home', color: '#2f8fd0' },
    { label: '在线用户', value: s.onlineCount || 0, delta: '当前系统在线人数', path: '/work/home', color: '#2f8fd0' }
  ]
})

const actions = computed(() => {
  if (role.value === 'purchaser') {
    return [
      { label: '采购订单', path: '/purchase/po', icon: 'ShoppingCart' },
      { label: '订单跟踪', path: '/purchase/track', icon: 'Connection' }
    ]
  }
  if (role.value === 'warehouse') {
    return [
      { label: '采购订单', path: '/purchase/po', icon: 'ShoppingCart' },
      { label: '采购入库', path: '/inbound/recv', icon: 'Box' },
      { label: '出库申请', path: '/outbound/req', icon: 'DocumentChecked' },
      { label: '材料出库', path: '/outbound/out', icon: 'Van' },
      { label: '库存余额', path: '/inventory/balance', icon: 'DataLine' }
    ]
  }
  if (isFinanceRole(role.value)) {
    return [
      { label: '我的审批', path: '/work/todo', icon: 'CircleCheck' },
      { label: '采购发票', path: '/invoice/list', icon: 'Tickets' },
      { label: '月末结账', path: '/cost/period-close', icon: 'Coin' },
      { label: '收发存汇总', path: '/report/in-out-summary', icon: 'DataAnalysis' }
    ]
  }
  return [
    { label: '用户管理', path: '/system/users', icon: 'User' },
    { label: '角色权限', path: '/system/roles', icon: 'Setting' },
    { label: '单据链查询', path: '/trace/chain', icon: 'Share' },
    { label: '基础资料', path: '/master/material', icon: 'Collection' }
  ]
})

// 快捷入口按业务类型着色（同一角色下各入口颜色互不相同，便于快速辨识）
function toneOf(path: string) {
  if (path.startsWith('/purchase/track')) return 'b-indigo'
  if (path.startsWith('/purchase')) return 'b-blue'
  if (path.startsWith('/inbound')) return 'b-green'
  if (path.startsWith('/outbound/out')) return 'b-rose'
  if (path.startsWith('/outbound')) return 'b-amber'
  if (path.startsWith('/inventory')) return 'b-teal'
  if (path.startsWith('/invoice')) return 'b-purple'
  if (path.startsWith('/cost')) return 'b-teal'
  if (path.startsWith('/report')) return 'b-indigo'
  if (path.startsWith('/work/todo')) return 'b-blue'
  if (path.startsWith('/trace')) return 'b-teal'
  if (path.startsWith('/master')) return 'b-rose'
  if (path.startsWith('/system/roles')) return 'b-indigo'
  if (path.startsWith('/system')) return 'b-gray'
  return 'b-blue'
}

// 快捷入口 = 用户收藏（可取消）在前 + 角色默认入口（去重）
const quickActions = computed(() => {
  const favs = favorites.items.map(f => ({ label: f.label, path: f.path, icon: f.icon || 'StarFilled', fav: true }))
  const favPaths = new Set(favs.map(f => f.path))
  const defaults = actions.value
    .filter(a => !favPaths.has(a.path))
    .map(a => ({ ...a, fav: false }))
  return [...favs, ...defaults]
})

async function refreshRouteMenus() {
  try { await user.loadUserInfo() } catch {}
}

async function navigatePath(path: string, query?: RouteQuery) {
  if (!path) return
  await refreshRouteMenus()
  await router.push({ path, query: query || {} })
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
    SO: '/sale/order',
    SALE_OUT: '/sale/out',
    SALE_INV: '/sale/invoice',
    SALE_RETURN: '/sale/return'
  }
  // 只按单号过滤即可精确定位到这张单——不要写死 status=PENDING:
  // 「已退回/驳回」待办的单据是 REJECTED 状态，带 PENDING 会筛成空列表(打开没内容)。
  const q = row.bizNo ? { keyword: row.bizNo, auto: '1', ...(row.bizType === 'OREQ_REV' ? { revision: '1' } : {}) } : {}
  await navigatePath(map[row.bizType] || '/work/todo', q)
}

function countCompanies(nodes: any[]): number {
  return (nodes || []).reduce((sum, item) => {
    const type = String(item?.type || '').toUpperCase()
    const isCompany = type === 'COMPANY' || type === 'GROUP'
    return sum + (isCompany ? 1 : 0) + countCompanies(item.children || [])
  }, 0)
}

async function openStat(stat: any) {
  if (!stat?.path) return
  await navigatePath(stat.path, stat.query || {})
}

// 点右上角刷新时，让「需要你留意」卡片也重新取数（换 key 即重建组件）
const alertTick = ref(0)
async function refresh() {
  loading.value = true
  alertTick.value++
  try {
    await Promise.all([notif.loadList(), notif.refreshCount(), loadBusinessStats(), loadLowStock()])
  } finally {
    loading.value = false
  }
}

// 库存双向预警：缺货(现存<最低) + 超储(现存>最高)，按账套汇总；接口只回数量不带成本
const stockAlerts = ref<any[]>([])
const lowStockLoading = ref(false)
const lowStockVisible = ref(false)
const lowStockShowOrg = computed(() => new Set(stockAlerts.value.map(r => r.org_name)).size > 1)
const shortageCount = computed(() => stockAlerts.value.filter(r => r.alert_type === 'SHORTAGE').length)
const overstockCount = computed(() => stockAlerts.value.filter(r => r.alert_type === 'OVERSTOCK').length)
async function loadLowStock() {
  lowStockLoading.value = true
  try { stockAlerts.value = (await reportApi.stockAlert() as any) || [] }
  catch { /* 预警加载失败不阻塞工作台 */ }
  finally { lowStockLoading.value = false }
}

async function totalOf(fn: (params: any) => Promise<any>, params: any) {
  const page = await fn({ current: 1, size: 1, ...params }) as any
  return Number(page?.total || 0)
}

async function loadBusinessStats() {
  const result: Record<string, number> = {}
  const read = async (key: string, fn: (params: any) => Promise<any>, params: any) => {
    try { result[key] = await totalOf(fn, params) } catch { result[key] = 0 }
  }
  // 月末结账那两项「降级为提醒后没人再逼着清」的活，搬到工作台天天摆在眼前
  const readReminders = async (into: Record<string, number>) => {
    try {
      const r: any = await periodCloseApi.reminders()
      into.unfinishedOutReq = r?.unfinishedOutReq || 0
      into.estimateUnmatched = r?.estimateUnmatched || 0
    } catch { into.unfinishedOutReq = 0; into.estimateUnmatched = 0 }
  }
  if (role.value === 'purchaser') {
    await Promise.all([
      read('poDraft', poApi.page, { status: 'DRAFT' }),
      read('poPending', poApi.page, { status: 'PENDING' }),
      read('poApproved', poApi.page, { status: 'APPROVED' })
    ])
  } else if (role.value === 'warehouse') {
    await Promise.all([
      read('poApproved', poApi.page, { status: 'APPROVED' }),
      read('recvPending', recvApi.page, { status: 'PENDING' }),
      read('oreqApproved', outApi.oreqPage, { status: 'APPROVED' }),
      readReminders(result)
    ])
  } else if (isFinanceRole(role.value)) {
    await Promise.all([
      read('poPending', poApi.page, { status: 'PENDING' }),
      read('recvPending', recvApi.page, { status: 'PENDING' }),
      read('invoicePending', invoiceApi.page, { status: 'PENDING' }),
      read('oreqPending', outApi.oreqPage, { status: 'PENDING' }),
      read('outPending', outApi.outPage, { status: 'PENDING' }),
      readReminders(result)
    ])
  } else if (role.value === 'admin') {
    const [userCount, roles, orgTree, masterCounts] = await Promise.all([
      totalOf(sysApi.users, {}).catch(() => 0),
      sysApi.roles().catch(() => []),
      sysApi.orgTree().catch(() => []),
      Promise.all([
        totalOf(masterApi.suppliers, {}).catch(() => 0),
        totalOf(masterApi.materials, {}).catch(() => 0),
        totalOf(masterApi.warehouses, {}).catch(() => 0),
        totalOf(masterApi.projects, {}).catch(() => 0)
      ])
    ])
    result.userCount = Number(userCount || 0)
    result.roleCount = Array.isArray(roles) ? roles.length : 0
    result.orgCount = countCompanies(Array.isArray(orgTree) ? orgTree : [])
    result.masterDataCount = Array.isArray(masterCounts) ? masterCounts.reduce((sum: number, n: any) => sum + Number(n || 0), 0) : 0
  } else if (role.value === 'account_admin') {
    const [userCount, orgTree, masterCounts, projectCount] = await Promise.all([
      totalOf(sysApi.users, {}).catch(() => 0),
      sysApi.orgTree().catch(() => []),
      Promise.all([
        totalOf(masterApi.suppliers, {}).catch(() => 0),
        totalOf(masterApi.materials, {}).catch(() => 0),
        totalOf(masterApi.warehouses, {}).catch(() => 0)
      ]),
      totalOf(masterApi.projects, {}).catch(() => 0)
    ])
    result.userCount = Number(userCount || 0)
    result.orgCount = countCompanies(Array.isArray(orgTree) ? orgTree : [])
    result.masterDataCount = Array.isArray(masterCounts) ? masterCounts.reduce((sum: number, n: any) => sum + Number(n || 0), 0) : 0
    result.projectCount = Number(projectCount || 0)
  }
  businessStats.value = result
}

// ===== 工作台时钟 =====
let clockTimer: ReturnType<typeof setInterval> | null = null
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const pad = (n: number) => String(n).padStart(2, '0')
const nowText = computed(() => {
  const d = now.value
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${WEEK[d.getDay()]} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

// 立即把时钟/问候语校准到当前时间（切回页签、标签重新可见时调用）
function syncNow() { now.value = new Date() }
// 浏览器后台标签会节流/暂停 setInterval，重新可见时立刻补一次校时
function onVisible() { if (!document.hidden) syncNow() }

onMounted(() => {
  favorites.load()
  refresh()
  syncNow()
  clockTimer = setInterval(syncNow, 30000)
  document.addEventListener('visibilitychange', onVisible)
})
// KeepAlive 缓存的工作台：从其它页签切回时立即校时，问候语随时段即时跟上
onActivated(syncNow)
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<style scoped lang="scss">
.home-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 问候语与数字卡直接放在页面底色上（原型 Home_Index），数字卡才能与下方面板左右边线对齐 */
.workspace-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.overview {
  padding: 2px 2px 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  min-width: 0;

  > div:first-child {
    min-width: 0;
  }

  h1 {
    margin: 6px 0 0;
    color: var(--text-1);
    font-size: 26px;
    line-height: 1.2;
    letter-spacing: 0;
    font-weight: 730;
  }
}

.overview-greet { min-width: 0; }
.home-ticker { align-self: center; }
/* 首页内容区是纵向排列:组件默认的 flex:1 1 0 在纵向会把高度压成 0,这里固定高度 */
.home-ticker-row { flex: 0 0 auto; height: 40px; }
/* 放不下时只把里面那条藏起来,外框照样占着剩余宽度——窗口拉宽后还能量到、自动挪回来(不能用 v-show,宽度会变 0) */
.home-ticker.nt-off :deep(.nt) { visibility: hidden; }

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-3);
  font-size: 13px;
  font-variant-numeric: tabular-nums;

  .el-icon { font-size: 15px; }
}

.overview-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.refresh-btn {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  transition: background-color .16s ease, color .16s ease;

  &:hover {
    background: var(--surface-soft);
    color: var(--color-primary);
  }
}

.home-account {
  width: auto;
  max-width: 100%;
}

.home-account :deep(.r) {
  flex-wrap: wrap;
  justify-content: flex-end;
  background: transparent;
  border-color: transparent;
  box-shadow: none;
  padding: 0;
}


.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(var(--n, 4), minmax(0, 1fr));
  gap: 12px;
}

.stat {
  width: 100%;
  text-align: left;
  min-height: 96px;
  border-radius: 8px;
  border: 1px solid var(--border-1);
  background: var(--bg-panel);
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color .16s ease, box-shadow .16s ease, background-color .16s ease, transform .16s ease;

  &:hover {
    border-color: var(--color-primary);
    background: var(--bg-panel-hover);
    box-shadow: var(--shadow-md);
    transform: translateY(-1px);
  }

  &:focus-visible {
    outline: 2px solid var(--color-primary-soft);
    outline-offset: 2px;
  }

  .lb {
    font-size: 12px;
    color: var(--text-3);
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .stat-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex: 0 0 auto;
  }

  .vl {
    font-size: 22px;
    font-weight: 700;
    margin: 8px 0 4px;
    color: var(--text-1);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .dt {
    font-size: 11px;
    color: var(--text-3);
  }
}

.stat-skeleton {
  cursor: default;
  pointer-events: none;
}
.sk-line {
  border-radius: 4px;
  background: linear-gradient(90deg, var(--surface-soft) 25%, var(--bg-panel-hover) 50%, var(--surface-soft) 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s infinite;
  &.sk-label { width: 60%; height: 12px; margin-bottom: 10px; }
  &.sk-value { width: 40%; height: 22px; margin-bottom: 6px; }
  &.sk-delta { width: 80%; height: 11px; }
}
@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.work-main {
  display: grid;
  grid-template-columns: repeat(var(--n, 4), minmax(0, 1fr));
  /* 两行高度写死：同一行的两张卡上下边永远对齐，内容多少都不会把卡片撑高 */
  grid-template-rows: 460px 292px;
  gap: 12px;
}
/* 左边两张卡与上方数字卡的前 n-2 张同宽，右边两张与最后 2 张同宽，竖线对齐 */
.wk-alerts { grid-column: 1 / span calc(var(--n, 4) - 2); grid-row: 1; }
.wk-low { grid-column: 1 / span calc(var(--n, 4) - 2); grid-row: 2; }
.wk-todo { grid-column: span 2 / -1; grid-row: 1; }
.wk-quick { grid-column: span 2 / -1; grid-row: 2; }

.work-card {
  width: 100%;
  height: 100%;
  min-width: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  :deep(.el-card__body) { flex: 1; min-height: 0; overflow: auto; }
}

.card-more {
  margin-top: 6px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: 13px;
  cursor: pointer;
  text-align: center;
  &:hover { text-decoration: underline; }
}

.card-head-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 500;

  .el-icon { font-size: 16px; color: var(--color-primary); }
}

.head-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--color-primary);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

/* ===== 待处理事项列表 ===== */
.todo-list {
  display: flex;
  flex-direction: column;
  min-height: 120px;
}

.todo-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 0;
  color: var(--text-3);
  font-size: 13px;

  .el-icon { font-size: 30px; color: var(--color-success); }
}

.todo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 11px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  transition: background-color .15s ease;

  & + .todo-row { border-top: 1px solid var(--border-2); }

  &:hover {
    background: var(--bg-panel-hover);
    .todo-go { color: var(--color-primary); }
  }
}

.todo-ic {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;

  &.b-blue   { background: var(--color-primary-soft); color: var(--color-primary); }
  &.b-green  { background: var(--color-success-soft); color: var(--color-success); }
  &.b-amber  { background: rgba(214, 136, 31, .12); color: var(--color-warning); }
  &.b-purple { background: rgba(122, 93, 176, .14); color: #7a5db0; }
  &.b-teal   { background: rgba(31, 138, 153, .14); color: #1f8a99; }
  &.b-indigo { background: rgba(63, 98, 176, .14); color: #3f62b0; }
  &.b-rose   { background: rgba(199, 92, 116, .14); color: #c75c74; }
  &.b-gray   { background: var(--surface-soft); color: var(--text-3); }
}

.todo-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.todo-title {
  font-size: 13px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-sub {
  font-size: 12px;
  color: var(--text-3);
}

.todo-go {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--text-3);
  transition: color .15s ease;
}

/* ===== 快捷入口卡片 ===== */
.action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.action-tile {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 52px;
  padding: 0 14px;
  border: 1px solid var(--border-1);
  border-radius: 10px;
  background: var(--bg-panel);
  color: var(--text-1);
  font-size: 13px;
  cursor: pointer;
  transition: border-color .15s ease, background-color .15s ease, transform .15s ease;

  &:hover {
    border-color: var(--color-primary);
    background: var(--bg-panel-hover);
    transform: translateY(-1px);

    .action-unfav { opacity: 1; }
  }

  &.is-fav .action-ic {
    background: rgba(214, 136, 31, .14);
    color: var(--color-warning);
  }
}

.action-ic {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;

  /* 业务色块：按入口类型着色，与待办色板保持一致 */
  &.b-blue   { background: var(--color-primary-soft); color: var(--color-primary); }
  &.b-green  { background: var(--color-success-soft); color: var(--color-success); }
  &.b-amber  { background: rgba(214, 136, 31, .14); color: var(--color-warning); }
  &.b-purple { background: rgba(122, 93, 176, .15); color: #7a5db0; }
  &.b-teal   { background: rgba(31, 138, 153, .14); color: #1f8a99; }
  &.b-indigo { background: rgba(63, 98, 176, .14); color: #3f62b0; }
  &.b-rose   { background: rgba(199, 92, 116, .14); color: #c75c74; }
  &.b-gray   { background: var(--surface-soft); color: var(--text-3); }
}

.action-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.action-unfav {
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  opacity: 0;
  transition: opacity .15s ease, color .15s ease, background-color .15s ease;

  &:hover { background: var(--color-danger); color: #fff; }
}

.action-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
  color: var(--text-3);
  font-size: 12px;

  .el-icon { font-size: 13px; color: var(--color-warning); }
}

@media (max-width: 1080px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .work-main { grid-template-columns: 1fr; grid-template-rows: none; grid-auto-rows: minmax(260px, auto); }
  .wk-alerts, .wk-low, .wk-todo, .wk-quick { grid-column: auto; grid-row: auto; }
}

@media (max-width: 720px) {
  .overview {
    align-items: flex-start;
    flex-direction: column;

    h1 {
      font-size: 24px;
    }
  }

  .stats-grid {
    grid-template-columns: 1fr;
  }

  .actions {
    grid-template-columns: 1fr;
  }
}

/* 库存告急卡片（在右列快捷入口下方，与左列待办等高对齐） */
.lowstock-card {
  margin-top: 0;
}

.lowstock-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 56px;
}

.lowstock-empty {
  padding: 4px 0 !important;
  min-height: 0 !important;
}

.ls-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  background: #fff7ea;
}

.ls-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ls-name {
  font-weight: 600;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ls-sub {
  font-size: 12px;
  color: #9ca3af;
}

.ls-qty {
  font-size: 13px;
  color: #b7791f;
  white-space: nowrap;
}

.ls-qty b {
  font-size: 15px;
}
</style>
