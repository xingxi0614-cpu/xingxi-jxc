<template>
  <!--
    「查询 / 重置」原来在表格上方单独占一行，白白吃掉一行高度，表格能看到的行数变少（2026-10-13 用户反馈）。
    现在挪到页面标题右侧那排按钮的最前面；页面没有那排按钮时留在原位，不会消失。
  -->
  <span ref="anchor" class="query-action-anchor" />
  <Teleport :to="headActions" :disabled="!headActions">
    <!-- 表格上方有快速查询行时,查询/清空值/更多条件都在那一行里,这里不再放第二个「查询」免得混淆 -->
    <div v-show="!quickShown" class="query-action-bar" :class="{ 'query-action-bar--in-head': !!headActions }">
      <el-button type="primary" @click="open()">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </div>
  </Teleport>

  <!--
    快速查询行(2026-10-16 先定方案 A 左侧栏,看了上线效果改定方案 B):
    表格上方一行放「日期 + 单号 + 往来单位 + 仓库」,后面跟 查询 / 清空值 / 更多条件。
    和查询弹窗是同一份表单:弹窗关着时表单挪到这一行只露常用几项,打开弹窗时整份挪进弹窗。
  -->
  <div v-show="quickShown" class="qp-quickbar">
    <div ref="quickHost" class="qp-quickbar-body" />
    <div class="qp-quickbar-actions">
      <el-button type="primary" @click="quickSearch">查询</el-button>
      <el-button @click="clearValues">清空值</el-button>
      <el-button link type="primary" @click="open()">更多条件（{{ items.length }}）</el-button>
      <QueryPresets v-if="model" link :model="model" :keys="presetKeys" @apply="quickSearch" />
    </div>
  </div>
  <Teleport defer :to="formTarget" :disabled="!formTarget">
    <div ref="formRoot" class="qp-form" @input="bump" @change="bump" @click="onFormClick"><slot /></div>
  </Teleport>

  <!-- 已选条件:查完一眼看到"现在按什么查的",点 × 单独去掉一个-->
  <div v-if="chips.length" class="query-chips">
    <span class="query-chips__title">已选条件：</span>
    <span v-for="c in chips" :key="c.label" class="query-chip">
      {{ c.text }}
      <button v-if="c.key" type="button" class="query-chip__x" title="去掉这个条件" @click="removeChip(c.key)">×</button>
    </span>
    <el-button link type="primary" @click="onReset">全部清除</el-button>
  </div>

  <!--
    查询条件弹窗(2026-10-16 按用户给的财务软件查询框参考图):
    左栏「候选条件」= 本页全部条件,点一下加到右边;右栏「查询条件」一行一个:× 去掉 · 条件名 · 查法 · 输入框;右上「清空值」。
    不用 el-dialog:它第一次打开前不渲染内容,页面刚打开时读不到默认条件,"已选条件"就会是空的。
    所以自己画遮罩、内容一直挂着只是隐藏;层级取 Element Plus 的全局 z-index,下拉框弹层照样压在上面。
  -->
  <Teleport to="body">
    <div v-show="visible" class="qp-mask" :style="{ zIndex }">
      <div class="qp-dialog" role="dialog" aria-modal="true" aria-label="查询条件">
        <div class="qp-head">
          <b>查询条件</b>
          <button type="button" class="qp-close" aria-label="关闭" @click="visible = false">×</button>
        </div>
        <div class="qp-body">
          <aside class="qp-side">
            <div class="qp-pane-title">候选条件</div>
            <el-input v-model="finder" size="small" clearable placeholder="找条件，如：仓库" class="qp-finder" />
            <button
              v-for="item in shownItems"
              :key="item.label"
              type="button"
              class="qp-item"
              :class="{ 'is-on': rowSet.has(item.el) }"
              :title="rowSet.has(item.el) ? '已在右边' : '加到右边'"
              @click="addRow(item.el)"
            >{{ item.label }}</button>
            <div v-if="!shownItems.length" class="qp-empty">没有匹配的条件</div>
          </aside>
          <section class="qp-main">
            <div class="qp-main-head">
              <span class="qp-pane-title">查询条件</span>
              <el-button size="small" @click="clearValues">清空值</el-button>
            </div>
            <div ref="dialogHost" />
            <div v-if="!rowSet.size" class="qp-empty">右边还没有条件，点左边的条件加进来</div>
          </section>
        </div>
        <div class="qp-foot">
          <QueryPresets v-if="model" :model="model" :keys="presetKeys" @apply="confirm" />
          <span class="qp-count">{{ setCount ? `已设 ${setCount} 个条件` : '未设条件（将显示全部）' }}</span>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" @click="confirm">确定查询</el-button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowReactive, watch } from 'vue'
