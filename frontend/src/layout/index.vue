<template>
  <el-container class="layout">
    <el-aside :width="`${asideWidth}px`" class="aside" :class="{ collapsed: sidebarCollapsed, resizing: isResizing }">
      <Sidebar v-model:collapsed="sidebarCollapsed" @toggle="toggleSidebar" />
      <button
        type="button"
        class="aside-resizer"
        aria-label="拖动调整侧边栏宽度"
        title="拖动调整侧边栏宽度，双击恢复默认"
        @mousedown.prevent="startResize"
        @dblclick.prevent="resetSidebarWidth"
      />
    </el-aside>
    <el-container class="workspace">
      <el-header v-if="!isHome" height="56px" class="header">
        <Header />
      </el-header>
      <PageTabs v-if="showTabs" />
      <el-main class="main">
        <RouterView v-slot="{ Component, route }">
          <!-- 不要用 <Transition mode="out-in"> 包 KeepAlive+动态key:导航 A→B→A 回来时 out-in 的
               enter 回调会与 KeepAlive 缓存实例错位,导致 router-view 永久空白、必须刷新(踩过的坑)。
               保留 KeepAlive(多标签缓存)+ fullPath key,去掉过渡即可稳定。 -->
          <KeepAlive :max="20">
            <component :is="Component" :key="route.fullPath" />
          </KeepAlive>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import Sidebar from './components/Sidebar.vue'
import Header from './components/Header.vue'
import PageTabs from './components/PageTabs.vue'
// 操作助手(2026-08-21 重做):只答「怎么操作」,未配 AI Key 时组件内部整体不渲染
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'

const route = useRoute()
const tabs = useTabsStore()

// 空闲 30 分钟自动退出已提到全局 App.vue（手机端不走本布局，挂这里会漏）
const sidebarCollapsed = ref(localStorage.getItem('jxc_sidebar_collapsed') === '1')   // 默认展开（一级图标栏 + 二级面板都显示）；记住用户自己的折叠选择
const sidebarWidth = ref(232)
const isResizing = ref(false)
const resizingWidth = ref(0)
const asideWidth = computed(() => {
  if (isResizing.value) {
    return sidebarCollapsed.value ? COLLAPSED_WIDTH : clampWidth(resizingWidth.value || sidebarWidth.value)
  }
  return sidebarCollapsed.value ? COLLAPSED_WIDTH : sidebarWidth.value
})
const isHome = computed(() => route.path === '/work/home')
const showTabs = computed(() => route.path !== '/login'
  && route.path !== '/404'
  && route.path !== '/work/home'
  && !route.path.startsWith('/mobile'))

const COLLAPSED_WIDTH = 72
const MIN_WIDTH = 232
const MAX_WIDTH = 340
const DEFAULT_WIDTH = 232
const COLLAPSE_TRIGGER = 208
const EXPAND_TRIGGER = 224
let resizeFrame = 0

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
// 记住用户的折叠/展开选择
watch(sidebarCollapsed, v => localStorage.setItem('jxc_sidebar_collapsed', v ? '1' : '0'))

function clampWidth(width: number) {
  return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width))
}

function startResize(event: MouseEvent) {
  isResizing.value = true
  resizingWidth.value = asideWidth.value
  applyResize(event.clientX)
  document.body.classList.add('is-sidebar-resizing')
  window.addEventListener('mousemove', onResize)
  window.addEventListener('mouseup', stopResize)
}

function onResize(event: MouseEvent) {
  const nextX = event.clientX
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => applyResize(nextX))
}

function applyResize(clientX: number) {
  const nextWidth = Math.max(COLLAPSED_WIDTH, Math.min(MAX_WIDTH, clientX))
  resizingWidth.value = nextWidth
  if (nextWidth <= COLLAPSE_TRIGGER) {
    sidebarCollapsed.value = true
    return
  }
  if (nextWidth < EXPAND_TRIGGER) return
  sidebarCollapsed.value = false
  sidebarWidth.value = clampWidth(nextWidth)
}

