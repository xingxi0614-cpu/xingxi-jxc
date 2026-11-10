import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed, ref } from 'vue'

export interface WorkTab {
  fullPath: string
  path: string
  title: string
  closable: boolean
}

const HOME_TAB: WorkTab = {
  fullPath: '/work/home',
  path: '/work/home',
  title: '工作台',
  closable: false
}

const STORAGE_KEY = 'scm_work_tabs'

export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<WorkTab[]>(loadTabs())

  const cachedKeys = computed(() => tabs.value.map(tab => tab.fullPath))

  function addFromRoute(route: RouteLocationNormalizedLoaded) {
    if (!isWorkRoute(route)) return
    const fullPath = route.fullPath || route.path
    const title = route.meta?.title ? String(route.meta.title) : titleFromPath(route.path)
    // 同一页面（path 相同）只保留一个标签：带不同查询参数跳转时更新它的 fullPath，而不是新开标签
    const existing = tabs.value.find(tab => tab.path === route.path)
    if (existing) {
      existing.title = title
      existing.fullPath = fullPath
      persist()
      return
    }
    tabs.value.push({
      fullPath,
      path: route.path,
      title,
      closable: route.path !== HOME_TAB.path
    })
    persist()
  }

  function close(fullPath: string) {
    const tab = tabs.value.find(item => item.fullPath === fullPath)
    if (!tab?.closable) return nextAfter(fullPath)
    tabs.value = tabs.value.filter(item => item.fullPath !== fullPath)
    ensureHome()
    persist()
    return nextAfter(fullPath)
  }

  function closeOthers(fullPath: string) {
    tabs.value = tabs.value.filter(item => !item.closable || item.fullPath === fullPath)
    ensureHome()
    persist()
  }

  function closeAll() {
    tabs.value = [HOME_TAB]
    persist()
  }

  function reset() {
    tabs.value = [HOME_TAB]
    persist()
  }

  function nextAfter(fullPath: string) {
    const index = tabs.value.findIndex(item => item.fullPath === fullPath)
    if (index >= 0) return tabs.value[index]
    return tabs.value[tabs.value.length - 1] || HOME_TAB
  }

  function ensureHome() {
    if (!tabs.value.some(tab => tab.fullPath === HOME_TAB.fullPath)) {
      tabs.value.unshift(HOME_TAB)
    }
  }

  function persist() {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tabs.value))
  }

  return { tabs, cachedKeys, addFromRoute, close, closeOthers, closeAll, reset }
})

function loadTabs() {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]') as WorkTab[]
    const valid = parsed.filter(tab => tab?.fullPath && tab?.path && tab?.title)
    if (!valid.some(tab => tab.fullPath === HOME_TAB.fullPath)) valid.unshift(HOME_TAB)
    return valid
  } catch {
    return [HOME_TAB]
  }
}

function isWorkRoute(route: RouteLocationNormalizedLoaded) {
  return route.path !== '/login'
    && route.path !== '/404'
    && !route.path.startsWith('/mobile')
}

function titleFromPath(path: string) {
  const last = path.split('/').filter(Boolean).pop()
  return last || '工作页'
}
