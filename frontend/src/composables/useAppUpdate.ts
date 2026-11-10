import { onBeforeUnmount, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 前端换版自动检测。
 *
 * 部署后服务器的 index.html(no-cache)会引用新的哈希入口 chunk(assets/index-XXXX.js)，
 * 但已经打开的页面仍在跑旧 JS，需要手动硬刷才能拿到新功能。这里定期拉取最新
 * index.html，比对入口 chunk 是否变化，发现新版本即提示用户刷新，免去手动硬刷。
 */
const CHECK_INTERVAL_MS = 5 * 60 * 1000   // 每 5 分钟检查一次
const SNOOZE_MS = 10 * 60 * 1000          // 用户选“稍后”后的免打扰时长
const ENTRY_RE = /assets\/index-[A-Za-z0-9_-]+\.js/

function runningEntry(): string | null {
  const scripts = Array.from(document.querySelectorAll('script[type="module"][src]'))
  for (const s of scripts) {
    const m = ((s as HTMLScriptElement).getAttribute('src') || '').match(ENTRY_RE)
    if (m) return m[0]
  }
  return null
}

async function latestEntry(): Promise<string | null> {
  try {
    const res = await fetch(`/?_=${Date.now()}`, { cache: 'no-store' })
    if (!res.ok) return null
    const m = (await res.text()).match(ENTRY_RE)
    return m ? m[0] : null
  } catch {
    return null
  }
}

export function useAppUpdate() {
  let timer: ReturnType<typeof setInterval> | null = null
  let busy = false
  let snoozedUntil = 0
  const current = runningEntry()

  async function check() {
    if (busy || !current || Date.now() < snoozedUntil) return
    busy = true
    try {
      const latest = await latestEntry()
      if (!latest || latest === current) return
      try {
        await ElMessageBox.confirm(
          '系统已更新到新版本，刷新后即可使用最新功能。是否立即刷新？',
          '发现新版本',
          { confirmButtonText: '立即刷新', cancelButtonText: '稍后', type: 'info', closeOnClickModal: false }
        )
        window.location.reload()
      } catch {
        snoozedUntil = Date.now() + SNOOZE_MS   // 选“稍后”：暂不再打扰
      }
    } finally {
      busy = false
    }
  }

  function onFocus() { check() }

  /**
   * 页面重新可见时立刻查一次。
   *
   * 🔴 2026-09-16 加：光靠 window 的 focus 事件在手机 App 里不够——
   * 安卓 WebView 退到后台时页面被挂起、定时器一并暂停，回到前台时 focus
   * 不一定触发，结果就是"App 一直开着就永远收不到新版提示，非得把它从
   * 多任务里划掉重开才行"。visibilitychange 在 WebView 和 iOS 的
   * 主屏幕网页（PWA）里都可靠，两个都监听，重复触发由 busy 标志挡住。
   */
  function onVisible() {
    if (!document.hidden) check()
  }

  onMounted(() => {
    timer = setInterval(check, CHECK_INTERVAL_MS)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisible)
  })
  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onVisible)
  })
}
