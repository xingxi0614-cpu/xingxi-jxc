<template>
  <div class="page-tabs">
    <div ref="stripRef" class="tab-strip" @wheel="onWheel">
      <button
        v-for="tab in tabs.tabs"
        :key="tab.fullPath"
        type="button"
        class="work-tab"
        :class="{ active: tab.fullPath === route.fullPath }"
        :data-active="tab.fullPath === route.fullPath || undefined"
        :title="tab.title"
        @click="go(tab.fullPath)"
      >
        <span>{{ tab.title }}</span>
        <el-icon v-if="tab.closable" class="close-tab" @click.stop="close(tab.fullPath)">
          <Close />
        </el-icon>
      </button>
    </div>
    <div class="tab-actions">
      <el-tooltip :content="isFav ? '已收藏到工作台' : '收藏到工作台快捷入口'" placement="bottom">
        <button
          v-if="canFav"
          type="button"
          class="tab-icon-btn"
          :class="{ 'is-fav': isFav }"
          @click="toggleFav"
        >
          <el-icon><component :is="isFav ? 'StarFilled' : 'Star'" /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="刷新当前页" placement="bottom">
        <button type="button" class="tab-icon-btn" @click="refresh">
          <el-icon><RefreshRight /></el-icon>
        </button>
      </el-tooltip>
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="tab-icon-btn">
          <el-icon><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="close-current">关闭当前</el-dropdown-item>
            <el-dropdown-item command="close-others">关闭其他</el-dropdown-item>
            <el-dropdown-item command="close-all">关闭全部</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Close, MoreFilled, RefreshRight } from '@/icons'
import { useRoute, useRouter } from 'vue-router'
import { useTabsStore } from '@/stores/tabs'
import { useFavoritesStore } from '@/stores/favorites'

const tabs = useTabsStore()
const route = useRoute()
const router = useRouter()
const favorites = useFavoritesStore()

onMounted(() => favorites.load())

// 页签多到一行放不下时：不显示滚动条(38px 高的页签栏里滚动条会直接压在页签文字上)，
// 改为 ①鼠标滚轮横向滚动 ②切换页面时自动把当前页签滚进可视区。
const stripRef = ref<HTMLElement>()
function onWheel(e: WheelEvent) {
  const el = stripRef.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  // 触控板横向滑动本身就带 deltaX，交给浏览器；只把鼠标滚轮的纵向滚动转成横向
  if (Math.abs(e.deltaX) >= Math.abs(e.deltaY)) return
  e.preventDefault()
  el.scrollLeft += e.deltaY
}
function revealActive() {
  nextTick(() => {
    stripRef.value?.querySelector<HTMLElement>('[data-active]')
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  })
}
watch(() => [route.fullPath, tabs.tabs.length], revealActive, { immediate: true })

// 当前页是否可收藏（首页/待办本身不收藏）
const canFav = computed(() => !['/work/home', '/work/todo'].includes(route.path))
const isFav = computed(() => favorites.has(route.path))
function currentTitle() {
  const t = tabs.tabs.find(x => x.fullPath === route.fullPath)
  return t?.title || (route.meta?.title as string) || '未命名页面'
}
function toggleFav() {
  favorites.toggle({ label: currentTitle(), path: route.path, icon: 'StarFilled' })
}

function go(fullPath: string) {
  if (fullPath !== route.fullPath) router.push(fullPath)
}

function close(fullPath: string) {
  const next = tabs.close(fullPath)
  if (fullPath === route.fullPath && next) router.push(next.fullPath)
}

function refresh() {
  router.go(0)
}

function handleCommand(command: string | number | object) {
  if (command === 'close-current') {
    close(route.fullPath)
    return
  }
  if (command === 'close-others') {
    tabs.closeOthers(route.fullPath)
    return
  }
  if (command === 'close-all') {
    tabs.closeAll()
    router.push('/work/home')
  }
}
</script>

<style scoped lang="scss">
.page-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 38px;
  width: 100%;
  min-width: 0;
  height: 38px;
  padding: 0 14px 0 16px;
  overflow: hidden;
  border-bottom: 1px solid var(--border-1);
  background: var(--surface-soft);
}

.tab-strip {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  flex: 1;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.work-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  max-width: 168px;
  flex: 0 0 auto;
  border: 1px solid transparent;
  border-radius: 6px 6px 0 0;
  padding: 0 9px;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
}

.work-tab span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.work-tab:hover {
  color: var(--text-1);
  background: var(--bg-panel-hover);
}

.work-tab.active {
  border-color: var(--color-primary);
  border-bottom-color: var(--bg-panel);
  background: var(--bg-panel);
  color: var(--text-1);
  font-weight: 600;
}

.close-tab {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  color: var(--text-3);
}

.close-tab:hover {
  background: var(--bg-panel-hover);
  color: var(--text-1);
}

.tab-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 4px;
}

.tab-icon-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-1);
  border-radius: 6px;
  background: var(--bg-panel);
  color: var(--text-2);
  cursor: pointer;
}

.tab-icon-btn:hover {
  color: var(--text-1);
  border-color: var(--color-primary);
}

.tab-icon-btn.is-fav {
  color: var(--color-warning);
  border-color: var(--color-warning);
}
</style>
