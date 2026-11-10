<template>
  <div class="tbx">
    <!-- 左:工具目录 -->
    <aside class="tbx-side">
      <div class="side-head">工具箱</div>
      <template v-for="g in groups" :key="g">
        <div class="side-grp">{{ g }}</div>
        <button
          v-for="t in toolsOf(g)" :key="t.code" type="button"
          class="side-item" :class="{ on: cur?.code === t.code }"
          @click="pick(t)"
        >
          <span class="side-ico"><component :is="iconOf(t)" :size="16" /></span>
          <span class="side-name">{{ t.name }}</span>
          <span class="side-kind" :class="kindClass(t)">{{ kindText(t) }}</span>
        </button>
      </template>
      <div v-if="!tools.length" class="side-empty">暂无可用工具</div>

      <div class="side-grp mt">最近生成</div>
      <div v-if="!tasks.length" class="side-empty sm">还没有记录</div>
      <button v-for="t in tasks.slice(0, 8)" :key="t.id" type="button" class="side-task" @click="focusTask(t)">
        <span class="dot" :class="stClass(t.status)"></span>
        <span class="side-task-t">{{ t.title }}</span>
      </button>
    </aside>

    <!-- 右:工作区 -->
    <section class="tbx-main">
      <div v-if="!cur" class="guide">
        <div class="guide-ico"><IconTool :size="30" /></div>
        <div class="guide-t">从左边选一个工具开始</div>
        <div class="guide-s">
          「即时」类填完立刻出结果;「生成」「传表格」类在后台跑,好了在下面下载。<br>
          生成的东西只有本公司看得到,保留 90 天。
        </div>
      </div>

      <template v-else>
        <header class="main-head">
          <div class="main-t">{{ cur.name }}<span class="main-kind" :class="kindClass(cur)">{{ kindText(cur) }}</span></div>
          <div class="main-s">{{ cur.desc }}</div>
        </header>

        <!-- ① 即时型:填完立刻出结果 -->
        <div v-if="cur.instant" class="panel">
          <template v-if="cur.code === 'amount-cn'">
            <div class="fld"><label>金额</label>
              <input v-model="inst.amount" class="ipt" placeholder="如 1234.56,可带逗号" @keyup.enter="runInstant" /></div>
          </template>
          <template v-else-if="cur.code === 'tax-split'">
            <div class="fld"><label>已知</label>
              <div class="seg">
                <button type="button" :class="{ on: inst.mode === 'fromGross' }" @click="inst.mode = 'fromGross'">含税额</button>
                <button type="button" :class="{ on: inst.mode === 'fromNet' }" @click="inst.mode = 'fromNet'">不含税额</button>
              </div>
            </div>
            <div class="fld"><label>金额</label><input v-model="inst.amount" class="ipt" placeholder="如 11300" @keyup.enter="runInstant" /></div>
            <div class="fld"><label>税率</label><input v-model="inst.rate" class="ipt" placeholder="填 13 或 0.13 都行" @keyup.enter="runInstant" /></div>
          </template>
          <template v-else-if="cur.code === 'id-check'">
            <div class="fld"><label>号码</label>
              <input v-model="inst.code" class="ipt" placeholder="统一社会信用代码或身份证号，18 位，可带空格" maxlength="30" autocomplete="off" @keyup.enter="runInstant" /></div>
          </template>
          <div class="acts"><button type="button" class="btn-main" :disabled="busy" @click="runInstant">{{ busy ? '计算中…' : '计算' }}</button></div>

          <div v-if="instErr" class="err">{{ instErr }}</div>
          <div v-else-if="instResult" class="result">
            <div v-for="(v, k) in instResult" :key="k" class="res-row" v-show="v !== ''">
              <span class="res-k">{{ k }}</span>
              <span class="res-v">{{ v }}</span>
              <button v-if="String(k).includes('大写') || String(k).includes('金额')" type="button" class="copy" @click="copy(String(v))">复制</button>
            </div>
          </div>
        </div>

        <!-- ② 传表格 -->
        <div v-else-if="cur.needFile" class="panel">
          <label class="drop" :class="{ has: pickedName }">
            <input type="file" accept=".xlsx,.xls" @change="onPick" />
            <IconUpload :size="22" />
            <span v-if="!pickedName">点这里选择 Excel 文件(.xlsx / .xls,不超过 20MB)</span>
            <span v-else class="drop-name">已选:{{ pickedName }}</span>
          </label>
          <div v-if="cur.code === 'dup-check'" class="fld"><label>按哪一列查</label>
            <input v-model="upl.column" class="ipt" placeholder="填表头里的列名，如：发票号码；不填自动找发票号、单号这类列" /></div>
          <div class="acts"><button type="button" class="btn-main" :disabled="busy || !picked" @click="submit">{{ busy ? '上传中…' : (cur.code === 'sheet-merge' ? '开始合并' : '开始分析') }}</button></div>
        </div>

        <!-- 本工具的生成记录 -->
        <div v-if="!cur.instant" class="hist">
          <div class="hist-h">生成记录<span>只有本公司看得到 · 保留 90 天</span></div>
          <div v-if="!myTasks.length" class="side-empty sm">还没有记录</div>
          <div v-for="t in myTasks" :key="t.id" class="task" :class="{ hl: hlId === t.id }">
            <span class="task-st" :class="stClass(t.status)">{{ stText(t.status) }}</span>
            <span class="task-title">{{ t.title }}</span>
            <span class="task-time">{{ (t.createdAt || '').replace('T', ' ').slice(5, 16) }}</span>
            <span v-if="t.status === 'FAILED'" class="task-err" :title="t.errorMsg">{{ t.errorMsg }}</span>
            <span v-else-if="t.status === 'SUCCESS'" class="task-files">
              <a v-for="f in filesOf(t)" :key="f.file" class="task-dl" @click="dl(t, f)">↓ {{ f.name }}</a>
            </span>
            <span v-else class="task-wait">生成中…</span>
            <button type="button" class="task-del" title="删除" @click="del(t)">×</button>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具箱:左边选工具,右边直接干活出结果。
 *
 * 【为什么改成两栏、不用弹窗】用户 2026-08-01 反馈"不像产品、不专业"。
 * 弹窗的毛病是:选工具要点一次、填完关掉才看得到结果、结果跟工具是断开的。
 * 改成左选右做之后,选 → 填 → 出结果在同一屏完成,即时类工具真正做到"点完就能看结果"。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  IconTool, IconUpload, IconCalculator, IconReceiptTax, IconClockHour4, IconId, IconCopyCheck, IconLayersUnion
} from '@tabler/icons-vue'
import { toolboxApi } from '@/api'
import { saveBlob } from '@/utils/download'
import { showError } from '@/utils/notify'

