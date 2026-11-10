<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">出库申请</div>
        <div class="page-sub">申请批准后，在详情登记本次领料；剩余到期确认还要不要。</div>
      </div>
      <el-space>
        <el-button :type="freezeLeft ? 'primary' : 'default'" plain @click="freezeLeft = !freezeLeft">{{ freezeLeft ? '已冻结左列' : '冻结左列' }}</el-button>
        <el-button :disabled="!searched || !rows.length" :title="!searched ? '请先选择条件并点击查询' : (!rows.length ? '当前没有数据' : '')" @click="exportExcel">导出Excel</el-button>
        <el-button :disabled="!searched" :title="!searched ? '请先选择条件并点击查询' : ''" @click="load">刷新</el-button>
        <el-button v-if="canCreateRequest" type="primary" @click="openCreate">+ 新建申请</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <StatusChips v-model="query.status" :options="STATUS_OPTIONS" :counts="statusCounts" @change="doSearch" />
      <QueryDrawer :model="query" @search="doSearch" @reset="resetQuery" @clear="k => { if (k === 'dateRange') dateRange = null }">
        <el-form :inline="true" :model="query" size="default" style="margin-bottom:8px">
        <el-form-item data-q="status" label="申请状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="PENDING" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="部分出库" value="PARTIAL_OUT" />
            <el-option label="已出库" value="OUT" />
            <el-option label="已结束领料" value="CLOSED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="pickupState" label="领料处理"><el-select v-model="query.pickupState" clearable placeholder="全部" style="width:150px"><el-option label="还没领完" value="OPEN"/><el-option label="到期要处理" value="DUE"/><el-option label="已结束领料" value="CLOSED"/></el-select></el-form-item>
        <el-form-item data-q="dateRange" label="申请日期">
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" />
        </el-form-item>
        <el-form-item data-q="keyword" label="单据号">
          <DocNoLookup v-model="query.keyword" type="OREQ" multiple placeholder="点一下列出最近申请，也可输申请单号/修订单号" width="230px" @enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="revisionStatus" label="修订状态">
          <el-select v-model="query.revisionStatus" placeholder="全部" clearable style="width:140px">
            <el-option label="修订待审批" value="PENDING" />
            <el-option label="修订草稿" value="DRAFT" />
            <el-option label="修订已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="projectId" label="工程项目">
          <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable style="width:190px">
            <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="constructionUnit" label="单位">
          <el-input v-model="query.constructionUnit" placeholder="施工单位" clearable style="width:180px" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="warehouseId" label="仓库">
          <el-select v-model="query.warehouseId" placeholder="全部仓库" clearable filterable style="width:170px">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </el-form-item>
        <el-form-item data-q="purpose" label="用途">
          <el-input v-model="query.purpose" placeholder="用途关键词" clearable style="width:150px" @keyup.enter="doSearch" />
        </el-form-item>
        <el-form-item data-q="applicantName" label="申请人">
          <el-input v-model="query.applicantName" placeholder="姓名/账号" clearable style="width:150px" @keyup.enter="doSearch" />
        </el-form-item>
      </el-form>
      </QueryDrawer>

      <el-table export-select max-height="calc(100vh - 280px)" class="readable-table" border :fit="false" :data="rows" show-summary :summary-method="pageSummary" v-loading="loading" :empty-text="searched ? '暂无数据' : '请选择条件后点击查询'" @sort-change="onSortChange">
        <el-table-column :fixed="freezeLeft ? 'left' : undefined" prop="docNo"     label="申请单号" width="210" sortable="custom">
          <template #default="{ row }"><button type="button" class="doc-link" aria-label="查看单据" :title="`查看单据 ${row.docNo}`" @click="openDetail(row.id)">{{ row.docNo }}</button></template>
        </el-table-column>
        <el-table-column prop="applyDate" label="日期"     width="110" sortable="custom" />
        <el-table-column prop="projectName" label="工程项目" min-width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="constructionUnit" label="单位" min-width="150" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="businessCategory" label="业务分类" min-width="150" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="purpose"   label="用途" min-width="180" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="materialApplicant" label="材料申请人" min-width="150" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="totalQty"  label="数量" width="100" align="right" :formatter="$qtyFmt" sortable="custom" />
        <el-table-column prop="grossOutQty" label="累计出库" width="100" align="right" :formatter="$qtyFmt" />
        <el-table-column prop="returnQty" label="已退料" width="90" align="right" :formatter="$qtyFmt" />
        <el-table-column prop="transferredQty" label="已转交" width="100" :formatter="$qtyFmt" />
        <el-table-column prop="closedQty" label="不再领" width="100" :formatter="$qtyFmt" />
        <el-table-column prop="pickupDueDate" label="余量确认期限" width="125" />
        <el-table-column prop="status" sortable="custom" label="状态" width="110">
          <template #default="{ row }"><span v-if="row.fulfillmentMode === 'TRANSFER'">{{ row.status==='CLOSED' ? '已结束领料' : Number(row.transferredQty)>0 ? '领了一部分' : '还没领' }}</span><StatusTag v-else :status="row.status" /></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip sortable="custom" />
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="primary" @click="printOutReq(row, 'print', 'A4')">打印</el-button>
            <el-button v-if="['DRAFT','REJECTED'].includes(row.status) && user.ownsDoc(row)" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="['DRAFT','REJECTED'].includes(row.status) && canCreateRequest" link type="primary" @click="submit(row)">提交</el-button>
            <el-button v-if="canWithdraw(row)" link type="warning" @click="withdraw(row)">取消提交</el-button>
            <el-button v-if="canApprovePending(row)" link type="success" @click="approveOrReject(row, true)">审批</el-button>
            <el-button v-if="canApprovePending(row)" link type="danger"  @click="approveOrReject(row, false)">驳回</el-button>
            <el-button v-if="row.fulfillmentMode !== 'TRANSFER' && ['APPROVED','PARTIAL_OUT','OUT'].includes(row.status) && (user.hasRole('purchaser') || user.hasRole('admin'))" link type="primary" @click="openRevision(row)">修订</el-button>
            <el-button v-if="row.status === 'APPROVED' && row.fulfillmentMode !== 'TRANSFER'" link type="warning" @click="requestCancelApproval('OREQ', row, load)">取消审批</el-button>
            <el-button v-if="user.canDeleteDoc(row)" link type="danger" @click="confirmDeleteDoc(row, outApi.oreqDelete, load, '出库申请')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="searched" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
                     :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper"
                     @size-change="load" @current-change="load"
                     style="margin-top:12px;justify-content:flex-end" />
    </el-card>

    <!-- 出库申请详情 -->
    <DocDrawer v-model="detailVisible" kind="出库申请单" :doc-no="detailData?.docNo" :status="detailData?.status" :sub="detailSub" :nums="detailNums">

      <template v-if="detailData">
        <DocStepGuide :guide="outReqGuide(detailData)" />
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="申请单号">{{ detailData.docNo }}</el-descriptions-item>
          <el-descriptions-item label="申请日期">{{ detailData.applyDate }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :status="detailData.status" /></el-descriptions-item>
          <el-descriptions-item label="仓库">{{ whMap[detailData.warehouseId]?.name || detailData.warehouseId }}</el-descriptions-item>
          <el-descriptions-item label="工程项目">{{ detailData.projectName || projectMap[detailData.projectId]?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detailData.constructionUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务分类">{{ detailData.businessCategory || '-' }}</el-descriptions-item>
          <el-descriptions-item label="材料申请人">{{ detailData.materialApplicant || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用途">{{ detailData.purpose }}</el-descriptions-item>
          <el-descriptions-item label="特殊事项审批单据号">{{ detailData.approvalDocNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailData.remark }}</el-descriptions-item>
          <el-descriptions-item label="申请总数量">{{ $fmtQty(detailData.totalQty) }}</el-descriptions-item>
          <el-descriptions-item label="累计出库数量">{{ $fmtQty(grossOutQty(detailData)) }}</el-descriptions-item>
          <el-descriptions-item label="已退料数量">{{ $fmtQty(detailData.returnQty) }}</el-descriptions-item>
          <el-descriptions-item label="净出库数量（出库−退料）">{{ $fmtQty(netOutQty(detailData)) }}</el-descriptions-item>
        </el-descriptions>
        <el-table max-height="min(56vh, 560px)" :fit="false" :data="detailData.lines" border size="small" empty-text="无明细" class="readable-table doc-detail-table" show-summary :summary-method="detailLineSummary">
          <el-table-column fixed="left" label="行" prop="lineNo" width="50" align="center" />
          <el-table-column fixed="left" label="物料编码" width="120">
            <template #default="{ row }">{{ matMap[row.materialId]?.code || '' }}</template>
          </el-table-column>
          <el-table-column fixed="left" label="物料名称" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ matMap[row.materialId]?.name || row.materialId }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="150">
            <template #default="{ row }">{{ matMap[row.materialId]?.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ matMap[row.materialId]?.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="申请数量" prop="qty" width="100" align="right" :formatter="$qtyFmt" />
          <el-table-column label="累计出库数量" width="116" align="right"><template #default="{ row }">{{ $fmtQty(grossOutQty(row)) }}</template></el-table-column>
          <el-table-column label="已退料数量" prop="returnQty" width="104" align="right" :formatter="$qtyFmt" />
          <el-table-column label="净出库数量" width="104" align="right"><template #default="{ row }">{{ $fmtQty(netOutQty(row)) }}</template></el-table-column>
          <el-table-column label="备注" prop="remark" min-width="90" show-overflow-tooltip />
        </el-table>
        <DocAttachments biz-type="OREQ" :biz-id="detailData.id" />
        <PickupPanel v-if="['APPROVED','PARTIAL_OUT','CLOSED'].includes(detailData.status)" :request-id="detailData.id" :source-warehouse="detailData.warehouseId" @changed="refreshPickupDetail" />
        <div class="dd-sec">修订记录<small v-if="!revisionRows.length">暂无修订</small></div>
        <el-alert v-if="route.query.revision === '1' || /-SAX-/i.test(String(query.keyword))" :title="`已按修订单号定位原申请单；请核对下方修订记录：${query.keyword}`" type="info" :closable="false" style="margin-bottom:12px" />
        <el-table v-if="revisionRows.length" max-height="min(56vh, 560px)" :fit="false" :data="revisionRows" border size="small" empty-text="暂无修订记录" class="readable-table doc-detail-table">
          <el-table-column prop="docNo" label="修订单号" width="210" sortable="custom" />
          <el-table-column prop="revisionDate" label="修订日期" width="110" />
          <el-table-column prop="reason" label="修订原因" min-width="220" show-overflow-tooltip />
          <el-table-column prop="totalDeltaQty" label="变更数量" width="110" align="right" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING' && row.canApprove === true" link type="success" @click="approveRevision(row, true)">通过</el-button>
              <el-button v-if="row.status === 'PENDING' && row.canApprove === true" link type="danger" @click="approveRevision(row, false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template v-if="detailData">
        <div class="dd-sec">审批记录</div>
        <ApprovalTimeline :doc-no="detailData.docNo" />
      </template>
      <template #footer-left>
        <ApprovalFlowDrawer :doc-no="detailData && detailData.docNo" :float-left="false" />
        <el-button v-if="detailData && user.canDeleteDoc(detailData)" type="danger" plain @click="deleteFromDetail">删除</el-button>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <PrintExportDropdown default-orientation="landscape"
          v-if="detailData"
          label="打印/导出"
          type="primary"
          @print="(paper, orientation) => printOutReq(detailData, 'print', paper, orientation)"
          @export="(paper, orientation) => printOutReq(detailData, 'export', paper, orientation)"
        />
      </template>

    </DocDrawer>

    <!-- 新建 / 编辑申请（草稿或已驳回可编辑，复用同一弹框） -->
    <el-dialog draggable v-model="createVisible" :before-close="createGuard.beforeClose" :title="form.id ? '编辑出库申请' : '新建出库申请'" width="min(1180px, calc(100vw - 48px))" class="doc-entry-dialog">
      <DocGuardHint :active="createVisible" :date="form.applyDate" :warehouse-ids="[form.warehouseId]" v-model:blocked="guardBlocked" />
      <el-form label-position="top" :model="form" label-width="100px">
        <el-row :gutter="20" class="doc-key-fields">
          <el-col :xs="24" :sm="8"><el-form-item label="仓库" required><el-select v-model="form.warehouseId" style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="工程项目" required><el-select v-model="form.projectId" filterable style="width:100%">
            <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.code} ${p.name}`" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="单位"><el-input v-model="form.constructionUnit" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="业务分类" required><el-select v-model="form.businessCategory" style="width:100%" @change="onBusinessCategoryChange">
            <el-option v-for="c in allowedBusinessCategories" :key="c" :value="c" :label="c" /></el-select></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="材料申请人" required><el-input v-model="form.materialApplicant" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="日期" required><el-date-picker v-model="form.applyDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="用途"><el-input v-model="form.purpose" placeholder="可直接填写用途" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="特殊事项审批单据号" label-width="150px"><el-input v-model="form.approvalDocNo" placeholder="如 TSSX-202609280025" title="友空间表单编号，如 TSSX-202609280025" /></el-form-item></el-col>
        </el-row>
        <el-divider>领用明细</el-divider>
        <p class="stk-muted">申请仅为用料计划，零库存或库存不足也可填报；实际发料时按实时库存校验。</p>
        <div class="line-toolbar">
          <el-button size="small" type="primary" plain @click="addLine">增行</el-button>
          <el-button size="small" @click="addLines(5)">批量增 5 行</el-button>
          <el-button size="small" type="success" plain @click="batchPasteRef?.open()">从Excel批量导入</el-button>
          <el-button size="small" type="danger" plain :disabled="!form.lines.length" @click="form.lines = []">清空明细</el-button>
        </div>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :data="form.lines" border>
          <el-table-column type="index" label="序号" width="64" />
          <el-table-column label="物料编码" min-width="180" resizable>
            <template #default="{ row }">
              <MaterialLookup v-model="row.materialId" placeholder="选择物料" title="选择物料编码" @change="onMaterialChange(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column label="物料名称" min-width="220" show-overflow-tooltip resizable>
            <template #default="{ row }">{{ row.materialName || '-' }}</template>
          </el-table-column>
          <el-table-column label="规格型号" min-width="140" show-overflow-tooltip resizable>
            <template #default="{ row }">{{ row.spec || '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" resizable>
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <!-- 当前库存:选完物料就地显示,不用跳去库存页查(2026-08-04) -->
          <el-table-column label="当前库存" width="120" align="right" resizable>
            <template #default="{ row }">
              <span v-if="row.materialId == null" class="stk-muted">—</span>
              <span v-else-if="stockOf(row.materialId) === null" class="stk-muted">{{ stockLoading ? '查询中…' : '暂不可用' }}</span>
              <span v-else-if="stockOf(row.materialId) <= 0" class="stk-zero">无库存</span>
              <span v-else class="stk-ok">{{ $qtyFmt(null, null, stockOf(row.materialId)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="150" resizable>
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty" :min="0.0001" style="width:100%" controls-position="right"
                placeholder="必填"
              />
              <!-- 零库存和库存不足统一作为计划提醒，不限制申请数量。 -->
              <div v-if="overStock(row)" class="stk-warn">库存不足，可先申请</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }"><el-button link type="danger" @click="form.lines.splice($index,1)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <el-form-item label="备注" style="margin-top:14px"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <DocAttachments ref="oreqAttRef" biz-type="OREQ" :biz-id="null" />
      <template #footer>
        <el-button @click="createGuard.cancel">取消</el-button>
        <el-button :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(false)">保存草稿</el-button>
        <el-button type="primary" :disabled="guardBlocked" :title="guardBlocked ? GUARD_BLOCK_TIP : ''" @click="save(true)">保存并提交</el-button>
      </template>
    </el-dialog>

    <!-- 出库申请修订 -->
    <el-dialog draggable v-model="revisionVisible" :before-close="revisionGuard.beforeClose" title="出库申请修订" width="min(980px, calc(100vw - 48px))" class="doc-entry-dialog">
      <el-alert type="info" show-icon :closable="false" class="mb-3" title="已审批的历史申请不能直接改；请通过修订单调整计划数量，审批后自动影响工程项目材料计划执行表。" />
      <el-form label-position="top" :model="revisionForm" label-width="96px">
        <el-row :gutter="16" class="doc-key-fields">
          <el-col :span="8"><el-form-item label="申请单号"><el-input :model-value="revisionSource?.docNo || ''" disabled /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="修订日期"><el-date-picker v-model="revisionForm.revisionDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="工程项目"><el-input :model-value="revisionSource?.projectName || ''" disabled /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="修订原因" required><el-input v-model="revisionForm.reason" placeholder="请填写材料计划变更原因" /></el-form-item></el-col>
        </el-row>
        <div class="line-toolbar">
          <el-button size="small" type="primary" plain @click="addRevisionNewLine">新增物料行</el-button>
        </div>
        <el-table max-height="min(56vh, 560px)" class="readable-table" :fit="false" :data="revisionForm.lines" border>
          <el-table-column type="index" label="序号" width="64" />
          <el-table-column label="物料编码" min-width="210">
            <template #default="{ row }">
              <span v-if="row.reqLineId">{{ materialText(row.materialId) }}</span>
              <MaterialLookup v-else v-model="row.materialId" placeholder="选择物料" title="新增物料行：选择物料" @change="onRevisionMaterialChange(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column label="原计划数量" width="120" align="right">
            <template #default="{ row }">{{ $fmtQty(row.oldQty) }}</template>
          </el-table-column>
          <el-table-column label="已占用数量" width="120" align="right">
            <template #default="{ row }">{{ $fmtQty(row.lockedQty) }}</template>
          </el-table-column>
          <el-table-column label="修订后数量" width="170">
            <template #default="{ row }">
              <el-input-number v-model="row.newQty" :min="row.lockedQty || 0.0001" :precision="4" :step="1" controls-position="right" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="变更量" width="110" align="right">
            <template #default="{ row }">{{ $fmtQty(Number(row.newQty || 0) - Number(row.oldQty || 0)) }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="180">
            <template #default="{ row }"><el-input v-model="row.remark" /></template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row, $index }">
              <el-button v-if="!row.reqLineId" link type="danger" @click="revisionForm.lines.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="revisionGuard.cancel">取消</el-button>
        <el-button type="primary" @click="submitRevision">提交修订</el-button>
      </template>
    </el-dialog>
  </div>
  <BatchLinePaste ref="batchPasteRef" @apply="applyBatchPaste" />
</template>

<script setup lang="ts">
import DocNoLookup from '@/components/DocNoLookup.vue'
import { makeLineSummary } from '@/utils/tableSummary'
import DocDrawer from '@/components/DocDrawer.vue'
import ApprovalTimeline from '@/components/ApprovalTimeline.vue'
import StatusChips from '@/components/StatusChips.vue'
import { useStatusCounts } from '@/composables/useStatusCounts'
async function refreshPickupDetail(){ if(detailData.value?.id) detailData.value=await outApi.oreqDetail(detailData.value.id); if(searched.value) await load() }

import DocGuardHint from '@/components/DocGuardHint.vue'
import { GUARD_BLOCK_TIP } from '@/utils/docGuard'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'
import DocStepGuide from '@/components/DocStepGuide.vue'
import { outReqGuide } from '@/utils/docSteps'
import { computed, onMounted, reactive, ref, watch} from 'vue'
import { useBizOptions } from '@/composables/useBizOptions'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { outApi, masterApi, sysApi, inventoryApi } from '@/api'
import { checkDuplicateOreq } from '@/utils/oreqDuplicate'
import { useUserStore } from '@/stores/user'
import StatusTag from '@/components/StatusTag.vue'
import PickupPanel from '@/components/PickupPanel.vue'
import { fmtDate, today, fmtQty } from '@/utils/format'
import { makeSortHandler } from '@/utils/tableSort'
import { applyRouteQuery } from '@/utils/routeQuery'
import { confirmDeleteDoc, requestCancelApproval } from '@/utils/docActions'
import { exportRowsToExcel, type ExportColumn } from '@/utils/tableExport'
import { grossOutQty, netOutQty } from '@/utils/outboundProgress'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn, type PrintOrientation } from '@/utils/printExport'
import { docStatusLabel } from '@/utils/statusLabel'
import { approvalRecordNote } from '@/utils/approvalPrint'
import { buildPrintQr } from '@/utils/docQr'
import MaterialLookup from '@/components/MaterialLookup.vue'
import PrintExportDropdown from '@/components/PrintExportDropdown.vue'
import DocAttachments from '@/components/DocAttachments.vue'
import BatchLinePaste from '@/components/BatchLinePaste.vue'

const route = useRoute()
const user = useUserStore()
const canCreateRequest = computed(() => user.hasRole('field_manager') || user.hasRole('service_staff') || user.hasRole('purchaser'))
const loading = ref(false)
const freezeLeft = ref(true)   // 冻结左列
const rows = ref<any[]>([])
const total = ref(0)
const searched = ref(false)
const query = reactive<any>({ sortField: '', sortOrder: '',
  current: 1,
  size: 20,
  status: '',
  pickupState: '', revisionStatus: '',
  keyword: '',
  warehouseId: null,
  projectId: null,
  constructionUnit: '',
  businessCategory: '',
  purpose: '',
  applicantName: '',
  startDate: null,
  endDate: null
})

const warehouses = ref<any[]>([])
const materials = ref<any[]>([])
const projects = ref<any[]>([])
const dateRange = ref<any>(null)
const { businessCategories, loadBizOptions } = useBizOptions()

const allowedBusinessCategories = computed(() => {
  const categories = businessCategories.value.length ? businessCategories.value : ['工程类', '运营类', '增值销售类', '其他']
  const allowed = categories.filter(category => {
    if (isProjectCategory(category)) return user.hasRole('field_manager')
    return user.hasRole('service_staff') || user.hasRole('purchaser')
  })
  return allowed.length ? allowed : categories.filter(category => !isProjectCategory(category))
})
const exportColumns: ExportColumn[] = [
  { label: '申请单号', key: 'docNo' },
  { label: '日期', key: 'applyDate' },
  { label: '工程项目', key: 'projectName' },
  { label: '单位', key: 'constructionUnit' },
  { label: '业务分类', key: 'businessCategory' },
  { label: '用途', key: 'purpose' },
  { label: '特殊事项审批单据号', key: 'approvalDocNo' },
  { label: '材料申请人', key: 'materialApplicant' },
  { label: '数量', key: 'totalQty', qty: true },
  { label: '累计出库', value: (row: any) => grossOutQty(row), qty: true },
  { label: '已退料', key: 'returnQty', qty: true },
  { label: '净出库', value: (row: any) => netOutQty(row), qty: true },
  { label: '状态', value: (row: any) => docStatusLabel(row.status) },
  { label: '备注', key: 'remark' }
]

// 状态页签：数字 = 按当前其它筛选条件、只换状态查出来的总条数（与点开后「共 N 条」一致）
const STATUS_OPTIONS = [{ label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' }, { label: '已审批', value: 'APPROVED' }, { label: '部分出库', value: 'PARTIAL_OUT' }, { label: '已出库', value: 'OUT' }, { label: '已结束领料', value: 'CLOSED' }, { label: '已驳回', value: 'REJECTED' }, { label: '已作废', value: 'CANCELLED' }]
const { counts: statusCounts, refresh: refreshStatusCounts } = useStatusCounts(q => outApi.oreqPage(q), ['DRAFT', 'PENDING', 'APPROVED', 'PARTIAL_OUT', 'OUT', 'CLOSED', 'REJECTED', 'CANCELLED'])

async function load() {
  applyDateRange()
  searched.value = true
  loading.value = true
  refreshStatusCounts(query)
  try {
    const r: any = await outApi.oreqPage(query)
    rows.value = r.records
    total.value = r.total
    if (r.total === 1 && (route.query.revision === '1' || /-SAX-/i.test(String(query.keyword))) && !String(query.keyword).includes(',')) {
      await openDetail(r.records[0].id)
    }
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
  Object.assign(query, { current: 1, size: 20, status: '', pickupState: '', revisionStatus: '', keyword: '', warehouseId: null, projectId: null, constructionUnit: '', businessCategory: '', purpose: '', applicantName: '', startDate: null, endDate: null })
  dateRange.value = null
  load()
}

function applyDateRange() {
  query.startDate = dateRange.value?.[0] ? fmtDate(dateRange.value[0]) : null
  query.endDate = dateRange.value?.[1] ? fmtDate(dateRange.value[1]) : null
}

function canApprovePending(row: any) {
  // 仅当后端判定当前用户是本节点审批人时才显示审批/驳回（申请人等非审批人不显示）
  return row.status === 'PENDING' && row.canApprove === true
}

onMounted(() => {
  loadOptions()
  loadBizOptions()
  masterApi.projects(true).then((list: any) => { projects.value = list }).catch(() => {})
  applyRouteQuery(query, route, ['status', 'revisionStatus', 'keyword', 'warehouseId', 'projectId', 'constructionUnit', 'businessCategory', 'purpose', 'applicantName'])
  load()
})

async function loadOptions() {
  const cats = await sysApi.optionList('business.category.options').catch(() => []) as any[]
  if (cats?.length) businessCategories.value = cats
}

const createVisible = ref(false)
// 所在月份已结账时为 true，保存类按钮置灰（提示见弹窗顶部 DocGuardHint）
const guardBlocked = ref(false)
const createGuard = useUnsavedGuard(createVisible)
const oreqAttRef = ref()
/** 编辑时原来的工程:没换工程就不再弹重复提醒(新建时为 null) */
const editProjectId = ref<number | null>(null)
const form = reactive<any>({
  id: null,
  docNo: '',
  warehouseId: null,
  projectId: null,
  constructionUnit: '',
  businessCategory: '运营类',
  materialApplicant: '',
  purpose: '工程领料',
  approvalDocNo: '',
  applyDate: today(),
  remark: '',
  lines: []
})

async function openCreate() {
  editProjectId.value = null
  oreqAttRef.value?.reset()  // 清掉上一单残留的暂存附件,防串单
  await loadOptions()
  if (warehouses.value.length === 0) warehouses.value = await masterApi.warehouses() as any
  if (projects.value.length === 0) projects.value = await masterApi.projects() as any
  const category = defaultBusinessCategory()
  Object.assign(form, {
    id: null,
    docNo: '',
    warehouseId: warehouses.value[0]?.id,
    projectId: null,
    constructionUnit: '',
    businessCategory: category,
    materialApplicant: user.userInfo?.name || '',
    purpose: isProjectCategory(category) ? '工程领料' : '',
    approvalDocNo: '',
    applyDate: today(),
    remark: '',
    lines: []
  })
  addLine()
  createVisible.value = true
}

/** 编辑草稿/已驳回的出库申请：载入已有单据到同一弹框（后端 update 仅放行 DRAFT/REJECTED，申请人或管理员） */
async function openEdit(row: any) {
  oreqAttRef.value?.reset()  // 编辑前也清暂存附件,防串单
  await loadOptions()
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  const detail: any = await outApi.oreqDetail(row.id)
  editProjectId.value = detail.projectId
  Object.assign(form, {
    id: detail.id,
    docNo: detail.docNo,
    warehouseId: detail.warehouseId,
    projectId: detail.projectId,
    constructionUnit: detail.constructionUnit || '',
    businessCategory: detail.businessCategory || '运营类',
    materialApplicant: detail.materialApplicant || '',
    purpose: detail.purpose || '',
    approvalDocNo: detail.approvalDocNo || '',
    applyDate: detail.applyDate || today(),
    remark: detail.remark || '',
    lines: (detail.lines || []).map((line: any) => {
      const m = matMap.value[line.materialId] || {}
      return { materialId: line.materialId, materialName: m.name || '', spec: m.spec || '', unit: m.unit || '', qty: Number(line.qty || 0), remark: line.remark || '' }
    })
  })
  if (!form.lines.length) addLine()
  createVisible.value = true
}
function addLine() { form.lines.push({ materialId: null, materialName: '', unit: '', qty: null }) }
const batchPasteRef = ref()
function applyBatchPaste(rows: { material: any; qty: number }[]) {
  // 去掉空白占位行后追加
  form.lines = form.lines.filter((l: any) => l.materialId)
  for (const r of rows) {
    form.lines.push({ materialId: r.material.id, materialName: r.material.name, spec: r.material.spec || '', unit: r.material.unit || '', qty: r.qty })
  }
}
function addLines(count = 5) { for (let i = 0; i < count; i++) addLine() }

function onBusinessCategoryChange(category: string) {
  if (isProjectCategory(category) && !String(form.purpose || '').trim()) {
    form.purpose = '工程领料'
  }
}

function onMaterialChange(row: any, material: any | null) {
  row.materialName = material?.name || ''
  row.spec = material?.spec || ''
  row.unit = material?.unit || ''
  // 换了物料要重新看库存;若该仓库的库存表还没拉过,这里会触发加载
  if (row.materialId != null && stockMap.value === null && !stockLoading.value) void loadStock()
}

/* 库存仅供计划参考。按仓库批量查询，不逐行请求；实际发料时再校验实时库存。 */
const stockMap = ref<Record<number, number> | null>(null)
const stockLoading = ref(false)
let stockRequestId = 0

async function loadStock() {
  const requestId = ++stockRequestId
  const wh = form.warehouseId
  stockMap.value = null
  if (!wh) { stockLoading.value = false; return }
  stockLoading.value = true
  try {
    // ⚠️ 参数名是 whId(不是 warehouseId)—— 传错会退化成"全部仓库合计",库存数就错了。
    //    includeZero 保持默认 0:零库存行本来就不返回,查不到即视为 0(下面 stockOf 的 ?? 0)
    const res: any = await inventoryApi.balance({ whId: wh })
    const rows: any[] = Array.isArray(res) ? res : (res?.records || res?.list || [])
    const m: Record<number, number> = {}
    for (const r of rows) {
      const mid = Number(r.materialId ?? r.material_id)
      if (!Number.isNaN(mid)) m[mid] = Number(r.qty ?? 0)
    }
    // 换仓库时可能有并发请求回来晚了,丢弃过期结果
    if (requestId === stockRequestId) stockMap.value = m
  } catch {
    // 查询失败表示未知，不能把读取失败显示成零库存，更不能影响计划填报。
    if (requestId === stockRequestId) {
      stockMap.value = null
      ElMessage.warning('库存读取失败，仍可填写申请；实际发料时会校验实时库存')
    }
  } finally {
    if (requestId === stockRequestId) stockLoading.value = false
  }
}

/** 返回该物料在当前仓库的库存;null = 查询中或查询失败 */
function stockOf(materialId: number | null): number | null {
  if (materialId == null) return null
  if (stockMap.value === null) return null
  return stockMap.value[materialId] ?? 0
}

/** 填的数量超过库存(只用于标红提醒,不拦提交) */
function overStock(row: any): boolean {
  const s = stockOf(row?.materialId)
  return s !== null && Number(row?.qty || 0) > s
}

// 换仓库 → 库存全变,重新拉
watch(() => form.warehouseId, () => { stockMap.value = null; void loadStock() })

masterApi.warehouses(true).then((list: any) => { warehouses.value = list }).catch(() => {})

async function save(submit: boolean) {
  const invalid = validateForm()
  if (invalid) { await cannotSave(invalid); return }
  // 2026-10-16:工程类申请,同一工程已有未完成的申请 → 保存前提醒一次(只提醒,可"仍新建")
  if (!form.id || form.projectId !== editProjectId.value) {
    const choice = await checkDuplicateOreq({
      category: form.businessCategory, projectId: form.projectId, excludeId: form.id,
      projectName: projects.value.find((p: any) => p.id === form.projectId)?.name, canRevise: true
    })
    if (choice === 'abort') return
    if (typeof choice === 'object') {
      createVisible.value = false
      if ('revise' in choice) await openRevision(choice.revise)
      else await openDetail(choice.view.id)
      return
    }
  }
  const payload = {
    warehouseId: form.warehouseId,
    projectId: form.projectId,
    constructionUnit: form.constructionUnit,
    businessCategory: form.businessCategory,
    materialApplicant: form.materialApplicant,
    purpose: form.purpose,
    approvalDocNo: form.approvalDocNo,
    applyDate: form.applyDate,
    remark: form.remark,
    lines: form.lines.map((line: any) => ({
      materialId: line.materialId,
      qty: line.qty,
      remark: line.remark
    }))
  }
  let docId: number
  let docNo: string
  if (form.id) {
    await outApi.oreqUpdate(form.id, payload)
    docId = form.id
    docNo = form.docNo
  } else {
    const r: any = await outApi.oreqCreate(payload)
    docId = r.id
    docNo = r.docNo
  }
  if (oreqAttRef.value) await oreqAttRef.value.uploadPending(docId)
  if (submit) await outApi.oreqSubmit(docId)
  ElMessage.success(submit ? `已提交 ${docNo}` : `已保存 ${docNo}`)
  createVisible.value = false
  await load()
}

function validateForm() {
  if (!form.warehouseId) return '请选择仓库'
  if (!form.projectId) return '请选择工程项目'
  if (!form.businessCategory) return '请选择业务分类'
  const roleError = applicantRoleError(form.businessCategory)
  if (roleError) return roleError
  if (!String(form.materialApplicant || '').trim()) return '请填写材料申请人'
  if (!form.applyDate) return '请选择申请日期'
  if (!form.lines.length) return '请至少添加一行领用明细'
  for (let i = 0; i < form.lines.length; i++) {
    const line = form.lines[i]
    if (!line.materialId) return `第 ${i + 1} 行请选择物料`
    if (line.qty === null || line.qty === undefined || line.qty === '' || Number(line.qty) <= 0) return `第 ${i + 1} 行请填写大于 0 的申请数量`
  }
  return ''
}

function isProjectCategory(category: string) {
  return ['工程业务', '工程类', 'PROJECT'].includes(String(category || '').trim())
}

function defaultBusinessCategory() {
  const categories = allowedBusinessCategories.value
  if (user.hasRole('field_manager')) {
    return categories.find(isProjectCategory) || categories[0] || '工程类'
  }
  return categories.find(category => category === '运营类') || categories[0] || '运营类'
}

function applicantRoleError(category: string) {
  // 采购岗可发起一般领料申请（直达财务审核）
  if (user.hasRole('purchaser')) return ''
  if (isProjectCategory(category)) {
    return user.hasRole('field_manager') ? '' : '工程类出库申请仅现场管理员可发起'
  }
  return user.hasRole('service_staff') ? '' : '运营类和增值销售类出库申请仅网点客服可发起'
}

async function cannotSave(message: string) {
  await ElMessageBox.alert(message, '无法保存', {
    confirmButtonText: '确定',
    type: 'warning'
  })
}

async function submit(row: any) { await outApi.oreqSubmit(row.id); ElMessage.success('已提交'); await load() }

function canWithdraw(row: any) {
  // 取消提交：制单人/申请人/管理员（与删除同口径，方便业务人员撤回修改；后端按角色再校验）
  return row.status === 'PENDING' && user.ownsDoc(row)
}
async function withdraw(row: any) {
  try {
    await ElMessageBox.confirm(`确定取消提交出库申请 ${row.docNo}？将退回草稿，可重新编辑后再提交。`, '取消提交', {
      type: 'warning', confirmButtonText: '取消提交', cancelButtonText: '关闭'
    })
    await outApi.oreqWithdraw(row.id)
    ElMessage.success('已取消提交，单据已退回草稿')
    await load()
  } catch {}
}
async function approveOrReject(row: any, approve: boolean) {
  try {
    if (approve) {
      await outApi.oreqApprove(row.id, '同意')  // 一键通过，不弹原因框
    } else {
      const { value } = await ElMessageBox.prompt('请输入驳回原因', `驳回 ${row.docNo}`, { inputType: 'textarea' })
      await outApi.oreqReject(row.id, value)
    }
    ElMessage.success(approve ? '已通过' : '已驳回'); await load()
  } catch {}
}

const revisionVisible = ref(false)
const revisionGuard = useUnsavedGuard(revisionVisible)
const revisionSource = ref<any>(null)
const revisionRows = ref<any[]>([])
const revisionForm = reactive<any>({
  revisionDate: today(),
  reason: '',
  lines: []
})

async function openRevision(row: any) {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  const detail: any = await outApi.oreqDetail(row.id)
  revisionSource.value = detail
  Object.assign(revisionForm, {
    revisionDate: today(),
    reason: '',
    lines: (detail.lines || []).map((line: any) => ({
      reqLineId: line.id,
      materialId: line.materialId,
      oldQty: Number(line.qty || 0),
      newQty: Number(line.qty || 0),
      lockedQty: Number(line.lockedOutQty ?? line.outQty ?? 0),
      remark: line.remark || ''
    }))
  })
  revisionVisible.value = true
}

function addRevisionNewLine() {
  revisionForm.lines.push({
    reqLineId: null,
    materialId: null,
    oldQty: 0,
    newQty: null,
    lockedQty: 0,
    remark: ''
  })
}

function onRevisionMaterialChange(row: any, material: any | null) {
  row.materialId = material?.id || row.materialId
}

function materialText(materialId: number) {
  const m = matMap.value[materialId]
  return m ? `${m.code} ${m.name}` : materialId || '-'
}

async function submitRevision() {
  if (!revisionSource.value) return
  if (!String(revisionForm.reason || '').trim()) {
    await cannotSave('请填写修订原因')
    return
  }
  if (!revisionForm.lines.length) {
    await cannotSave('修订明细不能为空')
    return
  }
  for (let i = 0; i < revisionForm.lines.length; i++) {
    const line = revisionForm.lines[i]
    if (!line.materialId) {
      await cannotSave(`第 ${i + 1} 行请选择物料`)
      return
    }
    if (!line.newQty || Number(line.newQty) <= 0) {
      await cannotSave(`第 ${i + 1} 行修订后数量必须大于 0`)
      return
    }
    if (Number(line.newQty) < Number(line.lockedQty || 0)) {
      await cannotSave(`第 ${i + 1} 行修订后数量不能小于已出库或已占用数量`)
      return
    }
  }
  const revision: any = await outApi.oreqRevisionCreate(revisionSource.value.id, {
    revisionDate: revisionForm.revisionDate,
    reason: revisionForm.reason,
    lines: revisionForm.lines.map((line: any) => ({
      reqLineId: line.reqLineId,
      materialId: line.materialId,
      newQty: line.newQty,
      remark: line.remark
    }))
  })
  await outApi.oreqRevisionSubmit(revision.id)
  ElMessage.success(`已提交修订单 ${revision.docNo}`)
  revisionVisible.value = false
  await load()
}

async function loadRevisions(reqId: number) {
  revisionRows.value = await outApi.oreqRevisions(reqId) as any
}

async function approveRevision(row: any, approve: boolean) {
  try {
    let value = '同意修订'
    if (!approve) {
      value = (await ElMessageBox.prompt('请输入驳回原因', `驳回修订 ${row.docNo}`, { inputType: 'textarea' })).value
    }
    await (approve ? outApi.oreqRevisionApprove(row.id, value) : outApi.oreqRevisionReject(row.id, value))
    ElMessage.success(approve ? '修订已通过' : '修订已驳回')
    if (detailData.value?.id) {
      detailData.value = await outApi.oreqDetail(detailData.value.id)
      await loadRevisions(detailData.value.id)
    }
    await load()
  } catch {}
}

const detailVisible = ref(false)
// 详情弹窗内直接删除：与列表行同口径（谁提交谁删除），删完关弹窗刷新列表
async function deleteFromDetail() {
  const row = detailData.value
  if (!row) return
  try { await confirmDeleteDoc(row, outApi.oreqDelete, () => {}, '出库申请') } catch { return }
  detailVisible.value = false
  await load()
}
const detailData = ref<any>(null)
const matMap = ref<Record<number, any>>({})
const whMap = ref<Record<number, any>>({})
const projectMap = ref<Record<number, any>>({})

async function openDetail(id: number) {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  whMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  projectMap.value = Object.fromEntries(projects.value.map((p: any) => [p.id, p]))
  detailData.value = await outApi.oreqDetail(id)
  await loadRevisions(id)
  detailVisible.value = true
}

function exportExcel() {
  exportRowsToExcel('出库申请', exportColumns, rows.value, '出库申请', { total: total.value, fetch: p => outApi.oreqPage({ ...query, ...p }) })
}

async function printOutReq(rowOrDoc: any, action: PaperAction, paper: PaperSize, orientation: PrintOrientation = 'portrait') {
  if (!materials.value.length) materials.value = await masterApi.allMaterials({ includeDisabled: true }) as any
  if (!warehouses.value.length) warehouses.value = await masterApi.warehouses(true) as any
  if (!projects.value.length) projects.value = await masterApi.projects(true) as any
  matMap.value = Object.fromEntries(materials.value.map((m: any) => [m.id, m]))
  whMap.value = Object.fromEntries(warehouses.value.map((w: any) => [w.id, w]))
  projectMap.value = Object.fromEntries(projects.value.map((p: any) => [p.id, p]))
  const doc: any = rowOrDoc?.lines ? rowOrDoc : await outApi.oreqDetail(rowOrDoc.id)
  const projectName = doc.projectName || projectMap.value[doc.projectId]?.name || ''
  const columns: PrintColumn[] = [
    { label: '行号', value: (_row, index) => index + 1, width: '5%', align: 'center' },
    { label: '物料编码', value: line => matMap.value[line.materialId]?.code || '', width: '13%' },
    { label: '物料名称', value: line => matMap.value[line.materialId]?.name || line.materialId || '', width: '16%' },
    { label: '规格型号', value: line => matMap.value[line.materialId]?.spec || '', width: '14%' },
    { label: '单位', value: line => matMap.value[line.materialId]?.unit || '', width: '6%', align: 'center' },
    { label: '申请数量', key: 'qty', width: '11%', align: 'right' },
    { label: '累计出库', value: line => grossOutQty(line), width: '9%', align: 'right' },
    { label: '已退料', value: line => line.returnQty || 0, width: '8%', align: 'right' },
    { label: '净出库', value: line => netOutQty(line), width: '9%', align: 'right' },
    { label: '备注', key: 'remark', width: '9%' }
  ]
  const options = {
    orgId: doc?.orgId,
    title: '出库申请单',
    subtitle: `${paper} 打印版`,
    fileName: `${doc.docNo || '出库申请'}-打印单-${paper}`,
    paper,
    orientation,
    qr: await buildPrintQr('OREQ', doc.id, doc.docNo),
    fields: [
      { label: '申请单号', value: doc.docNo },
      { label: '申请日期', value: doc.applyDate || '' },
      { label: '状态', value: docStatusLabel(doc.status) },
      { label: '仓库', value: whMap.value[doc.warehouseId]?.name || doc.warehouseId || '' },
      { label: '工程项目', value: projectName },
      { label: '施工单位', value: doc.constructionUnit || '' },
      { label: '业务分类', value: doc.businessCategory || '' },
      { label: '材料申请人', value: doc.materialApplicant || '' },
      { label: '用途', value: doc.purpose || '', span: 3 },
      { label: '特殊事项审批单据号', value: doc.approvalDocNo || '' },
      { label: '总数量', value: doc.totalQty || 0 },
      { label: '备注', value: doc.remark || '', span: 3 }
    ],
    columns,
    rows: doc.lines || [],
    signatures: [
      { label: '申请人确认', lines: ['签字：', '日期：'] },
      { label: '审批确认', lines: ['签字：', '日期：'] },
      { label: '仓管确认', lines: ['签字：', '日期：'] }
    ],
    note: await approvalRecordNote(doc.docNo, '本单据用于工程材料计划申请，审批后仓管可按剩余数量关联生成材料出库单。')
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}


// 合计行：只合计本页（与表格显示的行一致）
function pageSummary({ columns }: any) {
  return columns.map((col: any, i: number) => {
    if (i === 0) return '合计'
    if (col.property === 'totalQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.totalQty || 0), 0))
    if (col.property === 'grossOutQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.grossOutQty || 0), 0))
    if (col.property === 'returnQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.returnQty || 0), 0))
    if (col.property === 'transferredQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.transferredQty || 0), 0))
    if (col.property === 'closedQty') return fmtQty(rows.value.reduce((a: number, r: any) => a + Number(r.closedQty || 0), 0))
    return ''
  })
}

// 详情右侧面板：一行摘要、步骤、关键数字（2026-10-17 全站界面改版）
const detailSub = computed(() => {
  const d: any = detailData.value
  if (!d) return ''
  return [d.projectName || projectMap.value[d.projectId]?.name, d.applyDate && `申请 ${d.applyDate}`, d.materialApplicant && `申请人 ${d.materialApplicant}`].filter(Boolean).join(' · ')
})
const detailNums = computed(() => {
  const d: any = detailData.value
  if (!d) return []
  return [{ label: '申请数量', value: fmtQty(d.totalQty) }, { label: '累计出库', value: fmtQty(grossOutQty(d)) }, { label: '已退料', value: fmtQty(d.returnQty) }, { label: '净出库（出库−退料）', value: fmtQty(netOutQty(d)), hl: true }].filter(Boolean) as any[]
})

// 合计行（2026-10-17 全站补合计）：按列名对字段，只合计表格里显示的这些行
const detailLineSummary = makeLineSummary({ 申请数量: { field: 'qty', type: 'qty' }, 累计出库数量: { value: (r: any) => Number(grossOutQty(r) || 0), type: 'qty' }, 已退料数量: { field: 'returnQty', type: 'qty' }, 净出库数量: { value: (r: any) => Number(netOutQty(r) || 0), type: 'qty' } })
</script>

<style scoped>

/* 出库申请·库存联动(2026-08-04) */
.stk-muted { color: var(--text-3, #98a1b0); }
.stk-ok    { color: var(--text-1, #1a1f29); font-variant-numeric: tabular-nums; }
.stk-zero  { color: #d93b3b; font-weight: 650; }
.stk-warn  { margin-top: 3px; font-size: 11.5px; line-height: 1.3; color: #d6881f; }
</style>
