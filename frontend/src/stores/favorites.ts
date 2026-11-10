import { defineStore } from 'pinia'
import { ref } from 'vue'
import { prefApi } from '@/api'
import { useUserStore } from './user'

export interface FavItem {
  label: string
  path: string
  icon: string
}

/**
 * 功能页收藏(页签上点 ☆ 收的)。
 *
 * 【存服务器不存浏览器】存 localStorage 换台电脑就没了。
 * 与模块收藏、看板布局走同一套 /user-pref,跟着账号走。
 * 老用户浏览器里已收藏的会在首次加载时自动搬上去,不让人白收一遍。
 */
export const useFavoritesStore = defineStore('favorites', () => {
  const user = useUserStore()
  const items = ref<FavItem[]>([])

  const PREF_KEY = 'fav.pages'
  /** 老的本地键,只用于一次性迁移 */
  function legacyKey() {
    const uid = user.userInfo?.username || 'anon'
    return `jxc_fav_actions_${uid}`
  }
  /** 服务器数据回来之前不写回,否则会用空数组把人家收藏的清掉 */
  const loaded = ref(false)

  async function load() {
    let got = false
    try {
      const all: any = await prefApi.all()
      const raw = all?.[PREF_KEY]
      if (raw) {
        const v = JSON.parse(raw)
        if (Array.isArray(v)) { items.value = v; got = true }
      }
    } catch { /* 接口挂了就当没收藏,不弹错也不写回 */ }

    // 服务器没有 → 把浏览器里的老收藏搬上去(搬完删本地)
    if (!got) {
      try {
        const legacy = localStorage.getItem(legacyKey())
        if (legacy) {
          const v = JSON.parse(legacy)
          if (Array.isArray(v) && v.length) {
            items.value = v
            await prefApi.put(PREF_KEY, v).catch(() => {})
            localStorage.removeItem(legacyKey())
          }
        }
      } catch { /* 老数据坏了就算了 */ }
    }
    loaded.value = true
  }

  function persist() {
    if (!loaded.value) return
    prefApi.put(PREF_KEY, items.value).catch(() => { /* 存不上不打断操作 */ })
  }

  function has(path: string) {
    return items.value.some(i => i.path === path)
  }

  function toggle(item: FavItem) {
    const idx = items.value.findIndex(i => i.path === item.path)
    if (idx >= 0) items.value.splice(idx, 1)
    else items.value.unshift({ label: item.label, path: item.path, icon: item.icon || 'StarFilled' })
    persist()
  }

  function remove(path: string) {
    const idx = items.value.findIndex(i => i.path === path)
    if (idx >= 0) {
      items.value.splice(idx, 1)
      persist()
    }
  }

  return { items, load, has, toggle, remove }
})
