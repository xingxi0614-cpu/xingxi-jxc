<template>
  <div class="sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="sidebar-brand">
      <div class="brand-logo on-dark" :class="{ mark: collapsed }" aria-label="星嘻进销存">
        <img :src="collapsed ? '/logo-mark.svg' : '/logo-dark.svg'" alt="星嘻进销存" />
      </div>
      <el-tooltip :content="collapsed ? '展开菜单' : '收起菜单'" placement="right">
        <button
          type="button"
          class="collapse-btn"
          :aria-label="collapsed ? '展开菜单' : '收起菜单'"
          :title="collapsed ? '展开菜单' : '收起菜单'"
          @click="$emit('toggle')"
        >
          <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <div class="sidebar-cols">
      <!-- 一级图标栏 -->
      <nav class="rail" aria-label="一级菜单">
        <el-tooltip
          v-for="m in compactMenus"
          :key="m.id"
          :content="m.name"
          placement="right"
          :show-after="160"
          effect="dark"
          :disabled="!collapsed"
        >
          <button
            type="button"
            class="rail-item"
            :class="{ active: m.id === activeGroupId }"
            :aria-label="m.name"
            @click="selectGroup(m)"
          >
            <el-icon :style="{ color: groupIconColor(m) }"><component :is="iconComp(m.icon, m.path)" /></el-icon>
            <span class="rail-label">{{ m.name }}</span>
          </button>
        </el-tooltip>
      </nav>

      <!-- 二级菜单面板（常驻；折叠时隐藏） -->
      <div v-if="!collapsed" class="panel">
        <div class="panel-title">{{ activeGroup?.name }}</div>
        <nav class="panel-list" aria-label="二级菜单">
          <template v-if="activeGroupChildren.length">
            <RouterLink
              v-for="sub in activeGroupChildren"
              :key="sub.id"
              :to="sub.path"
              class="panel-item"
              :class="{ active: isActivePath(sub.path) }"
              @click="closePanelAfterNav"
            >
              <el-icon :style="{ color: groupIconColor(sub) }"><component :is="iconComp(sub.icon, sub.path)" /></el-icon>
              <span>{{ sub.name }}</span>
            </RouterLink>
          </template>
          <RouterLink
            v-else-if="activeGroup?.path"
            :to="activeGroup.path"
            class="panel-item"
            :class="{ active: isActivePath(activeGroup.path) }"
            @click="closePanelAfterNav"
          >
            <el-icon><component :is="iconComp(activeGroup.icon, activeGroup.path)" /></el-icon>
            <span>{{ activeGroup.name }}</span>
          </RouterLink>
        </nav>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { Expand, Fold } from '@/icons'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const menus = computed(() => user.menus)
const compactMenus = computed(() => menus.value.map((m: any) => ({
  id: m.id,
  name: m.name,
  icon: m.icon || m.children?.[0]?.icon || 'document',
  path: firstNavigablePath(m),
  children: m.children || []
})).filter((m: any) => !!m.path))

const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ (e: 'toggle'): void; (e: 'update:collapsed', v: boolean): void }>()

// 侧边栏在浅/深两套模式下都是深底（浅色=品牌蓝），Logo 统一加白色托底卡片（.on-dark），不再随主题切换。

const iconMap: Record<string, string> = {
  desktop: 'Monitor',
  home: 'House',
  check: 'CircleCheck',
  shopping: 'ShoppingCart',
  edit: 'EditPen',
  document: 'Document',
  aim: 'Aim',
  box: 'Box',
  time: 'Timer',
  truck: 'Van',
  database: 'DataLine',
  list: 'List',
  tickets: 'Tickets',
  connection: 'Connection',
  warning: 'Warning',
  money: 'Coin',
  share: 'Share',
  'data-board': 'DataBoard',
  'data-line': 'DataLine',
  'data-analysis': 'DataAnalysis',
  setting: 'Setting',
  user: 'User',
  avatar: 'Avatar',
  'office-building': 'OfficeBuilding',
  menu: 'Menu',
  'document-copy': 'DocumentCopy',
  tools: 'Tools',
  collection: 'Collection',
  shop: 'Shop',
  house: 'House',
  reading: 'Reading',
  guide: 'Guide',
  histogram: 'Histogram',
  memo: 'Memo',
  goods: 'Goods'
}

/** 按页面路径精修图标，让语义更直观（不依赖数据库的图标字段） */
const PATH_ICON: Record<string, string> = {
  '/report/po-tracking': 'DataAnalysis',      // 采购订单跟踪 — 报表分析（与「追溯」的链路图标区分）
  '/report/estimate': 'Money',               // 暂估清单 — 金额估算
  '/report/balance': 'Histogram',            // 现存量表 — 库存量
  '/report/in-out-summary': 'Tickets',       // 收发存汇总表
  '/report/inventory-ledger': 'Reading',     // 存货明细账(按公司,逐笔)
  '/report/out-summary': 'Van',              // 出库汇总表
  '/report/project-material-plan': 'Memo'    // 工程项目材料计划
}

