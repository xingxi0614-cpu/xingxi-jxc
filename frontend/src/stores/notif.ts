import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notifApi } from '@/api'

export const useNotifStore = defineStore('notif', () => {
  const unread = ref(0)
  const list = ref<any[]>([])

  async function refreshCount() {
    try {
      const r: any = await notifApi.unreadCount()
      unread.value = r.count
    } catch {}
  }

  /**
   * 拉待办列表(最多 50 条)。
   *
   * 🔴 这里【绝不能】顺手改 unread —— 列表是封顶 50 的,拿它的长度当未读数,
   *    等于把"最多 50"当成了真实值。真实未读数只能由 refreshCount() 维护
   *    (countMyUnread 不封顶,和 listMyUnread 的过滤条件逐条一致)。
   *    原来这里覆盖 unread,导致铃铛显示 ≤50、模块角标显示 ≤20、
   *    真实值是第三个数,同屏三个数字打架,按铃铛判断"还剩多少活"会系统性低估。
   */
  async function loadList() {
    list.value = await notifApi.my(50) as any
  }

  async function read(id: number) {
    await notifApi.read(id)
    // 列表和未读数是两个来源,标已读后【两个都要刷】,
    // 否则铃铛要等 30 秒轮询才降下来(loadList 已不再维护 unread)
    await Promise.all([loadList(), refreshCount()])
  }

  async function readAll() {
    await notifApi.readAll()
    list.value = []
    unread.value = 0
  }

  return { unread, list, refreshCount, loadList, read, readAll }
})
