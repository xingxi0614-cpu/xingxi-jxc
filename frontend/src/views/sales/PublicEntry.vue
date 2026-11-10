<template>
  <div class="so-page">
    <div class="so-card">
      <div class="so-brand">
        <div class="so-title">销售录单</div>
        <div v-if="ctx" class="so-chips">
          <span class="so-chip">{{ ctx.orgName }}</span>
          <span class="so-chip ghost">{{ ctx.label || ctx.warehouseName }}</span>
        </div>
      </div>

      <div v-if="invalid" class="so-invalid">
        <el-icon><WarningFilled /></el-icon>
        <p>{{ invalidMsg || '链接无效或已停用,请联系管理员获取新链接' }}</p>
      </div>

      <div v-else-if="submitted" class="so-done">
        <el-result icon="success" title="已提交" :sub-title="`单号:${resultDocNo}`">
          <template #extra><el-button type="primary" @click="resetForNext">再录一单</el-button></template>
        </el-result>
        <p class="so-tip">提交的是销售订单,公司核对后再进入审批。此时还未出库。</p>
      </div>

      <el-form v-else-if="ctx" label-position="top" class="so-form">
        <el-form-item label="客户姓名" required>
          <el-input v-model="form.customerName" placeholder="必填" clearable />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.customerPhone" placeholder="选填" clearable inputmode="tel" />
        </el-form-item>

        <div class="so-sec">
          商品明细
          <el-button link type="primary" @click="addLine">+ 加一项</el-button>
        </div>

        <div v-for="(l, i) in form.lines" :key="i" class="so-line">
          <div class="so-line-head">
            <span>第 {{ i + 1 }} 项</span>
            <el-button v-if="form.lines.length > 1" link type="danger" @click="form.lines.splice(i, 1)">删除</el-button>
          </div>
          <el-select
            v-model="l.materialId" filterable remote clearable
            :remote-method="(kw:string) => searchMat(kw)" :loading="matLoading"
            placeholder="选择型号(可搜编码/名称)" style="width:100%"
          >
            <el-option v-for="m in mats" :key="m.id" :value="m.id"
                       :label="`${m.code} ${m.name}${m.spec ? '(' + m.spec + ')' : ''}`" />
          </el-select>
          <div class="so-line-row">
            <el-input-number v-model="l.qty" :min="0.01" :precision="2" :controls="false"
                             placeholder="数量" class="so-num" />
            <el-input-number v-model="l.amount" :min="0" :precision="2" :controls="false"
                             placeholder="金额(元)" class="so-num" />
          </div>
        </div>

        <div class="so-total">
          合计 <b>{{ fmtMoney(totalAmt) }}</b> 元
        </div>

        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>

        <el-button type="primary" size="large" style="width:100%" :loading="saving" @click="submit">
          提交
        </el-button>
      </el-form>

      <div v-else class="so-loading">加载中…</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { soPublicApi } from '@/api'
import { showError } from '@/utils/notify'

const route = useRoute()
const token = String(route.query.token || '')

const ctx = ref<any>(null)
const invalid = ref(false)
const invalidMsg = ref('')
const submitted = ref(false)
const resultDocNo = ref('')
const saving = ref(false)
const mats = ref<any[]>([])
const matLoading = ref(false)

const form = reactive<any>({
  customerName: '', customerPhone: '', remark: '',
  lines: [{ materialId: null, qty: null, amount: null }]
})

const totalAmt = computed(() =>
  form.lines.reduce((s: number, l: any) => s + Number(l.amount || 0), 0))

function fmtMoney(v: any) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function addLine() { form.lines.push({ materialId: null, qty: null, amount: null }) }
function resetForNext() {
  submitted.value = false
  form.customerName = ''; form.customerPhone = ''; form.remark = ''
  form.lines = [{ materialId: null, qty: null, amount: null }]
}

async function searchMat(kw: string) {
  matLoading.value = true
  try {
    mats.value = await soPublicApi.materials(token, kw) as any || []
  } catch { mats.value = [] } finally { matLoading.value = false }
}

async function submit() {
  if (!form.customerName.trim()) { ElMessage.warning('请填写客户姓名'); return }
  const lines = form.lines.filter((l: any) => l.materialId)
  if (!lines.length) { ElMessage.warning('请至少选择一项商品'); return }
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].qty || Number(lines[i].qty) <= 0) { ElMessage.warning(`第 ${i + 1} 项请填数量`); return }
    if (lines[i].amount === null || lines[i].amount === undefined || Number(lines[i].amount) < 0) {
      ElMessage.warning(`第 ${i + 1} 项请填金额`); return
    }
  }
  saving.value = true
  try {
    const r: any = await soPublicApi.submit({
      token, customerName: form.customerName.trim(), customerPhone: form.customerPhone,
      remark: form.remark, lines
    })
    resultDocNo.value = r?.docNo || ''
    submitted.value = true
  } catch (e: any) {
    showError(e, '提交失败,请重试')
  } finally { saving.value = false }
}

onMounted(async () => {
  if (!token) { invalid.value = true; return }
  try {
    ctx.value = await soPublicApi.context(token)
    await searchMat('')
  } catch (e: any) {
    invalid.value = true
    invalidMsg.value = e?.message || ''
  }
})
</script>

<style scoped>
/* 手机优先:网点是在手机上扫码打开的 */
.so-page { min-height: 100vh; background: #f2f5f9; padding: 12px; box-sizing: border-box; }
.so-card { max-width: 560px; margin: 0 auto; background: #fff; border-radius: 14px; padding: 18px; box-shadow: 0 6px 22px rgba(16,24,40,.08); }
.so-brand { margin-bottom: 14px; }
.so-title { font-size: 20px; font-weight: 750; color: #1a1f29; }
.so-chips { margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.so-chip { font-size: 12px; padding: 2px 10px; border-radius: 999px; background: #e8f1fb; color: #1d63b0; }
.so-chip.ghost { background: #f0f2f5; color: #5a6373; }
.so-sec { display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 650; color: #5a6373; margin: 14px 0 8px; }
.so-line { border: 1px solid #e9eef4; border-radius: 10px; padding: 10px; margin-bottom: 10px; background: #fafcff; }
.so-line-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #98a1b0; margin-bottom: 8px; }
.so-line-row { display: flex; gap: 8px; margin-top: 8px; }
.so-num { flex: 1; }
.so-num :deep(.el-input__inner) { text-align: left; }
.so-total { text-align: right; font-size: 14px; color: #5a6373; margin: 6px 0 14px; }
.so-total b { font-size: 19px; color: #1d63b0; }
.so-invalid { text-align: center; padding: 40px 12px; color: #d93b3b; }
.so-invalid p { margin-top: 10px; font-size: 14px; }
.so-tip { text-align: center; font-size: 12px; color: #98a1b0; margin-top: 8px; line-height: 1.6; }
.so-loading { text-align: center; padding: 50px; color: #98a1b0; }
</style>
