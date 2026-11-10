import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 空闲自动退出登录。
 *
 * 后端 Sa-Token 配置了 active-timeout=30 分钟（无请求即过期），但页面里有
 * 通知/在线数等后台轮询会持续“续命”会话，导致空闲超时形同虚设。这里以
 * 真实用户操作（鼠标/键盘/触摸）为准做空闲检测：连续 30 分钟无操作即自动
 * 退出登录，后台轮询不算操作。
 */
const IDLE_MS = 30 * 60 * 1000  // 与后端 active-timeout 保持一致：30 分钟
const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'scroll', 'touchstart', 'click', 'mousemove']

export function useIdleLogout() {
  const user = useUserStore()
  const router = useRouter()
  let timer: ReturnType<typeof setTimeout> | null = null
  let lastReset = 0

  async function autoLogout() {
    if (!user.isLogin) return
    try { await user.logout() } catch { /* 退出失败也继续跳登录 */ }
    ElMessage.warning('因长时间未操作，已自动退出登录，请重新登录')
    if (router.currentRoute.value.path !== '/login') router.push('/login')
  }

  function reset() {
    const now = Date.now()
    if (now - lastReset < 1000) return  // 1 秒节流，避免 mousemove 频繁重置
    lastReset = now
    if (timer) clearTimeout(timer)
    timer = setTimeout(autoLogout, IDLE_MS)
  }

  onMounted(() => {
    ACTIVITY_EVENTS.forEach(e => window.addEventListener(e, reset, { passive: true }))
    reset()
  })

  onBeforeUnmount(() => {
    ACTIVITY_EVENTS.forEach(e => window.removeEventListener(e, reset))
    if (timer) clearTimeout(timer)
  })
}
