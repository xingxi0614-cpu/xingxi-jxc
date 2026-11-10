<template>
  <!--
    更新说明「带我去看」：跳到对应页面，把那一块挖亮、其余压暗，旁边讲一句。
    找不到那一块（没权限进页面 / 这块此刻没显示）时，就在屏幕中间讲，同样能点「看懂了」。
  -->
  <teleport to="body">
    <div v-if="item" class="rt-layer" role="dialog" aria-modal="true" :aria-label="item.title">
      <div v-if="rect" class="rt-spot" :style="spotStyle" />
      <div v-else class="rt-dim" />
      <div class="rt-bubble" :class="{ center: !rect }" :style="bubbleStyle">
        <div class="rt-title">{{ item.title }}</div>
        <div class="rt-tip">{{ item.tip }}</div>
        <div v-if="note" class="rt-note">{{ note }}</div>
        <div class="rt-foot">
          <el-button ref="okBtn" type="primary" @click="finish">看懂了，回到清单</el-button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { GuideItem } from '@/utils/releaseGuide'


const router = useRouter()
const item = ref<GuideItem | null>(null)
const rect = ref<DOMRect | null>(null)
const note = ref('')
let targetEl: HTMLElement | null = null
let resolver: (() => void) | null = null
let tourEpoch = 0

const PAD = 6
const spotStyle = computed(() => {
  const r = rect.value!
  return { top: `${r.top - PAD}px`, left: `${r.left - PAD}px`, width: `${r.width + PAD * 2}px`, height: `${r.height + PAD * 2}px` }
})
const bubbleStyle = computed(() => {
  const r = rect.value
  if (!r) return {}
  const w = Math.min(360, window.innerWidth - 24)
  const left = Math.max(12, Math.min(r.left, window.innerWidth - w - 12))
  // 下面放得下就放下面，否则放上面
  const below = r.bottom + PAD + 12
  if (below + 170 < window.innerHeight) return { top: `${below}px`, left: `${left}px`, width: `${w}px` }
  return { top: `${Math.max(12, r.top - PAD - 12 - 170)}px`, left: `${left}px`, width: `${w}px` }
})

function measure() { rect.value = targetEl && targetEl.isConnected ? targetEl.getBoundingClientRect() : null }

async function waitFor(selector: string, ms: number, active: () => boolean): Promise<HTMLElement | null> {
  const end = Date.now() + ms
  while (active() && Date.now() < end) {
    const el = document.querySelector<HTMLElement>(selector)
    if (el && el.offsetParent !== null) return el
    await new Promise(r => setTimeout(r, 120))
  }
  return null
}

/** 带看一条；用户点「看懂了」后 resolve。 */
async function start(it: GuideItem): Promise<void> {
  cancel()
  const epoch = tourEpoch
  note.value = ''
  targetEl = null
  rect.value = null
  if (it.path) {
    // 🔴 先查这个人有没有这个页面，没有就不跳：
    //   没权限的地址会落到 /404，而 /404 在整体框架之外——跳过去后更新弹窗和这个带看层都会被卸掉，人就卡在 404 上了
    const r = router.resolve(it.path)
    const reachable = r.matched.length > 0 && r.path !== '/404' && !r.matched.some(m => m.path.includes('pathMatch') || m.path === '/404')
    if (reachable) {
      await router.push(it.path).catch(() => {})
      if (epoch !== tourEpoch) return
      if (router.currentRoute.value.path !== it.path) note.value = '这个页面暂时打不开，看上面的说明就行。'
    } else {
      note.value = '你的账号没有这个页面，看上面的说明就行。'
    }
  }
  if (it.target && !note.value) {
    targetEl = await waitFor(`[data-tour="${it.target}"]`, 3000, () => epoch === tourEpoch)
    if (epoch !== tourEpoch) return
    if (targetEl) {
      targetEl.scrollIntoView({ block: 'center', behavior: 'auto' })
      await nextTick()
      if (epoch !== tourEpoch) return
      measure()
    } else {
      note.value = '这一块此刻没有显示（比如暂时没有要紧事），看上面的说明就行。'
    }
  }
  item.value = it
  window.addEventListener('resize', measure)
  window.addEventListener('scroll', measure, true)
  return new Promise(resolve => { resolver = resolve })
}

function cleanup() {
  window.removeEventListener('resize', measure)
  window.removeEventListener('scroll', measure, true)
}
function cancel() {
  tourEpoch++
  cleanup()
  item.value = null
  rect.value = null
  targetEl = null
  const r = resolver
  resolver = null
  r?.()
}
function finish() { cancel() }
onBeforeUnmount(cancel)
defineExpose({ start, cancel })
</script>

<style scoped>
.rt-layer { position: fixed; inset: 0; z-index: 3000; }
.rt-dim { position: fixed; inset: 0; background: rgba(0, 0, 0, .5); }
/* 挖亮：目标块本身透明，四周用超大阴影压暗 */
.rt-spot { position: fixed; border-radius: 8px; box-shadow: 0 0 0 9999px rgba(0, 0, 0, .5); outline: 2px solid var(--el-color-primary); pointer-events: none; transition: all .2s; }
.rt-bubble { position: fixed; background: var(--el-bg-color-overlay, #fff); border-radius: 8px; padding: 14px 16px; box-shadow: 0 8px 24px rgba(0, 0, 0, .25); }
.rt-bubble.center { top: 50%; left: 50%; transform: translate(-50%, -50%); width: min(400px, calc(100vw - 24px)); }
.rt-title { font-weight: 600; font-size: 15px; margin-bottom: 6px; }
.rt-tip { color: var(--el-text-color-regular); line-height: 1.7; font-size: 14px; }
.rt-note { margin-top: 8px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.rt-foot { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
