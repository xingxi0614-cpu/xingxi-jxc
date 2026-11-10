const FORCE_DESKTOP_KEY = 'jxc_force_desktop'

/**
 * 是否手机设备：UA 命中手机机型，或触屏 + 窄屏兜底。
 * 用于"手机打开网址自动进入移动端 /mobile"。
 */
export function isMobileDevice(): boolean {
  if (typeof navigator === 'undefined' || typeof window === 'undefined') return false
  const ua = navigator.userAgent || ''
  const uaMobile = /Android|iPhone|iPod|Windows Phone|BlackBerry|IEMobile|Opera Mini|Mobile/i.test(ua)
  // iPad 新系统会把 UA 伪装成 Mac，用触点 + 窄边兜底（平板大屏仍按桌面处理）
  const touchNarrow = 'ontouchstart' in window && Math.min(window.innerWidth || 0, window.innerHeight || 0) <= 768
  return uaMobile || touchNarrow
}

/** 用户是否手动选择了"强制电脑版"（在手机上也用桌面版）。 */
export function isForceDesktop(): boolean {
  try { return sessionStorage.getItem(FORCE_DESKTOP_KEY) === '1' } catch { return false }
}

export function setForceDesktop(on: boolean) {
  try {
    if (on) sessionStorage.setItem(FORCE_DESKTOP_KEY, '1')
    else sessionStorage.removeItem(FORCE_DESKTOP_KEY)
  } catch { /* ignore */ }
}
