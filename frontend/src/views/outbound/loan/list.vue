<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">借料登记</div>
        <div class="page-sub">仓管登记临时借料 → 提交材料会计审核，审核通过后才扣减库存；归还后回增库存。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('warehouse')" type="primary" @click="openCreate">+ 新增借料</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom: 8px">
          <el-form-item data-q="dateRange" label="借料日期">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item data-q="status" label="状态">
            <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已登记" value="REGISTERED" />
              <el-option label="已归还" value="CLOSED" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="keyword" label="借料单号">
            <DocNoLookup v-model="query.keyword" type="LOAN" multiple placeholder="点一下列出最近借料单" width="220px" @enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="borrower" label="借料人">
            <el-input v-model="query.borrower" placeholder="输入借料人" clearable style="width: 170px" @keyup.enter="doSearch" />
          </el-form-item>
          <el-form-item data-q="warehouseId" label="仓库">
            <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width: 190px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item data-q="projectId" label="工程项目">
            <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable style="width: 220px">
              <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="projectLabel(p)" />
            </el-select>
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <div class="loan-supervise-bar">
        <el-checkbox v-model="onlyPending">只看未还清（督办）</el-checkbox>
        <span v-if="onlyPending && displayRows.length" class="supervise-hint">共 {{ displayRows.length }} 单未还清，其中逾期 {{ overdueCount }} 单</span>
      </div>
      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border
        :fit="false"
        :data="displayRows" show-summary :summary-method="pageSummary"
        v-loading="loading"
        :empty-text="searched ? (onlyPending ? '没有未还清的借料单' : '暂无借料记录') : '请选择条件后点击查询'"
       @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" type="index" label="序号" width="70" />
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo" label="借料单号" width="210" sortable="custom">
          <template #default="{ row }">
            <button type="button" class="doc-link" :title="`查看借料单 ${row.docNo}`" @click="openDetail(row.id)">
              {{ row.docNo }}
            </button>
          </template>
        </el-table-column>
        <el-table-column prop="borrowDate" label="借料日期" width="112" sortable="custom" />
        <el-table-column prop="expectedReturnDate" label="预计归还" width="112" sortable="custom">
          <template #default="{ row }">{{ row.expectedReturnDate || '-' }}</template>
        </el-table-column>
        <el-table-column label="仓库" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column prop="projectName" label="工程项目" min-width="170" show-overflow-tooltip sortable="custom">
          <template #default="{ row }">{{ row.projectName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="borrower" label="借料人" width="110" sortable="custom" />
        <el-table-column prop="purpose" label="用途" min-width="160" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="totalQty" label="借出数量" width="110" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column prop="returnedQty" label="已归还" width="100" align="right" sortable="custom" />
        <el-table-column label="未归还" width="100" align="right">
          <template #default="{ row }">{{ $fmtQty(pendingQty(row)) }}</template>
        </el-table-column>
        <el-table-column label="已借天数" width="110" align="right">
          <template #default="{ row }">
            <span v-if="pendingQty(row) <= 0">-</span>
            <span v-else :class="{ 'loan-overdue': isOverdue(row) }">
              {{ borrowedDays(row) }} 天<template v-if="isOverdue(row)">·逾期</template>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button v-if="canSubmit(row)" link type="primary" @click="submitLoan(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdrawLoan(row)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="approveLoan(row)">审批</el-button>
            <el-button v-if="canApprovePending(row)" link type="danger" @click="rejectLoan(row)">驳回</el-button>
            <el-button v-if="canReturn(row)" link type="success" @click="openReturn(row)">归还</el-button>
            <PrintExportDropdown default-orientation="landscape"
              class="row-print"
              label="打印/导出"
              @print="(paper, orientation) => printLoan(row, 'print', paper, orientation)"
              @export="(paper, orientation) => printLoan(row, 'export', paper, orientation)"
            />
            <el-button v-if="canDelete(row)" link type="danger" @click="deleteLoan(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="searched"
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="load"
        @current-change="load"
        style="margin-top: 12px; justify-content: flex-end"
      />
    </el-card>

    <el-dialog
      v-model="createVisible" :before-close="createGuard.beforeClose"
      title="新增借料登记"
      width="min(1080px, calc(100vw - 48px))"
      class="doc-entry-dialog"
      append-to-body
      destroy-on-close
    >
      <DocGuardHint :active="createVisible" :date="form.borrowDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="mb-3"
        title="借料登记保存后会立即扣减库存数量；归还时通过“归还”登记回增库存。"
      />
      <el-form label-position="top" :model="form" label-width="96px">
        <el-row :gutter="18" class="doc-key-fields">
          <el-col :span="8">
            <el-form-item label="仓库" required>
              <el-select v-model="form.warehouseId" filterable style="width: 100%" @change="reloadLineBalances">
                <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="借料日期" required>
              <el-date-picker v-model="form.borrowDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="预计归还">
              <el-date-picker v-model="form.expectedReturnDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="借料人" required>
              <el-input v-model="form.borrower" placeholder="填写借料人姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="借料部门">
              <el-input v-model="form.borrowerDept" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="工程项目">
              <el-select v-model="form.projectId" filterable clearable style="width: 100%">
                <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="projectLabel(p)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="用途" required>
              <el-input v-model="form.purpose" placeholder="例如：抢修临借、现场临时周转、设备调试" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider>借料明细</el-divider>
        <div class="line-toolbar">
          <el-button size="small" type="primary" plain @click="addLine">增行</el-button>
          <el-button size="small" @click="addLines(5)">批量增 5 行</el-button>
          <el-button size="small" type="danger" plain :disabled="!form.lines.length" @click="clearLines">清空明细</el-button>
        </div>
        <el-table max-height="min(56vh, 560px)" :data="form.lines" border class="readable-table loan-line-table">
          <el-table-column type="index" label="序号" width="64" resizable/>
          <el-table-column label="物料编码" min-width="190" resizable>
            <template #default="{ row }">
              <MaterialLookup v-model="row.materialId" placeholder="选择物料" title="选择物料编码" @change="onMaterialChange(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="140" resizable show-overflow-tooltip>
            <template #default="{ row }">{{ row.materialName || '-' }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="100" resizable show-overflow-tooltip>
            <template #default="{ row }">{{ row.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" resizable>
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column resizable label="当前库存" width="110" align="right">
            <template #default="{ row }">{{ $fmtQty(row.currentQty) }}</template>
          </el-table-column>
          <el-table-column label="借料数量" width="150" resizable>
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="0.0001"
                :max="row.currentQty == null ? undefined : Number(row.currentQty)"
                :precision="4"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120" resizable>
            <template #default="{ row }"><el-input v-model="row.remark" placeholder="可选" /></template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }"><el-button link type="danger" @click="form.lines.splice($index, 1)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <el-form-item label="备注" style="margin-top: 14px">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <DocAttachments ref="loanAttRef" biz-type="LOAN" :biz-id="null" />
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="createLoan">保存借料登记</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="returnVisible" :before-close="returnGuard.beforeClose"
      title="借料归还登记"
      width="min(980px, calc(100vw - 48px))"
      append-to-body
      destroy-on-close
    >
      <template v-if="returnSource">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="借料单号">{{ returnSource.docNo }}</el-descriptions-item>
          <el-descriptions-item label="借料人">{{ returnSource.borrower }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(returnSource.warehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="借出数量">{{ $fmtQty(returnSource.totalQty) }}</el-descriptions-item>
          <el-descriptions-item label="已归还">{{ $fmtQty(returnSource.returnedQty) }}</el-descriptions-item>
          <el-descriptions-item label="未归还">{{ $fmtQty(pendingQty(returnSource)) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top" :model="returnForm" label-width="90px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="归还日期" required>
                <el-date-picker v-model="returnForm.returnDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="备注">
                <el-input v-model="returnForm.remark" placeholder="可选" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :fit="false" :data="returnLines" border>
          <el-table-column type="index" label="序号" width="64" resizable/>
          <el-table-column label="物料编码" width="130">
            <template #default="{ row }">{{ materialText(row.materialId, 'code') }}</template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="180">
            <template #default="{ row }">{{ materialText(row.materialId, 'name') }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" resizable>
            <template #default="{ row }">{{ materialText(row.materialId, 'unit') }}</template>
          </el-table-column>
          <el-table-column label="借出数量" prop="qty" width="110" align="right" :formatter="$qtyFmt" />
          <el-table-column label="已归还" prop="returnedQty" width="100" align="right" />
          <el-table-column label="本次归还" width="170">
            <template #default="{ row }">
              <el-input-number
                v-model="row.returnQty"
                :min="0"
                :max="Number(row.availableQty || 0)"
                :precision="4"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="returnGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="submitReturn">保存归还登记</el-button>
      </template>
    </el-dialog>

    <DocDrawer v-model="detailVisible" kind="借料单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :steps="['登记借出', '审批', '借出中', '已归还']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detailData">
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="借料单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="借料日期">{{ detailData.borrowDate }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseName(detailData.warehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ detailData.projectName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="预计归还">{{ detailData.expectedReturnDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="借料人">{{ detailData.borrower }}</el-descriptions-item>
          <el-descriptions-item label="借料部门">{{ detailData.borrowerDept || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用途">{{ detailData.purpose }}</el-descriptions-item>
          <el-descriptions-item label="借出数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item label="已归还">{{ $fmtQty(detailData.returnedQty) }}</el-descriptions-item>
          <el-descriptions-item label="未归还">{{ $fmtQty(pendingQty(detailData)) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detailData.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="56" align="center" />
          <el-table-column fixed="left" label="物料编码" width="130">
            <template #default="{ row }">{{ materialText(row.materialId, 'code') }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="180">
            <template #default="{ row }">{{ materialText(row.materialId, 'name') }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="170">
            <template #default="{ row }">{{ materialText(row.materialId, 'spec') || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" resizable>
            <template #default="{ row }">{{ materialText(row.materialId, 'unit') }}</template>
          </el-table-column>
          <el-table-column label="借出数量" prop="qty" width="110" align="right" :formatter="$qtyFmt" />
          <el-table-column label="已归还" prop="returnedQty" width="100" align="right" />
          <el-table-column label="未归还" prop="availableQty" width="100" align="right" />
          <el-table-column label="备注" prop="remark" min-width="180" show-overflow-tooltip />
        </el-table>
        <template v-if="detailData.returns && detailData.returns.length">
          <div class="dd-sec">归还记录</div>
          <el-table :fit="false" :data="detailData.returns" border size="small" class="readable-table doc-detail-table">
            <el-table-column label="归还日期" prop="returnDate" width="110" />
            <el-table-column label="物料" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ returnLineLabel(row.loanLineId) }}</template>
            </el-table-column>
            <el-table-column label="归还数量" prop="qty" width="110" align="right" :formatter="$qtyFmt" />
            <el-table-column label="登记人" prop="createdByName" width="100" />
            <el-table-column label="登记时间" prop="createdAt" width="160" />
            <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip />
          </el-table>
        </template>
        <DocAttachments biz-type="LOAN" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          type="primary"
          @print="(paper, orientation) => printLoan(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printLoan(detailData, 'export', paper, orientation)"
        />
      </template>

    </DocDrawer>
  </div>
</template>

<script setup lang="ts">
import DocNoLookup from '@/components/DocNoLookup.vue'
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inventoryApi, masterApi, outApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { applyRouteQuery } from '@/utils/routeQuery'
import { fmtDate, today, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { buildPrintQr } from '@/utils/docQr'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import QueryDrawer from '@/components/QueryDrawer.vue'
import StatusTag from '@/components/StatusTag.vue'
import DocAttachments from '@/components/DocAttachments.vue'

const user = useUserStore()
const route = useRoute()
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])

// ── 借料督办：只看未还清 + 已借天数/逾期 ──
const onlyPending = ref(false)
const displayRows = computed(() => onlyPending.value ? rows.value.filter((r: any) => pendingQty(r) > 0) : rows.value)
const overdueCount = computed(() => displayRows.value.filter((r: any) => pendingQty(r) > 0 && isOverdue(r)).length)
function borrowedDays(row: any) {
  if (!row.borrowDate) return 0
  return Math.max(0, Math.floor((Date.now() - new Date(row.borrowDate).getTime()) / 86400000))
}
function isOverdue(row: any) {
  return !!row.expectedReturnDate && new Date(row.expectedReturnDate).getTime() < Date.now() - 86399000
}
const total = ref(0)
const searched = ref(false)
const dateRange = ref<any>(null)
const warehouses = ref<any[]>([])
const projects = ref<any[]>([])
const materials = ref<any[]>([])
const warehouseMap = ref<Record<number, any>>({})
const materialMap = ref<Record<number, any>>({})

const query = reactive<any>({ sortField: '', sortOrder: '',
  current: 1,
  size: 20,
  status: '',
  keyword: '',
  warehouseId: null,
  borrower: '',
  projectId: null,
  startDate: null,
  endDate: null
})

const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const loanAttRef = ref()
const createSubmitting = ref(false)
const form = reactive<any>({
  warehouseId: null,
  projectId: null,
  borrower: '',
  borrowerDept: '',
  borrowDate: today(),
  expectedReturnDate: '',
  purpose: '',
  remark: '',
  lines: []
})

const detailVisible = ref(false)
const detailData = ref<any | null>(null)
const returnVisible = ref(false)
const returnGuard = useUnsavedGuard(returnVisible)
const returnSource = ref<any | null>(null)
const returnLines = ref<any[]>([])
const returnSubmitting = ref(false)
const returnForm = reactive<any>({
  returnDate: today(),
  remark: ''
})

const exportColumns = computed<ExportColumn[]>(() => [
  { label: '借料单号', key: 'docNo' },
  { label: '借料日期', key: 'borrowDate' },
  { label: '预计归还', key: 'expectedReturnDate' },
  { label: '仓库', value: row => warehouseName(row.warehouseId) },
  { label: '工程项目', key: 'projectName' },
  { label: '借料人', key: 'borrower' },
  { label: '借料部门', key: 'borrowerDept' },
  { label: '用途', key: 'purpose' },
  { label: '借出数量', qty: true, key: 'totalQty' },
  { label: '已归还', qty: true, key: 'returnedQty' },
  { label: '未归还', value: row => pendingQty(row) },
  { label: '状态', value: row => row.status === 'CLOSED' ? '已归还' : '已登记' },
  { label: '备注', key: 'remark' }
])

onMounted(async () => {
  await loadOptions()
  // 从「待处理事项」点借料待办跳来时，自动按单号+状态查询并定位
  applyRouteQuery(query, route, ['status', 'keyword', 'borrower', 'warehouseId', 'projectId'])
  load()
})

async function loadOptions() {
  const [whs, mats, projs] = await Promise.all([
    masterApi.warehouses(true),
    masterApi.allMaterials({ includeDisabled: true }),
    masterApi.projects(true)
  ])
  warehouses.value = (whs as any[]) || []
  materials.value = (mats as any[]) || []
  projects.value = (projs as any[]) || []
  warehouseMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  materialMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] || null
  query.endDate = dateRange.value?.[1] || null
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已驳回', value: 'REJECTED' }, { label: '已登记', value: 'REGISTERED' }, { label: '已归还', value: 'CLOSED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => outApi.loanPage(q), ['DRAFT', 'PENDING', 'REJECTED', 'REGISTERED', 'CLOSED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const result: any = await outApi.loanPage(query)
    rows.value = result.records || []
    total.value = result.total || rows.value.length
  } finally {
    loading.value = false
  }
}

// 表头排序:交给后端按数据库排(前端排只会排当前页那几条,翻页就乱)
const onSortChange = makeSortHandler(query, load)

function doSearch() {
  query.current = 1
  load()
}

function resetQuery() {
  Object.assign(query, {
    current: 1,
    size: 20,
    status: '',
    keyword: '',
    warehouseId: null,
    borrower: '',
    projectId: null,
    startDate: null,
    endDate: null
  })
  dateRange.value = null
  load()
}

function openCreate() {
  loanAttRef.value?.reset()  // 清掉上一单残留的暂存附件,防串单
  Object.assign(form, {
    warehouseId: warehouses.value[0]?.id ?? null,
    projectId: null,
    borrower: '',
    borrowerDept: '',
    borrowDate: today(),
    expectedReturnDate: '',
    purpose: '',
    remark: '',
    lines: []
  })
  addLine()
  createVisible.value = true
}

function addLine() {
  form.lines.push({
    materialId: null,
    materialName: '',
    spec: '',
    unit: '',
    currentQty: null,
    qty: undefined,
    remark: ''
  })
}

function addLines(count: number) {
  for (let i = 0; i < count; i += 1) addLine()
}

function clearLines() {
  form.lines = []
  addLine()
}

async function onMaterialChange(row: any, material: any | null) {
  if (!material) {
    Object.assign(row, { materialName: '', spec: '', unit: '', currentQty: null })
    return
  }
  Object.assign(row, {
    materialId: material.id,
    materialName: material.name,
    spec: material.spec,
    unit: material.unit
  })
  await loadLineBalance(row)
}

async function reloadLineBalances() {
  for (const row of form.lines) {
    if (row.materialId) await loadLineBalance(row)
  }
}

async function loadLineBalance(row: any) {
  if (!form.warehouseId || !row.materialId) {
    row.currentQty = null
    return
  }
  const balance: any = await inventoryApi.single(row.materialId, form.warehouseId)
  row.currentQty = Number(balance?.qty ?? 0)
  if (row.qty && row.currentQty != null && Number(row.qty) > row.currentQty) {
    row.qty = row.currentQty
  }
}

function validateCreate() {
  if (!form.warehouseId) return '请选择仓库'
  if (!form.borrowDate) return '请选择借料日期'
  if (!String(form.borrower || '').trim()) return '请填写借料人'
  if (!String(form.purpose || '').trim()) return '请填写用途'
  const validLines = form.lines.filter((line: any) => line.materialId && Number(line.qty || 0) > 0)
  if (!validLines.length) return '请至少填写一条借料明细'
  for (const line of validLines) {
    if (line.currentQty != null && Number(line.qty) > Number(line.currentQty)) {
      return `物料 ${line.materialName || line.materialId} 借料数量不能超过当前库存`
    }
  }
  if (form.expectedReturnDate && form.expectedReturnDate < form.borrowDate) {
    return '预计归还日期不能早于借料日期'
  }
  return ''
}

async function createLoan() {
  const error = validateCreate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  createSubmitting.value = true
  try {
    const payload = {
      warehouseId: form.warehouseId,
      projectId: form.projectId,
      borrower: String(form.borrower).trim(),
      borrowerDept: String(form.borrowerDept || '').trim(),
      borrowDate: form.borrowDate,
      expectedReturnDate: form.expectedReturnDate || null,
      purpose: String(form.purpose).trim(),
      remark: form.remark,
      lines: form.lines
        .filter((line: any) => line.materialId && Number(line.qty || 0) > 0)
        .map((line: any) => ({
          materialId: line.materialId,
          qty: Number(line.qty),
          remark: line.remark
        }))
    }
    const created: any = await outApi.loanCreate(payload)
    if (loanAttRef.value && created?.id) await loanAttRef.value.uploadPending(created.id)
    ElMessage.success('借料登记已保存（草稿），请在列表点「提交」交材料会计审核，通过后才扣减库存')
    createVisible.value = false
    await load()
    if (created?.id) await openDetail(created.id)
  } finally {
    createSubmitting.value = false
  }
}

// 归还记录只存借料行 id,按本单明细找回物料显示(编码 名称)
function returnLineLabel(lineId: number) {
  const line = (detailData.value?.lines || []).find((l: any) => l.id === lineId)
  return line ? `${materialText(line.materialId, 'code')} ${materialText(line.materialId, 'name')}` : '-'
}

async function openDetail(id: number) {
  detailData.value = await outApi.loanDetail(id)
  detailVisible.value = true
}

async function openReturn(row: any) {
  const detail: any = await outApi.loanDetail(row.id)
  returnSource.value = detail
  returnForm.returnDate = today()
  returnForm.remark = ''
  returnLines.value = (detail.lines || [])
    .filter((line: any) => Number(line.availableQty || 0) > 0)
    .map((line: any) => ({
      ...line,
      returnQty: Number(line.availableQty || 0)
    }))
  returnVisible.value = true
}

async function submitReturn() {
  if (!returnSource.value) return
  const lines = returnLines.value
    .filter(line => Number(line.returnQty || 0) > 0)
    .map(line => ({ loanLineId: line.id, qty: Number(line.returnQty) }))
  if (!lines.length) {
    ElMessage.warning('请填写本次归还数量')
    return
  }
  returnSubmitting.value = true
  try {
    const updated: any = await outApi.loanReturn(returnSource.value.id, {
      returnDate: returnForm.returnDate || today(),
      remark: returnForm.remark,
      lines
    })
    ElMessage.success('归还登记已保存，库存已回增')
    returnVisible.value = false
    await load()
    detailData.value = updated
  } finally {
    returnSubmitting.value = false
  }
}

async function deleteLoan(row: any) {
  await ElMessageBox.confirm(`确认删除借料单 ${row.docNo}？删除后会冲回借料库存流水。`, '删除确认', { type: 'warning' })
  await outApi.loanDelete(row.id)
  ElMessage.success('借料单已删除')
  await load()
}

function exportExcel() {
  exportRowsToExcel('借料登记', exportColumns.value, displayRows.value, '借料登记', {
    total: total.value, fetch: p => outApi.loanPage({ ...query, ...p }),
    filter: onlyPending.value ? (r: any) => pendingQty(r) > 0 : undefined
  })
}

async function printLoan(row: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'portrait') {
  const detail = row.lines ? row : await outApi.loanDetail(row.id) as any
  const columns: PrintColumn[] = [
    { label: '序号', value: (_row, index) => index + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: line => materialText(line.materialId, 'code'), width: '13%' },
    { label: '物料名称', value: line => materialText(line.materialId, 'name'), width: '20%' },
    { label: '规格型号', value: line => materialText(line.materialId, 'spec'), width: '14%' },
    { label: '单位', value: line => materialText(line.materialId, 'unit'), width: '6%', align: 'center' },
    { label: '借出数量', key: 'qty', width: '11%', align: 'right' },
    { label: '已归还', key: 'returnedQty', width: '10%', align: 'right' },
    { label: '未归还', key: 'availableQty', width: '10%', align: 'right' },
    { label: '备注', key: 'remark', width: '11%' }
  ]
  const options = {
    orgId: detail?.orgId,
    title: '借料登记单',
    subtitle: `单据号：${detail.docNo} · ${paper}`,
    fileName: `借料登记单-${detail.docNo}-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('LOAN', detail.id, detail.docNo),
    fields: [
      { label: '借料单号', value: detail.docNo },
      { label: '借料日期', value: fmtDate(detail.borrowDate) },
      { label: '仓库', value: warehouseName(detail.warehouseId) },
      { label: '借料人', value: detail.borrower },
      { label: '借料部门', value: detail.borrowerDept || '-' },
      { label: '预计归还', value: detail.expectedReturnDate || '-' },
      { label: '工程项目', value: detail.projectName || '-' },
      { label: '用途', value: detail.purpose || '-' },
      { label: '状态', value: detail.status === 'CLOSED' ? '已归还' : '已登记' }
    ],
    columns,
    rows: detail.lines || [],
    signatures: [
      { label: '借料人', lines: ['签字：', '日期：'] },
      { label: '仓管经办', lines: ['签字：', '日期：'] },
      { label: '归还确认', lines: ['签字：', '日期：'] }
    ],
    note: detail.remark ? `备注：${detail.remark}` : ''
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

function warehouseName(id: number) {
  return warehouseMap.value[id]?.name || id || '-'
}

function materialText(id: number, key: 'code' | 'name' | 'spec' | 'unit') {
  const mat = materialMap.value[id]
  return mat?.[key] || ''
}

function projectLabel(project: any) {
  return `${project.code || ''} ${project.name || ''}`.trim()
}

function pendingQty(row: any) {
  return Math.max(0, Number(row.totalQty || 0) - Number(row.returnedQty || 0))
}

function canReturn(row: any) {
  return user.hasRole('warehouse') && row.status === 'REGISTERED' && pendingQty(row) > 0
}

function canDelete(row: any) {
  // 草稿/驳回：创建人可删；已登记(审核通过、已扣库存)且无归还记录：创建人/管理员可删(会冲回库存)
  if (Number(row.returnedQty || 0) > 0) return false
  if (['DRAFT', 'REJECTED'].includes(row.status)) return user.ownsDoc(row)
  if (row.status === 'REGISTERED') return user.ownsDoc(row)
  return false
}

// ===== 审批流(提交→材料会计审核→通过才扣库存) =====
function canSubmit(row: any) {
  return user.hasRole('warehouse') && user.ownsDoc(row) && ['DRAFT', 'REJECTED'].includes(row.status)
}
function canWithdraw(row: any) {
  return user.ownsDoc(row) && row.status === 'PENDING'
}
function canApprovePending(row: any) {
  return row.status === 'PENDING' && user.hasRole('material_accountant')
}
async function submitLoan(row: any) {
  await ElMessageBox.confirm(`确认提交借料单 ${row.docNo} 交材料会计审核？`, '提交审核', { type: 'info' })
  await outApi.loanSubmit(row.id)
  ElMessage.success('已提交，等待材料会计审核')
  await load()
}
async function withdrawLoan(row: any) {
  await outApi.loanWithdraw(row.id)
  ElMessage.success('已取消提交，退回草稿')
  await load()
}
async function approveLoan(row: any) {
  await outApi.loanApprove(row.id, '同意')
  ElMessage.success('已审核通过，库存已扣减')
  await load()
}
async function rejectLoan(row: any) {
  const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回借料单', { inputPlaceholder: '驳回原因' })
  await outApi.loanReject(row.id, value || '驳回')
  ElMessage.success('已驳回')
  await load()
}

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(displayRows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'returnedQty') return fmtQty(displayRows.value.reduce((a: number, r: any) => a + Number(r.returnedQty || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detailData.value
  if (!d) return ''
  return [d.borrower && `借料人 ${d.borrower}`, d.projectName, d.borrowDate && `借出 ${d.borrowDate}`, d.expectedReturnDate && `预计归还 ${d.expectedReturnDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "REGISTERED": 2, "CLOSED": 4} as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '借出数量', value: fmtQty(d.totalQty) }, { label: '已归还', value: fmtQty(d.returnedQty) }, { label: '未归还', value: fmtQty(pendingQty(d)), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 借出数量: { field: 'qty', type: 'qty' }, 已归还: { field: 'returnedQty', type: 'qty' }, 未归还: { field: 'availableQty', type: 'qty' } })
</script>

<style scoped lang="scss">
.line-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.loan-line-table :deep(.el-input-number .el-input__inner) {
  text-align: right;
}

.row-print {
  display: inline-flex;
  margin: 0 4px;
  vertical-align: middle;
}

.row-print :deep(.el-button) {
  --el-button-size: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: var(--el-color-primary);
}

.row-print :deep(.el-button span) {
  font-size: 14px;
}

/* 借料督办 */
.loan-supervise-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.supervise-hint {
  font-size: 12px;
  color: #b7791f;
}

.loan-overdue {
  color: #c0392b;
  font-weight: 700;
}
</style>
