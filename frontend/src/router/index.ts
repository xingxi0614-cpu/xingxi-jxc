import { createRouter, createWebHashHistory, RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { generateRoutes } from './dynamic'
import { isMobileDevice, isForceDesktop, setForceDesktop } from '@/utils/device'

const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { hidden: true, title: '登录' }
  },
  {
    path: '/mobile',
    name: 'MobileWorkspace',
    component: () => import('@/views/mobile/App.vue'),
    meta: { hidden: true, title: '移动工作台' }
  },
  {
    path: '/mobile/login',
    name: 'MobileLogin',
    component: () => import('@/views/mobile/Login.vue'),
    meta: { hidden: true, title: '登录' }
  },
  {
    path: '/mobile/po',
    name: 'MobilePO',
    component: () => import('@/views/mobile/PurchaseOrder.vue'),
    meta: { hidden: true, title: '采购订单' }
  },
  {
    path: '/mobile/oreq',
    name: 'MobileOutRequest',
    component: () => import('@/views/mobile/OutRequest.vue'),
    meta: { hidden: true, title: '出库申请' }
  },
  {
    path: '/mobile/recv',
    name: 'MobileReceive',
    component: () => import('@/views/mobile/Receive.vue'),
    meta: { hidden: true, title: '采购入库' }
  },
  {
    path: '/mobile/out',
    name: 'MobileMaterialOut',
    component: () => import('@/views/mobile/MaterialOut.vue'),
    meta: { hidden: true, title: '材料出库' }
  },
  {
    path: '/mobile/loan',
    name: 'MobileLoan',
    component: () => import('@/views/mobile/Loan.vue'),
    meta: { hidden: true, title: '借料登记' }
  },
  {
    path: '/mobile/doc/:type/:id',
    name: 'MobileDocumentDetail',
    component: () => import('@/views/mobile/DocumentDetail.vue'),
    meta: { hidden: true, title: '移动单据详情' }
  },
  {
    path: '/lr-apply',
    name: 'MaterialReqApply',
    component: () => import('@/views/materialreq/Apply.vue'),
    meta: { hidden: true, title: '领料申请' }
  },
  {
    // 网点扫码录销售单(免登录,2026-08-21)。与 /lr-apply 同类:
    // 顶级路由、不走 ERP 布局、不需要登录态,靠 URL 里的 token 认身份。
    path: '/so-entry',
    name: 'SalesPublicEntry',
    component: () => import('@/views/sales/PublicEntry.vue'),
    meta: { hidden: true, title: '销售录单' }
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { hidden: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes: staticRoutes
})

let routesAdded = false
let routeSignature = ''

function menuSignature(menus: any[]): string {
  const rows: string[] = []
  const walk = (items: any[]) => {
    for (const item of items || []) {
      rows.push(`${item.id}:${item.path || ''}:${item.component || ''}:${item.type || ''}`)
      walk(item.children || [])
    }
  }
  walk(menus)
  return rows.join('|')
}

function resetDynamicRoutes() {
  router.getRoutes().forEach(route => {
    const name = route.name as string | undefined
    if (name && (name.startsWith('M_') || name === 'DynRoot' || name === 'DynNotFound')) {
      router.removeRoute(name)
    }
  })
  routesAdded = false
  routeSignature = ''
}

router.beforeEach(async (to, from, next) => {
  const t = to.meta.title as string
  document.title = t ? `${t} · 星嘻进销存` : '星嘻进销存'
  const user = useUserStore()
  // 领料申请免登录提交页：手机/电脑均直接放行（不跳移动端、不要求登录）
  if (to.path === '/lr-apply') return next()
  if (to.path === '/so-entry') return next()   // 网点扫码录单,免登录
  if (to.path === '/mobile' && import.meta.env.DEV && to.query.preview === '1') {
    return next()
  }

  // 手机设备自动进入移动端 /mobile（?desktop=1 强制电脑版，?desktop=0 取消强制）
  if (to.query.desktop === '1') setForceDesktop(true)
  else if (to.query.desktop === '0') setForceDesktop(false)
  if (isMobileDevice() && !isForceDesktop() && !to.path.startsWith('/mobile')) {
    const target = to.path === '/login' ? '/mobile/login' : '/mobile'
    return next({ path: target, query: to.path === '/login' ? to.query : {} })
  }

  if (!user.isLogin) {
    if (to.path === '/login' || to.path === '/mobile/login') return next()
    const isMobile = to.path.startsWith('/mobile')
    return next({ path: isMobile ? '/mobile/login' : '/login', query: { redirect: to.fullPath } })
  }

  if (!user.loaded) {
    try {
      await user.loadUserInfo()
    } catch {
      return next('/login')
    }
  }

  const nextSignature = menuSignature(user.menus)
  if (routesAdded && (!router.hasRoute('DynRoot') || routeSignature !== nextSignature)) {
    resetDynamicRoutes()
  }

  if (!routesAdded) {
    const dynRoutes = generateRoutes(user.menus)
    dynRoutes.forEach(r => router.addRoute(r))
    routesAdded = true
    routeSignature = nextSignature
    return next({ ...to, replace: true })
  }

  // 已登录还去登录页 → 送回 ERP 首页('/' 按本人权限落到第一个能看的菜单)。
  if (to.path === '/login') return next('/')

  next()
})

// 换版后旧页面引用的 chunk 已被替换，懒加载会报 "Failed to fetch dynamically imported module"。
// 此时自动刷新一次取最新版本（10 秒内只刷一次，避免循环）。
router.onError((error: any) => {
  const msg = String(error?.message || '')
  if (/dynamically imported module|Importing a module script failed|Failed to fetch/i.test(msg)) {
    const KEY = 'jxc_chunk_reload_at'
    const last = Number(sessionStorage.getItem(KEY) || 0)
    if (Date.now() - last > 10000) {
      sessionStorage.setItem(KEY, String(Date.now()))
      window.location.reload()
    }
  }
})

export default router

export function resetRouter() {
  resetDynamicRoutes()
}
