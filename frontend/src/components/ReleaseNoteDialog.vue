<template>
  <el-dialog
    v-model="visible"
    title="系统更新"
    width="min(640px, calc(100vw - 40px))"
    align-center
    append-to-body
    class="release-note-dialog"
    @closed="onClosed"
  >
    <div v-if="note" class="release-note-body">
      <div class="release-meta">
        <el-tag size="small" effect="plain">v{{ note.versionNo }}</el-tag>
        <span>{{ formatTime(note.publishedAt) }}</span>
      </div>
      <h2>{{ note.title }}</h2>
      <p class="release-summary">{{ note.summary }}</p>

      <!-- 有带看条目：逐条列出，每条可「带我去看」；标「必看」的看完才能点「知道了」 -->
      <div v-if="guideItems.length" class="guide-list">
        <div v-for="it in guideItems" :key="it.key" class="guide-item">
          <el-tag size="small" :type="tagOf(it).type" effect="light" class="guide-tag">{{ tagOf(it).label }}</el-tag>
          <div class="guide-main">
            <div class="guide-title">{{ it.title }}<span v-if="it.must" class="guide-must">必看</span></div>
            <div v-if="it.desc" class="guide-desc">{{ it.desc }}</div>
          </div>
          <span v-if="readKeys.has(it.key)" class="guide-ok">✓ 已看</span>
          <el-button link type="primary" @click="go(it)">{{ readKeys.has(it.key) ? '再看一次' : '带我去看 ›' }}</el-button>
        </div>
      </div>
      <ul v-else class="release-points">
        <li v-for="line in noteLines" :key="line">{{ line }}</li>
      </ul>
    </div>
    <template #footer>
      <div class="release-foot">
        <span v-if="mustLeft" class="release-left">还有 <b>{{ mustLeft }}</b> 条必看没看</span>
        <span class="release-btns">
          <el-button @click="openHistory">查看全部更新</el-button>
          <el-button v-if="mustLeft" @click="visible = false">稍后再看</el-button>
          <el-button type="primary" :disabled="mustLeft > 0" :title="mustLeft ? '标「必看」的条目点开看完后才能关闭' : ''" @click="visible = false">知道了</el-button>
        </span>
      </div>
    </template>
  </el-dialog>
  <ReleaseTour ref="tourRef" />
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { sysApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ReleaseTour from './ReleaseTour.vue'
import { GUIDE_TAG, type GuideItem } from '@/utils/releaseGuide'

/**
 * 登录后自动弹更新说明的总开关。是否真的弹,还要看那条说明自己的 popup 字段。
 */
const AUTO_POPUP = true
const router = useRouter()
const user = useUserStore()
const visible = ref(false)
const note = ref<any | null>(null)
const loaded = ref(false)
let retries = 0
const guideItems = ref<GuideItem[]>([])
const readKeys = ref(new Set<string>())
const tourRef = ref<InstanceType<typeof ReleaseTour> | null>(null)
const accountKey = computed(() => user.sessionActive && user.userInfo?.id != null ? String(user.userInfo.id) : null)
let identityEpoch = 0
let loadGeneration = 0
let controller: AbortController | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let closing: { epoch: number; account: string; version: string } | null = null
let resetting = false

const storageKey = computed(() => {
  const userKey = accountKey.value || 'anonymous'
  const version = note.value?.versionNo || 'unknown'
  return `jxc_release_seen_${userKey}_${version}`
})

const noteLines = computed(() => String(note.value?.content || '')
  .split(/\r?\n/)
  .map(line => line.trim().replace(/^[-*]\s*/, ''))
  .filter(Boolean))

/** 必看但还没看的条数。以服务器记录为准，所以换电脑、清缓存也不会漏。 */
const mustLeft = computed(() => guideItems.value.filter(i => i.must && !readKeys.value.has(i.key)).length)

function tagOf(it: GuideItem) { return GUIDE_TAG[it.tag || ''] || GUIDE_TAG.improve }

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : ''
}

function markSeen() {
  if (!accountKey.value || !note.value?.versionNo) return
  localStorage.setItem(storageKey.value, '1')
}
/** 关掉时：必看都看完了才算「这一版看过了」；否则（稍后再看）下次登录还会弹。 */
function onClosed() {
  if (closing && closing.epoch === identityEpoch && closing.account === accountKey.value
    && closing.version === note.value?.versionNo && !mustLeft.value) markSeen()
  closing = null
}
watch(visible, (now, before) => {
  if (now) closing = null
  else if (before && !resetting && accountKey.value && note.value?.versionNo) {
    closing = { epoch: identityEpoch, account: accountKey.value, version: note.value.versionNo }
  }
}, { flush: 'sync' })

async function openHistory() {
  if (!mustLeft.value) markSeen()
  visible.value = false
  if (router.currentRoute.value.path.startsWith('/mobile')) {
    // 手机工作台没有桌面更新历史路由，仍留在此处看本版清单。
    visible.value = true
  } else await router.push('/help/release-notes')
}