import { ElMessage, useZIndex } from 'element-plus'
import { collectItems, decorateRow, emptyLike, itemDisplayValue, pickQuickItems, type PanelItem } from '@/utils/query-panel'
import QueryPresets from './QueryPresets.vue'
import './query-drawer.css'
import './query-quick.css'

/**
 * model:页面的查询对象(可选)。传了它,× 和「清空值」才能把对应字段清空——
 * 表单项上标 data-q="字段名" 指明是哪个字段;不在 model 里的(如单独的日期区间)发 clear 事件让页面自己清。
 */
const props = defineProps<{ model?: Record<string, any> }>()
const emit = defineEmits<{
  search: []
  reset: []
  clear: [key: string]
}>()

/** 右边默认摆出前几个条件(加上已经有值的);其余在左栏点了再加 */
const DEFAULT_ROWS = 8

const visible = ref(false)
const zIndex = ref(2000)
const { nextZIndex } = useZIndex()
const finder = ref('')
const formRoot = ref<HTMLElement | null>(null)
const items = ref<PanelItem[]>([])
/** 右边正在显示的条件行 */
const rowSet = shallowReactive(new Set<HTMLElement>())
let rowsInited = false
/** 表单里任何输入/点选都 +1,让左下角"已设 N 个条件"跟着刷新(值在 DOM 上,Vue 侦听不到) */
const tick = ref(0)
const chips = ref<{ label: string; text: string; key: string | null }[]>([])

/* ===== 快速查询栏 ===== */
const quickHost = ref<HTMLElement | null>(null)
const dialogHost = ref<HTMLElement | null>(null)
const quickSet = shallowReactive(new Set<HTMLElement>())
const narrowScreen = ref(false)
const mq = typeof window !== 'undefined' ? window.matchMedia('(max-width: 760px)') : null
const onMq = () => { narrowScreen.value = !!mq?.matches }
/** 至少 2 个条件才值得常驻;手机宽度不显示(仍用顶部「查询」弹窗) */
const quickShown = computed(() => items.value.length >= 2 && !narrowScreen.value)
/** 表单此刻在哪:弹窗开着→弹窗;否则快速栏显示时→快速栏;再否则放回弹窗里(弹窗隐藏着) */
const formTarget = computed(() => (!visible.value && quickShown.value ? quickHost.value : dialogHost.value))
function quickSearch() {
  emit('search')
  refreshChips(120)
}

/** 常用方案只存/套用页面查询对象里的字段(表单项上标了 data-q 的) */
const presetKeys = computed(() => items.value.map(i => i.key).filter((k): k is string => !!k))

const shownItems = computed(() => {
  const k = finder.value.trim()
  return k ? items.value.filter(i => i.label.includes(k)) : items.value
})

function currentValues() {
  return items.value
    .map(item => ({ item, value: itemDisplayValue(item.el) }))
    .filter(x => x.value)
}
const setCount = computed(() => { void tick.value; return visible.value ? currentValues().length : 0 })

function bump() { tick.value++ }

function rescan() {
  items.value = collectItems(formRoot.value)
  items.value.forEach(i => decorateRow(i.el))
  for (const el of Array.from(rowSet)) if (!items.value.some(i => i.el === el)) rowSet.delete(el)
  if (!rowsInited && items.value.length) {
    items.value.slice(0, DEFAULT_ROWS).forEach(i => rowSet.add(i.el))
    rowsInited = true
  }
  quickSet.clear()
  pickQuickItems(items.value).forEach(el => quickSet.add(el))
  // 已经有值的条件(默认条件、从别处带过来的单号)一定摆在右边,不然看不见却在起作用
  currentValues().forEach(({ item }) => rowSet.add(item.el))
  applyVisibility()
}

/** 不显示的条件只是隐藏(仍在页面上,值照常参与查询——所以弹窗里有值的不许藏,见 removeRow) */
function applyVisibility() {
  const shown = visible.value ? rowSet : quickSet
  for (const item of items.value) item.el.classList.toggle('qp-hidden', !shown.has(item.el))
}

function addRow(el: HTMLElement) {
  rowSet.add(el)
  applyVisibility()
  nextTick(() => {
    el.scrollIntoView({ block: 'nearest' })
    el.querySelector<HTMLInputElement>('input:not([type=hidden]):not([readonly])')?.focus()
  })
}

function clearItem(item: PanelItem): boolean {
  if (item.fixed) return false
  if (!itemDisplayValue(item.el)) return true
  if (!item.key) return false
  if (props.model && item.key in props.model) props.model[item.key] = emptyLike(props.model[item.key])
  emit('clear', item.key)
  return true
}