function iconComp(name: string, path?: string) {
  if (path && PATH_ICON[path]) return PATH_ICON[path]
  return iconMap[name] || 'Document'
}

/** 按模块分组为图标注入颜色，辨识度更高 */
// 注：图标色只用于侧边栏（深底）。为在深品牌蓝/深色底上更醒目，整体调亮一档。
const GROUP_COLORS: Record<number, string> = {
  100: '#6ea7da',   // 工作台 — 亮蓝
  200: '#e3ad62',   // 采购管理 — 暖琥珀
  300: '#7cba68',   // 入库管理 — 青绿
  400: '#dc9063',   // 出库管理 — 暖铜
  500: '#54bca6',   // 库存 — 翠绿
  600: '#b08fc6',   // 发票管理 — 暖紫
  700: '#85a0c6',   // 库存参考 — 灰蓝
  800: '#bca992',   // 追溯 — 暖灰
  900: '#7493c2',   // 报表中心 — 蓝灰
  1000: '#ad9b86',  // 系统管理 — 暖炭
  1200: '#94b6c8',  // 帮助中心 — 暖灰蓝
}

function groupIconColor(menu: any): string {
  const groupId = menu.parentId || menu.id
  return GROUP_COLORS[groupId] || 'inherit'
}

function firstNavigablePath(menu: any): string {
  if (menu.type === 'MENU' && menu.path) return menu.path
  for (const child of menu.children || []) {
    const path = firstNavigablePath(child)
    if (path) return path
  }
  return ''
}

function goCompact(menu: any) {
  if (menu.path) router.push(menu.path)
}

function isCompactActive(menu: any) {
  if (menu.path && route.path === menu.path) return true
  return (menu.children || []).some((sub: any) => sub.path === route.path)
}

// ===== 双列侧边栏：左一级图标栏 + 右二级面板 =====
function isActivePath(path: string) {
  if (!path) return false
  return route.path === path || route.path.startsWith(path + '/')
}

/** 当前路由所属的一级分组 id */
function groupIdForRoute(path: string): number | null {
  for (const m of compactMenus.value) {
    if (m.path === path) return m.id
    if ((m.children || []).some((c: any) => c.path && (path === c.path || path.startsWith(c.path + '/')))) return m.id
  }
  return null
}

const activeGroupId = ref<number | null>(null)
function ensureActiveGroup() {
  const matched = groupIdForRoute(route.path)
  if (matched != null) activeGroupId.value = matched
  else if (activeGroupId.value == null && compactMenus.value.length) activeGroupId.value = compactMenus.value[0].id
}
const activeGroup = computed(() => compactMenus.value.find((m: any) => m.id === activeGroupId.value) || null)
const activeGroupChildren = computed(() => (activeGroup.value?.children || []).filter((c: any) => !!c.path))

function selectGroup(m: any) {
  const hasChildren = (m.children || []).some((c: any) => !!c.path)
  if (!hasChildren) {                              // 叶子一级 → 直接进入，保持展开（面板显示该项）
    activeGroupId.value = m.id
    if (m.path) router.push(m.path)
    return
  }
  if (!props.collapsed && activeGroupId.value === m.id) {
    emit('update:collapsed', true)                 // 再点已展开的一级 → 收起二级面板
  } else {
    activeGroupId.value = m.id
    emit('update:collapsed', false)                // 点一级 → 展开显示其二级
  }
}
function closePanelAfterNav() {
  // 点二级导航后保持二级面板展开；是否折叠由用户用左上角折叠按钮自行决定
}

watch(() => route.path, ensureActiveGroup, { immediate: true })
watch(compactMenus, ensureActiveGroup)
</script>

<style scoped lang="scss">
.sidebar {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--sidebar-bg);
  transition: background-color .24s ease;
}

/* ===== 双列：左一级图标栏 + 右二级面板 ===== */
.sidebar-cols {
  flex: 1;
  display: flex;
  min-height: 0;
}

.rail {
  flex: 0 0 72px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 3px;
  padding: 10px 8px 18px;
  overflow-y: auto;
  overflow-x: hidden;
  border-right: 1px solid var(--sidebar-divider);
  /* 图标多一屏放不下时会出现滚动条,深色底上像一条竖线很别扭(10-16 用户反馈):隐藏滚动条,滚轮照常能翻 */
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.rail-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 2px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--sidebar-text-muted);
  cursor: pointer;
  transition: background-color .15s ease, color .15s ease;

  .el-icon { font-size: 19px; }

  .rail-label {
    font-size: 11px;
    line-height: 1.1;
    text-align: center;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 100%;
  }

  &:hover { background: var(--sidebar-active-bg); color: var(--sidebar-text); }

  &.active {
    position: relative;
    background: var(--sidebar-active-bg);
    color: var(--sidebar-active-text);
    font-weight: 500;

  }
}

.panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 10px 10px 16px;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none; /* 同图标栏:隐藏滚动条,滚轮照常能翻 */

  &::-webkit-scrollbar {
    display: none;
  }
}

