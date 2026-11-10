<template>
  <!-- 通知全文弹窗:顶栏滚动条点开 和 登录后自动弹出(LoginNoticeDialog)共用这一个,两处长得一样。
       正文按纯文本显示(不用 v-html):通知内容是人写的,当不可信输入。 -->
  <el-dialog v-model="visible" width="min(620px, calc(100vw - 32px))" append-to-body class="nt-dialog"
             :show-close="true" :close-on-click-modal="!forceRead">
    <template #header>
      <div class="nt-dh"><span class="k">系统通知</span>
        <div class="t"><span class="nt-tag" :class="cur?.level === 'IMPORTANT' ? 'imp' : 'nor'">{{ cur?.level === 'IMPORTANT' ? '重要' : '通知' }}</span>{{ cur?.title }}</div>
      </div>
    </template>
    <div class="nt-meta">显示时间：{{ cur?.startDate }} 至 {{ cur?.endDate }}</div>
    <div class="nt-body">{{ cur?.content || cur?.title }}</div>
    <template #footer>
      <div class="nt-df">
        <template v-if="items.length > 1">
          <el-button :disabled="index <= 0" @click="index--">‹ 上一条</el-button>
          <el-button :disabled="index >= items.length - 1" @click="index++">下一条 ›</el-button>
          <span class="nt-idx">第 {{ index + 1 }} / {{ items.length }} 条</span>
        </template>
        <el-button type="primary" class="nt-ok" @click="visible = false">知道了</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script lang="ts">
export interface Ticker { id: number; title: string; content?: string; level: string; startDate: string; endDate: string }
</script>

<script setup lang="ts">
import { computed } from 'vue'

/** forceRead:点遮罩不关(登录弹窗用,防止一进来误点空白处就关掉、没看见) */
const props = withDefaults(defineProps<{ items: Ticker[]; forceRead?: boolean }>(), { forceRead: false })
const visible = defineModel<boolean>({ required: true })
const index = defineModel<number>('index', { default: 0 })
const cur = computed(() => props.items[index.value])
</script>

<style scoped>
.nt-dh .k { font-size: 12px; color: var(--text-3); }
.nt-dh .t { margin-top: 2px; font-size: 17px; font-weight: 600; display: flex; align-items: center; }
.nt-tag { font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 4px; font-weight: 600; flex-shrink: 0; margin-right: 6px; }
.nt-tag.imp { background: #fde4d6; color: #b4471a; }
.nt-tag.nor { background: rgba(29, 99, 176, .12); color: var(--color-primary); }
.nt-meta { font-size: 12px; color: var(--text-3); margin: -6px 0 10px; }
.nt-body { white-space: pre-line; color: var(--text-1); line-height: 1.75; max-height: 50vh; overflow: auto; }
/* 手机宽度下四样东西挤一行会把「第 1 / 2 条」折成两行(10-22 实测 375 宽):不许折字,放不下整块换行 */
.nt-df { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; row-gap: 8px; }
.nt-idx { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.nt-ok { margin-left: auto; }   /* 永远靠右:一行放得下就在行尾,放不下换到下一行也靠右 */
</style>
