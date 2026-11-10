<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">采购订单</div>
        <div class="page-sub">采购订单作为采购流程起点，提交后由财务审核，审核后支持分批入库 / 分批开票</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="user.hasRole('purchaser')" type="primary" @click="openCreate">+ 新建订单</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
          <el-form-item label="状态" data-q="status">
            <el-select v-model="query.status" placeholder="全部" clearable style="width:150px">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="已审批" value="APPROVED" />
              <el-option label="部分入库" value="PARTIAL_RECEIVED" />
              <el-option label="已入库" value="RECEIVED" />
              <el-option label="已完成" value="CLOSED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已作废" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="下单日期" data-q="dateRange">
            <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
          </el-form-item>
          <el-form-item label="订单号" data-q="keyword">
            <DocNoLookup v-model="query.keyword" type="PO" multiple placeholder="点一下列出最近采购订单，也可输入单号" @enter="doSearch" />
          </el-form-item>
          <el-form-item label="供应商" data-q="supplierId">
            <SupplierLookup v-model="query.supplierId" placeholder="全部供应商" width="220px" />
          </el-form-item>
          <el-form-item label="仓库" data-q="warehouseId">
            <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="物料" data-q="materialId">
            <MaterialLookup v-model="query.materialId" placeholder="编码、名称或规格" />
          </el-form-item>
        </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border
        :fit="false"
        :data="rows" show-summary :summary-method="pageSummary"
        v-loading="loading"
        highlight-current-row
        :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'"
        @current-change="selectedRow = $event"
       @sort-change="onSortChange">
        <el-table-column prop="docNo" :fixed="freezeLeft ? 'left' : undefined" label="订单号" width="220" sortable="custom">
          <template #default="{ row }">
            <button type="button" class="doc-link" aria-label="查看单据" :title="`查看单据 ${row.docNo}`" @click="openDetail(row.id)">{{ row.docNo }}</button>
          </template>
        </el-table-column>
        <el-table-column prop="orderDate" label="下单日期" width="110" sortable="custom" />
        <el-table-column prop="supplierName" sortable="custom" label="供应商" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ supplierMap[row.supplierId]?.name || row.supplierId || '-' }}</template>
        </el-table-column>
        <el-table-column label="物料编码" min-width="125" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialCodes) }}</template>
        </el-table-column>
        <el-table-column label="物料名称" min-width="170">
          <template #default="{ row }"><span class="mat-name-cell">{{ firstMat(row.materialNames) }}<MoreMaterialsTag :codes="row.materialCodes" :names="row.materialNames" :specs="row.materialSpecs" /></span></template>
        </el-table-column>
        <el-table-column label="规格型号" min-width="115" show-overflow-tooltip>
          <template #default="{ row }">{{ firstMat(row.materialSpecs) }}</template>
        </el-table-column>
        <el-table-column label="仓库" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ warehouseMap[row.warehouseId]?.name || row.warehouseId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="totalQty" label="订货" width="110" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column prop="receivedQty" label="已入" width="110" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column label="未入" width="110" align="right">
          <template #default="{ row }">{{ formatQty(Number(row.totalQty || 0) - Number(row.receivedQty || 0)) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" prop="totalAmt" sortable="custom" label="金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalAmt) }}</template>
        </el-table-column>
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="操作" width="390">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printPo(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="primary" @click="openEdit(row)">修改</el-button>
            <el-button v-if="['DRAFT','REJECTED'].includes(row.status) && user.hasRole('purchaser')" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="approveOrReject(row, true)">审批</el-button>
            <el-button v-if="canApprovePending(row)" link type="danger" @click="approveOrReject(row, false)">驳回</el-button>
            <el-button v-if="['APPROVED','PARTIAL_RECEIVED'].includes(row.status) && user.hasRole('warehouse')" link type="primary" @click="openPushRecv(row)">下推入库</el-button>
            <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="requestCancelApproval('PO', row, load)">取消审批</el-button>
            <el-button v-if="canCorrectTax(row)" link type="warning" @click="openTaxFix(row)">税率订正</el-button>
            <el-button v-if="['DRAFT','REJECTED'].includes(row.status) && user.hasRole('purchaser')" link type="danger" @click="confirmDeleteDoc(row, poApi.delete, load, '采购订单')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]"
                     layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <el-dialog draggable v-model="formVisible" :before-close="formGuard.beforeClose" :title="form.id ? '修改采购订单' : '新建采购订单'" width="min(1360px, calc(100vw - 48px))" class="doc-entry-dialog">
      <DocGuardHint :active="formVisible" :date="form.orderDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <el-form label-position="top" :model="form" label-width="96px" size="default">
        <el-row :gutter="20" class="doc-key-fields">
          <el-col :span="8">
            <el-form-item label="供应商" required>
              <SupplierLookup v-model="form.supplierId" @change="form.supplier = $event" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="仓库" required>
              <el-select v-model="form.warehouseId" placeholder="请选择仓库" filterable style="width:100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="下单日期" required>
              <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="交货日期">
              <el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="制单人">
              <el-input :model-value="form.creatorName" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可填写合同号、交付说明等" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider>订单明细</el-divider>
        <div class="line-toolbar">
          <el-button size="small" type="primary" plain @click="addLine">增行</el-button>
          <el-button size="small" @click="addLines(5)">批量增 5 行</el-button>
          <el-button size="small" type="success" plain @click="batchPasteRef?.open()">从Excel批量导入</el-button>
          <el-button size="small" type="danger" plain :disabled="!form.lines.length" @click="form.lines = []">清空明细</el-button>
        </div>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="form.lines" border show-summary :summary-method="formLineSummary">
          <el-table-column label="序号" type="index" width="64" />
          <el-table-column label="物料编码" min-width="190" resizable>
            <template #default="{ row }">
              <MaterialLookup v-model="row.materialId" placeholder="选择物料" title="选择物料编码" @change="onMaterialChange(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="140" show-overflow-tooltip resizable>
            <template #default="{ row }">{{ row.materialName || '-' }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="100" show-overflow-tooltip resizable>
            <template #default="{ row }">{{ row.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" resizable>
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="110" resizable>
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" controls-position="right" style="width:100%" placeholder="必填" @change="onQtyChange(row)" />
            </template>
          </el-table-column>
          <el-table-column label="税率%" width="112" resizable>
            <template #default="{ row }">
              <el-input-number v-model="row.taxRate" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" placeholder="税率" @change="recalcLine(row)" />
            </template>
          </el-table-column>
          <!-- 2026-09-29 单价录成金额防错：主填含税单价，金额=数量×单价自动算；金额仍可手改（调尾差），会反算单价 -->
          <el-table-column label="含税单价" width="170" resizable>
            <template #default="{ row }">
              <el-input-number v-model="row.priceIncl" :min="0" :precision="4" :controls="false" style="width:100%" placeholder="填单价"
                               :class="{ 'price-bad': lineDeviation(row) }" @change="onPriceChange(row)" />
              <div v-if="row.materialId && priceRefs[row.materialId]" class="price-ref" :class="{ bad: lineDeviation(row) }">
                历史价约 {{ fmtMoney(priceRefs[row.materialId].refPrice) }}<template v-if="lineDeviation(row)">，{{ deviationText(Number(row.priceIncl), priceRefs[row.materialId].refPrice) }}</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="含税金额" width="130" resizable>
            <template #default="{ row }">
              <el-input-number v-model="row.amount" :min="0" :precision="2" controls-position="right" style="width:100%" placeholder="自动算" title="金额=数量×含税单价，可手改调尾差" @change="onAmountChange(row)" />
            </template>
          </el-table-column>
          <el-table-column label="不含税金额" width="120" align="right" resizable>
            <template #default="{ row }">{{ fmtMoney(row.amountExclTax) }}</template>
          </el-table-column>
          <el-table-column label="税额" width="110" align="right" resizable>
            <template #default="{ row }">{{ fmtMoney(row.taxAmount) }}</template>
          </el-table-column>
          <el-table-column label="不含税单价" width="120" align="right" resizable>
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="120" resizable>
            <template #default="{ row }"><el-input v-model="row.remark" placeholder="可选" /></template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <DocAttachments ref="poAttRef" biz-type="PO" :biz-id="form.id" />
      <template #footer>
        <el-button @click="formGuard.cancel">取消</el-button>
        <el-button :loading="saving" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(false)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <DocDrawer v-model="detailVisible" kind="采购订单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :steps="['制单', '审批', '入库', '完成']" :step-active="detailStep" :step-rejected="detailData?.status === 'REJECTED'" :nums="detailNums">

      <template v-if="detailData">
        <!-- 价格偏离提示放在最上面：明细表列多、"价格检查"在最右，审批人不滚动就会漏看 -->
        <el-alert v-if="canSeeCost && detailData.lines?.some((l: any) => l.priceCheckNote)" type="warning" show-icon :closable="false" class="mb-3"
                  :title="`本单有 ${detailData.lines.filter((l: any) => l.priceCheckNote).length} 行单价与历史采购价相差过大，制单人已确认并写了理由，请审批前核对（见明细最右「价格检查」列）`" />
        <el-descriptions :column="3" border size="small" class="mb-3">
          <el-descriptions-item label="订单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="供应商">{{ supplierMap[detailData.supplierId]?.name || detailData.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ warehouseMap[detailData.warehouseId]?.name || detailData.warehouseId }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ detailData.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="交货日期">{{ detailData.deliveryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="canSeeCost" label="金额">{{ fmtMoney(detailData.totalAmt) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailData.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="170">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" prop="qty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column label="已入" prop="receivedQty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column v-if="canSeeCost" label="税率%" width="80" align="right">
            <template #default="{ row }">{{ Number(row.taxRate || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="含税金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="不含税金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amountExclTax) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="税额" width="110" align="right">
            <template #default="{ row }">{{ fmtMoney(row.taxAmount) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="不含税单价" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost && detailData.lines?.some((l: any) => l.priceCheckNote)" label="价格检查" min-width="220">
            <template #default="{ row }">
              <template v-if="row.priceCheckNote">
                <el-tag type="warning" size="small">价格偏离·制单人已确认</el-tag>
                <div class="price-note">历史价约 {{ fmtMoney(row.priceCheckRef) }}，本单 {{ fmtMoney(row.qty ? row.amount / row.qty : 0) }}。理由：{{ row.priceCheckNote }}</div>
              </template>
              <span v-else style="color:#909399">正常</span>
            </template>
          </el-table-column>
        </el-table>
        <DocAttachments biz-type="PO" :biz-id="detailData.id" />
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
        <el-button v-if="detailData && detailData.status === 'PENDING' && detailData.canApprove" type="danger" plain @click="approveFromDetail(false)">驳回</el-button>
        <el-button v-if="detailData && ['DRAFT','REJECTED'].includes(detailData.status) && user.hasRole('purchaser')" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          :type="detailData && detailData.status === 'PENDING' && detailData.canApprove ? '' : 'primary'"
          @print="(paper, orientation) => printPo(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printPo(detailData, 'export', paper, orientation)"
        />
        <el-button v-if="detailData && detailData.status === 'PENDING' && detailData.canApprove" type="primary" @click="approveFromDetail(true)">审批通过</el-button>
      </template>

    </DocDrawer>

    <!-- 税率订正（仅未入库订单）：含税金额不变，重算不含税 -->
    <el-dialog draggable v-model="taxFixVisible" :before-close="taxFixGuard.beforeClose" title="税率订正（仅未入库订单）" width="min(900px, calc(100vw - 48px))">
      <el-alert type="info" :closable="false" show-icon class="mb-3"
        title="含税金额不变，按下方税率重算不含税金额/税额/不含税单价。仅未入库订单可订正；已入库的请通过发票核销修正成本。" />
      <el-table :data="taxFixLines" border size="small" class="readable-table">
        <el-table-column label="物料" min-width="220">
          <template #default="{ row }">{{ matMap[row.materialId]?.code }} {{ matMap[row.materialId]?.name }}<span v-if="matMap[row.materialId]?.spec" style="color:#909399"> · {{ matMap[row.materialId]?.spec }}</span></template>
        </el-table-column>
        <el-table-column label="数量" prop="qty" width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column label="含税金额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="税率%" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.taxRate" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" />
          </template>
        </el-table-column>
        <el-table-column label="重算·不含税单价" width="140" align="right">
          <template #default="{ row }">{{ fmtMoney(taxFixExclUnit(row)) }}</template>
        </el-table-column>
        <el-table-column label="重算·不含税金额" width="140" align="right">
          <template #default="{ row }">{{ fmtMoney(taxFixExclAmt(row)) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="taxFixGuard.cancel">取消</el-button>
        <el-button type="primary" :loading="taxFixSaving" @click="saveTaxFix">保存订正</el-button>
      </template>
    </el-dialog>

  </div>
  <BatchLinePaste ref="batchPasteRef" price-label="含税单价" @apply="applyBatchPaste" />
  <PoPriceCheckDialog ref="priceCheckRef" :materials="lineMaterials" />
</template>

<script setup lang="ts">
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { poApi, masterApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { fmtDate, fmtMoney, today, roundMoney, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { applyRouteQuery } from '@/utils/routeQuery'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { approvalRecordNote } from '@/utils/approvalPrint'
import { buildPrintQr } from '@/utils/docQr'
import StatusTag from '@/components/StatusTag.vue'
import MaterialLookup from '@/components/MaterialLookup.vue'
import MoreMaterialsTag from '@/components/MoreMaterialsTag.vue'
import BatchLinePaste from '@/components/BatchLinePaste.vue'
import PoPriceCheckDialog from '@/components/PoPriceCheckDialog.vue'
import { fetchPriceRefs, fetchPriceRefsForSave, findPriceIssues, priceDeviation, deviationText, saveWithPriceNote, type PriceRef } from '@/utils/poPriceCheck'
import SupplierLookup from '@/components/SupplierLookup.vue'
import DocNoLookup from '@/components/DocNoLookup.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'

function firstMat(s?: string | null) { return (s || '').split('、')[0] || '-' }

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const canSeeCost = computed(() => user.hasRole('purchaser')
  || user.hasFinanceAccess()
  || user.hasRole('admin')
  || user.hasRole('finance_manager')
  || user.hasRole('deputy_general_manager')
  || user.hasRole('general_manager'))

function canApprovePending(row: any) {
  // 仅当后端判定当前用户是本节点审批人时才显示审批/驳回（采购员等非审批人不显示）
  return row.status === 'PENDING' && row.canApprove === true
}
function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
const loading = ref(false)
const saving = ref(false)
const freezeLeft = ref(true)   // 冻结左列(横向滚动看数字时保持标识列可见)
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const selectedRow = ref<any>(null)
const query = reactive<any>({ sortField: '', sortOrder: '', current: 1, size: 20, status: '', keyword: '', supplierId: null, warehouseId: null, materialId: null, startDate: null, endDate: null })
const dateRange = ref<any>(null)

const materials = ref<any[]>([])
const suppliers = ref<any[]>([])
const warehouses = ref<any[]>([])
const matMap = ref<Record<number, any>>({})
const supplierMap = ref<Record<number, any>>({})
const warehouseMap = ref<Record<number, any>>({})
const exportColumns = computed<ExportColumn[]>(() => [
  { label: '订单号', key: 'docNo' },
  { label: '下单日期', key: 'orderDate' },
  { label: '供应商', value: row => supplierMap.value[row.supplierId]?.name || row.supplierId || '-' },
  { label: '仓库', value: row => warehouseMap.value[row.warehouseId]?.name || row.warehouseId || '-' },
  { label: '订货', key: 'totalQty', qty: true },
  { label: '已入', key: 'receivedQty', qty: true },
  { label: '未入', value: row => formatQty(Number(row.totalQty || 0) - Number(row.receivedQty || 0)), qty: true },
  ...(canSeeCost.value ? [{ label: '金额', money: true, value: (row: any) => fmtMoney(row.totalAmt) }] : []),
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '备注', key: 'remark' }
])

async function ensureMaster() {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!suppliers.value.length) suppliers.value = await masterApi.allSuppliers() as any
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  supplierMap.value = Object.fromEntries(suppliers.value.map((s: any) => [s.id, s]))
  warehouseMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
}

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '部分入库', value: 'PARTIAL_RECEIVED' }, { label: '已入库', value: 'RECEIVED' }, { label: '已完成', value: 'CLOSED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => poApi.page(q), ['DRAFT', 'PENDING', 'APPROVED', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    await ensureMaster()
    const r: any = await poApi.page(query)
    rows.value = r.records
    total.value = r.total
    selectedRow.value = null
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
  Object.assign(query, { current: 1, size: 20, status: '', keyword: '', supplierId: null, warehouseId: null, materialId: null, startDate: null, endDate: null })
  dateRange.value = null
  selectedRow.value = null
  load()
}

