<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购发票</div>
        <div class="page-sub">发票审批通过 → 自动核销 + 暂估冲销 + 库存差额调整</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasFinanceAccess()" type="primary" @click="openCreate">+ 录入发票</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="status" label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="PENDING" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已核销" value="MATCHED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="dateRange" label="开票日期">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item data-q="keyword" label="单据/发票号">
          <el-input v-model="query.keyword" placeholder="系统单号或供应商发票号" clearable style="width:230px" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="supplierId" label="供应商">
          <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="发票号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" aria-label="查看单据" :title="`查看单据 ${row.docNo}`" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="invoiceNo" label="供应商发票号" width="140" sortable="custom" />
        <el-table-column prop="invoiceDate" label="开票日期" width="110" sortable="custom" />
        <el-table-column prop="totalQty" label="数量" width="90" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column label="净额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.netAmt) }}</template>
        </el-table-column>
        <el-table-column label="税额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.taxAmt) }}</template>
        </el-table-column>
        <el-table-column prop="totalAmt" sortable="custom" label="价税合计" width="130" align="right">
          <template #default="{ row }"><b>{{ fmtMoney(row.totalAmt) }}</b></template>
        </el-table-column>
        <el-table-column label="差额" width="100" align="right">
          <template #default="{ row }">
            <span v-if="Number(row.adjustAmt || 0) !== 0"
                  :style="{ color: Number(row.adjustAmt) > 0 ? 'var(--color-warning)' : 'var(--color-danger)' }">
              {{ Number(row.adjustAmt) > 0 ? '+' : '' }}{{ fmtMoney(row.adjustAmt) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="100">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printInvoice(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="row.status === 'DRAFT' && user.hasFinanceAccess()" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="success" @click="openApprove(row)">审批</el-button>
            <el-button v-if="row.status === 'PENDING' && user.hasFinanceAccess()" link type="danger"  @click="rejectRow(row)">驳回</el-button>
            <el-button v-if="['APPROVED','MATCHED'].includes(row.status)" link type="warning" @click="requestCancelApproval('INV', row, load)">取消审批</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="confirmDeleteDoc(row, invoiceApi.delete, load, '发票')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <!-- 发票详情 -->
    <DocDrawer v-model="detailVisible" kind="采购发票" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :steps="['录入', '审批', '核销入库']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detailData">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="系统单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商发票号">{{ detailData.invoiceNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="供应商">{{ supplierMap[detailData.supplierId]?.name || detailData.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="开票日期">{{ detailData.invoiceDate }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailData.remark }}</el-descriptions-item>
          <el-descriptions-item label="净额">{{ fmtMoney(detailData.netAmt) }}</el-descriptions-item>
          <el-descriptions-item label="税额">{{ fmtMoney(detailData.taxAmt) }}</el-descriptions-item>
          <el-descriptions-item label="价税合计"><b>{{ fmtMoney(detailData.totalAmt) }}</b></el-descriptions-item>
          <el-descriptions-item label="库存差额调整" :span="3">
            <span :style="{ color: Number(detailData.adjustAmt || 0) !== 0 ? 'var(--color-warning)' : 'var(--text-3)' }">
              {{ Number(detailData.adjustAmt || 0) > 0 ? '+' : '' }}{{ fmtMoney(detailData.adjustAmt) }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <!-- 手工录入的价税合计:直接写明系统算的数和差额,审批人不用另外算(10-16 用户要求去掉黄框、改显这个) -->
        <div v-if="manualCount(detailData.lines) > 0" class="amt-manual-note">
          <span class="amt-manual-tag">手工</span>价税合计以录入人填的发票金额为准：
          <span v-for="row in detailData.lines.filter(savedManual)" :key="row.id || row.lineNo" class="amt-manual-item">
            第{{ row.lineNo }}行 {{ fmtMoney(lineTotal(row)) }}（{{ manualDetail(row) }}）
          </span>
        </div>
        <!-- 列宽合计要放进 1366 宽屏幕上的详情框(约 1110px),否则最右边的价税合计被挤出可视区(10-16 用户截图);物料编码/规格型号由 AutoIndexTable 统一放宽到 150,只能从其他列省 -->
        <el-table max-height="min(56vh, 560px)" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="100">
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="60">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" prop="qty" width="80" align="right" :formatter="$qtyFmt" />
          <el-table-column label="发票单价" width="100" align="right">
            <template #default="{ row }">{{ fmtPrice(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column label="税率" width="80" align="right">
            <template #default="{ row }">{{ row.taxRate }}%</template>
          </el-table-column>
          <el-table-column label="金额" width="105" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="税额" width="95" align="right">
            <template #default="{ row }">{{ fmtMoney(row.taxAmount) }}</template>
          </el-table-column>
          <!-- 手工录入的价税合计要留在明面上,事后翻单也看得见(2026-08-12 审计补) -->
          <el-table-column label="价税合计" width="124" align="right">
            <template #default="{ row }">
              <span v-if="savedManual(row)" class="amt-manual-tag" :title="manualHint(row)">手工</span>
              <b>{{ fmtMoney(lineTotal(row)) }}</b>
            </template>
          </el-table-column>
        </el-table>
        <DocAttachments biz-type="INV" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
        <el-button v-if="detailData && detailData.status === 'PENDING' && user.hasFinanceAccess()" type="danger" plain @click="rejectFromDetail()">驳回</el-button>
        <el-button v-if="detailData && user.canDeleteDoc(detailData)" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          :type="detailData && detailData.status === 'PENDING' && user.hasFinanceAccess() ? '' : 'primary'"
          @print="(paper, orientation) => printInvoice(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printInvoice(detailData, 'export', paper, orientation)"
        />
        <el-button v-if="detailData && detailData.status === 'PENDING' && user.hasFinanceAccess()" type="primary" @click="approveFromDetail()">审批通过并核销</el-button>
      </template>

    </DocDrawer>

    <!-- 录入发票 -->
    <el-dialog draggable v-model="createVisible" :before-close="createGuard.beforeClose" title="录入采购发票" width="min(1040px, calc(100vw - 48px))" class="doc-entry-dialog">
      <DocGuardHint :active="createVisible" :date="form.invoiceDate" v-model:blocked="guardBlocked" />
      <el-alert title="发票必须关联到具体的入库行（暂估或发票入库均可）" type="info" show-icon :closable="false" style="margin-bottom:14px" />
      <el-form label-position="top" :model="form" label-width="100px" size="default">
        <el-row :gutter="20" class="doc-key-fields">
          <el-col :span="8"><el-form-item label="供应商发票号"><el-input v-model="form.invoiceNo" placeholder="供应商开具的发票号" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="开票日期"><el-date-picker v-model="form.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="供应商">
            <SupplierLookup v-model="form.supplierId" />
          </el-form-item></el-col>
        </el-row>
        <el-divider>发票明细（关联待核销的入库行）</el-divider>

        <div class="line-toolbar">
          <el-button size="small" type="primary" plain @click="openPicker" :disabled="!form.supplierId">＋ 选择入库明细（可多选）</el-button>
          <el-button size="small" type="danger" plain :disabled="!form.lines.length" @click="form.lines = []">清空明细</el-button>
          <span v-if="form.lines.length" class="line-count">已添加 {{ form.lines.length }} 行</span>
        </div>

        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="form.lines" border show-summary :summary-method="entrySummary">
          <el-table-column label="序号" type="index" width="64" />
          <el-table-column label="入库单号" prop="recvNo" width="210" />
          <el-table-column label="物料" prop="matName" min-width="180" show-overflow-tooltip />
          <el-table-column label="暂估单价" prop="estPrice" width="120" align="right">
            <template #default="{ row }">{{ fmtPrice(row.estPrice) }}</template>
          </el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" :max="row.maxQty" :precision="2" controls-position="right" style="width:100%" @change="recalcLine(row)" />
            </template>
          </el-table-column>
          <!-- 2026-10-01 单价防错(同采购订单思路):栏名写明「不含税」;与暂估单价差 >20%、或正好差一个税率(多半填成含税单价)时行内标红 -->
          <el-table-column label="发票单价(不含税)" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :step="1" controls-position="right" style="width:100%"
                               :class="{ 'price-bad': invPriceIssue(row) }" @change="recalcLine(row)" />
              <div v-if="invPriceIssue(row)" class="amt-stale price-issue">⚠ {{ invPriceIssue(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="差额" width="100" align="right">
            <template #default="{ row }">
              <span :style="{color: row.delta > 0 ? 'var(--color-warning)' : row.delta < 0 ? 'var(--color-danger)' : 'var(--text-3)'}">
                {{ row.delta > 0 ? '+' : '' }}{{ fmtMoney(row.delta) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="税率%" width="130">
            <template #default="{ row }"><el-input-number v-model="row.taxRate" :min="0" :max="100" :precision="2" :step="1" controls-position="right" style="width:100%" @change="recalcLine(row)" /></template>
          </el-table-column>
          <!-- 价税合计可手工改(2026-08-05):发票上的不含税单价常是多位小数,
               按两位单价乘出来的金额与发票对不上几分钱。改为照发票直接填,
               后端以这个数为准倒算不含税金额与税额。改过的行会标出来。 -->
          <el-table-column label="价税合计" width="160" align="right">
            <template #default="{ row }">
              <el-input-number
                v-model="row.totalAmt" :min="0" :precision="2" :step="0.01" :controls="false"
                style="width:100%" @focus="ensureTotal(row)" @change="row.amtTouched = true" />
              <div v-if="staleManualAmt(row)" class="amt-stale">
                ⚠ 数量/单价已改,请复核金额(现算 {{ fmtMoney(lineTaxTotal(row)) }})
              </div>
              <div v-else-if="isManualAmt(row)" class="amt-manual">
                手工 · 算出为 {{ fmtMoney(lineTaxTotal(row)) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }"><el-button link type="danger" @click="form.lines.splice($index,1)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <el-form-item label="备注" style="margin-top:14px">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <DocAttachments ref="invAttRef" biz-type="INV" :biz-id="null" />
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(false)">保存草稿</el-button>
        <el-button type="primary" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <!-- 选行弹窗（多选、可搜索、已添加置灰） -->
    <el-dialog draggable v-model="picker.visible" title="选择待核销的入库明细" width="min(1000px, calc(100vw - 48px))" class="doc-entry-dialog">
      <div class="picker-toolbar">
        <el-input v-model="picker.keyword" clearable :prefix-icon="Search" placeholder="搜索物料 / 入库单号 / 来源订单" style="width:300px" />
        <span class="picker-stat">待核销 <b>{{ pickerView.length }}</b> 行 · 已勾选 <b>{{ picker.selected.length }}</b></span>
        <el-button size="small" @click="addAll" :disabled="!addableCount">全部添加（{{ addableCount }}）</el-button>
      </div>
      <el-table ref="pickerTableRef" class="readable-table" border :fit="false" :data="pickerView" height="440"
        v-loading="picker.loading" empty-text="该供应商暂无待核销入库明细"
        :row-class-name="({ row }) => isAdded(row) ? 'row-added' : ''"
        @selection-change="picker.selected = $event" @row-click="togglePick" highlight-current-row>
        <el-table-column type="selection" width="46" :selectable="row => !isAdded(row)" />
        <el-table-column prop="docNo" label="入库单号" width="200" sortable="custom" />
        <el-table-column prop="recvDate" label="入库日期" width="106" />
        <el-table-column label="物料" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.matName }}</template>
        </el-table-column>
        <el-table-column prop="remainQty" label="可开票数量" width="110" align="right" />
        <el-table-column label="暂估单价" width="110" align="right">
          <template #default="{ row }">{{ fmtPrice(row.estPrice) }}</template>
        </el-table-column>
        <el-table-column prop="sourcePoNo" label="来源订单" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="82" align="center">
          <template #default="{ row }">
            <el-tag v-if="isAdded(row)" size="small" type="success" effect="light">已添加</el-tag>
            <span v-else style="color:var(--text-3)">—</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="picker-foot-hint">单击整行即可勾选；已添加的行不会重复加入</span>
        <el-button @click="picker.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!picker.selected.length" @click="addSelected">添加所选（{{ picker.selected.length }}）</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@/icons'
import { invoiceApi, recvApi, masterApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtDate, fmtMoney, fmtPrice, fmtQty, today } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { makeLineSummary } from '@/utils/tableSummary'
import { applyRouteQuery } from '@/utils/routeQuery'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { takeInvoiceNavigationIntent } from '@/utils/invoiceNavigation'
import { showError } from '@/utils/notify'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { buildPrintQr } from '@/utils/docQr'
import StatusTag from '@/components/StatusTag.vue'
import SupplierLookup from '@/components/SupplierLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'

const route = useRoute()
const user = useUserStore()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', supplierId: null, startDate: null, endDate: null })
const dateRange = ref<any>(null)

const suppliers = ref<any[]>([])
const materials = ref<any[]>([])
const matMap = ref<Record<number, any>>({})
const supplierMap = ref<Record<number, any>>({})

const exportColumns: ExportColumn[] = [
  { label: '发票号', key: 'docNo' },
  { label: '供应商发票号', key: 'invoiceNo' },
  { label: '开票日期', key: 'invoiceDate' },
  { label: '供应商', value: row => supplierMap.value[row.supplierId]?.name || row.supplierId || '' },
  { label: '数量', qty: true, key: 'totalQty' },
  { label: '净额', money: true, value: row => fmtMoney(row.netAmt) },
  { label: '税额', money: true, value: row => fmtMoney(row.taxAmt) },
  { label: '价税合计', money: true, value: row => fmtMoney(row.totalAmt) },
  { label: '库存差额', money: true, value: row => fmtMoney(row.adjustAmt) },
  { label: '状态', key: 'status' },
  { label: '备注', key: 'remark' }
]

async function ensureMaster() {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!suppliers.value.length) suppliers.value = await masterApi.allSuppliers() as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  supplierMap.value = Object.fromEntries(suppliers.value.map((s: any) => [s.id, s]))
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '已核销', value: 'MATCHED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => invoiceApi.page(q), ['DRAFT', 'PENDING', 'APPROVED', 'MATCHED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await invoiceApi.page(query)
    rows.value = r.records
    total.value = r.total
  } finally { loading.value = false }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那 20 条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  applyDateRange()
  query.current = 1
  return load()
}

function resetQuery() {
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', supplierId: null, startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

ensureMaster().catch(() => {})

const entryFullPath = route.fullPath
let initialEntryConsumed = false
function consumeInvoiceEntry() {
  if (route.fullPath !== entryFullPath) return
  const requested = takeInvoiceNavigationIntent(entryFullPath)
  if (initialEntryConsumed && !requested) return
  initialEntryConsumed = true
  const supplierId = Number(route.query.createForSupplier)
  if (supplierId) openCreateForSupplier(supplierId, typeof route.query.recvNo === 'string' ? route.query.recvNo : '')
    .catch(error => showError(error, '发票录入打开失败'))
}
onMounted(() => {
  applyRouteQuery(query, route, ['status', 'keyword', 'supplierId'])
  load()
  consumeInvoiceEntry()
})
onActivated(consumeInvoiceEntry)

/** 从暂估清单「去录入发票核销」进来：打开录入、选好供应商，入库明细框按入库单号筛好 */
async function openCreateForSupplier(supplierId: number, recvNo: string) {
  if (!user.hasFinanceAccess()) { ElMessage.warning('录入采购发票需要财务权限'); return }
  await openCreate()
  form.supplierId = supplierId
  await openPicker()
  picker.keyword = recvNo
}

const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const invAttRef = ref()
const form = reactive<any>({ supplierId: null, invoiceNo: '', invoiceDate: today(), remark: '', lines: [] })

async function openCreate() {
  invAttRef.value?.reset()  // 清掉上一单残留的暂存附件,防串单
  await ensureMaster()
  Object.assign(form, { supplierId: null, invoiceNo: '', invoiceDate: today(), remark: '', lines: [] })
  createVisible.value = true
}

const pickerTableRef = ref()
const picker = reactive<any>({ visible: false, list: [], selected: [], keyword: '', loading: false })

// 按关键词过滤（物料/入库单号/来源订单）
const pickerView = computed(() => {
  const kw = (picker.keyword || '').trim().toLowerCase()
  if (!kw) return picker.list
  return picker.list.filter((r: any) => `${r.docNo} ${r.matName} ${r.sourcePoNo || ''}`.toLowerCase().includes(kw))
})
// 当前视图里还没添加的行数（“全部添加”按钮用）
const addableCount = computed(() => pickerView.value.filter((r: any) => !isAdded(r)).length)

function isAdded(row: any) {
  return form.lines.some((l: any) => l.recvLineId === row.recvLineId)
}

// 一次性拉取该供应商全部待核销入库明细，打开多选弹窗
async function openPicker() {
  await ensureMaster()
  picker.keyword = ''
  picker.selected = []
  picker.list = []
  picker.loading = true
  picker.visible = true
  try {
    const recvs: any[] = await recvApi.estimatesForInvoicing(form.supplierId) as any
    const rows: any[] = []
    for (const recv of recvs) {
      const detail: any = await recvApi.detail(recv.id)
      for (const ln of detail.lines || []) {
        const remainQty = Number(ln.qty || 0) - Number(ln.invoicedQty || 0)
        if (remainQty <= 0) continue
        const mat = matMap.value[ln.materialId]
        rows.push({
          docNo: recv.docNo,
          recvDate: recv.recvDate,
          sourcePoNo: recv.sourcePoNo,
          recvLineId: ln.id,
          materialId: ln.materialId,
          matName: mat ? `${mat.name}${mat.spec ? `(${mat.spec})` : ''}` : `物料${ln.materialId}`,
          remainQty,
          estPrice: Number(ln.unitPrice)
        })
      }
    }
    picker.list = rows
  } finally {
    picker.loading = false
  }
}

// 单击整行 = 勾选/取消（已添加的行不响应；点到复选框列本身时交给复选框，避免双重切换）
function togglePick(row: any, column: any) {
  if (isAdded(row)) return
  if (column && column.type === 'selection') return
  pickerTableRef.value?.toggleRowSelection(row)
}

function lineFromPick(row: any) {
  return {
    recvLineId: row.recvLineId,
    recvNo: row.docNo,
    materialId: row.materialId,
    matName: row.matName,
    estPrice: row.estPrice,
    qty: row.remainQty,
    maxQty: row.remainQty,
    unitPrice: row.estPrice,
    taxRate: 13,
    delta: 0
  }
}

function addSelected() {
  const toAdd = picker.selected.filter((r: any) => !isAdded(r))
  toAdd.forEach((r: any) => form.lines.push(lineFromPick(r)))
  ElMessage.success(`已添加 ${toAdd.length} 行`)
  picker.visible = false
}

function addAll() {
  const toAdd = pickerView.value.filter((r: any) => !isAdded(r))
  toAdd.forEach((r: any) => form.lines.push(lineFromPick(r)))
  ElMessage.success(`已添加 ${toAdd.length} 行`)
  picker.visible = false
}

/**
 * 发票单价防错（2026-10-01，同采购订单单价防错思路：让录错的那一行自己跳出来）。
 * 这一栏填【不含税】单价；暂估单价来自入库单，也是不含税。
 * - 正好 ≈ 暂估价 ×(1+税率) → 多半是把发票上的含税单价填进来了，税会被算两遍；
 * - 差 20% 以上 → 可能填错位/多一个零，请对着发票核一下。
 * 只提示不拦截：真实发票价可以与暂估价不同（差额会进暂估差异）。
 */
function invPriceIssue(row: any): string {
  const est = Number(row?.estPrice || 0)
  if (!(est > 0) || row?.unitPrice === null || row?.unitPrice === undefined || row?.unitPrice === '') return ''
  const r = Number(row.unitPrice) / est
  const tax = Number(row.taxRate || 0) / 100
  if (tax > 0 && Math.abs(r - (1 + tax)) < 0.005) return `正好是暂估价×(1+${Number(row.taxRate)}%)，像是填了含税单价，这里要填不含税`
  if (r > 1.2 || r < 0.8) return `比暂估单价${r > 1 ? '高' : '低'} ${Math.round(Math.abs(r - 1) * 100)}%，请对着发票核对`
  return ''
}

function recalcLine(row: any) {
  row.delta = (Number(row.unitPrice || 0) - Number(row.estPrice || 0)) * Number(row.qty || 0)
  /*
   * 🔴 2026-08-18 修:改数量/单价后,价税合计必须跟着走。
   * 原来只更新 delta,不动 totalAmt —— 于是「点过价税合计框(自动带出 2.10)
   * → 发现数量填错改成 3 件 → 金额还是 2.10」,保存时后端算出应是 6.31、
   * 差 67% 被 1% 闸门拒掉,录入人完全不知道为什么。
   * 只在【用户没有手工改过金额】时自动跟随;真手工填过的(amtTouched)保留他填的值,
   * 由下面的 staleManualAmt 提示他复核。
   */
  if (!row.amtTouched) row.totalAmt = lineTaxTotal(row)
}

/** 手工填过金额、之后又改了数量/单价 → 那个金额多半已经过时,提示复核 */
function staleManualAmt(row: any) {
  if (!row?.amtTouched) return false
  const t = Number(row?.totalAmt)
  if (!t || Number.isNaN(t)) return false
  return Math.abs(t - lineTaxTotal(row)) >= 0.005
}

// 系统按「数量 × 发票单价 ×(1+税率)」算出的价税合计 —— 现在只作参考值与对照
function lineTaxTotal(row: any) {
  const net = round2(Number(row.qty || 0) * Number(row.unitPrice || 0))
  const tax = round2(net * Number(row.taxRate || 0) / 100)
  return round2(net + tax)
}
function round2(n: number) { return Math.round((Number(n) || 0) * 100) / 100 }

/**
 * 价税合计的实际取值:手工填过就用手工值,否则用系统算的。
 * 2026-08-05 起这一列可改 —— 发票的不含税单价常是多位小数,
 * 按两位单价乘出来对不上发票,财务要求「系统里的金额 = 发票上的金额」。
 */
function effTotal(row: any) {
  const t = Number(row?.totalAmt)
  return (row?.totalAmt === null || row?.totalAmt === undefined || Number.isNaN(t) || t <= 0)
    ? lineTaxTotal(row) : t
}
/** 手工值与系统算的不一致 → 标出来,让审批人一眼看到哪几行被改过 */
function isManualAmt(row: any) {
  const t = Number(row?.totalAmt)
  if (row?.totalAmt === null || row?.totalAmt === undefined || Number.isNaN(t) || t <= 0) return false
  return Math.abs(t - lineTaxTotal(row)) >= 0.005
}
/**
 * ── 已保存单据的「手工金额」留痕(2026-08-12 审计补)───────────────────────
 * 上面的 isManualAmt 是给【录入页】用的,靠当场重算判断;
 * 已保存的单据不能这么判 —— 单价是四舍五入过的显示值,重算会失真。
 * 后端在 8fa9605 就已经把 amt_source(AUTO/MANUAL)和 calc_total_amt(系统算的)
 * 存进库并随详情返回了,这里直接读它,详情页与审批页都用同一套口径。
 *
 * 【为什么必须做】手工改价税合计能过内控这一关,靠的就是「审批人一眼看得到」。
 * 原来标黄只做在录入页 —— 填单人自己看得到,审批人看不到,承诺等于没兑现。
 */
function savedManual(row: any) { return row?.amtSource === 'MANUAL' }
/**
 * 已保存行的价税合计 = 金额 + 税额。
 * ⚠️ 不能直接读 row.totalAmt —— 它是 @TableField(exist=false),【设计上就不落库】,
 *    详情接口返回的行里【永远是空的】,直接显示会全变成「-」。
 *    库里存的是拆开后的 amount + tax_amount,后端保证两者相加恒等于价税合计
 *    (手工行也一样:税额是用「价税合计 − 净额」相减得出的,不会有分位差)。
 *    E2E ⑧b 有断言守着这条恒等式。
 */
function lineTotal(row: any) {
  const t = Number(row?.totalAmt)
  if (Number.isFinite(t) && t > 0) return t
  return round2(Number(row?.amount || 0) + Number(row?.taxAmount || 0))
}
function manualCount(lines: any[] | undefined | null) {
  return Array.isArray(lines) ? lines.filter(savedManual).length : 0
}
/**
 * 「手工 · 系统算 2.10（差 -0.01）」——差额让审批人不用自己减。
 * 差额必须用 lineTotal(金额+税额):详情返回的 row.totalAmt 永远是空的(见上),
 * 2026-10-16 用户截图发现差额显示成「-5,776.56」= 0 − 系统算,就是用错了它。
 */
function manualDetail(row: any) {
  const calc = Number(row?.calcTotalAmt)
  if (!Number.isFinite(calc)) return '系统算的值未记录'
  const diff = round2(lineTotal(row) - calc)
  return `系统算 ${fmtMoney(calc)}，差 ${diff > 0 ? '+' : ''}${fmtMoney(diff)}`
}
function manualHint(row: any) { return `手工 · ${manualDetail(row)}` }

/** 首次点进输入框时,先把系统算的值填进去,免得要从空白开始敲 */
function ensureTotal(row: any) {
  if (row.totalAmt === null || row.totalAmt === undefined || row.totalAmt === '') {
    row.totalAmt = lineTaxTotal(row)
  }
}

// 录入表合计：数量 / 差额 / 价税合计
function entrySummary({ columns, data }: { columns: any[]; data: any[] }): string[] {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.label === '数量') return fmtQty(data.reduce((s, r) => s + Number(r.qty || 0), 0))
    if (col.label === '差额') return fmtMoney(data.reduce((s, r) => s + Number(r.delta || 0), 0))
    // 合计用【实际生效值】(手工填过就用手工值),否则底部合计与明细对不上
    if (col.label === '价税合计') return fmtMoney(data.reduce((s, r) => s + effTotal(r), 0))
    return ''
  })
}

// 详情表合计：数量 / 金额(净额) / 税额 / 价税合计
const baseDetailSummary = makeLineSummary({
  数量: { field: 'qty', type: 'qty' },
  金额: { field: 'amount', type: 'money' },
  税额: { field: 'taxAmount', type: 'money' }
})
/** 价税合计一列要走 lineTotal 兜底(老单据 total_amt 为空),不能直接按字段求和 */
function detailSummary(ctx: { columns: any[]; data: any[] }): string[] {
  const out = baseDetailSummary(ctx)
  const i = ctx.columns.findIndex((c: any) => c.label === '价税合计')
  if (i > 0) out[i] = fmtMoney((ctx.data || []).reduce((s: number, r: any) => s + lineTotal(r), 0))
  return out
}

async function save(submit: boolean) {
  const invalid = validateForm()
  if (invalid) {
    await ElMessageBox.alert(invalid, '无法保存', { confirmButtonText: '知道了', type: 'warning' })
    return
  }
  // 单价偏离暂估价的行，保存前集中亮一次，确认后才存（只提醒不拦：发票价与暂估价本就可能不同）
  const issues = form.lines.map((l: any, i: number) => ({ l, i, msg: invPriceIssue(l) })).filter((x: any) => x.msg)
  if (issues.length) {
    const text = issues.map((x: any) => `第 ${x.i + 1} 行 ${x.l.matName || ''}：发票单价 ${fmtPrice(x.l.unitPrice)}，暂估单价 ${fmtPrice(x.l.estPrice)} —— ${x.msg}`).join('\n')
    try {
      await ElMessageBox.confirm(text, `有 ${issues.length} 行发票单价与暂估单价差得多，请核对`, {
        type: 'warning', confirmButtonText: '已核对，继续保存', cancelButtonText: '返回修改', customStyle: { whiteSpace: 'pre-line' } as any
      })
    } catch { return }
  }
  // 后端字段映射
  const payload: any = {
    supplierId: form.supplierId,
    invoiceNo: form.invoiceNo,
    invoiceDate: form.invoiceDate,
    remark: form.remark,
    lines: form.lines.map((l: any) => ({
      recvLineId: l.recvLineId, materialId: l.materialId,
      qty: l.qty, unitPrice: l.unitPrice, taxRate: l.taxRate,
      // 🔴 必须带上手工填的价税合计,否则后端收不到、按「数量×单价」重算,
      //    手工改的值保存后就被打回原形(2026-08-11 修:上线时漏了这个字段)。
      //    没改过时是 undefined,后端走原路径,行为不变。
      totalAmt: l.totalAmt
    }))
  }
  const r: any = await invoiceApi.create(payload)
  if (invAttRef.value) await invAttRef.value.uploadPending(r.id)
  if (submit) await invoiceApi.submit(r.id)
  ElMessage.success(submit ? `已提交 ${r.docNo}` : `已保存 ${r.docNo}`)
  createVisible.value = false
  await load()
}

function validateForm() {
  if (!form.supplierId) return '请选择供应商。'
  if (!form.invoiceNo) return '请填写供应商发票号。'
  if (!form.invoiceDate) return '请选择开票日期。'
  if (!form.lines.length) return '请至少添加一行发票明细。'
  for (let i = 0; i < form.lines.length; i++) {
    const line = form.lines[i]
    if (line.qty === null || line.qty === undefined || line.qty === '' || Number(line.qty) <= 0) return `第 ${i + 1} 行请填写大于 0 的数量。`
    if (line.unitPrice === null || line.unitPrice === undefined || line.unitPrice === '' || Number(line.unitPrice) < 0) return `第 ${i + 1} 行请填写单价。`
    if (line.taxRate === null || line.taxRate === undefined || line.taxRate === '' || Number(line.taxRate) < 0) return `第 ${i + 1} 行请填写税率。`
  }
  return ''
}

async function submit(row: any) {
  await invoiceApi.submit(row.id); ElMessage.success('已提交'); await load()
}

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交发票 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await invoiceApi.withdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

// 详情弹窗内直接审批/驳回（沿用带核销预览的审批弹窗）
function approveFromDetail() { const row = detailData.value; detailVisible.value = false; openApprove(row) }
// 详情弹窗内直接删除：与列表行同口径（谁提交谁删除），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, invoiceApi.delete, () => {}, '发票') } catch { return }
  detailVisible.value = false
  await load()
}
function rejectFromDetail() { const row = detailData.value; detailVisible.value = false; rejectRow(row) }

// 一键通过：直接调审批接口，不再弹核销预览框（误批可走“取消审批”撤回）
async function openApprove(row: any) {
  await invoiceApi.approve(row.id, '同意')
  ElMessage.success(`${row.docNo} 已审批，库存已自动调差`)
  await load()
}
async function rejectRow(row: any) {
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因', `驳回 ${row.docNo}`, { inputType: 'textarea' })
    await invoiceApi.reject(row.id, value)
    ElMessage.success('已驳回'); await load()
  } catch {}
}
const detailVisible = ref(false)
const detailData = ref<any>(null)
async function openDetail(id: number) {
  await ensureMaster()
  detailData.value = await invoiceApi.detail(id)
  detailVisible.value = true
}

function exportExcel() {
  exportRowsToExcel('采购发票', exportColumns, rows.value, '采购发票', { total: total.value, fetch: p => invoiceApi.page({ ...query, ...p }) })
}

async function printInvoice(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'landscape') {
  await ensureMaster()
  const doc = rowOrDoc.lines ? rowOrDoc : await invoiceApi.detail(rowOrDoc.id)
  const columns: PrintColumn[] = [
    { label: '行号', key: 'lineNo', width: '6%', align: 'center' },
    { label: '物料编码', value: row => matMap.value[row.materialId]?.code || '', width: '13%' },
    { label: '物料名称', value: row => matMap.value[row.materialId]?.name || row.materialId, width: '19%' },
    { label: '规格型号', value: row => matMap.value[row.materialId]?.spec || '', width: '13%' },
    { label: '数量', key: 'qty', width: '9%', align: 'right' },
    { label: '发票单价', value: row => fmtPrice(row.unitPrice), width: '11%', align: 'right' },
    { label: '税率', value: row => `${row.taxRate ?? 0}%`, width: '8%', align: 'right' },
    { label: '金额', value: row => fmtMoney(row.amount), width: '11%', align: 'right' },
    { label: '税额', value: row => fmtMoney(row.taxAmount), width: '10%', align: 'right' }
  ]
  const options = {
    orgId: doc?.orgId,
    title: '采购发票',
    subtitle: `${paper} 打印版`,
    fileName: `${doc.docNo || '采购发票'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('INV', doc.id, doc.docNo),
    fields: [
      { label: '系统单号', value: doc.docNo },
      { label: '供应商发票号', value: doc.invoiceNo },
      { label: '状态', value: doc.status },
      { label: '供应商', value: supplierMap.value[doc.supplierId]?.name || doc.supplierId || '-' },
      { label: '开票日期', value: doc.invoiceDate },
      { label: '数量', value: doc.totalQty },
      { label: '净额', value: fmtMoney(doc.netAmt) },
      { label: '税额', value: fmtMoney(doc.taxAmt) },
      { label: '价税合计', value: fmtMoney(doc.totalAmt) },
      { label: '库存差额调整', value: fmtMoney(doc.adjustAmt), span: 2 },
      { label: '备注', value: doc.remark || '-', span: 2 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '经办确认', lines: ['签字：', '日期：'] },
      { label: '财务审核', lines: ['签字：', '日期：'] }
    ],
    note: '采购发票打印单用于发票入账、暂估核销和供应商对账留档。'
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}


// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a: number, r: any) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detailData.value
  if (!d) return ''
  return [supplierMap.value[d.supplierId]?.name, d.invoiceNo && `发票号 ${d.invoiceNo}`, d.invoiceDate && `开票 ${d.invoiceDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 3, "MATCHED": 3} as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '不含税', value: fmtMoney(d.netAmt) }, { label: '税额', value: fmtMoney(d.taxAmt) }, { label: '价税合计', value: fmtMoney(d.totalAmt), hl: true }, Number(d.adjustAmt || 0) !== 0 && { label: '库存差额调整', value: (Number(d.adjustAmt) > 0 ? '+' : '') + fmtMoney(d.adjustAmt) }].filter(Boolean) as any[]
})
</script>

<style scoped>
/* 手工改过价税合计的行:标出来并显示系统算的值,便于审批人核对(2026-08-05) */
.amt-stale  { margin-top: 2px; font-size: 11px; line-height: 1.3; color: #d93b3b; font-weight: 600; white-space: nowrap; }
.price-issue { white-space: normal; }
:deep(.price-bad .el-input__wrapper) { box-shadow: 0 0 0 1px #d93b3b inset; background: #fff5f5; }
.amt-manual { margin-top: 2px; font-size: 11px; line-height: 1.3; color: #d6881f; white-space: nowrap; } /* 录入页「手工」提示 */
.amt-manual-note { margin: 0 0 10px; color: var(--text-2); font-size: 13px; line-height: 1.7; }
.amt-manual-item + .amt-manual-item::before { content: '；'; }
.amt-manual-tag { display: inline-block; margin-right: 6px; padding: 0 5px; border: 1px solid #f0c78a; border-radius: 4px; color: #b86e10; font-size: 11px; line-height: 16px; vertical-align: 1px; }
</style>