/** × 去掉一行:先清掉它的值,清不掉的(页面没标字段名)不让藏,免得看不见却还在筛 */
function removeRow(el: HTMLElement) {
  const item = items.value.find(i => i.el === el)
  if (!item) return
  // grouping:连点几下只弹一条(以前会叠四条);2026-10-19 起全站页面都标了 data-q,正常走不到这里
  if (!clearItem(item)) { ElMessage({ type: 'warning', message: item.fixed ? `「${item.label}」是必选条件，不能去掉，可以直接改选` : '这个条件请先在输入框里清空，再去掉', grouping: true }); return }
  rowSet.delete(el)
  applyVisibility()
  bump()
}

function onFormClick(e: MouseEvent) {
  const x = (e.target as HTMLElement).closest('.qp-row-x')
  if (x) removeRow(x.parentElement as HTMLElement)
  else bump()
}

/** 清空值:只清值、不关窗口、不查询(同 财务软件);页面没传 model 时退回页面自己的「重置」 */
function clearValues() {
  if (!props.model) { emit('reset'); bump(); return }
  items.value.forEach(i => { if (!i.fixed) clearItem(i) })
  bump()
}

function open(focusLabel?: string) {
  rescan()
  if (focusLabel) {
    const hit = items.value.find(i => i.label === focusLabel)
    if (hit) addRow(hit.el)
  }
  zIndex.value = nextZIndex()
  finder.value = ''
  visible.value = true
  applyVisibility()
  bump()
}

/** "已选条件"只在弹窗关着时刷新:反映的是已经拿去查询的条件 */
let chipTimer: ReturnType<typeof setTimeout> | undefined
function refreshChips(delay = 60) {
  clearTimeout(chipTimer)
  chipTimer = setTimeout(() => {
    if (visible.value) return
    if (!items.value.length) items.value = collectItems(formRoot.value)
    // 快速查询行里已经看得到的条件不再重复列出,只列在「更多条件」里设的
    chips.value = currentValues().filter(({ item }) => !(quickShown.value && quickSet.has(item.el))).map(({ item, value }) => ({
      label: item.label,
      // 单独一个勾选框(如「只看待核销」)勾上时值就是自己的名字,只显示一遍
      text: value === item.label ? item.label : `${item.label}：${value}`,
      key: item.key
    }))
  }, delay)
}

function confirm() {
  emit('search')
  visible.value = false
  refreshChips(120)
}

function onReset() {
  emit('reset')
  refreshChips(120)
}

function removeChip(key: string) {
  if (props.model && key in props.model) props.model[key] = emptyLike(props.model[key])
  emit('clear', key)
  emit('search')
  refreshChips(120)
}

function onKeydown(e: KeyboardEvent) {
  if (!visible.value || e.key !== 'Escape') return
  // 面板里又打开了弹窗(如物料放大镜)时,Esc 只关那个弹窗,不连带关掉查询面板
  const inner = Array.from(document.querySelectorAll<HTMLElement>('.el-overlay')).some(o => getComputedStyle(o).display !== 'none')
  if (!inner) visible.value = false
}

// 找到本页标题栏右侧的按钮区；只在自己所在的 .page 里找，多页签同时存在时不会串到别的页面
const anchor = ref<HTMLElement | null>(null)
const headActions = ref<HTMLElement | null>(null)
let observer: MutationObserver | undefined
onMounted(() => {
  headActions.value = anchor.value?.closest('.page')?.querySelector<HTMLElement>(':scope > .page-head .el-space') ?? null
  onMq(); mq?.addEventListener('change', onMq)
  rescan()
  // 下拉选项是异步加载的,加载完才显示名称;表单结构变化(v-if 的条件项)也要重扫
  observer = new MutationObserver(() => {
    if (visible.value) { bump(); return }
    refreshChips(150)
  })
  if (formRoot.value) observer.observe(formRoot.value, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['class'] })
  document.addEventListener('keydown', onKeydown)
  refreshChips(300)
})
onBeforeUnmount(() => {
  mq?.removeEventListener('change', onMq)
  observer?.disconnect()
  clearTimeout(chipTimer)
  document.removeEventListener('keydown', onKeydown)
})
// 弹窗关上后表单回到快速栏,只露常用几项
watch(visible, () => nextTick(applyVisibility))

// 查询条件被程序改了(选择表带回单号、从别的页面带单号跳过来)也同步刷新;等 DOM 更新完再读
watch(() => props.model, () => (visible.value ? bump() : refreshChips(150)), { deep: true, flush: 'post' })

// 供页面在输入框回车时直接查询并收起弹窗;open('仓库') 可直接定位到某个条件
defineExpose({ confirm, open, refresh: () => refreshChips(0) })
</script>