function exportExcel() {
  exportRowsToExcel('采购订单', exportColumns.value, rows.value, '采购订单', { total: total.value, fetch: p => poApi.page({ ...query, ...p }) })
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

function formatQty(value: number) {
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

ensureMaster().catch(() => {})

onMounted(() => {
  applyRouteQuery(query, route, ['status', 'keyword', 'supplierId', 'warehouseId'])
  load()
})

const formVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const formGuard = useUnsavedGuard(formVisible)
const poAttRef = ref()
const form = reactive<any>({ id: null, supplierId: null, supplier: null, warehouseId: null, orderDate: today(), deliveryDate: '', creatorName: '', remark: '', lines: [] })

async function openCreate() {
  poAttRef.value?.reset()  // 清掉上一单残留的暂存附件,防串单
  await ensureMaster()
  Object.assign(form, {
    id: null,
    supplierId: null,
    supplier: null,
    warehouseId: warehouses.value[0]?.id ?? null,
    orderDate: today(),
    deliveryDate: '',
    creatorName: user.userInfo?.name || user.userInfo?.username || '',
    remark: '',
    lines: []
  })
  addLine()
  priceRefs.value = {}
  formVisible.value = true
}

async function openEdit(row: any) {
  poAttRef.value?.reset()  // 编辑前也清暂存附件,防串单
  await ensureMaster()
  const detail: any = await poApi.detail(row.id)
  Object.assign(form, {
    id: detail.id,
    supplierId: detail.supplierId,
    supplier: supplierMap.value[detail.supplierId] || null,
    warehouseId: detail.warehouseId,
    orderDate: detail.orderDate || today(),
    deliveryDate: detail.deliveryDate || '',
    creatorName: user.userInfo?.name || user.userInfo?.username || '',
    remark: detail.remark || '',
    lines: (detail.lines || []).map((line: any) => {
      const material = matMap.value[line.materialId] || {}
      return {
        materialId: line.materialId,
        materialName: material.name || '',
        spec: material.spec || '',
        unit: material.unit || '',
        qty: Number(line.qty || 0),
        taxRate: Number(line.taxRate || 0),
        amount: Number(line.amount || 0),                 // 含税金额
        amountExclTax: Number(line.amountExclTax || 0),   // 不含税金额
        taxAmount: Number(line.taxAmount || 0),           // 税额
        unitPrice: Number(line.unitPrice || 0),           // 不含税单价
        priceIncl: Number(line.qty) > 0 ? Number((Number(line.amount || 0) / Number(line.qty)).toFixed(4)) : null,  // 含税单价（录入栏）
        priceCheckNote: line.priceCheckNote || '',
        remark: line.remark || ''
      }
    })
  })
  if (!form.lines.length) addLine()
  priceRefs.value = {}
  loadPriceRefs(form.lines.map((l: any) => l.materialId))
  formVisible.value = true
}

function addLine() {
  form.lines.push({ materialId: null, materialName: '', spec: '', unit: '', qty: null, taxRate: null, priceIncl: null, amount: null, amountExclTax: 0, taxAmount: 0, unitPrice: 0, priceCheckNote: '', remark: '' })
}
const batchPasteRef = ref()
function applyBatchPaste(rows: { material: any; qty: number; price: number | null }[]) {
  form.lines = form.lines.filter((l: any) => l.materialId)
  for (const r of rows) {
    form.lines.push({ materialId: r.material.id, materialName: r.material.name, spec: r.material.spec || '', unit: r.material.unit || '', qty: r.qty, taxRate: null, priceIncl: r.price, amount: r.price != null ? roundMoney(r.qty * r.price) : null, amountExclTax: 0, taxAmount: 0, unitPrice: 0, priceCheckNote: '', remark: '' })
  }
  loadPriceRefs(rows.map(r => r.material.id))
}

function addLines(count = 5) {
  for (let i = 0; i < count; i++) addLine()
}

function recalcLine(row: any) {
  const amount = Number(row.amount || 0)        // 含税金额（录入）
  const taxRate = Number(row.taxRate || 0)
  const qty = Number(row.qty || 0)
  const exclTax = amount / (1 + taxRate / 100)  // 不含税金额
  row.amountExclTax = Number(exclTax.toFixed(2))
  row.taxAmount = Number((amount - row.amountExclTax).toFixed(2))
  row.unitPrice = qty > 0 ? Number((row.amountExclTax / qty).toFixed(2)) : 0   // 不含税单价（倒挤）
}

// ===== 单价录成金额防错（2026-09-29）=====
// 单价 ↔ 金额双向联动：改单价 → 金额=数量×单价；改金额（调尾差）→ 反算单价；改数量 → 按单价重算金额
function onPriceChange(row: any) {
  row.priceCheckNote = ''   // 价格动过，之前写的理由作废，保存时重新检查
  if (row.priceIncl != null && Number(row.qty) > 0) row.amount = roundMoney(Number(row.qty) * Number(row.priceIncl))
  recalcLine(row)
}
function onAmountChange(row: any) {
  row.priceCheckNote = ''   // 价格动过，之前写的理由作废，保存时重新检查
  row.priceIncl = Number(row.qty) > 0 && row.amount != null ? Number((Number(row.amount) / Number(row.qty)).toFixed(4)) : null
  recalcLine(row)
}
function onQtyChange(row: any) {
  row.priceCheckNote = ''   // 价格动过，之前写的理由作废，保存时重新检查
  if (row.priceIncl != null && Number(row.qty) > 0) row.amount = roundMoney(Number(row.qty) * Number(row.priceIncl))
  else if (row.amount != null && Number(row.qty) > 0) row.priceIncl = Number((Number(row.amount) / Number(row.qty)).toFixed(4))
  recalcLine(row)
}
const priceRefs = ref<Record<number, PriceRef>>({})
const priceCheckRef = ref()
async function loadPriceRefs(ids: number[]) {
  const want = ids.filter(id => id && !(id in priceRefs.value))
  if (!want.length) return
  try { Object.assign(priceRefs.value, await fetchPriceRefs(want, form.id)) } catch { /* 参考价只是提示，拿不到不挡录入；保存时后端仍会把关 */ }
}
function lineDeviation(row: any) {
  const qty = Number(row.qty || 0)
  return qty > 0 && !!priceDeviation(Number(row.amount || 0) / qty, priceRefs.value[row.materialId])
}
const lineMaterials = computed(() => {
  const m: Record<number, any> = {}
  form.lines.forEach((l: any) => { if (l.materialId) m[l.materialId] = { code: l.materialCode || matMap.value[l.materialId]?.code || '', name: l.materialName, spec: l.spec, unit: l.unit } })
  return m
})
/** 保存前价格检查；返回 false 表示用户选择返回修改 */
async function runPriceCheck(): Promise<boolean> {
  let lastNote = ''
  for (let round = 0; round < 50; round++) {
    const refs = await fetchPriceRefsForSave(form.lines.map((l: any) => l.materialId), form.id)
    Object.assign(priceRefs.value, refs)
    const issues = findPriceIssues(form.lines, refs)
    form.lines.forEach((l: any, i: number) => { if (!issues.some(x => x.index === i)) l.priceCheckNote = '' })
    if (!issues.length) return true
    if (issues.every(x => form.lines[x.index].priceCheckNote)) return true   // 已写过理由（改单再存）
    const r = await priceCheckRef.value.open(issues, lastNote)
    if (!r) return false
    if (r.action === 'fix') {
      const row = form.lines[r.index]
      row.priceIncl = Number(row.amount)
      onPriceChange(row)
      continue
    }
    lastNote = r.note
    issues.forEach(x => { form.lines[x.index].priceCheckNote = r.note })
    return true
  }
  return false
}

function onMaterialChange(row: any, material: any | null) {
  row.materialName = material?.name || ''
  row.spec = material?.spec || ''
  row.unit = material?.unit || ''
  row.materialCode = material?.code || ''
  if (material) loadPriceRefs([material.id])
  if (material) row.taxRate = Number(material.defaultTax || 0)   // 带出物料默认税率，可手改
  recalcLine(row)
}

async function save(submitAfterSave: boolean) {
  if (saving.value) return
  saving.value = true
  try {
    const invalid = validateForm()
    if (invalid) { await cannotSave(invalid); return }
    // 税率为 0 提醒：含税金额录入后系统按税率倒挤不含税成本；税率漏填(=0)会导致进项税未拆出、存货成本与报表金额虚高
    const zeroTaxCount = form.lines.filter((l: any) => !Number(l.taxRate)).length
    if (zeroTaxCount > 0) {
      try {
        await ElMessageBox.confirm(
          `有 ${zeroTaxCount} 行明细的税率为 0（此时“不含税=含税”，进项税额不会被拆出，存货成本会按全额计）。\n若该采购确有可抵扣进项（如 13% 专票），请返回填写正确税率；确认按 0 税率继续吗？`,
          '税率为 0，请确认',
          { type: 'warning', confirmButtonText: '确认按 0 税率', cancelButtonText: '返回填税率', distinguishCancelAndClose: true }
        )
      } catch {
        return  // 用户选择返回填税率
      }
    }
    if (!(await runPriceCheck())) return
    const payload = {
      supplierId: form.supplierId,
      warehouseId: form.warehouseId,
      orderDate: form.orderDate,
      deliveryDate: form.deliveryDate || null,
      remark: form.remark,
      lines: form.lines.map((line: any) => ({
        materialId: line.materialId,
        qty: line.qty,
        taxRate: line.taxRate,
        amount: line.amount,
        priceCheckNote: line.priceCheckNote || null,
        remark: line.remark
      }))
    }
    let saved: any = null
    if (form.id) {
      await saveWithPriceNote(payload, p => poApi.update(form.id, p))
      saved = { id: form.id, docNo: '当前订单' }
    } else {
      saved = await saveWithPriceNote(payload, p => poApi.create(p))
    }
    // 建单阶段暂存的附件，拿到订单 id 后补传（在提交前，审批人审时即可见）
    if (poAttRef.value) await poAttRef.value.uploadPending(saved.id)
    if (submitAfterSave) await poApi.submit(saved.id)
    ElMessage.success(submitAfterSave ? '已保存并提交采购订单' : '已保存采购订单草稿')
    formVisible.value = false
    await load()
  } catch { /* 请求错误已由统一拦截器提示；保留表单供重试。 */ }
  finally { saving.value = false }
}

// ===== 税率订正（仅未入库订单）=====
const taxFixVisible = ref(false)
const taxFixGuard = useUnsavedGuard(taxFixVisible)
const taxFixSaving = ref(false)
const taxFixLines = ref<any[]>([])
let taxFixId: number | null = null
function canCorrectTax(row: any) {
  // 未入库(received=0)的草稿/驳回/已审批订单，财务/管理员均可订正税率（含退回草稿后）
  return user.hasFinanceAccessOrAdmin() && ['DRAFT', 'REJECTED', 'APPROVED'].includes(row.status) && Number(row.receivedQty || 0) === 0
}
async function openTaxFix(row: any) {
  await ensureMaster()
  const d: any = await poApi.detail(row.id)
  taxFixId = row.id
  taxFixLines.value = (d.lines || []).map((l: any) => ({ id: l.id, materialId: l.materialId, qty: l.qty, amount: l.amount, taxRate: Number(l.taxRate || 0) }))
  taxFixVisible.value = true
}
function taxFixExclAmt(row: any) {
  const amt = Number(row.amount || 0), r = Number(row.taxRate || 0)
  return amt / (1 + r / 100)
}
function taxFixExclUnit(row: any) {
  const qty = Number(row.qty || 0)
  return qty ? taxFixExclAmt(row) / qty : 0
}
async function saveTaxFix() {
  if (!taxFixId) return
  taxFixSaving.value = true
  try {
    await poApi.correctTax(taxFixId, taxFixLines.value.map((l: any) => ({ lineId: l.id, taxRate: Number(l.taxRate || 0) })))
    ElMessage.success('税率已订正，不含税成本已重算')
    taxFixVisible.value = false
    await load()
  } finally { taxFixSaving.value = false }
}

function validateForm() {
  if (!form.supplierId) return '请选择供应商'
  if (!form.warehouseId) return '请选择仓库'
  if (!form.orderDate) return '请选择下单日期'
  if (!form.lines.length) return '请至少添加一行订单明细'
  for (let i = 0; i < form.lines.length; i++) {
    const line = form.lines[i]
    if (!line.materialId) return `第 ${i + 1} 行请选择物料`
    if (line.qty === null || line.qty === undefined || line.qty === '' || Number(line.qty) <= 0) return `第 ${i + 1} 行请填写大于 0 的数量`
    if (line.amount === null || line.amount === undefined || line.amount === '' || Number(line.amount) <= 0) return `第 ${i + 1} 行含税金额必须大于 0（不允许 0 元采购；赠品请走盘盈等其他入库）`
  }
  return ''
}

async function cannotSave(message: string) {
  await ElMessageBox.alert(message, '无法保存', {
    confirmButtonText: '确定',
    type: 'warning'
  })
}

async function submit(row: any) {
  await poApi.submit(row.id)
  ElMessage.success('已提交采购订单')
  await load()
}

async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交订单 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await poApi.withdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}

async function approveOrReject(row: any, approve: boolean): Promise<boolean> {
  try {
    if (approve) {
      // 列表行一键通过不打开详情——有"价格偏离·制单人已确认"的行时先亮给审批人看，别让它悄悄溜过去
      const lines = row.lines?.length ? row.lines : ((await poApi.detail(row.id)) as any)?.lines || []
      const flagged = lines.filter((l: any) => l.priceCheckNote)
      if (flagged.length) {
        const text = flagged.map((l: any) => `${matMap.value[l.materialId]?.name || '物料' + l.materialId}：本单含税单价 ${fmtMoney(Number(l.qty) ? l.amount / l.qty : 0)}，历史价约 ${fmtMoney(l.priceCheckRef)}；制单人理由：${l.priceCheckNote}`).join('\n')
        await ElMessageBox.confirm(text, `订单 ${row.docNo} 有 ${flagged.length} 行单价偏离历史价`, { type: 'warning', confirmButtonText: '已核对，审批通过', cancelButtonText: '先不审', customStyle: { whiteSpace: 'pre-line' } as any })
      }
      // 审批通过：一键直接通过，不再弹原因框（误批可走“取消审批”撤回）
      await poApi.approve(row.id, '同意')
    } else {
      // 驳回仍需说明理由
      const { value: comment } = await ElMessageBox.prompt('请输入驳回原因', `驳回订单 ${row.docNo}`, { inputType: 'textarea' })
      await poApi.reject(row.id, comment)
    }
    ElMessage.success(`已${approve ? '通过' : '驳回'}`)
    await load()
    return true
  } catch { return false }
}

// 详情弹窗内直接审批：成功后关闭弹窗
async function approveFromDetail(approve: boolean) {
  if (await approveOrReject(detailData.value, approve)) detailVisible.value = false
}

const detailVisible = ref(false)
const detailData = ref<any>(null)
// 详情弹窗内直接删除：与列表行同口径（草稿/已驳回 + 采购员），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, poApi.delete, () => {}, '采购订单') } catch { return }
  detailVisible.value = false
  await load()
}

