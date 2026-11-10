<template>
  <!-- 顶栏滚动通知(2026-10-21 用户敲定)。
       外层永远占住空白(flex 只吃剩余宽度,不挤右边按钮);有通知才画里面那条。 -->
  <div ref="slotRef" class="nt-slot" :class="{ badge: mode === 'badge' }">
    <div v-if="items.length" class="nt" :class="{ imp: hasImportant, compact: compact || mode === 'badge' }" role="button" tabindex="0"
         title="点击查看全文" @click="open(0)" @keydown.enter="open(0)">
      <span class="nt-ico" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>
      </span>
      <template v-if="!compact && mode !== 'badge'">
        <div class="nt-win">
          <!-- 内容复制一份接在后面,滚到一半(-50%)时无缝衔接 -->
          <div class="nt-track" :style="{ animationDuration: duration + 's' }">
            <template v-for="copy in 2" :key="copy">
              <span v-for="(x, i) in items" :key="copy + '-' + x.id" class="nt-item" @click.stop="open(i)">
                <span class="nt-tag" :class="x.level === 'IMPORTANT' ? 'imp' : 'nor'">{{ x.level === 'IMPORTANT' ? '重要' : '通知' }}</span>{{ x.title }}
              </span>
            </template>
          </div>
        </div>
        <span v-if="items.length > 1" class="nt-cnt">{{ items.length }} 条</span>
        <button type="button" class="nt-x" title="今天不再显示（明天还会出现，直到结束日期）" aria-label="今天不再显示" @click.stop="dismissToday">×</button>
      </template>
      <!-- 空白不足 200 像素时不硬塞滚动文字:只留喇叭 + 条数角标(约 50 像素),点开看全文 -->
      <span v-else class="nt-short" :title="`${items.length} 条通知，点开查看`">{{ items.length }}</span>
    </div>

    <NoticeDialog v-model="dlg" v-model:index="idx" :items="items" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { tickerApi } from '@/api'
import NoticeDialog, { type Ticker } from './NoticeDialog.vue'
import { useUserStore } from '@/stores/user'
import { today } from '@/utils/format'

/** bar=整条滚动(默认);badge=只有「喇叭 + 条数」小标 */
const props = withDefaults(defineProps<{ mode?: 'bar' | 'badge' }>(), { mode: 'bar' })
/** room=false:这块空白放不下整条滚动(不足 200 像素),首页据此改在问候语下面单独放一行 */
const emit = defineEmits<{ room: [ok: boolean] }>()
const user = useUserStore()
const all = ref<Ticker[]>([])
const dismissed = ref<Record<string, string>>(readDismissed())
const items = computed(() => all.value.filter(x => dismissed.value[String(x.id)] !== today()))
const hasImportant = computed(() => items.value.some(x => x.level === 'IMPORTANT'))
// 速度按字数算:约每秒 2 个字,最短 14 秒一圈
const duration = computed(() => Math.max(14, items.value.reduce((s, x) => s + x.title.length, 0) * 0.55))

const dlg = ref(false)
const idx = ref(0)
function open(i: number) { idx.value = i; dlg.value = true }

/* 「×」:这几条今天不再显示,第二天照常出现,直到结束日期(用户 10-21 定)。
   只是个人便利,存本机浏览器;隐私窗口/清缓存读不到就当没关过,不影响使用 */
function dismissKey() { return `ticker.dismissed.${user.userInfo?.id ?? 'anon'}` }
function readDismissed(): Record<string, string> {
  try { return JSON.parse(localStorage.getItem(dismissKey()) || '{}') || {} } catch { return {} }
}
function dismissToday() {
  const next: Record<string, string> = {}
  const d = today()
  for (const [k, v] of Object.entries(dismissed.value)) if (v === d) next[k] = v   // 旧日期的顺手清掉
  for (const x of items.value) next[String(x.id)] = d
  dismissed.value = next
  try { localStorage.setItem(dismissKey(), JSON.stringify(next)) } catch { /* 存不了就只在这次打开里生效 */ }
}

async function load() {
  try { all.value = (await tickerApi.live() as any) || [] } catch { /* 读不到通知不影响用系统,静默 */ }
}

