<template>
  <div class="lr-apply">
    <div class="lr-card">
      <div class="lr-brand">
        <div class="lr-title">{{ mode === 'pp' ? '采购计划' : mode === 'lr' ? '领料申请' : '在线提报' }}</div>
        <div v-if="ctx" class="lr-chips">
          <span class="lr-chip">{{ ctx.orgName }}</span>
          <span class="lr-chip ghost">仓库 · {{ ctx.warehouseName }}</span>
        </div>
      </div>

      <div v-if="invalid" class="lr-invalid">
        <el-icon><WarningFilled /></el-icon>
        <p>{{ invalidMsg || '链接无效或已停用，请联系管理员获取新链接' }}</p>
      </div>

      <div v-else-if="submitted" class="lr-done">
        <el-result icon="success" title="已提交，等待处理" :sub-title="`单号：${resultDocNo}`">
          <template #extra><el-button type="primary" @click="resetForNext">再提交一单</el-button></template>
        </el-result>
        <p class="lr-tip">{{ mode === 'pp' ? '采购计划仅供采购结合库存制定采购量，提交后等采购确认。' : '提交的只是领料计划，需采购→仓管→财务确认后由仓库发料。' }}</p>
      </div>

      <!-- 类型选择 -->
      <div v-else-if="ctx && !mode" class="lr-chooser">
        <button type="button" class="choose-card lr-tone" @click="pick('lr')">
          <el-icon><Tickets /></el-icon><span class="c-t">领料申请</span><span class="c-s">提报领用材料，走审核后发料</span>
        </button>
        <button type="button" class="choose-card pp-tone" @click="pick('pp')">
          <el-icon><ShoppingCart /></el-icon><span class="c-t">采购计划</span><span class="c-s">提报采购需求，结合库存制定采购量</span>
        </button>
      </div>

      <!-- 表单（领料申请 / 采购计划共用物料明细） -->
      <template v-else-if="ctx && mode">
        <el-button link class="lr-back" @click="backToChooser">‹ 返回选择</el-button>

        <div class="lr-sec">基本信息</div>
        <el-form label-position="top" class="lr-form" :model="form">
          <template v-if="mode === 'lr'">
            <el-form-item label="领料人" required>
              <el-select v-model="form.pickerId" placeholder="选择领料人" filterable style="width:100%" @change="onPickerChange">
                <el-option v-for="p in ctx.pickers" :key="p.id" :value="p.id" :label="p.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="工程项目" required>
              <el-select v-model="form.projectId" placeholder="选择工程项目" filterable style="width:100%">
                <el-option v-for="p in ctx.projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
              </el-select>
            </el-form-item>
            <div class="lr-row2">
              <el-form-item label="业务分类" required>
                <el-select v-model="form.bizType" placeholder="选择" filterable style="width:100%">
                  <el-option v-for="c in ctx.businessCategories" :key="c" :value="c" :label="c" />
                </el-select>
              </el-form-item>
            </div>
            <el-form-item label="施工地点"><el-input v-model="form.site" placeholder="选填" /></el-form-item>
            <el-form-item label="填表人"><el-input v-model="form.applicantName" placeholder="默认同领料人，可修改" /></el-form-item>
          </template>
          <template v-else>
            <div class="lr-row2">
              <el-form-item label="提报人" required><el-input v-model="form.applicantName" placeholder="填写你的姓名" /></el-form-item>
              <el-form-item label="部门"><el-input v-model="form.dept" placeholder="选填" /></el-form-item>
            </div>
          </template>
        </el-form>

        <div class="lr-sec">物料明细</div>
        <div class="lr-search">
          <el-input v-model="keyword" placeholder="搜物料编码/名称/规格" clearable @keyup.enter="doSearch">
            <template #append><el-button :loading="searching" @click="doSearch">搜索</el-button></template>
          </el-input>
        </div>
        <div v-if="searchResults.length" class="lr-results">
          <div v-for="m in searchResults" :key="m.id" class="lr-result" @click="addLine(m)">
            <div class="lr-result-main"><span class="lr-mname">{{ m.name }}</span><span class="lr-mspec">{{ m.spec || '' }}</span></div>
            <div class="lr-result-sub">
              <span>{{ m.code }} · {{ m.unit || '' }}</span>
              <span class="lr-stock">库存 {{ fmtQty(m.stockQty) }}<template v-if="mode === 'pp'"> · 最低 {{ fmtQty(m.safetyQty) }}</template></span>
            </div>
          </div>
        </div>

        <div v-if="lines.length" class="lr-lines">
          <div v-for="(ln, idx) in lines" :key="ln.materialId" class="lr-line">
            <div class="lr-line-head">
              <div>
                <div class="lr-mname">{{ ln.name }} <span class="lr-mspec">{{ ln.spec || '' }}</span></div>
                <div class="lr-result-sub">
                  <span>{{ ln.code }} · {{ ln.unit || '' }}</span>
                  <span class="lr-stock">库存 {{ fmtQty(ln.stockQty) }}<template v-if="mode === 'pp'"> · 最低 {{ fmtQty(ln.safetyQty) }}</template></span>
                </div>
              </div>
              <el-button link type="danger" @click="removeLine(idx)">移除</el-button>
            </div>
            <div class="lr-line-qty">
              <span>{{ mode === 'pp' ? '需求数量' : '申领数量' }}</span>
              <el-input-number v-model="ln.qty" :min="0" :precision="2" style="width:150px" />
              <span class="lr-unit">{{ ln.unit || '' }}</span>
            </div>
            <div v-if="mode === 'pp'" class="lr-suggest">建议采购量 ≈ {{ fmtQty(suggest(ln)) }}（需求+最低−库存）</div>
            <div v-else-if="overStock(ln)" class="lr-over">⚠ 超过当前库存（{{ fmtQty(ln.stockQty) }}），可提交</div>
          </div>
        </div>
        <div v-else class="lr-empty">搜索并点选物料，填{{ mode === 'pp' ? '需求' : '申领' }}数量</div>

        <div class="lr-att">
          <div class="lr-att-head">
            <span>照片/附件（选填）</span>
            <el-upload :show-file-list="false" accept=".jpg,.jpeg,.png,.webp,.pdf" :http-request="pickFile" multiple>
              <el-button size="small">添加照片/附件</el-button>
            </el-upload>
          </div>
          <div v-for="(f, i) in pendingFiles" :key="i" class="lr-att-row">
            <span class="lr-att-name">{{ f.name }}</span>
            <el-button link type="danger" size="small" @click="pendingFiles.splice(i, 1)">移除</el-button>
          </div>
          <p v-if="pendingFiles.length" class="lr-att-tip">提交时随单一起上传，采购确认时可看到。</p>
        </div>

        <el-button type="primary" size="large" class="lr-submit" :loading="submitting" @click="submit">提交{{ mode === 'pp' ? '采购计划' : '领料申请' }}</el-button>
        <p class="lr-tip">{{ mode === 'pp' ? '采购计划仅供采购参考制定采购量，不动库存。' : '本提交仅为领料计划，不直接影响库存；库存为实时参考，实际以发料为准。' }}</p>
      </template>

      <div v-else class="lr-loading" v-loading="true" style="height:120px" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WarningFilled, Tickets, ShoppingCart } from '@element-plus/icons-vue'