async function openDetail(id: number) {
  await ensureMaster()
  detailData.value = await poApi.detail(id)
  detailVisible.value = true
}

async function printPo(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'landscape') {
  await ensureMaster()
  const doc: any = rowOrDoc?.lines ? rowOrDoc : await poApi.detail(rowOrDoc.id)
  // 用百分比宽度（合计≤100%）：table-layout:fixed 下既不会塌成 0px 竖排，也不会因 px 超宽撑破纸面
  const columns: PrintColumn[] = [
    { label: '序号', value: (_row, index) => index + 1, width: '4%', align: 'center' },
    { label: '物料编码', value: line => matMap.value[line.materialId]?.code || '', width: '10%' },
    { label: '物料名称', value: line => matMap.value[line.materialId]?.name || line.materialId || '', width: '15%' },
    { label: '规格型号', value: line => matMap.value[line.materialId]?.spec || '', width: '11%' },
    { label: '单位', value: line => matMap.value[line.materialId]?.unit || '', width: '4%', align: 'center' },
    { label: '订货数量', key: 'qty', width: '7%', align: 'right' },
    ...(canSeeCost.value ? [
      { label: '不含税单价', value: (line: any) => fmtMoney(line.unitPrice), width: '9%', align: 'right' },
      { label: '税率%', value: (line: any) => Number(line.taxRate || 0).toFixed(2), width: '6%', align: 'right' },
      { label: '不含税金额', value: (line: any) => fmtMoney(line.amountExclTax), width: '10%', align: 'right' },
      { label: '税额', value: (line: any) => fmtMoney(line.taxAmount), width: '8%', align: 'right' },
      { label: '含税金额', value: (line: any) => fmtMoney(line.amount), width: '9%', align: 'right' }
    ] as PrintColumn[] : []),
    { label: '备注', key: 'remark', width: '7%' }
  ]
  const supplier = supplierMap.value[doc.supplierId] || {}
  const warehouse = warehouseMap.value[doc.warehouseId] || {}
  // 头表不存不含税合计/税额（仅行级 amountExclTax/taxAmount），按明细汇总
  const linesArr: any[] = doc.lines || []
  const sumExcl = linesArr.reduce((s, l) => s + Number(l.amountExclTax || 0), 0)
  const sumTax = linesArr.reduce((s, l) => s + Number(l.taxAmount || 0), 0)
  const options = {
    orgId: doc?.orgId,
    title: '采购订单',
    subtitle: `${paper} 打印版`,
    fileName: `${doc.docNo || '采购订单'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('PO', doc.id, doc.docNo),
    fields: [
      { label: '订单号', value: doc.docNo },
      { label: '状态', value: docStatusLabel(doc.status) },
      { label: '供应商', value: supplier.name || doc.supplierId || '' },
      { label: '仓库', value: warehouse.name || doc.warehouseId || '' },
      { label: '下单日期', value: doc.orderDate || '' },
      { label: '交货日期', value: doc.deliveryDate || '' },
      { label: '数量', value: doc.totalQty || 0 },
      ...(canSeeCost.value ? [
        { label: '不含税合计', value: fmtMoney(sumExcl) },
        { label: '税额', value: fmtMoney(sumTax) },
        { label: '含税合计', value: fmtMoney(doc.totalAmt) }
      ] : []),
      { label: '备注', value: doc.remark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '采购经办', lines: ['签字：', '日期：'] },
      { label: '审批确认', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '本单据为采购订货依据，双方签字盖章后作为供货约定。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}

// 下推入库：跳到采购入库单页，自动打开「下推生成入库单」弹窗并预选本订单
// （与入库单页的下推同一流程，可勾选明细行、可改本次入库数量——避免两处逻辑不一致）
function openPushRecv(row: any) {
  router.push({ path: '/inbound/recv', query: { pushPo: row.id } })
}

// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'receivedQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.receivedQty || 0), 0))
    if (col.property === 'totalAmt') return fmtMoney(rows.value.reduce((a: number, r: any) => a + Number(r.totalAmt || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detailData.value
  if (!d) return ''
  return [supplierMap.value[d.supplierId]?.name, warehouseMap.value[d.warehouseId]?.name, d.orderDate && `下单 ${d.orderDate}`, d.deliveryDate && `交货 ${d.deliveryDate}`].filter(Boolean).join(' · ')
})
const detailStep = computed(() => ({"DRAFT": 0, "PENDING": 1, "REJECTED": 1, "APPROVED": 2, "PARTIAL_RECEIVED": 2, "RECEIVED": 4, "CLOSED": 4} as Record<string, number>)[detailData.value?.status] ?? 0)
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '订货数量', value: fmtQty(d.totalQty) }, { label: '已入库', value: fmtQty(d.receivedQty) }, { label: '未入库', value: fmtQty(Number(d.totalQty || 0) - Number(d.receivedQty || 0)) }, canSeeCost.value && { label: '订单金额', value: fmtMoney(d.totalAmt), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 已入: { field: 'receivedQty', type: 'qty' }, 含税金额: { field: 'amount', type: 'money' }, 不含税金额: { field: 'amountExclTax', type: 'money' }, 税额: { field: 'taxAmount', type: 'money' } })

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const formLineSummary = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 含税金额: { field: 'amount', type: 'money' }, 不含税金额: { field: 'amountExclTax', type: 'money' }, 税额: { field: 'taxAmount', type: 'money' } })
</script>

<style scoped>
/* 单价录成金额防错：参考价提示与偏离高亮 */
.price-ref { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.4; margin-top: 2px; white-space: normal; }
.price-ref.bad { color: var(--el-color-danger); }
.price-bad :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; background: var(--el-color-danger-light-9); }
.price-note { font-size: 12px; color: var(--el-text-color-regular); margin-top: 2px; white-space: normal; }
</style>