/* 位置固定:外层只吃剩余空白;不足 200 像素收成「N 条通知」 */
const slotRef = ref<HTMLElement | null>(null)
const compact = ref(false)
let ro: ResizeObserver | null = null
// 每 5 分钟刷新一次。空闲自动退出看的是真人操作(useIdleLogout),后台刷新不会让它失效
let timer: number | undefined
onMounted(() => {
  void load()
  timer = window.setInterval(load, 5 * 60 * 1000)
  if (slotRef.value && 'ResizeObserver' in window) {
    ro = new ResizeObserver(es => {
      const w = es[0]?.contentRect.width ?? 999
      compact.value = w < 200
      if (props.mode === 'bar') emit('room', w >= 200)
    })
    ro.observe(slotRef.value)
  }
})
onUnmounted(() => { window.clearInterval(timer); ro?.disconnect() })
</script>

<style scoped>
/* overflow:hidden 兜底:无论多窄都不压到右边按钮(10-21 实点:1440 宽首页收起态曾压住「刷新」) */
/* 只吃真正空出来的宽度,一个像素都不强占(强占会把首页右边工具栏挤成两行,10-21 实测);
   overflow:hidden 兜底,无论多窄都不压到右边按钮 */
.nt-slot { flex: 1 1 0; min-width: 0; display: flex; align-items: center; overflow: hidden; padding: 2px 0; }
/* badge:首页空白连小标都放不下时,挂在日期那一行后面的小号「喇叭 + 条数」 */
.nt-slot.badge { flex: 0 0 auto; overflow: visible; padding: 0; }
.nt-slot.badge .nt { height: 22px; padding: 0 6px; border-radius: 11px; }
.nt-slot.badge .nt-ico svg { width: 14px; height: 14px; }
.nt {
  flex: 1; min-width: 0; height: 36px; display: flex; align-items: center; gap: 8px; padding: 0 6px 0 10px;
  --nt-bg: var(--color-primary-soft, #e8f1fb);
  border-radius: 8px; background: var(--nt-bg); border: 1px solid rgba(29, 99, 176, .18);
  color: var(--text-1); cursor: pointer; overflow: hidden;
}
.nt.imp { --nt-bg: #fff6f0; border-color: #f3cfb8; }
.nt.compact { flex: 0 0 auto; padding: 0 8px; gap: 4px; }
.nt-ico { flex-shrink: 0; display: grid; place-items: center; color: var(--color-primary); }
.nt.imp .nt-ico { color: #c4561c; }
/* 两头渐隐用叠在上面的渐变条,不用 mask-image:手机端(移动端渲染)下 mask 会让整条的底色和喇叭都画不出来(10-21 实测) */
.nt-win { flex: 1; min-width: 0; height: 36px; overflow: hidden; position: relative; }
.nt-win::before, .nt-win::after { content: ''; position: absolute; top: 0; bottom: 0; width: 18px; z-index: 1; pointer-events: none; }
.nt-win::before { left: 0; background: linear-gradient(90deg, var(--nt-bg), transparent); }
.nt-win::after { right: 0; background: linear-gradient(270deg, var(--nt-bg), transparent); }
.nt-track {
  position: absolute; left: 0; top: 0; height: 36px; display: flex; align-items: center; gap: 40px;
  white-space: nowrap; animation: nt-roll linear infinite;
}
.nt:hover .nt-track { animation-play-state: paused; }   /* 鼠标放上去停住 */
@keyframes nt-roll { from { transform: translateX(0); } to { transform: translateX(-50%); } }
@media (prefers-reduced-motion: reduce) { .nt-track { animation-duration: 120s !important; } }
.nt-item { display: inline-flex; align-items: center; gap: 6px; font-size: 13.5px; }
.nt-tag { font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 4px; font-weight: 600; flex-shrink: 0; margin-right: 6px; }
.nt-tag.imp { background: #fde4d6; color: #b4471a; }
.nt-tag.nor { background: rgba(29, 99, 176, .12); color: var(--color-primary); }
.nt-cnt { flex-shrink: 0; font-size: 12px; color: var(--text-3); }
.nt-x {
  flex-shrink: 0; width: 22px; height: 22px; border: 0; background: none; color: var(--text-3);
  border-radius: 4px; cursor: pointer; display: grid; place-items: center; font-size: 16px; line-height: 1;
}
.nt-x:hover { background: rgba(0, 0, 0, .06); color: var(--text-1); }
.nt-short { min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px; background: var(--color-primary); color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; text-align: center; }
.nt.imp .nt-short { background: #c4561c; }
:global(html[data-theme='dark']) .nt.imp { --nt-bg: #3a2a20; border-color: rgba(196, 86, 28, .4); }
</style>
