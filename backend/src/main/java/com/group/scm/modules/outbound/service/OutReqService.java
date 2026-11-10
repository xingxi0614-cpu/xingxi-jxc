package com.group.scm.modules.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.master.entity.MdProject;
import com.group.scm.modules.master.mapper.MdProjectMapper;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import com.group.scm.modules.outbound.mapper.BizOutLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutReqService {

    private final PickupService pickup;
    private final BizOutReqMapper reqMapper;
    private final BizOutReqLineMapper lineMapper;
    private final BizOutLineMapper outLineMapper;
    private final OutReqIntegrity integrity;
    private final com.group.scm.modules.outbound.mapper.BizReturnLineMapper returnLineMapper;
    private final MdProjectMapper projectMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final PeriodCloseService periodCloseService;
    private final com.group.scm.modules.notif.service.NotifService notifService;
    // 申请是用料计划，零库存或数量不足也可先保存、审批；实时库存由实际出库过账校验。

    @Transactional(rollbackFor = Exception.class)
    public BizOutReq create(BizOutReq dto) {
        dto.setFulfillmentMode("UNSET");dto.setPickupDueDate(null);dto.setPickupExtensions(0);dto.setPickupNoticeStage(0);
        dto.setDocNo(docNoService.next("OREQ"));
        dto.setApplicantId(SecurityUtil.uid());
        dto.setDeptId(SecurityUtil.orgId());
        dto.setOrgId(SecurityUtil.orgId());
        validateRequired(dto);
        validateApplicantRole(dto);
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
        orgScope.assertSameCompany(dto.getOrgId(), dto.getWarehouseId(), "出库申请");   // 计算审查 U11 同类
        applyProject(dto);
        if (dto.getApplyDate() == null) dto.setApplyDate(LocalDate.now());
        periodCloseService.assertPeriodOpen(dto.getApplyDate(), dto.getOrgId(), "出库申请", dto.getDocNo());
        dto.setStatus("DRAFT");
        dto.setApprovalDocNo(normalizeApprovalDocNo(dto.getApprovalDocNo()));

        BigDecimal totalQty = calculateLines(dto.getLines());
        dto.setTotalQty(totalQty);
        dto.setOutQty(BigDecimal.ZERO);
        reqMapper.insert(dto);
        insertLines(dto.getId(), dto.getLines());
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BizOutReq dto) {
        BizOutReq current = requireExisting(id);
        if (!"DRAFT".equals(current.getStatus()) && !"REJECTED".equals(current.getStatus())) {
            throw new BizException("只有草稿或已驳回的出库申请可以修改");
        }
        if (!current.getApplicantId().equals(SecurityUtil.uid()) && !SecurityUtil.isAdmin()) {
            throw new BizException("只有申请人或管理员可以修改该出库申请");
        }
        integrity.assertNoDownstream(id);
        periodCloseService.assertPeriodOpen(current.getApplyDate(), current.getOrgId(), "出库申请", current.getDocNo());
        validateRequired(dto);
        validateApplicantRole(dto);
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
        orgScope.assertSameCompany(current.getOrgId(), dto.getWarehouseId(), "出库申请");
        applyProject(dto);
        if (dto.getApplyDate() == null) dto.setApplyDate(current.getApplyDate());
        periodCloseService.assertPeriodOpen(dto.getApplyDate(), current.getOrgId(), "出库申请", current.getDocNo());

        BigDecimal totalQty = calculateLines(dto.getLines());
        dto.setId(id);
        dto.setDocNo(current.getDocNo());
        dto.setApplicantId(current.getApplicantId());
        dto.setDeptId(current.getDeptId());
        dto.setOrgId(current.getOrgId());
        dto.setStatus(current.getStatus());
        dto.setOutQty(current.getOutQty() == null ? BigDecimal.ZERO : current.getOutQty());
        dto.setTotalQty(totalQty);
        dto.setFulfillmentMode(current.getFulfillmentMode());dto.setPickupDueDate(current.getPickupDueDate());
        dto.setPickupExtensions(current.getPickupExtensions());dto.setPickupNoticeStage(current.getPickupNoticeStage());
        dto.setApprovalDocNo(normalizeApprovalDocNo(dto.getApprovalDocNo()));
        reqMapper.updateById(dto);

        lineMapper.delete(new QueryWrapper<BizOutReqLine>().eq("out_req_id", id));
        insertLines(id, dto.getLines());
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizOutReq req = requireExisting(id);
        if (!"DRAFT".equals(req.getStatus()) && !"REJECTED".equals(req.getStatus())) {
            throw new BizException("当前状态不允许提交：" + req.getStatus());
        }
        if (!req.getApplicantId().equals(SecurityUtil.uid())) {
            throw new BizException("只有申请人可以提交该出库申请");
        }
        validateApplicantRole(req);
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (reqMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizOutReq>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("OREQ", id, req.getDocNo(), BigDecimal.ZERO);
    }

    /** 取消提交：申请人在出库申请审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizOutReq req = requireExisting(id);
        if (!"PENDING".equals(req.getStatus())) throw new BizException("只有待审批的出库申请可以取消提交");
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());
        wfService.withdraw("OREQ", id);
        req.setStatus("DRAFT");
        reqMapper.updateById(req);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizOutReq req = requireExisting(id);
        if (!"DRAFT".equals(req.getStatus()) && !"REJECTED".equals(req.getStatus()) && !("CANCELLED".equals(req.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的出库申请可以删除");
        }
        // 删除是发起人的权利，审批人(财务)不删别人的单。领料申请生成的出库申请经办人=采购
        // (收件箱受理人)：新单 applicant 已是采购；历史单 applicant 可能是财务确认者，故对领料
        // 生成单(remark 前缀识别)兜底放开给采购角色，仍不给财务。
        boolean lrGenerated = req.getRemark() != null && req.getRemark().startsWith("由领料申请");
        boolean allowed = req.getApplicantId().equals(SecurityUtil.uid())
                || SecurityUtil.isAdmin()
                || (lrGenerated && SecurityUtil.hasRole("purchaser"));
        if (!allowed) {
            throw new BizException("只有发起人或管理员可以删除该出库申请");
        }
        integrity.assertNoDownstream(id);
        BigDecimal activeOut = req.getOutQty() == null ? BigDecimal.ZERO : req.getOutQty();
        if (activeOut.signum() > 0) throw new BizException("该出库申请已产生材料出库记录，不能删除");
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());
        lineMapper.delete(new QueryWrapper<BizOutReqLine>().eq("out_req_id", id));
        reqMapper.deleteById(id);
        notifService.closeBusinessTodos("OREQ", id);   // 删单同时关闭"已退回,请修改"等待办,避免留下点开为空的孤儿待办
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizOutReq req = requireExisting(id);
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());
        wfService.approve("OREQ", id, comment, (no, bizId) -> {
            BizOutReq next = reqMapper.selectById(bizId);
            next.setStatus("APPROVED");
            reqMapper.updateById(next);
            pickup.initialized(bizId);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizOutReq req = requireExisting(id);
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());
        wfService.reject("OREQ", id, comment, (no, bizId) -> {
            BizOutReq next = reqMapper.selectById(bizId);
            next.setStatus("REJECTED");
            reqMapper.updateById(next);
        });
    }

    public BizOutReq detail(Long id) {
        BizOutReq req = reqMapper.selectById(id);
        if (req == null) return null;
        orgScope.assertOrgVisible(req.getOrgId());
        List<BizOutReqLine> lines = lineMapper.selectList(
                new QueryWrapper<BizOutReqLine>().eq("out_req_id", id).orderByAsc("line_no"));
        for (BizOutReqLine line : lines) {
            BigDecimal lockedOutQty = outLineMapper.sumActiveOutQtyByReqLine(line.getId());
            line.setLockedOutQty(lockedOutQty == null ? BigDecimal.ZERO : lockedOutQty);
            BigDecimal returnQty = returnLineMapper.sumApprovedReturnQtyByReqLine(line.getId());
            line.setReturnQty(returnQty == null ? BigDecimal.ZERO : returnQty);
            BigDecimal gross = outLineMapper.sumApprovedOutQtyByReqLine(line.getId());
            line.setGrossOutQty(gross == null ? BigDecimal.ZERO : gross);
            line.setNetOutQty(line.getGrossOutQty().subtract(line.getReturnQty()));
        }
        req.setLines(lines);
        req.setReturnQty(lines.stream().map(BizOutReqLine::getReturnQty).reduce(BigDecimal.ZERO, BigDecimal::add));
        req.setGrossOutQty(lines.stream().map(BizOutReqLine::getGrossOutQty).reduce(BigDecimal.ZERO, BigDecimal::add));
        req.setNetOutQty(req.getGrossOutQty().subtract(req.getReturnQty()));
        pickup.fillProgress(req);
        return req;
    }

    public IPage<BizOutReq> page(long current, long size, String status, String keyword, Long warehouseId,
                                 String purpose, String applicantName, Long projectId, String constructionUnit,
                                 String outType, String businessCategory, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        return page(current, size, status, keyword, warehouseId, purpose, applicantName, projectId,
                constructionUnit, outType, businessCategory, startDate, endDate, sortField, sortOrder, null);
    }

    public IPage<BizOutReq> page(long current, long size, String status, String keyword, Long warehouseId,
                                String purpose, String applicantName, Long projectId, String constructionUnit,
                                String outType, String businessCategory, LocalDate startDate, LocalDate endDate,
                                String sortField, String sortOrder, String revisionStatus) {
        return page(current,size,status,keyword,warehouseId,purpose,applicantName,projectId,constructionUnit,outType,businessCategory,startDate,endDate,sortField,sortOrder,revisionStatus,null);
    }
    public IPage<BizOutReq> page(long current,long size,String status,String keyword,Long warehouseId,String purpose,String applicantName,Long projectId,String constructionUnit,String outType,String businessCategory,LocalDate startDate,LocalDate endDate,String sortField,String sortOrder,String revisionStatus,String pickupState) {
        QueryWrapper<BizOutReq> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if("OPEN".equals(pickupState) || "DUE".equals(pickupState)) {
            qw.in("fulfillment_mode",java.util.List.of("UNSET","TRANSFER")).in("status",java.util.List.of("APPROVED","PARTIAL_OUT"));
            if("DUE".equals(pickupState)) qw.lt("pickup_due_date",LocalDate.now());
        } else if("CLOSED".equals(pickupState)) qw.eq("fulfillment_mode","TRANSFER").eq("status","CLOSED");

        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) {
            String placeholders = java.util.stream.IntStream.range(0, _docNos.size())
                    .mapToObj(i -> "{" + i + "}").collect(java.util.stream.Collectors.joining(","));
            qw.and(w -> w.in("doc_no", _docNos).or().apply(
                    "id IN (SELECT source_req_id FROM biz_out_req_revision WHERE deleted=0 AND doc_no IN ("
                            + placeholders + "))", _docNos.toArray()));
        } else if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("doc_no", keyword.trim()).or().apply(
                    "id IN (SELECT source_req_id FROM biz_out_req_revision WHERE deleted=0 AND doc_no LIKE {0})",
                    "%" + keyword.trim() + "%"));
        }
        if (revisionStatus != null && !revisionStatus.isBlank()) {
            qw.apply("id IN (SELECT source_req_id FROM biz_out_req_revision WHERE deleted=0 AND status={0})",
                    revisionStatus);
        }
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (purpose != null && !purpose.isBlank()) qw.like("purpose", purpose.trim());
        if (projectId != null) {
            orgScope.assertProjectVisible(projectId);
            qw.eq("project_id", projectId);
        }
        if (constructionUnit != null && !constructionUnit.isBlank()) qw.like("construction_unit", constructionUnit.trim());
        if (outType != null && !outType.isBlank()) qw.eq("out_type", outType);
        if (businessCategory != null && !businessCategory.isBlank()) qw.eq("business_category", businessCategory);
        if (startDate != null) qw.ge("apply_date", startDate);
        if (endDate != null) qw.le("apply_date", endDate);
        if (applicantName != null && !applicantName.isBlank()) {
            String like = "%" + applicantName.trim() + "%";
            qw.apply("applicant_id IN (SELECT id FROM sys_user WHERE username LIKE {0} OR name LIKE {0})", like);
        }
        // 列表排序：只认白名单，防止列名被拼进 ORDER BY；不在白名单时回落到默认的按录入倒序
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizOutReq.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizOutReq> result=reqMapper.selectPage(new Page<>(current,size),qw);
        result.getRecords().forEach(pickup::fillProgress);
        fillReturnQty(result.getRecords());
        return result;
    }

    public void addOut(Long reqId, Long reqLineId, BigDecimal qty) {
        // 进度计数回写，不动历史账：跨月申请在出库单所在期间（已单独校验）开放时允许回写
        if (reqLineId == null || lineMapper.addOutQty(reqLineId, qty, reqId) != 1
                || reqMapper.addOutQty(reqId, qty) != 1) {
            throw new BizException("出库数量超过原申请可出数量或来源状态已变化，请刷新后重试");
        }
    }

    private BizOutReq requireExisting(Long id) {
        BizOutReq req = reqMapper.findForUpdate(id);
        if (req == null) throw new BizException("出库申请不存在");
        orgScope.assertOrgVisible(req.getOrgId());
        return req;
    }

    /** 特殊事项审批单据号：选填，trim 后空串落 NULL；超长直接拒绝（避免静默截断导致对不上 OA 表单号） */
    private String normalizeApprovalDocNo(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        if (t.length() > 64) throw new BizException("特殊事项审批单据号不能超过 64 个字符");
        return t;
    }

    private void validateRequired(BizOutReq dto) {
        if (dto.getWarehouseId() == null) throw new BizException("仓库不能为空");
        if (dto.getProjectId() == null) throw new BizException("工程项目不能为空");
        // 出库类型不在申请环节设置（申请只是计划），改由仓管在「材料出库」环节确定
        if (dto.getBusinessCategory() == null || dto.getBusinessCategory().isBlank()) throw new BizException("业务分类不能为空");
        if (dto.getMaterialApplicant() == null || dto.getMaterialApplicant().isBlank()) throw new BizException("材料申请人不能为空");
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("领用明细不能为空");
    }

    private void validateApplicantRole(BizOutReq dto) {
        // 采购岗可发起出库申请（一般领料），提交后直接经财务（材料会计）审核
        if (SecurityUtil.hasRole("purchaser")) {
            return;
        }
        if (isProjectRequest(dto)) {
            if (!SecurityUtil.hasRole("field_manager")) {
                throw new BizException("工程类出库申请仅现场管理员可发起");
            }
            return;
        }
        if (!SecurityUtil.hasRole("service_staff")) {
            throw new BizException("运营类和增值销售类出库申请仅网点客服可发起");
        }
    }

    private boolean isProjectRequest(BizOutReq dto) {
        return matches(dto.getBusinessCategory(), "工程业务", "工程类", "PROJECT")
                || matches(dto.getOutType(), "工程领料", "PROJECT_MATERIAL");
    }

    private boolean matches(String value, String... candidates) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        for (String candidate : candidates) {
            if (trimmed.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private BigDecimal calculateLines(List<BizOutReqLine> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        if (lines != null) {
            for (BizOutReqLine line : lines) {
                validateLine(line);
                totalQty = totalQty.add(line.getQty());
            }
        }
        if (totalQty.signum() <= 0) throw new BizException("至少需要一行有效的领用明细");
        return totalQty;
    }

    private void validateLine(BizOutReqLine line) {
        if (line.getMaterialId() == null) throw new BizException("物料编码不能为空");
        orgScope.assertMaterialVisible(line.getMaterialId());
        if (line.getQty() == null || line.getQty().signum() <= 0) throw new BizException("申请数量必须大于 0");
    }

    private void insertLines(Long reqId, List<BizOutReqLine> lines) {
        if (lines == null) return;
        int i = 1;
        for (BizOutReqLine line : lines) {
            line.setId(null);
            line.setOutReqId(reqId);
            line.setLineNo(i++);
            line.setOriginalQty(line.getQty());
            line.setRevisedQty(BigDecimal.ZERO);
            line.setOutQty(BigDecimal.ZERO);
            line.setReissueQty(BigDecimal.ZERO); // 系统过账进度，拒绝客户端伪造再领额度
            line.setTransferredQty(BigDecimal.ZERO);line.setClosedQty(BigDecimal.ZERO);
            lineMapper.insert(line);
        }
    }

    private void applyProject(BizOutReq dto) {
        MdProject project = projectMapper.selectById(dto.getProjectId());
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw new BizException("工程项目不存在或已停用");
        }
        orgScope.assertProjectVisible(dto.getProjectId());
        dto.setProjectName(project.getName());
        if (dto.getPurpose() == null || dto.getPurpose().isBlank()) {
            dto.setPurpose(project.getName());
        }
    }

    /**
     * 2026-10-15:列表「已出库」原来只看 out_qty(不扣退料),和计划执行表的实际出库对不上。
     * 同时补真实累计出库与净出库；out_qty 保留再领额度进度，不能拿它再扣一次退料。
     */
    private void fillReturnQty(List<BizOutReq> reqs) {
        if (reqs == null || reqs.isEmpty()) return;
        java.util.Map<Long, BigDecimal> byReq = new java.util.HashMap<>();
        java.util.Map<Long, BigDecimal> grossByReq = new java.util.HashMap<>();
        for (var row : outLineMapper.approvedOutQtyByReqIds(reqs.stream().map(BizOutReq::getId).toList())) {
            grossByReq.put(((Number) row.get("reqId")).longValue(), new BigDecimal(String.valueOf(row.get("qty"))));
        }
        for (var row : returnLineMapper.approvedReturnQtyByReqIds(reqs.stream().map(BizOutReq::getId).toList())) {
            byReq.put(((Number) row.get("reqId")).longValue(), new BigDecimal(String.valueOf(row.get("qty"))));
        }
        reqs.forEach(r -> {
            r.setReturnQty(byReq.getOrDefault(r.getId(), BigDecimal.ZERO));
            r.setGrossOutQty(grossByReq.getOrDefault(r.getId(), BigDecimal.ZERO));
            r.setNetOutQty(r.getGrossOutQty().subtract(r.getReturnQty()));
        });
    }
}
