<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">现存量</div>
        <div class="page-sub">{{ canSeeCost ? '实时现存量，不分结账期、录单即变，可作盘点底表；结存单价按移动加权平均法计算' : '实时现存量，不分结账期、录单即变，可作盘点底表' }}</div>
      </div>
      <el-space>
        <el-button type="primary" :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('account_admin') || user.hasRole('admin')" @click="openingVisible = true">期初导入</el-button>
        <el-button v-if="user.hasFinanceAccessOrAdmin()" :loading="recalcing" @click="triggerRecalc">重算结存单价</el-button>
        <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!rows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :icon="CopyDocument" @click="copyTable">复制</el-button>
      </el-space>
    </div>

    <!-- 期初库存导入（新账套开账用） -->
    <el-dialog v-model="openingVisible" title="期初库存导入" width="min(480px, 94vw)" align-center>
      <el-form label-position="top" label-width="72px">
        <el-form-item label="仓库" required>
          <el-select v-model="openingWhId" placeholder="选择要导入期初的仓库" filterable style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件">
          <el-upload :show-file-list="false" accept=".xls,.xlsx" :http-request="uploadOpening" :disabled="!openingWhId || openingLoading">
            <el-button type="primary" :loading="openingLoading" :disabled="!openingWhId">选择 Excel 并导入</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
        title="列：物料编码 / 数量 / 单价(不含税成本)。物料须已在物料档案中存在；该仓已有结存的物料会自动跳过，避免重复导入把期初翻倍。导入会记 INIT 期初流水，可在收发流水中溯源。" />
      <template #footer>
        <el-button @click="downloadOpeningTemplate">下载模板</el-button>
        <el-button @click="openingVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-card shadow="never">
      <QueryDrawer :model="query" ref="drawerRef" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'includeZero') includeZero = false }">
        <el-alert v-if="onlyAbnormal" title="当前显示结账检查的库存异常，包含零数量但金额不为零的记录；重置可查看全部库存" type="warning" :closable="false" style="margin-bottom:12px" />
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="keyword" label="物料关键字" style="grid-column: 1 / -1">
          <el-input v-model="query.keyword" clearable maxlength="500" style="width:100%"
                    placeholder="输入编码、名称或规格，如：L型角铁"
                    @keyup.enter="drawerRef?.confirm()" />
          <div class="kw-tip">一次列出所有匹配物料的库存。多个用逗号隔开，如"M10001,M10002"；空格表示同时包含，如"角铁 150"。</div>
        </el-form-item>
        <el-form-item data-q="matId" label="指定物料">
          <MaterialLookup v-model="query.matId" />
        </el-form-item>
        <el-form-item data-q="whId" label="仓库">
          <el-select v-model="query.whId" placeholder="全部仓库" clearable filterable style="width:180px">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="includeZero">
          <el-checkbox v-model="includeZero">包含零库存</el-checkbox>
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 300px)" class="readable-table" border :fit="false" :data="pageRows" :index-offset="pgOffset" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" show-summary :summary-method="(p: any) => balanceSummary({ ...p, data: rows })">
        <el-table-column prop="matCode" label="物料编码" min-width="180" show-overflow-tooltip />
        <el-table-column prop="matName" label="物料名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="matSpec" label="规格型号" min-width="150" />
        <el-table-column prop="matUnit" label="单位" width="80" />
        <el-table-column prop="whName" label="仓库" min-width="150" show-overflow-tooltip />
        <el-table-column prop="qty"     label="结存数量" align="right" width="120" :formatter="$qtyFmt" />
        <el-table-column v-if="canSeeCost" label="结存金额" align="right" width="140">
          <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="结存单价" align="right" width="140">
          <template #default="{ row }"><b style="color:var(--color-primary)">{{ fmtPrice(row.avg_price ?? row.avgPrice) }}</b></template>
        </el-table-column>
      </el-table>
      <!-- 前端分页：只画当前页，合计/汇总卡/导出仍按全部行（见 useClientPager） -->
      <el-pagination v-if="rows.length > pgSize || pgSize !== 100" v-model:current-page="pgPage" v-model:page-size="pgSize" :total="rows.length"
                     :page-sizes="[50, 100, 200, 500]" layout="total, sizes, prev, pager, next, jumper"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useClientPager } from '@/composables/useClientPager'
import { makeLineSummary } from '@/utils/tableSummary'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, CopyDocument } from '@/icons'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inventoryApi, masterApi } from '@/api'
import { fmtMoney, fmtPrice } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, type ExportColumn } from '@/utils/tableExport'
import { saveBlob } from '@/utils/download'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import { showError } from '@/utils/notify'