import { materialReqApi } from '@/api'
import { showError } from '@/utils/notify'

const route = useRoute()
const token = String(route.query.token || '')

const invalid = ref(false)
const invalidMsg = ref('')
const ctx = ref<any>(null)
const mode = ref<'' | 'lr' | 'pp'>('')

const form = reactive<any>({ pickerId: null, projectId: null, bizType: '', site: '', applicantName: '', dept: '' })
const keyword = ref('')
const searching = ref(false)
const searchResults = ref<any[]>([])
const lines = ref<any[]>([])
const submitting = ref(false)
const submitted = ref(false)
const resultDocNo = ref('')
const lastPickerName = ref('')

function fmtQty(v: any) { return Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }
function overStock(ln: any) { return Number(ln.qty || 0) > Number(ln.stockQty || 0) }
function suggest(ln: any) { return Math.max(0, Number(ln.qty || 0) + Number(ln.safetyQty || 0) - Number(ln.stockQty || 0)) }

function pick(m: 'lr' | 'pp') { mode.value = m; lines.value = []; searchResults.value = []; keyword.value = '' }
function backToChooser() { mode.value = ''; lines.value = []; searchResults.value = []; keyword.value = '' }

function onPickerChange(id: number) {
  const p = ctx.value?.pickers?.find((x: any) => x.id === id)
  if (p && (!form.applicantName || form.applicantName === lastPickerName.value)) form.applicantName = p.name
  lastPickerName.value = p?.name || ''
}

