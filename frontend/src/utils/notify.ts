import { ElMessage } from 'element-plus'

/**
 * 页面 catch 里报错用这个，别直接 ElMessage.error(e.message)。
 * 为什么：接口业务报错 api/request.ts 已经统一弹过一次红字（并在 err 上打 toasted 标记），
 * 页面再弹一次，用户就看到两条一模一样的红字（2026-10-01 全站 43 处）。
 * 没被统一弹过的（本地校验抛的错、静默码、4220 等）照常弹。
 */
export function showError(e: any, fallback: string) {
  if (e?.toasted) return
  ElMessage.error(e?.message || fallback)
}
