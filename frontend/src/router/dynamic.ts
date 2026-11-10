import { RouteRecordRaw } from 'vue-router'
import type { MenuItem } from '@/api/auth'

/** 通配 import.meta.glob 一次性收集所有 view */
const modules = import.meta.glob('../views/**/*.vue')
const Layout = () => import('@/layout/index.vue')

const hiddenBusinessRoutes: MenuItem[] = [
  { id: 900202, parentId: 0, name: '采购订单', path: '/purchase/po', component: 'purchase/po/list', icon: 'document', permCode: 'po.view', type: 'MENU', sort: 2, children: [] },
  { id: 900301, parentId: 0, name: '采购入库单', path: '/inbound/recv', component: 'inbound/recv/list', icon: 'box', permCode: 'recv.view', type: 'MENU', sort: 3, children: [] },
  { id: 900401, parentId: 0, name: '出库申请', path: '/outbound/req', component: 'outbound/req/list', icon: 'edit', permCode: 'oreq.view', type: 'MENU', sort: 4, children: [] },
  { id: 900402, parentId: 0, name: '材料出库', path: '/outbound/out', component: 'outbound/out/list', icon: 'truck', permCode: 'out.view', type: 'MENU', sort: 5, children: [] },
  { id: 900601, parentId: 0, name: '采购发票', path: '/invoice/list', component: 'invoice/list', icon: 'tickets', permCode: 'inv.view', type: 'MENU', sort: 6, children: [] },
  // 单据链/追溯：已取消侧边栏独立菜单，融入单据（点单据号 DocLink 跳此页）；permCode 留空→对所有登录用户保留路由
  { id: 900801, parentId: 0, name: '单据链查询', path: '/trace/chain', component: 'trace/chain', icon: 'share', permCode: '', type: 'MENU', sort: 0, children: [] }
]

/**
 * 把后端返回的菜单树转成 vue-router 路由数组。
 * 后端约定：DIR 类型的菜单 → component = 'Layout'；MENU 类型 → component 是相对路径（如 'purchase/po/list'）
 */
export function generateRoutes(menus: MenuItem[]): RouteRecordRaw[] {
  const routes: RouteRecordRaw[] = []
  const flatMenus = flattenMenus(menus)
  const authorizedPermCodes = new Set(flatMenus.map(m => m.permCode).filter(Boolean) as string[])
  const defaultPath = firstAccessibleMenuPath(menus) || '/404'

  // 主入口：根路径 + Layout 包裹所有业务路由
  const root: RouteRecordRaw = {
    path: '/',
    name: 'DynRoot',
    component: Layout as any,
    redirect: defaultPath,
    meta: { hidden: true },
    children: []
  }

  const registeredPaths = new Set<string>()
  const register = (route: RouteRecordRaw) => {
    if (!route.path || registeredPaths.has(route.path)) return
    root.children!.push(route)
    registeredPaths.add(route.path)
  }

  for (const m of flatMenus) {
    if (m.type === 'MENU' && m.path && m.component) {
      register(buildRoute(m))
    }
  }

  for (const m of flatMenus) {
    if (m.type !== 'DIR' || !m.path) continue
    const redirect = firstMenuPath(m)
    if (!redirect) continue
    register({
      path: m.path,
      name: 'M_DIR_' + m.id,
      redirect,
      meta: { title: m.name, icon: m.icon, hidden: true }
    })
  }

  for (const route of hiddenBusinessRoutes) {
    if (!route.path || registeredPaths.has(route.path)) continue
    if (route.permCode && !authorizedPermCodes.has(route.permCode)) continue
    const built = buildRoute(route)
    register({ ...built, meta: { ...built.meta, hidden: true } })
  }
  routes.push(root)

  // 兜底：未匹配 → 404
  routes.push({ path: '/:pathMatch(.*)*', name: 'DynNotFound', redirect: '/404', meta: { hidden: true } })
  return routes
}

function flattenMenus(menus: MenuItem[]): MenuItem[] {
  const rows: MenuItem[] = []
  const walk = (items?: MenuItem[]) => {
    for (const item of items || []) {
      rows.push(item)
      walk(item.children)
    }
  }
  walk(menus)
  return rows
}

function firstMenuPath(menu: MenuItem): string | undefined {
  for (const child of menu.children || []) {
    if (child.type === 'MENU' && child.path) return child.path
    const nested = firstMenuPath(child)
    if (nested) return nested
  }
  return undefined
}

function firstAccessibleMenuPath(menus: MenuItem[]): string | undefined {
  for (const menu of menus || []) {
    if (menu.type === 'MENU' && menu.path) return menu.path
    const childPath = firstAccessibleMenuPath(menu.children || [])
    if (childPath) return childPath
  }
  return undefined
}

function buildRoute(m: MenuItem): RouteRecordRaw {
  const compPath = `../views/${m.component}.vue`
  const loader = modules[compPath] || modules[`../views/${m.component}/index.vue`]
  return {
    path: m.path,
    name: 'M_' + m.id,
    component: loader || (() => import('@/views/error/404.vue')),
    meta: { title: m.name, icon: m.icon, permCode: m.permCode }
  }
}
