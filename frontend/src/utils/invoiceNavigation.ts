import type { RouteLocationRaw, Router } from 'vue-router'

// 业务入口每次点击登记一个意图；URL 保持稳定，不额外创建页签或 KeepAlive 缓存。
// 普通切回页签没有新意图，因此不会重复打开上次已关闭的录入窗口。
const pending = new Map<string, symbol>()

export async function navigateToInvoice(router: Router, to: RouteLocationRaw) {
  const key = router.resolve(to).fullPath
  const intent = Symbol(key)
  pending.set(key, intent)
  try {
    const failure = await router.push(to)
    if ((failure || router.currentRoute.value.fullPath !== key) && pending.get(key) === intent) pending.delete(key)
    return failure
  } catch (error) {
    if (pending.get(key) === intent) pending.delete(key)
    throw error
  }
}

export function takeInvoiceNavigationIntent(fullPath: string) {
  const exists = pending.has(fullPath)
  pending.delete(fullPath)
  return exists
}
