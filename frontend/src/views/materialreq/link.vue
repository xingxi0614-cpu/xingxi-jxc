<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">提交链接管理</div>
        <div class="page-sub">每个公司生成一个免登录提交链接，发到微信群里。一线点链接填表提交领料申请（仅计划，不动库存）。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button type="primary" @click="openCreate">+ 新增链接</el-button>
        <el-button @click="load">刷新</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :data="rows" v-loading="loading" empty-text="暂无提交链接">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" type="index" label="序号" width="64" />
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" label="公司" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgName || orgName(row.orgId) || row.orgId }}</template>
        </el-table-column>
        <el-table-column label="默认仓库" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.warehouseName || '-' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.label || '-' }}</template>
        </el-table-column>
        <el-table-column label="提交链接" min-width="320">
          <template #default="{ row }">
            <div class="link-cell">
              <el-input :model-value="fullUrl(row.token)" readonly size="small" />
              <el-button size="small" @click="copy(row.token)">复制</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="270">
          <template #default="{ row }">
            <el-button link type="success" @click="showQr(row)">二维码</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="regen(row)">重置链接</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert type="info" :closable="false" show-icon class="mt-3"
        title="「重置链接」会让旧链接立即失效；停用后该链接不再接受提交。链接只是计划入口，提交的申请须经采购→仓管→财务确认才生成正式出库申请。" />
    </el-card>

    <el-dialog v-model="dialogVisible" :before-close="dialogGuard.beforeClose" :title="form.id ? '编辑提交链接' : '新增提交链接'" width="460px">
      <el-form label-position="top" :model="form" label-width="92px">
        <el-form-item label="公司" required>
          <el-select v-model="form.orgId" placeholder="选择公司" filterable style="width:100%" :disabled="!!form.id" @change="onOrgChange">
            <el-option v-for="o in orgOptions" :key="o.id" :value="o.id" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认仓库" required>
          <el-select v-model="form.warehouseId" placeholder="选择仓库（申请显示该仓库存）" filterable style="width:100%">
            <el-option v-for="w in warehouseOptions" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.label" placeholder="如：一号仓库领料入口" maxlength="80" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="qrVisible" title="提交二维码" width="360px" align-center>
      <div class="qr-box">
        <img v-if="qrDataUrl" :src="qrDataUrl" class="qr-img" alt="提交二维码" />
        <div class="qr-label">{{ qrLabel }}</div>
        <div class="qr-url">{{ qrUrl }}</div>
        <div class="qr-tip">微信扫码即可打开提交页（可选领料申请/采购计划）。也可保存图片发到群里。</div>
      </div>
      <template #footer>
        <el-button @click="qrVisible = false">关闭</el-button>
        <el-button @click="copy(qrToken)">复制链接</el-button>
        <el-button type="primary" @click="downloadQr">下载二维码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { materialReqApi, masterApi, sysApi } from '@/api'
import { showError } from '@/utils/notify'

const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const orgOptions = ref<any[]>([])
const warehouses = ref<any[]>([])

function flattenOrgs(nodes: any[], level = 0, out: any[] = []) {
  for (const node of nodes || []) {
    out.push({ id: node.id, name: node.name, label: `${'　'.repeat(level)}${node.name}（${node.code}）`, type: node.type, status: node.status })
    flattenOrgs(node.children || [], level + 1, out)
  }
  return out
}
function orgName(id: number) { return orgOptions.value.find(o => o.id === id)?.name || '' }

function fullUrl(token: string) {
  return `${location.origin}/#/lr-apply?token=${token}`
}
async function copy(token: string) {
  try { await navigator.clipboard.writeText(fullUrl(token)); ElMessage.success('链接已复制，可粘贴到微信群') }
  catch { ElMessage.warning('复制失败，请手动选中复制') }
}

// 二维码分享
const qrVisible = ref(false)
const qrDataUrl = ref('')
const qrUrl = ref('')
const qrLabel = ref('')
const qrToken = ref('')
async function showQr(row: any) {
  qrToken.value = row.token
  qrUrl.value = fullUrl(row.token)
  qrLabel.value = row.label || `${row.orgName || orgName(row.orgId) || ''}提交入口`
  try { qrDataUrl.value = await QRCode.toDataURL(qrUrl.value, { width: 260, margin: 1 }) }
  catch { qrDataUrl.value = ''; ElMessage.error('二维码生成失败') }
  qrVisible.value = true
}
function downloadQr() {
  if (!qrDataUrl.value) return
  const a = document.createElement('a')
  a.href = qrDataUrl.value
  a.download = `${qrLabel.value || '提交'}-二维码.png`
  a.click()
}

const warehouseOptions = computed(() =>
  warehouses.value.filter((w: any) => !form.orgId || w.orgId == null || w.orgId === form.orgId))

async function loadOptions() {
  orgOptions.value = flattenOrgs(await sysApi.orgTree() as any[]).filter(o => o.status !== 0 && o.type === 'COMPANY')
  warehouses.value = await masterApi.warehouses(true) as any
}

async function load() {
  loading.value = true
  try { rows.value = await materialReqApi.linkList({}) as any }
  finally { loading.value = false }
}

const dialogVisible = ref(false)
const dialogGuard = useUnsavedGuard(dialogVisible)
const saving = ref(false)
const form = reactive<any>({ id: null, orgId: null, warehouseId: null, label: '', status: 1 })

function onOrgChange() { form.warehouseId = null }
function openCreate() {
  Object.assign(form, { id: null, orgId: orgOptions.value[0]?.id || null, warehouseId: null, label: '', status: 1 })
  dialogVisible.value = true
}
function openEdit(row: any) {
  Object.assign(form, { id: row.id, orgId: row.orgId, warehouseId: row.warehouseId, label: row.label, status: row.status })
  dialogVisible.value = true
}

async function save() {
  if (!form.orgId) { ElMessage.warning('请选择公司'); return }
  if (!form.warehouseId) { ElMessage.warning('请选择默认仓库'); return }
  saving.value = true
  try {
    if (form.id) await materialReqApi.linkUpdate(form.id, { warehouseId: form.warehouseId, label: form.label, status: form.status })
    else await materialReqApi.linkCreate({ orgId: form.orgId, warehouseId: form.warehouseId, label: form.label, status: form.status })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e: any) { showError(e, '保存失败') }
  finally { saving.value = false }
}

async function regen(row: any) {
  await ElMessageBox.confirm('重置后旧链接立即失效，需重新发新链接。确定重置?', '提示', { type: 'warning' })
  await materialReqApi.linkRegen(row.id)
  ElMessage.success('已重置链接')
  await load()
}
async function remove(row: any) {
  await ElMessageBox.confirm('确定删除该提交链接?', '提示', { type: 'warning' })
  await materialReqApi.linkDelete(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => { await loadOptions(); await load() })
</script>

<style scoped>
.link-cell { display: flex; gap: 6px; align-items: center; }
.mt-3 { margin-top: 12px; }
.qr-box { text-align: center; }
.qr-img { width: 240px; height: 240px; border: 1px solid #eef1f5; border-radius: 8px; }
.qr-label { font-weight: 700; color: #1f2937; margin-top: 10px; }
.qr-url { font-size: 12px; color: #9ca3af; word-break: break-all; margin-top: 4px; }
.qr-tip { font-size: 12px; color: #6b7280; margin-top: 10px; line-height: 1.6; }
</style>
