import { onBeforeUnmount, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { formSnapshot } from './useUnsavedGuard'

/**
 * 手机建单页的「没提交就退出」保护（电脑端弹窗用 useUnsavedGuard，判断方法相同）。
 *
 * 为什么要有：手机上点「‹ 返回」、安卓返回键、侧滑手势都会直接离开页面，填了好几行物料就全没了。
 * 安卓 App 是网页壳，返回键也是走浏览器后退，所以在路由离开时拦一次就都管住了。
 *
 * 怎么判断改没改：用户第一次点/敲页面时，给填写区（默认 .mf-body）拍基准快照；离开时再拍一次，不同才问。
 * 进页面时程序自动带出的默认值（今天的日期、第一个仓库）不算改动。
 *
 * 提交成功后页面自己跳走前，先调 allow()，否则会被自己拦下。
 */
export function useLeaveGuard(selector = '.mf-body', extraSnapshot: () => string = () => '') {
  let baseline: string | null = null
  let allowed = false

  function onFirstTouch() {
    if (baseline !== null) return
    const el = document.querySelector<HTMLElement>(selector)
    if (el) baseline = formSnapshot(el) + '\u0003' + extraSnapshot()
    stop()
  }
  function stop() {
    document.removeEventListener('pointerdown', onFirstTouch, true)
    document.removeEventListener('keydown', onFirstTouch, true)
  }
  onMounted(() => {
    document.addEventListener('pointerdown', onFirstTouch, true)
    document.addEventListener('keydown', onFirstTouch, true)
  })
  onBeforeUnmount(stop)

  function isDirty(): boolean {
    if (allowed || baseline === null) return false
    const el = document.querySelector<HTMLElement>(selector)
    return !!el && formSnapshot(el) + '\u0003' + extraSnapshot() !== baseline
  }

  onBeforeRouteLeave(async () => {
    if (!isDirty()) return true
    try {
      await ElMessageBox.confirm('现在退出，刚才填的内容会全部丢失。', '还没提交', {
        type: 'warning',
        // 主按钮（默认焦点）是「继续填」
        confirmButtonText: '继续填',
        cancelButtonText: '不保存，退出',
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
        // 🔴 必须关：手机返回是浏览器后退，网址（#hash）先变；默认设置下确认框一见网址变化就自动关掉，
        //   结果是框一闪就没、页面也没退（2026-10-07 本地实测踩到）
        closeOnHashChange: false,
        customClass: 'mf-leave-box'
      })
      return false
    } catch (action) {
      return action === 'cancel'
    }
  })

  /** 提交成功、准备跳走时调用：这次离开不再询问。 */
  function allow() { allowed = true }

  return { allow, isDirty }
}