const tools = ref<any[]>([])
const tasks = ref<any[]>([])
const cur = ref<any>(null)
const busy = ref(false)
const hlId = ref<number | null>(null)
let timer: number | undefined

const inst = reactive<any>({ amount: '', rate: '13', mode: 'fromGross', code: '' })
/** 传表格类工具的附加参数(查重:按哪一列) */
const upl = reactive<any>({ column: '' })
const instResult = ref<any>(null)
const instErr = ref('')

const picked = ref<File | null>(null)
const pickedName = ref('')

const groups = computed(() => [...new Set(tools.value.map(t => t.group || '常用'))])
function toolsOf(g: string) { return tools.value.filter(t => (t.group || '常用') === g) }
/** 只看当前工具的记录 —— 全堆一起看不清哪条是刚才那次 */
const myTasks = computed(() => tasks.value.filter(t => t.toolCode === cur.value?.code))

const ICONS: Record<string, any> = {
  'amount-cn': IconCalculator, 'tax-split': IconReceiptTax,
  'aging': IconClockHour4,
  'id-check': IconId, 'dup-check': IconCopyCheck, 'sheet-merge': IconLayersUnion
}
function iconOf(t: any) { return ICONS[t.code] || IconTool }
function kindText(t: any) { return t.instant ? '即时' : (t.needFile ? '传表格' : '生成') }
function kindClass(t: any) { return t.instant ? 'k-fast' : (t.needFile ? 'k-file' : 'k-form') }

