<template>
  <!-- 表单里的一行：标签在左、选中的内容在右，点一下从屏幕底部弹出选项 -->
  <button type="button" class="mx-row" @click="open">
    <span class="mx-row__label">{{ label }}<i v-if="required">*</i></span>
    <span class="mx-row__val" :class="{ 'is-ph': !shown, 'is-wrap': wrap }">{{ shown || placeholder }}</span>
    <ArrowRight class="mx-row__chev" :size="16" />
  </button>

  <!-- 面板挂到 body 上：不在填写区里，「没提交就退出」的快照就不会把面板里的搜索字算成用户改了内容 -->
  <Teleport to="body">
    <div v-if="visible" class="mxs-mask" @click.self="visible = false">
      <div class="mxs">
        <div class="mxs-grip"></div>
        <div class="mxs-head">
          <button v-if="clearable && hasValue" type="button" class="mxs-clear" @click="pick(null)">清除</button>
          <span>{{ title || `选择${label}` }}</span>
          <button type="button" class="mxs-close" @click="visible = false">关闭</button>
        </div>
        <input v-if="searchable" v-model="kw" class="mx-search mxs-search" :placeholder="`搜索${label}`" @input="onKeyword" />
        <div class="mxs-list">
          <div v-if="loading" class="mx-tip">加载中…</div>
          <div v-else-if="!list.length" class="mx-tip">{{ kw ? '没有匹配的选项，换个关键字试试' : '没有可选的内容' }}</div>
          <button v-for="o in list" :key="String(o.value)" type="button" class="mx-row" :class="{ 'is-sel': o.value === modelValue }" @click="pick(o)">
            <span class="mx-row__tt"><b>{{ o.label }}</b><span v-if="o.sub">{{ o.sub }}</span></span>
            <Check v-if="o.value === modelValue" class="mx-row__check" :size="18" />
          </button>
          <div v-if="truncated" class="mxs-more">只显示前 {{ MAX_SHOWN }} 条，输入关键字可以缩小范围</div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 手机端「底部弹出选择」。替代 el-select：下拉框在手机上选项又小又挤，弹出层还常被键盘顶歪。
 *
 * 两种用法：
 *   · 选项不多、已经在页面里（仓库、业务分类）→ 传 :options，面板里的搜索在本地过滤
 *   · 选项很多、要按关键字去后端查（物料、供应商）→ 传 :search，每个选择器各查各的
 *
 * 选中项的名字会记住：远程搜索换了关键字后，原来选的那条不在结果里了，行上也不会变空白。
 */
import { computed, ref, watch } from 'vue'
import { ArrowRight, CircleCheck as Check } from '@/icons'

export interface PickOption { value: string | number; label: string; sub?: string }

const props = withDefaults(defineProps<{
  modelValue: string | number | null | undefined
  label: string
  options?: PickOption[]
  search?: (keyword: string) => Promise<PickOption[]>
  placeholder?: string
  title?: string
  required?: boolean
  clearable?: boolean
  searchable?: boolean
  /** 选中的名字太长时折行显示，不用省略号（物料要靠全称对货） */
  wrap?: boolean
}>(), { placeholder: '请选择', searchable: true })

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number | null): void
  (e: 'change', v: string | number | null, option: PickOption | null): void
}>()

/** 一次最多画这么多行：几千条物料全画出来手机会卡 */
const MAX_SHOWN = 100

const visible = ref(false)
const kw = ref('')
const loading = ref(false)
const remote = ref<PickOption[]>([])
const picked = ref<PickOption | null>(null)

const hasValue = computed(() => props.modelValue !== null && props.modelValue !== undefined && props.modelValue !== '')
const shown = computed(() => {
  if (!hasValue.value) return ''
  const hit = (props.options || []).find(o => o.value === props.modelValue)
  if (hit) return hit.label
  return picked.value?.value === props.modelValue ? picked.value!.label : ''
})

const matched = computed(() => {
  if (props.search) return remote.value
  const k = kw.value.trim().toLowerCase()
  const all = props.options || []
  return k ? all.filter(o => `${o.label} ${o.sub || ''}`.toLowerCase().includes(k)) : all
})
const list = computed(() => matched.value.slice(0, MAX_SHOWN))
const truncated = computed(() => matched.value.length > MAX_SHOWN)

let seq = 0
async function runSearch() {
  if (!props.search) return
  const mine = ++seq
  loading.value = true
  try {
    const rows = await props.search(kw.value.trim())
    // 连着敲字时只认最后一次的结果
    if (mine === seq) remote.value = rows || []
  } catch {
    if (mine === seq) remote.value = []
  } finally {
    if (mine === seq) loading.value = false
  }
}
let timer: ReturnType<typeof setTimeout> | undefined
function onKeyword() {
  if (!props.search) return
  clearTimeout(timer)
  timer = setTimeout(runSearch, 250)
}

function open() {
  kw.value = ''
  visible.value = true
  runSearch()
}
function pick(o: PickOption | null) {
  picked.value = o
  visible.value = false
  emit('update:modelValue', o ? o.value : null)
  emit('change', o ? o.value : null, o)
}

// 外面把值清掉了（比如重选订单），记住的名字也跟着清
watch(() => props.modelValue, () => { if (!hasValue.value) picked.value = null })
</script>

<style scoped lang="scss">
/* 层级压在 Element Plus 弹框（2000 起）下面：「没提交就退出」的确认框要能盖住它 */
.mxs-mask { position: fixed; inset: 0; z-index: 1500; display: flex; align-items: flex-end; background: rgba(30, 26, 18, .45); }
.mxs {
  display: flex; flex-direction: column; width: 100%; max-height: 72vh;
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--mob-bg); border-radius: 16px 16px 0 0;
  animation: mxs-up .2s ease-out;
}
@keyframes mxs-up { from { transform: translateY(24px); opacity: .6; } }
.mxs-grip { width: 36px; height: 4px; margin: 8px auto 2px; border-radius: 2px; background: #d9d2c2; }
.mxs-head {
  display: flex; align-items: center; padding: 6px 14px 4px;
  span { flex: 1; text-align: center; font-size: 15.5px; font-weight: 650; color: var(--mob-ink); }
  button { flex: 0 0 48px; height: 36px; padding: 0; border: 0; background: none; font-size: 14px; cursor: pointer; }
}
.mxs-clear { color: var(--biz-danger); text-align: left; }
/* 没有「清除」时左边也要空出同样宽度，标题才居中 */
.mxs-head span:first-child { padding-left: 48px; }
.mxs-close { color: var(--mob-ink-2); text-align: right; }
.mxs-search { margin-top: 6px; background: #fff; }
.mxs-list { flex: 1; min-height: 120px; margin: 10px 14px 14px; overflow-y: auto; overscroll-behavior: contain; background: #fff; border-radius: 12px; }
.mxs-list .mx-row { min-height: 50px; }
.mxs-list .mx-row.is-sel b { color: var(--mob-brand); }
.mxs-more { padding: 10px 14px 12px; text-align: center; font-size: 12px; color: var(--mob-ink-2); }
</style>