const user = useUserStore()
const route = useRoute()
const loading = ref(false)
const recalcing = ref(false)
const rows = ref<any[]>([])
const { page: pgPage, size: pgSize, pageRows, offset: pgOffset } = useClientPager(rows)
const searched = ref(false)
// 支持带关键字打开(集团汇总分析的物料排行点进来)
const query = ref<any>({ keyword: String(route.query.keyword || ''), matId: null, whId: null })
const drawerRef = ref<{ confirm: () => void }>()
const includeZero = ref(route.query.abnormal === '1')
const onlyAbnormal = ref(route.query.abnormal === '1')
const warehouses = ref<any[]>([])
const canSeeCost = user.hasFinanceAccess()
const exportColumns = computed<ExportColumn[]>(() => {
  const cols: ExportColumn[] = [
    { label: '物料编码', key: 'matCode' },
    { label: '物料名称', key: 'matName' },
    { label: '规格型号', key: 'matSpec' },
    { label: '单位', key: 'matUnit' },
    { label: '仓库', key: 'whName' },
    { label: '结存数量', qty: true, key: 'qty' }
  ]
  if (canSeeCost) {
    cols.push(
      { label: '结存金额', money: true, value: row => fmtMoney(row.amount) },
      { label: '结存单价', money: true, value: row => fmtPrice(row.avg_price ?? row.avgPrice) }
    )
  }
  return cols
})

async function loadOptions() {
  warehouses.value = await masterApi.warehouses(true) as any
}

function doSearch() {
  return load()
}

function resetQuery() {
  query.value = { keyword: '', matId: null, whId: null }
  includeZero.value = false
  onlyAbnormal.value = false
  load()
}

async function load() {
  searched.value = true
  loading.value = true
  try { const data = await inventoryApi.balance({ ...query.value, includeZero: includeZero.value ? 1 : 0 }) as any[]
    rows.value = onlyAbnormal.value ? data.filter(row => Number(row.qty) < 0 || Number(row.amount) < 0
      || Number(row.avg_price ?? row.avgPrice) < 0 || (Number(row.qty) === 0 && Math.round(Number(row.amount) * 100) !== 0)) : data } finally { loading.value = false }
}

// 重算结存单价：按当前结存数量与金额，重新计算可见仓库的移动加权平均单价（原「库存成本参考」页的功能并入此处）
async function triggerRecalc() {
  try {
    await ElMessageBox.confirm(
      '将按当前结存数量和金额，重新计算本账套可见仓库的结存单价（移动加权平均）。是否继续？',
      '重算结存单价',
      { type: 'warning', confirmButtonText: '确认重算', cancelButtonText: '取消' }
    )
  } catch { return }
  recalcing.value = true
  try {
    const result: any = await inventoryApi.recalc({})
    ElMessage.success(result?.msg || '结存单价已重算')
    if (searched.value) await load()
  } finally {
    recalcing.value = false
  }
}
function exportExcel() {
  exportRowsToExcel('库存余额', exportColumns.value, rows.value, '库存余额')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('库存余额', exportColumns.value, rows.value, action, paper, canSeeCost ? '实时库存余额' : '实时库存数量')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns.value, rows.value)
  ElMessage.success('已复制，可直接粘贴到 Excel')
}

// ── 期初库存导入（账套管理员开账用） ──
const openingVisible = ref(false)
const openingWhId = ref<number | null>(null)
const openingLoading = ref(false)
async function uploadOpening(options: any) {
  if (!openingWhId.value) { ElMessage.warning('请先选择仓库'); return }
  openingLoading.value = true
  try {
    const result: any = await inventoryApi.openingImport(openingWhId.value, options.file)
    const msg = `期初导入完成：成功 ${result.inserted || 0} 行，跳过 ${result.skipped || 0} 行`
    if (result.errors?.length) ElMessage.warning(`${msg}；${result.errors.slice(0, 3).join('；')}${result.errors.length > 3 ? '…' : ''}`)
    else ElMessage.success(msg)
    if (searched.value) await load()
  } catch (e: any) {
    showError(e, '期初导入失败')
  } finally {
    openingLoading.value = false
  }
}
async function downloadOpeningTemplate() {
  const blob = await inventoryApi.openingImportTemplate() as unknown as Blob
  saveBlob(blob, '期初库存导入模板.xlsx')
}

onMounted(async () => { await loadOptions(); await load() })

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const balanceSummary = makeLineSummary({ 结存数量: { field: 'qty', type: 'qty' }, 结存金额: { field: 'amount', type: 'money' } })
</script>

<style scoped>
.kw-tip { width: 100%; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; margin-top: 2px; }
</style>