async function loadContext() {
  if (!token) { invalid.value = true; invalidMsg.value = '链接缺少参数'; return }
  try { ctx.value = await materialReqApi.publicContext(token) as any }
  catch (e: any) { invalid.value = true; invalidMsg.value = e?.message || '链接无效或已停用' }
}

async function doSearch() {
  searching.value = true
  try {
    searchResults.value = await materialReqApi.publicMaterials(token, keyword.value.trim()) as any
    if (!searchResults.value.length) ElMessage.info('没有匹配的物料')
  } catch (e: any) { showError(e, '搜索失败') }
  finally { searching.value = false }
}

function addLine(m: any) {
  if (lines.value.some(l => l.materialId === m.id)) { ElMessage.info('该物料已在清单中'); return }
  lines.value.push({ materialId: m.id, code: m.code, name: m.name, spec: m.spec, unit: m.unit, stockQty: m.stockQty, safetyQty: m.safetyQty, qty: 1, remark: '' })
}
function removeLine(idx: number) { lines.value.splice(idx, 1) }

async function submit() {
  const valid = lines.value.filter(l => Number(l.qty) > 0)
  if (!valid.length) { ElMessage.warning('请添加物料并填写数量'); return }
  submitting.value = true
  try {
    let r: any
    if (mode.value === 'pp') {
      if (!form.applicantName?.trim()) { ElMessage.warning('请填写提报人'); submitting.value = false; return }
      r = await materialReqApi.publicPpSubmit({
        token, applicantName: form.applicantName, dept: form.dept,
        lines: valid.map(l => ({ materialId: l.materialId, demandQty: l.qty, remark: l.remark }))
      })
    } else {
      if (!form.pickerId) { ElMessage.warning('请选择领料人'); submitting.value = false; return }
      if (!form.projectId) { ElMessage.warning('请选择工程项目'); submitting.value = false; return }
      if (!form.bizType) { ElMessage.warning('请选择业务分类'); submitting.value = false; return }
      r = await materialReqApi.publicSubmit({
        token, pickerId: form.pickerId, projectId: form.projectId, bizType: form.bizType,
        site: form.site, applicantName: form.applicantName,
        lines: valid.map(l => ({ materialId: l.materialId, qty: l.qty, remark: l.remark }))
      })
    }
    resultDocNo.value = r?.docNo || ''
    // 提交成功后上传照片/附件（挂到刚建的申请上，采购确认时可见）
    if (r?.id && r?.bizType && pendingFiles.value.length) {
      for (const f of [...pendingFiles.value]) {
        try { await materialReqApi.publicUploadAttachment(token, r.bizType, r.id, f) }
        catch (e: any) { ElMessage.warning(`附件「${f.name}」上传失败：${e?.message || ''}`) }
      }
    }
    pendingFiles.value = []
    submitted.value = true
  } catch (e: any) { showError(e, '提交失败') }
  finally { submitting.value = false }
}

const pendingFiles = ref<File[]>([])
async function pickFile(options: any) {
  const f: File = options.file
  if (f.size > 20 * 1024 * 1024) { ElMessage.warning('单个文件不能超过 20MB'); return }
  pendingFiles.value.push(f)
}

function resetForNext() {
  submitted.value = false
  resultDocNo.value = ''
  lines.value = []
  searchResults.value = []
  keyword.value = ''
  if (mode.value === 'lr') { form.projectId = null; form.bizType = ''; form.site = '' }
}

onMounted(loadContext)
</script>

