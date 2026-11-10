import { ref } from 'vue'

const THEME_KEY = 'jxc-ui-theme'

const isDark = ref(false)
const isFullscreen = ref(false)
let fullscreenListenerReady = false

function browserReady() {
  return typeof window !== 'undefined' && typeof document !== 'undefined'
}

export function initTheme() {
  if (!browserReady()) return
  const saved = window.localStorage.getItem(THEME_KEY)
  if (saved === 'dark' || saved === 'light') {
    applyTheme(saved === 'dark')
  } else {
    // 未设置时跟随系统偏好
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    applyTheme(prefersDark)
  }
}

export function applyTheme(nextDark: boolean) {
  isDark.value = nextDark
  if (!browserReady()) return
  document.documentElement.dataset.theme = nextDark ? 'dark' : 'light'
  document.documentElement.classList.toggle('dark', nextDark)
  window.localStorage.setItem(THEME_KEY, nextDark ? 'dark' : 'light')
}

export function toggleTheme() {
  applyTheme(!isDark.value)
}

export function initFullscreenState() {
  if (!browserReady() || fullscreenListenerReady) return
  fullscreenListenerReady = true
  const sync = () => {
    isFullscreen.value = !!document.fullscreenElement
  }
  sync()
  document.addEventListener('fullscreenchange', sync)
}

export async function toggleFullscreen() {
  if (!browserReady()) return
  if (document.fullscreenElement) {
    await document.exitFullscreen()
    return
  }
  await document.documentElement.requestFullscreen()
}

export function useUiPreferences() {
  initFullscreenState()
  return {
    isDark,
    isFullscreen,
    toggleTheme,
    toggleFullscreen
  }
}