function pick(t: any) {
  cur.value = t
  instResult.value = null
  instErr.value = ''
  picked.value = null
  pickedName.value = ''
  hlId.value = null
}
function focusTask(t: any) {
  const tool = tools.value.find(x => x.code === t.toolCode)
  if (tool) { pick(tool); hlId.value = t.id }
}

function onPick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] || null
  picked.value = f
  pickedName.value = f ? f.name : ''
}

async function load() {
  try { tools.value = (await toolboxApi.tools()) || [] } catch { tools.value = [] }
  await loadTasks()
}
async function loadTasks() {
  try { tasks.value = (await toolboxApi.tasks()) || [] } catch { tasks.value = [] }
}

async function runInstant() {
  busy.value = true
  instErr.value = ''
  try {
    instResult.value = await toolboxApi.instant({ toolCode: cur.value.code, params: { ...inst } })
  } catch (e: any) {
    instResult.value = null
    instErr.value = e?.message || '计算失败'
  } finally { busy.value = false }
}

async function submit() {
  if (cur.value?.needFile && !picked.value) { ElMessage.warning('请先选择 Excel 文件'); return }
  busy.value = true
  try {
    await toolboxApi.upload(cur.value.code, cur.value.name, cur.value.code === 'dup-check' ? { column: upl.column.trim() } : {}, picked.value as File)
    ElMessage.success('已提交,正在生成…')
    await loadTasks()
  } catch (e: any) {
    showError(e, '提交失败')
  } finally { busy.value = false }
}

function filesOf(t: any) { try { return JSON.parse(t.outputFiles || '[]') } catch { return [] } }

async function dl(t: any, f: any) {
  try { saveBlob(await toolboxApi.file(t.id, f.file) as any, f.file) }
  catch (e: any) { showError(e, '下载失败,文件可能已过期') }
}

async function del(t: any) {
  const ok = await ElMessageBox.confirm(`删除「${t.title}」及它生成的文件?`, '确认', { type: 'warning' }).catch(() => null)
  if (!ok) return
  try { await toolboxApi.remove(t.id); await loadTasks(); ElMessage.success('已删除') }
  catch (e: any) { showError(e, '删除失败') }
}

function copy(v: string) {
  navigator.clipboard?.writeText(v).then(() => ElMessage.success('已复制')).catch(() => {})
}
function stText(s: string) { return ({ PENDING: '排队中', RUNNING: '生成中', SUCCESS: '已完成', FAILED: '失败' } as any)[s] || s }
function stClass(s: string) { return ({ PENDING: 'wait', RUNNING: 'wait', SUCCESS: 'ok', FAILED: 'bad' } as any)[s] || '' }

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    if (tasks.value.some(t => t.status === 'PENDING' || t.status === 'RUNNING')) loadTasks()
  }, 3000)
})
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.tbx {
  display: flex; gap: 16px; align-items: flex-start;
  animation: jxc-fade-up .28s cubic-bezier(.22, .61, .36, 1);
}