<style scoped>
.lr-apply { min-height: 100vh; background: #eef2f7; padding: 14px; box-sizing: border-box; }
.lr-card { max-width: 560px; margin: 0 auto; background: #fff; border-radius: 18px; padding: 18px 16px 26px; box-shadow: 0 10px 30px rgba(15,23,42,.09); }
.lr-brand { display: flex; flex-direction: column; align-items: center; gap: 8px; margin-bottom: 16px; }
.lr-title { font-size: 21px; font-weight: 800; color: #1d63b0; letter-spacing: 1px; }
.lr-chips { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.lr-chip { background: #1d63b0; color: #fff; border-radius: 999px; padding: 3px 12px; font-size: 12px; font-weight: 600; }
.lr-chip.ghost { background: #eaf1fb; color: #1d63b0; }
.lr-chooser { display: flex; flex-direction: column; gap: 14px; padding: 8px 0 4px; }
.choose-card { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 18px; border: 1px solid #e5e7eb; border-radius: 14px; cursor: pointer; background: #fff; transition: transform .12s, box-shadow .12s; }
.choose-card:active { transform: scale(.99); }
.choose-card .el-icon { font-size: 26px; }
.choose-card .c-t { font-size: 17px; font-weight: 700; color: #1f2937; }
.choose-card .c-s { font-size: 12px; color: #9ca3af; }
.choose-card.lr-tone { border-color: #cfe1f7; } .choose-card.lr-tone .el-icon { color: #1d63b0; }
.choose-card.pp-tone { border-color: #cdeadd; } .choose-card.pp-tone .el-icon { color: #2f9e6f; }
.lr-back { margin-bottom: 4px; color: #6b7280; }
.lr-sec { font-weight: 700; color: #1f2937; font-size: 14px; margin: 6px 0 10px; padding-left: 9px; border-left: 3px solid #1d63b0; }
.lr-form :deep(.el-form-item) { margin-bottom: 12px; }
.lr-form :deep(.el-form-item__label) { padding-bottom: 2px; font-weight: 600; color: #374151; }
.lr-row2 { display: flex; gap: 10px; }
.lr-row2 :deep(.el-form-item) { flex: 1; }
.lr-search { margin-bottom: 10px; }
.lr-results { border: 1px solid #e5e7eb; border-radius: 12px; max-height: 240px; overflow-y: auto; margin-bottom: 12px; }
.lr-result { padding: 10px 12px; border-bottom: 1px solid #f0f2f5; cursor: pointer; }
.lr-result:last-child { border-bottom: 0; }
.lr-result:active { background: #f3f7fc; }
.lr-result-main { display: flex; gap: 8px; align-items: baseline; }
.lr-mname { font-weight: 600; color: #1f2937; }
.lr-mspec { font-size: 12px; color: #6b7280; }
.lr-result-sub { display: flex; justify-content: space-between; font-size: 12px; color: #9ca3af; margin-top: 2px; }
.lr-stock { color: #2f9e6f; }
.lr-lines { display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
.lr-line { border: 1px solid #e5e7eb; border-radius: 12px; padding: 11px 12px; background: #fcfdff; }
.lr-line-head { display: flex; justify-content: space-between; align-items: flex-start; }
.lr-line-qty { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.lr-line-qty > span:first-child { font-size: 13px; color: #6b7280; }
.lr-unit { color: #9ca3af; font-size: 13px; }
.lr-suggest { color: #2f8fd0; font-size: 12px; margin-top: 6px; }
.lr-over { color: #d9542b; font-size: 12px; margin-top: 6px; }
.lr-empty { color: #9ca3af; text-align: center; padding: 16px 0; border: 1px dashed #e0e4ea; border-radius: 12px; margin-bottom: 12px; }
.lr-submit { width: 100%; margin-top: 6px; height: 46px; font-size: 16px; border-radius: 12px; }
.lr-tip { color: #9ca3af; font-size: 12px; text-align: center; line-height: 1.6; margin-top: 12px; }
.lr-invalid { text-align: center; color: #d9542b; padding: 30px 0; }
.lr-invalid .el-icon { font-size: 40px; }
.lr-done { padding-top: 8px; }
.lr-att { border: 1px dashed #e0e4ea; border-radius: 12px; padding: 10px 12px; margin-bottom: 12px; }
.lr-att-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #4b5563; font-size: 13px; }
.lr-att-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 4px 2px; }
.lr-att-name { font-size: 13px; color: #1f2937; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lr-att-tip { color: #9ca3af; font-size: 12px; margin: 4px 0 0; }
</style>
