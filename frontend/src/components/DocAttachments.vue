<template>
  <div v-if="!denied" class="doc-att">
    <div class="att-head">
      <span class="att-title">附件<span v-if="totalCount" class="att-count">{{ totalCount }}</span></span>
      <el-upload :show-file-list="false" accept=".jpg,.jpeg,.png,.webp,.pdf,.xls,.xlsx,.doc,.docx,.zip" :http-request="doUpload" :disabled="uploading" multiple>
        <el-button size="small" :loading="uploading">{{ bizId ? '上传附件' : '选择附件' }}</el-button>
      </el-upload>
      <span class="att-tip">图片/PDF/Office/压缩包，单个≤20MB（合同、发票影像、验收照片等）<template v-if="!bizId">，保存单据后自动上传</template></span>
    </div>

    <!-- 已上传（bizId 已存在） -->
    <div v-if="rows.length" class="att-list" v-loading="loading">
      <div v-for="a in rows" :key="a.id" class="att-row">
        <span class="att-name" :title="a.fileName">{{ a.fileName }}</span>
        <span class="att-meta">{{ fmtSize(a.sizeBytes) }} · {{ a.createdByName || '-' }} · {{ String(a.createdAt || '').slice(0, 16).replace('T', ' ') }}</span>
        <el-button v-if="previewKind(a.fileName)" link type="primary" size="small" :loading="previewingId === a.id" @click="doPreview(a)">查看</el-button>
        <el-button link type="primary" size="small" @click="doDownload(a)">下载</el-button>
        <el-button link type="danger" size="small" @click="doRemove(a)">删除</el-button>
      </div>
    </div>

    <!-- 待上传（建单暂存） -->
    <div v-if="pending.length" class="att-list">
      <div v-for="(p, i) in pending" :key="i" class="att-row pending">
        <span class="att-name" :title="p.file.name">{{ p.file.name }}</span>
        <span class="att-meta">{{ fmtSize(p.file.size) }} · <span class="pend-tag">待上传</span></span>
        <el-button link type="danger" size="small" @click="pending.splice(i, 1)">移除</el-button>
      </div>
    </div>
    <!-- 图片就地放大查看(可缩放/旋转)；PDF 走浏览器新标签页 -->
    <el-image-viewer v-if="viewerUrl" :url-list="[viewerUrl]" teleported hide-on-click-modal @close="closeViewer" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { attachmentApi } from '@/api'
import { saveBlob } from '@/utils/download'
import { showError } from '@/utils/notify'

const props = defineProps<{ bizType: string; bizId: number | null | undefined }>()
const rows = ref<any[]>([])
const pending = ref<{ file: File }[]>([])
const loading = ref(false)
const uploading = ref(false)
// 当前角色无权看这类单据的附件(后端 4003)：整块不显示，避免每次打开详情都弹报错
const denied = ref(false)

const totalCount = computed(() => rows.value.length + pending.value.length)
const MAX = 20 * 1024 * 1024