/* ── 左侧目录 ── */
.tbx-side {
  flex: 0 0 236px; position: sticky; top: 0;
  padding: 14px 12px;
  border: 1px solid var(--border-2, #e9eef4); border-radius: 14px;
  background: var(--bg-panel, #fff);
}
.side-head { font-size: 14px; font-weight: 700; color: var(--text-1, #1a1f29); margin-bottom: 10px; }
.side-grp { margin: 12px 0 6px; font-size: 11.5px; font-weight: 650; color: #0f766e; }
.side-grp.mt { margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--border-2, #eef1f5); }
.side-item {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 9px 10px; margin-bottom: 4px;
  border: 1px solid transparent; border-radius: 9px;
  background: transparent; font: inherit; font-size: 13px; cursor: pointer; text-align: left;
  color: var(--text-1, #1a1f29);
  transition: background .14s, border-color .14s;
}
.side-item:hover { background: #f0fbfa; }
.side-item.on { background: #e0f2f1; border-color: #99d6cf; font-weight: 600; }
.side-ico { flex: none; display: inline-flex; color: #0f766e; }
.side-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.side-kind { flex: none; padding: 1px 6px; border-radius: 999px; font-size: 10px; }
.side-empty { padding: 8px 2px; font-size: 12px; color: var(--text-3, #98a1b0); }
.side-empty.sm { font-size: 11.5px; }
.side-task {
  display: flex; align-items: center; gap: 7px; width: 100%;
  padding: 6px 10px; border: 0; background: transparent;
  font: inherit; font-size: 12px; color: var(--text-2, #5a6373);
  cursor: pointer; text-align: left; border-radius: 7px;
}
.side-task:hover { background: #f7fbff; }
.side-task-t { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { flex: none; width: 7px; height: 7px; border-radius: 50%; }
.dot.ok { background: #2e9e6b; } .dot.bad { background: #dc4d4d; } .dot.wait { background: #d6881f; }

/* ── 右侧工作区 ── */
.tbx-main { flex: 1; min-width: 0; }
.guide {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 70px 24px; text-align: center;
  border: 1.5px dashed var(--border-1, #e6e9ee); border-radius: 14px;
  background: var(--surface-soft, #f8fafc);
}
.guide-ico {
  width: 62px; height: 62px; border-radius: 17px;
  display: flex; align-items: center; justify-content: center;
  background: #e0f2f1; color: #0f766e;
}
.guide-t { font-size: 16px; font-weight: 700; color: var(--text-1, #1a1f29); }
.guide-s { font-size: 13px; color: var(--text-2, #5a6373); line-height: 1.9; }

.main-head { margin-bottom: 14px; }
.main-t { font-size: 18px; font-weight: 750; color: var(--text-1, #1a1f29); }
.main-kind { margin-left: 8px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 500; vertical-align: middle; }
.main-s { margin-top: 5px; font-size: 13px; color: var(--text-2, #5a6373); line-height: 1.7; }

.k-fast { background: #e7f6ef; color: #2e9e6b; }
.k-file { background: #fbf1e0; color: #d6881f; }
.k-form { background: #e8f1fb; color: #1d63b0; }

.panel {
  padding: 18px 20px;
  border: 1px solid var(--border-2, #e9eef4); border-radius: 14px;
  background: var(--bg-panel, #fff);
}
.fld { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.fld > label { flex: none; width: 62px; font-size: 13px; color: var(--text-2, #5a6373); }
.row3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.row3 .fld > label { width: 42px; }
.ipt, .sel {
  flex: 1; min-width: 0; height: 34px; padding: 0 11px;
  border: 1px solid var(--border-1, #e6e9ee); border-radius: 8px;
  background: var(--bg-panel, #fff); font: inherit; font-size: 13px; color: var(--text-1, #1a1f29);
}
.ipt:focus, .sel:focus, .ta:focus { outline: none; border-color: #0f766e; }
.ta {
  width: 100%; padding: 10px 12px;
  border: 1px solid var(--border-1, #e6e9ee); border-radius: 9px;
  font: inherit; font-size: 13px; line-height: 1.8; color: var(--text-1, #1a1f29);
  resize: vertical; background: var(--bg-panel, #fff);
}
.ta.sm { margin-top: 6px; font-size: 12.5px; }

.seg { display: inline-flex; border: 1px solid var(--border-1, #e6e9ee); border-radius: 8px; overflow: hidden; }
.seg button {
  padding: 0 14px; height: 32px; border: 0; background: var(--bg-panel, #fff);
  font: inherit; font-size: 12.5px; color: var(--text-2, #5a6373); cursor: pointer;
}
.seg button.on { background: #0f766e; color: #fff; font-weight: 600; }

.acts { margin-top: 16px; }
.btn-main {
  height: 38px; padding: 0 26px;
  border: 0; border-radius: 9px; background: #0f766e;
  color: #fff; font: inherit; font-size: 13.5px; font-weight: 600; cursor: pointer;
  transition: background .16s, transform .16s;
}
.btn-main:hover:not(:disabled) { background: #0c5f59; transform: translateY(-1px); }
.btn-main:disabled { opacity: .6; cursor: not-allowed; }

.result {
  margin-top: 16px; padding: 14px 16px;
  border: 1px solid #99d6cf; border-radius: 11px; background: #e0f2f1;
}
.res-row { display: flex; align-items: center; gap: 12px; padding: 6px 0; font-size: 14px; }
.res-k { flex: none; width: 92px; font-size: 12.5px; color: #5a6373; }
.res-v { flex: 1; font-weight: 700; color: #0f4c46; word-break: break-all; font-variant-numeric: tabular-nums; }
.copy { flex: none; height: 24px; padding: 0 9px; border: 1px solid #99d6cf; border-radius: 6px; background: #fff; color: #0f766e; font: inherit; font-size: 11.5px; cursor: pointer; }
.err { margin-top: 14px; padding: 11px 13px; border-radius: 9px; background: #fdeeee; color: #a33; font-size: 13px; }

.drop {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 34px 20px; cursor: pointer;
  border: 1.5px dashed #cfe0f2; border-radius: 12px; background: #f8fbff;
  color: var(--text-2, #5a6373); font-size: 13px; text-align: center;
}
.drop:hover { border-color: #0f766e; background: #f0fbfa; }
.drop.has { border-color: #0f766e; background: #e0f2f1; }
.drop input { display: none; }
.drop-name { color: #0f766e; font-weight: 600; }

.sel { flex: 0 0 100px; }

.hist { margin-top: 22px; }
.hist-h { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; font-size: 13.5px; font-weight: 700; color: var(--text-1, #1a1f29); }
.hist-h span { font-size: 12px; font-weight: 400; color: var(--text-3, #98a1b0); }
.task {
  display: flex; align-items: center; gap: 10px; margin-bottom: 8px;
  padding: 10px 12px; border: 1px solid var(--border-2, #e9eef4); border-radius: 10px;
  background: var(--bg-panel, #fff); font-size: 13px;
}
.task.hl { border-color: #0f766e; background: #f0fbfa; }
.task-st { flex: none; padding: 2px 9px; border-radius: 999px; font-size: 11.5px; font-weight: 600; }
.task-st.ok { background: #e7f6ef; color: #2e9e6b; }
.task-st.bad { background: #fdeeee; color: #dc4d4d; }
.task-st.wait { background: #fbf1e0; color: #d6881f; }
.task-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-time { flex: none; font-size: 12px; color: var(--text-3, #98a1b0); font-variant-numeric: tabular-nums; }
.task-files { display: flex; gap: 10px; flex-wrap: wrap; }
.task-dl { color: #0f766e; cursor: pointer; font-size: 12.5px; white-space: nowrap; }
.task-dl:hover { text-decoration: underline; }
.task-err { color: #dc4d4d; font-size: 12.5px; max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-wait { color: var(--text-3, #98a1b0); font-size: 12.5px; }
.task-del { flex: none; width: 22px; height: 22px; border: 0; border-radius: 50%; background: transparent; color: #b9c6d8; cursor: pointer; font-size: 15px; }
.task-del:hover { background: #fbebeb; color: #dc4d4d; }

@media (max-width: 900px) {
  .tbx { flex-direction: column; }
  .tbx-side { flex: none; width: 100%; position: static; }
  .row3 { grid-template-columns: 1fr; }
}
</style>
