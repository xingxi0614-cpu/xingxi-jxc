<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">暂估清单</div>
        <div class="page-sub">货到票未到的入库单，等待发票核销</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button :icon="Download" @click="exportExcel">导出Excel</el-button>
        <PrintExportDropdown default-orientation="landscape" :disabled="!shownRows.length" label="打印/纸张" @print="paper => printReport('print', paper)" @export="paper => printReport('export', paper)" />
        <el-button :icon="CopyDocument" @click="copyTable">复制</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item label="入库单号" data-q="recvNo">
          <DocNoLookup v-model="query.recvNo" type="RECV" :params="{ recvType: 'ESTIMATE', status: 'APPROVED' }" placeholder="点一下列出最近入库单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="入库日期" data-q="dateRange">
          <el-date-picker v-model="query.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="供应商" data-q="supplierId">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
        <el-form-item label="关联订单" data-q="sourcePoNo">
          <DocNoLookup v-model="query.sourcePoNo" type="PO" placeholder="点一下列出最近采购订单，也可输入单号" @enter="doSearch" />
        </el-form-item>
        <el-form-item label="状态" data-q="status">
          <el-select v-model="query.status" placeholder="全部" clearable>
            <el-option label="待核销" value="UNMATCHED" />
            <el-option label="已核销" value="MATCHED" />
          </el-select>
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="shownRows" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" show-summary :summary-method="listSummary">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo"      label="入库单号"  width="160" />
        <el-table-column prop="sourcePoNo" label="关联订单" min-width="210" />
        <el-table-column prop="recvDate"   label="入库日期"  width="110" />
        <el-table-column prop="totalQty"   label="数量"     width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column label="暂估金额" width="140" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.invoiceId ? 'success' : 'warning'" size="small">
              {{ row.invoiceId ? '已核销' : '待核销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="goInvoice(row)">去录入发票核销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { navigateToInvoice } from '@/utils/invoiceNavigation'
import { Download, CopyDocument } from '@/icons'
import { ElMessage } from 'element-plus'
import { recvApi, masterApi } from '@/api'
import { fmtMoney } from '@/utils/format'
import { copyRowsForPaste, exportRowsToExcel, printOrExportRows, type ExportColumn } from '@/utils/tableExport'
import type { PaperAction, PaperSize } from '@/utils/printExport'
import SupplierLookup from '@/components/SupplierLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

const router = useRouter()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const searched = ref(false)
const query = ref<any>({ supplierId: null, recvNo: '', sourcePoNo: '', dateRange: null, status: '' })
/**
 * 入库单号/日期/关联订单/状态在页面上筛(2026-10-16):这个接口一次返回全部待开票暂估单、不分页,
 * 页面筛的就是全部数据,不存在"只筛当前页"的问题;供应商仍交给后端(接口本来就支持)。
 * 导出、打印、复制都用筛后的这一份,和表格看到的一致。
 */
const shownRows = computed(() => {
  const f = query.value
  const has = (v: unknown, k: string) => !k || String(v || '').toUpperCase().includes(k.trim().toUpperCase())
  const nos = String(f.recvNo || '').split(',').map((x: string) => x.trim()).filter(Boolean)
  return rows.value.filter(r =>
    (nos.length <= 1 ? has(r.docNo, nos[0] || '') : nos.includes(r.docNo))
    && has(r.sourcePoNo, f.sourcePoNo || '')
    && (!f.dateRange?.[0] || String(r.recvDate) >= f.dateRange[0])
    && (!f.dateRange?.[1] || String(r.recvDate) <= f.dateRange[1])
    && (!f.status || (f.status === 'MATCHED') === !!r.invoiceId))
})
const suppliers = ref<any[]>([])
const exportColumns: ExportColumn[] = [
  { label: '入库单号', key: 'docNo' },
  { label: '关联订单', key: 'sourcePoNo' },
  { label: '入库日期', key: 'recvDate' },
  { label: '数量', qty: true, key: 'totalQty' },
  { label: '暂估金额', money: true, value: row => fmtMoney(row.totalAmt) },
  { label: '状态', value: row => row.invoiceId ? '已核销' : '待核销' },
  { label: '备注', key: 'remark' }
]

async function loadOptions() {
  suppliers.value = await masterApi.allSuppliers() as any
}

function doSearch() {
  return load()
}

function resetQuery() {
  query.value = { supplierId: null, recvNo: '', sourcePoNo: '', dateRange: null, status: '' }
  load()
}

async function load() {
  searched.value = true
  loading.value = true
  try { rows.value = await recvApi.estimatesForInvoicing(query.value.supplierId) as any } finally { loading.value = false }
}
function exportExcel() {
  exportRowsToExcel('暂估清单', exportColumns, shownRows.value, '暂估清单')
}

function printReport(action: PaperAction, paper: PaperSize) {
  printOrExportRows('暂估清单', exportColumns, shownRows.value, action, paper, '货到票未到入库清单')
}

async function copyTable() {
  await copyRowsForPaste(exportColumns, shownRows.value)
  ElMessage.success('已复制，可直接粘贴到 Excel')
}
// 带上供应商和入库单号：发票页直接打开录入并选好供应商、筛出这张入库单的明细(2026-10-15,原来只跳列表要自己再找)
function goInvoice(row: any) { return navigateToInvoice(router, { path: '/invoice/list', query: { createForSupplier: String(row.supplierId || ''), recvNo: row.docNo || '' } }) }
onMounted(() => { loadOptions(); load() })

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const listSummary = makeLineSummary({ 数量: { field: 'totalQty', type: 'qty' }, 暂估金额: { field: 'totalAmt', type: 'money' } })
</script>