.panel-title {
  font-size: 12px;
  color: var(--sidebar-text-muted);
  padding: 4px 10px 8px;
  letter-spacing: .3px;
}

.panel-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.panel-item {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 38px;
  padding: 0 10px;
  border-radius: 9px;
  color: var(--sidebar-text);
  font-size: 13.5px;
  text-decoration: none;
  transition: background-color .15s ease, color .15s ease;

  .el-icon { font-size: 16px; flex: 0 0 auto; }
  span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

  &:hover { background: var(--sidebar-active-bg); color: var(--sidebar-active-text); }

  &.active {
    position: relative;
    background: var(--sidebar-active-bg);
    color: var(--sidebar-active-text);
    font-weight: 500;

  }
}

.sidebar.is-collapsed .rail {
  flex: 1;
  border-right: 0;
}
.sidebar.is-collapsed .rail-label { display: none; }
.sidebar.is-collapsed .rail-item { padding: 9px 2px; }

/* 浅色模式 — 深品牌蓝侧边栏 + 顶部一层极淡白光增加层次 */
:global([data-theme='light']) .sidebar,
:global(:root:not([data-theme='dark'])) .sidebar {
  background:
    radial-gradient(120% 55% at 50% 0, rgba(255, 255, 255, .06), transparent 60%),
    var(--sidebar-bg);
}

/* 深色模式 — 深暖色侧边栏 */
:global([data-theme='dark']) .sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-1);
}

.sidebar-brand {
  position: sticky;
  top: 0;
  z-index: 5;
  height: 64px;
  flex: 0 0 64px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px 0 14px;
  border-bottom: 1px solid var(--sidebar-divider);
  background: var(--sidebar-bg);
  transition: background-color .24s ease, border-color .24s ease;
}

.brand-logo {
  width: 148px;
  height: 42px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 6px;
  background: transparent;
  transition: width .18s ease, height .18s ease, border-radius .18s ease;

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    display: block;
  }
}

/* 深色模式：官方 Logo 自带白底，这里给它一个干净的白色卡片托底（圆角 + 内边距 + 细边），
   既保持真实 Logo，又让白色看起来是有意为之而非未样式化的白块。 */
.brand-logo.on-dark {
  background: #ffffff;
  border-radius: 8px;
  padding: 3px 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .28), inset 0 0 0 1px rgba(255, 255, 255, .6);
}


.brand-logo.mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;

  img {
    width: 34px;
    height: 34px;
    max-width: 34px;
    object-fit: contain;
  }
}

.sidebar.is-collapsed {
  .sidebar-brand {
    flex-direction: column;
    justify-content: center;
    gap: 10px;
    padding: 12px 0 10px;
    height: auto;
  }

  .collapse-btn {
    position: static;
    margin: 0 auto;
    width: 30px;
    height: 30px;
    border-radius: 8px;
  }
}

.compact-menu {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 8px 18px;
}

.compact-item {
  position: relative;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--sidebar-text-muted);
  cursor: pointer;
  transition: background-color .16s ease, color .16s ease;

  &:hover {
    background: var(--sidebar-active-bg);
    color: var(--sidebar-active-text);
  }

  &.active {
    background: var(--sidebar-active-bg);
    color: var(--sidebar-active-text);
    font-weight: 600;

  }
}

.collapse-btn {
  margin-left: auto;
  width: 30px;
  height: 30px;
  border: 1px solid var(--sidebar-divider);
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--sidebar-text-muted);
  cursor: pointer;
  transition: background-color .16s ease, border-color .16s ease, color .16s ease;

  &:hover {
    background: var(--sidebar-active-bg);
    border-color: var(--color-accent);
    color: var(--sidebar-active-text);
  }
}

.el-menu {
  border-right: none;
  padding: 8px 8px 18px;
  background: transparent !important;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  height: 40px;
  line-height: 40px;
  border-radius: 8px;
  margin: 2px 0;
  color: var(--sidebar-text) !important;
  font-weight: 500;
  font-size: 13.5px;
  transition: background-color .16s ease, color .16s ease;
  padding-left: 12px !important;
}

:deep(.el-menu-item:hover),
:deep(.el-sub-menu__title:hover) {
  background: var(--sidebar-active-bg) !important;
  color: var(--sidebar-active-text) !important;
}

:deep(.el-menu-item.is-active) {
  position: relative;
  background: var(--sidebar-active-bg) !important;
  color: var(--sidebar-active-text) !important;
  font-weight: 600;

}

:deep(.el-sub-menu .el-menu-item) {
  padding-left: 36px !important;
  height: 36px;
  line-height: 36px;
  font-size: 13px;
  color: var(--sidebar-text-muted) !important;
}

:deep(.el-sub-menu .el-menu-item.is-active),
:deep(.el-sub-menu .el-menu-item:hover) {
  color: var(--sidebar-active-text) !important;
}

:deep(.el-icon) {
  font-size: 16px;
}

:deep(.el-menu--inline) {
  background: transparent !important;
}
</style>