function stopResize() {
  if (resizeFrame) {
    cancelAnimationFrame(resizeFrame)
    resizeFrame = 0
  }
  if (sidebarCollapsed.value || resizingWidth.value <= COLLAPSE_TRIGGER) {
    sidebarCollapsed.value = true
  } else {
    sidebarCollapsed.value = false
    sidebarWidth.value = clampWidth(resizingWidth.value || sidebarWidth.value)
  }
  resizingWidth.value = 0
  isResizing.value = false
  document.body.classList.remove('is-sidebar-resizing')
  window.removeEventListener('mousemove', onResize)
  window.removeEventListener('mouseup', stopResize)
}

function resetSidebarWidth() {
  sidebarCollapsed.value = false
  sidebarWidth.value = DEFAULT_WIDTH
}

onUnmounted(stopResize)

watch(() => route.fullPath, () => {
  if (showTabs.value) tabs.addFromRoute(route)
}, { immediate: true })
</script>

<style scoped lang="scss">
.layout {
  height: 100vh;
  background:
    radial-gradient(circle at 14% 8%, rgba(200, 149, 74, .10), transparent 28%),
    radial-gradient(circle at 88% 2%, rgba(45, 59, 79, .08), transparent 34%),
    var(--bg-page);
}

.aside {
  position: relative;
  flex: 0 0 auto;
  width: var(--el-aside-width);
  min-width: var(--el-aside-width);
  max-width: var(--el-aside-width);
  background: var(--sidebar-bg);
  overflow: visible;
  box-shadow: 1px 0 0 0 var(--border-1);
  border-right: none;
  transition: width .18s ease, min-width .18s ease, max-width .18s ease, background-color .24s ease;
}

.aside.resizing {
  transition: none;
}

.aside-resizer {
  position: absolute;
  top: 0;
  right: -4px;
  width: 8px;
  height: 100%;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: col-resize;
  z-index: 8;
}

.aside-resizer::after {
  content: "";
  position: absolute;
  top: 92px;
  right: 1px;
  width: 3px;
  height: 46px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 253, 248, .82), rgba(200, 149, 74, .42));
  box-shadow: 0 6px 14px rgba(200, 149, 74, .16);
  opacity: 0;
  transition: opacity .16s ease, transform .16s ease, box-shadow .16s ease;
}

.aside-resizer:hover::after,
.aside.resizing .aside-resizer::after {
  opacity: .86;
  transform: scaleY(1.12);
  box-shadow: 0 10px 22px rgba(200, 149, 74, .20);
}

.aside.collapsed .aside-resizer::after {
  top: 118px;
  background: rgba(200, 149, 74, .18);
}

:global(body.is-sidebar-resizing) {
  cursor: col-resize;
  user-select: none;
}

.workspace {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  min-width: 0;
  overflow: hidden;
}

.header {
  height: 56px;
  background: var(--app-header-bg);
  border-bottom: 1px solid var(--app-header-border);
  display: flex;
  align-items: center;
  padding: 0 20px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  z-index: 2;
  transition: background-color .24s ease, border-color .24s ease;
}

.main {
  flex: 1 1 auto;
  background: var(--main-bg);
  padding: 0;
  min-width: 0;
  overflow: auto;
  transition: background-color .24s ease;
}

.fade-enter-active {
  transition: opacity .24s ease, transform .28s cubic-bezier(.34, 1.56, .64, 1);
}

.fade-leave-active {
  transition: opacity .14s ease, transform .14s ease;
}

.fade-enter-from {
  opacity: 0;
  transform: translateY(8px) scale(.98);
}

.fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 页面路由切换动画 */
.page-fade-enter-active {
  transition: opacity .18s ease, transform .22s cubic-bezier(.4, 0, .2, 1);
}

.page-fade-leave-active {
  transition: opacity .12s ease, transform .12s ease;
  position: absolute;
  width: 100%;
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

:global(html[data-theme='dark']) {
  .aside {
    background: var(--sidebar-bg);
    border-right: 1px solid var(--border-1);
    box-shadow: none;
  }

  .header {
    background: var(--app-header-bg);
    backdrop-filter: blur(12px);
    border-bottom-color: var(--app-header-border);
  }

  .main {
    background:
      radial-gradient(circle at 20% 0, rgba(200, 149, 74, .04), transparent 30%),
      radial-gradient(circle at 72% 10%, rgba(232, 221, 202, .08), transparent 36%),
      var(--main-bg);
  }
}
</style>