function fmtSize(n: number) {
  if (!n) return '0KB'
  return n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(n / 1024))}KB`
}

async function load() {
  denied.value = false   // 同一组件换看另一张单时重新判定
  if (!props.bizId) { rows.value = []; return }
  loading.value = true
  try { rows.value = await attachmentApi.list(props.bizType, props.bizId) as any }
  catch (e: any) { rows.value = []; denied.value = e?.code === 4003 }
  finally { loading.value = false }
}

async function doUpload(options: any) {
  const file: File = options.file
  if (file.size > MAX) { ElMessage.warning('单个附件不能超过 20MB'); return }
  if (!props.bizId) {
    // 建单阶段：先暂存，保存单据后由父组件调 uploadPending 补传
    pending.value.push({ file })
    return
  }
  uploading.value = true
  try {
    await attachmentApi.upload(props.bizType, props.bizId, file)
    ElMessage.success('附件已上传')
    await load()
  } catch (e: any) { showError(e, '上传失败') }
  finally { uploading.value = false }
}

/** 父表单保存拿到新单据 id 后调用：把暂存文件逐个上传。
 *  失败的文件保留在 pending 里(不静默丢弃),成功的移除,便于用户重试/父页判断是否真全部成功。返回成功数。 */
async function uploadPending(bizId: number): Promise<number> {
  if (!bizId || !pending.value.length) return 0
  let ok = 0
  const failed: { file: File }[] = []
  for (const p of [...pending.value]) {
    try { await attachmentApi.upload(props.bizType, bizId, p.file); ok++ }
    catch (e: any) { ElMessage.error(`附件「${p.file.name}」上传失败：${e?.message || ''}`); failed.push(p) }
  }
  pending.value = failed   // 只清成功的,失败项留下可重试
  return ok
}

/** 清空本组件状态——父组件在打开新建/编辑弹框时调用,防止上一单暂存的附件串到下一单 */
function reset() { pending.value = []; rows.value = [] }

defineExpose({ uploadPending, reset, hasPending: () => pending.value.length > 0, pendingCount: () => pending.value.length })

async function doDownload(a: any) {
  try { saveBlob(await attachmentApi.download(a.id) as unknown as Blob, a.fileName) }
  catch (e: any) { showError(e, '下载失败') }
}

/* ---------- 在线查看 ----------
 * 只放行浏览器能安全直接显示的两类：图片、PDF。Office/压缩包浏览器打不开，仍只能下载。
 * 类型按文件扩展名白名单自己定，不信后端回来的 Content-Type——万一有人传了伪装成图片的网页文件，
 * 也只会被当图片解码(显示失败)，不会被当网页执行。
 * 取文件走的是和「下载」同一个接口，能不能看由后端同一套权限决定，前端没有另开口子。 */
const PREVIEW_MIME: Record<string, string> = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', pdf: 'application/pdf'
}
function previewMime(name: string) {
  return PREVIEW_MIME[String(name || '').split('.').pop()!.toLowerCase()] || ''
}
function previewKind(name: string): 'image' | 'pdf' | '' {
  const mime = previewMime(name)
  return mime ? (mime === 'application/pdf' ? 'pdf' : 'image') : ''
}
const previewingId = ref<number | null>(null)
const viewerUrl = ref('')
function closeViewer() {
  if (viewerUrl.value) URL.revokeObjectURL(viewerUrl.value)
  viewerUrl.value = ''
}
onBeforeUnmount(closeViewer)

async function doPreview(a: any) {
  const kind = previewKind(a.fileName)
  if (!kind) return
  // PDF 要开新标签页：必须在点击的同一瞬间先把页签开出来，等文件下完再开会被浏览器当弹窗拦掉
  const tab = kind === 'pdf' ? window.open('', '_blank') : null
  previewingId.value = a.id
  try {
    const raw = await attachmentApi.download(a.id) as unknown as Blob
    if (raw?.type?.includes('json')) throw new Error(JSON.parse(await raw.text())?.msg || '无法查看')
    const url = URL.createObjectURL(new Blob([raw], { type: previewMime(a.fileName) }))
    if (kind === 'image') { closeViewer(); viewerUrl.value = url; return }
    if (tab) tab.location.href = url   // 不回收这个地址：用户在那个标签页刷新/另存时还要用，关掉 ERP 页面时浏览器自会释放
    else { URL.revokeObjectURL(url); ElMessage.warning('浏览器拦截了新窗口，请允许弹出窗口后重试，或点「下载」') }
  } catch (e: any) { tab?.close(); showError(e, '查看失败') }
  finally { previewingId.value = null }
}

async function doRemove(a: any) {
  await ElMessageBox.confirm(`删除附件「${a.fileName}」?（删除会计入操作日志）`, '提示', { type: 'warning' })
  try { await attachmentApi.remove(a.id); ElMessage.success('已删除'); await load() }
  catch (e: any) { showError(e, '删除失败') }
}

// bizId 变化(切到另一张单/从新建切到已存单)时,清掉上一上下文残留的暂存附件,防串单
watch(() => props.bizId, () => { pending.value = []; load() })
onMounted(load)
</script>

<style scoped>
/* 2026-10-17：不再单独套一圈框线（详情弹窗里框中套框，用户嫌乱）；标题与「审批记录」等小节同一样式 */
.doc-att { margin-top: 10px; }
.att-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.att-title { font-weight: 600; color: var(--table-header-text, #374151); font-size: 14px; }
.att-empty { margin-top: 6px; font-size: 13px; color: #9ca3af; }
.att-count { margin-left: 4px; background: #eef2f7; border-radius: 8px; padding: 0 6px; font-size: 12px; color: #4b5563; }
.att-tip { font-size: 12px; color: #9ca3af; }
.att-list { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.att-row { display: flex; align-items: center; gap: 10px; padding: 4px 6px; border-radius: 6px; }
.att-row:hover { background: #f8fafc; }
.att-row.pending { background: #fffaf0; }
.att-name { font-size: 13px; color: #1f2937; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-meta { font-size: 12px; color: #9ca3af; flex: 1; }
.pend-tag { color: #b7791f; }
</style>