async function go(it: GuideItem) {
  if (!accountKey.value || !note.value?.versionNo) return
  const epoch = identityEpoch
  const version = note.value.versionNo
  const from = router.currentRoute.value.fullPath
  visible.value = false
  // 手机路由不进入桌面 Layout。仍逐条讲解，但在当前屏幕原地完成。
  await tourRef.value?.start(router.currentRoute.value.path.startsWith('/mobile') ? { ...it, path: null, target: null } : it)
  if (epoch !== identityEpoch || !accountKey.value) return
  try {
    await sysApi.releaseGuideRead(version, it.key, controller?.signal)
    if (epoch !== identityEpoch || !accountKey.value) return
    readKeys.value = new Set([...readKeys.value, it.key])
  } catch {
    // 网络失败不能在本地伪装已读；再次点该条即可重试提交。
  }
  if (epoch !== identityEpoch || !accountKey.value) return
  if (it.path && router.currentRoute.value.fullPath !== from) await router.push(from).catch(() => {})
  if (epoch !== identityEpoch || !accountKey.value) return
  visible.value = true
}

async function loadLatest() {
  if (loaded.value || !accountKey.value) return
  const epoch = identityEpoch
  const generation = ++loadGeneration
  const signal = controller?.signal
  loaded.value = true
  let failed = false
  const data: any = await sysApi.latestReleaseNote(signal).catch(() => { failed = true; return null })
  if (epoch !== identityEpoch || generation !== loadGeneration || !accountKey.value) return
  if (failed) {
    // 刚登录那一下偶尔会取不到（会话还没就绪）。必看内容不能因此整次登录都不弹，隔几秒再试，最多 3 次
    loaded.value = false
    if (retries++ < 3) retryTimer = setTimeout(() => { retryTimer = null; if (epoch === identityEpoch) void loadLatest() }, 3000)
    return
  }
  if (!data?.versionNo || Number(data.popup) !== 1) return
  const guide: any = await sysApi.releaseGuide(data.versionNo, signal).catch(() => { failed = true; return null })
  if (epoch !== identityEpoch || generation !== loadGeneration || !accountKey.value) return
  if (failed) {
    loaded.value = false
    if (retries++ < 3) retryTimer = setTimeout(() => { retryTimer = null; if (epoch === identityEpoch) void loadLatest() }, 3000)
    return
  }
  note.value = data
  guideItems.value = Array.isArray(guide?.items) ? guide.items : []
  readKeys.value = new Set<string>(Array.isArray(guide?.readKeys) ? guide.readKeys : [])
  // 有必看没看完 → 不管本机记没记过都弹；否则沿用「本机看过这一版就不再弹」
  if (AUTO_POPUP && (mustLeft.value > 0 || localStorage.getItem(storageKey.value) !== '1')) {
    visible.value = true
  }
}

async function openNow() {
  if (!accountKey.value) return
  if (!note.value) { loaded.value = false; await loadLatest() }
  if (accountKey.value && note.value) visible.value = true
}
function onOpenRequest() { void openNow() }
onMounted(() => window.addEventListener('jxc-release-notes-open', onOpenRequest))
function resetIdentity(account: string | null) {
  identityEpoch++
  loadGeneration++
  controller?.abort()
  controller = account ? new AbortController() : null
  if (retryTimer) clearTimeout(retryTimer)
  retryTimer = null
  retries = 0
  tourRef.value?.cancel()
  resetting = true
  visible.value = false
  resetting = false
  closing = null
  loaded.value = false
  note.value = null
  guideItems.value = []
  readKeys.value = new Set()
  if (account) void loadLatest()
}
watch(accountKey, resetIdentity, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => {
  resetIdentity(null)
  window.removeEventListener('jxc-release-notes-open', onOpenRequest)
})
</script>

<style scoped lang="scss">
.release-note-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.release-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-3);
  font-size: 12px;
}

h2 {
  margin: 0;
  font-size: 22px;
  line-height: 1.3;
  color: var(--text-1);
}

.release-summary {
  margin: 0;
  color: var(--text-2);
  line-height: 1.7;
}

.release-points {
  margin: 0;
  padding: 12px 14px 12px 30px;
  border: 1px solid var(--border-2);
  border-radius: 8px;
  background: var(--surface-soft, #fcfaf7);
  color: var(--text-1);
  line-height: 1.8;
}

.release-points li + li {
  margin-top: 6px;
}

.guide-list {
  border: 1px solid var(--border-2);
  border-radius: 8px;
  padding: 2px 14px;
}
.guide-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-2);
}
.guide-item:last-child { border-bottom: 0; }
.guide-tag { flex: none; margin-top: 2px; }
.guide-main { flex: 1; min-width: 0; }
.guide-title { font-weight: 600; color: var(--text-1); line-height: 1.6; }
.guide-must { margin-left: 6px; color: var(--el-color-danger); font-size: 12px; font-weight: 400; }
.guide-desc { color: var(--text-2); font-size: 13px; line-height: 1.6; }
.guide-ok { color: var(--el-color-success); font-size: 12px; white-space: nowrap; margin-top: 3px; }

.release-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.release-left { color: var(--text-2); font-size: 13px; }
.release-left b { color: var(--el-color-danger); }
.release-btns { margin-left: auto; display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.release-btns .el-button + .el-button { margin-left: 0; }

@media (max-width: 640px) {
  .guide-item { flex-wrap: wrap; }
}
</style>
