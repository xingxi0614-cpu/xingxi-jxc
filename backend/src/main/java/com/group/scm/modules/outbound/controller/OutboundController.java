package com.group.scm.modules.outbound.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.outbound.entity.BizOut;
import com.group.scm.modules.outbound.entity.BizLoan;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqRevision;
import com.group.scm.modules.outbound.entity.BizReturn;
import com.group.scm.modules.outbound.service.LoanService;
import com.group.scm.modules.outbound.service.OutReqRevisionService;
import com.group.scm.modules.outbound.service.OutReqService;
import com.group.scm.modules.outbound.service.OutService;
import com.group.scm.modules.outbound.service.ReturnService;
import com.group.scm.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "出库管理")
@RestController
@RequiredArgsConstructor
public class OutboundController {

    private final OutReqService oreqService;
    private final com.group.scm.modules.outbound.service.OutReqDuplicates oreqDuplicates;
    private final OutService outService;
    private final OutReqRevisionService revisionService;
    private final ReturnService returnService;
    private final LoanService loanService;
    private final WorkflowService workflowService;

    /** 给"待审批"出库申请标注当前用户是否可审批 */
    private void attachOreqCanApprove(List<BizOutReq> records) {
        if (records == null) return;
        for (BizOutReq r : records) {
            if (r != null && "PENDING".equals(r.getStatus())) {
                r.setCanApprove(workflowService.canApprove("OREQ", r.getId()));
            }
        }
    }

    /** 给"待审批"材料出库单标注当前用户是否可审批 */
    private void attachOutCanApprove(List<BizOut> records) {
        if (records == null) return;
        for (BizOut o : records) {
            if (o != null && "PENDING".equals(o.getStatus())) {
                o.setCanApprove(workflowService.canApprove("OUT", o.getId()));
            }
        }
    }

    private static final String[] OREQ_ACCESS_ROLES = {
            "purchaser", "warehouse", "material_accountant", "finance", "admin",
            "field_manager", "tech_manager",
            "service_staff", "operation_repair", "branch_manager", "area_manager",
            "operation_leader", "engineering_leader", "market_leader", "account_admin"
    };
    private static final String[] OUT_ACCESS_ROLES = {
            "warehouse", "material_accountant", "finance", "admin",
            "tech_manager", "engineering_leader",
            // 出库申请发起人(采购/工程现场/客服)会收到出库单退回等待办:可查看(非财务自动金额脱敏),写操作仍仅仓管
            "purchaser", "field_manager", "service_staff"
    };
    private static final String[] OREQ_APPLICANT_ROLES = {
            "field_manager", "service_staff", "purchaser"
    };

    /* ============ 出库申请 ============ */
    @GetMapping("/outbound/oreq")
    public Result<IPage<BizOutReq>> oreqPage(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) String purpose,
                                              @RequestParam(required = false) String applicantName,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) String constructionUnit,
                                              @RequestParam(required = false) String outType,
                                              @RequestParam(required = false) String businessCategory,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder,
                                              @RequestParam(required = false) String revisionStatus,
                                              @RequestParam(required = false) String pickupState) {
        SecurityUtil.requireAnyRole(OREQ_ACCESS_ROLES);
        IPage<BizOutReq> page = oreqService.page(current, size, status, keyword, warehouseId, purpose, applicantName,
                projectId, constructionUnit, outType, businessCategory, startDate, endDate, sortField, sortOrder, revisionStatus, pickupState);
        attachOreqCanApprove(page.getRecords());
        return Result.ok(page);
    }

    /** 制单页保存前查:这个工程是否已有未完成的工程类申请(只提醒不拦,口径见 OutReqDuplicates)。 */
    @GetMapping("/outbound/oreq/open-on-project")
    public Result<List<Map<String, Object>>> oreqOpenOnProject(@RequestParam Long projectId,
                                                              @RequestParam(required = false) Long excludeId) {
        SecurityUtil.requireAnyRole(OREQ_ACCESS_ROLES);
        return Result.ok(oreqDuplicates.openOnSameProject(projectId, excludeId));
    }

    @GetMapping("/outbound/oreq/{id}")
    public Result<BizOutReq> oreqDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OREQ_ACCESS_ROLES);
        BizOutReq req = oreqService.detail(id);
        if (req != null && "PENDING".equals(req.getStatus())) {
            req.setCanApprove(workflowService.canApprove("OREQ", req.getId()));
        }
        return Result.ok(req);
    }

    @PostMapping("/outbound/oreq")
    public Result<BizOutReq> oreqCreate(@RequestBody BizOutReq dto) { SecurityUtil.requireAnyRole(OREQ_APPLICANT_ROLES); return Result.ok(oreqService.create(dto)); }

    @PutMapping("/outbound/oreq/{id}")
    public Result<?> oreqUpdate(@PathVariable Long id, @RequestBody BizOutReq dto) { SecurityUtil.requireAnyRole(OREQ_APPLICANT_ROLES); oreqService.update(id, dto); return Result.ok(); }

    @PostMapping("/outbound/oreq/{id}/submit")
    public Result<?> oreqSubmit(@PathVariable Long id) { SecurityUtil.requireAnyRole(OREQ_APPLICANT_ROLES); oreqService.submit(id); return Result.ok(); }

    @PostMapping("/outbound/oreq/{id}/withdraw")
    public Result<?> oreqWithdraw(@PathVariable Long id) { SecurityUtil.requireAnyRole("field_manager", "service_staff", "purchaser", "admin"); oreqService.withdraw(id); return Result.ok(); }

    @OpLog(module = "出库申请", action = "删除")
    @DeleteMapping("/outbound/oreq/{id}")
    public Result<?> oreqDelete(@PathVariable Long id) { SecurityUtil.requireAnyRole(OREQ_APPLICANT_ROLES); oreqService.delete(id); return Result.ok(); }

    @PostMapping("/outbound/oreq/{id}/approve")
    public Result<?> oreqApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) { oreqService.approve(id, dto.getComment()); return Result.ok(); }

    @PostMapping("/outbound/oreq/{id}/reject")
    public Result<?> oreqReject(@PathVariable Long id, @RequestBody ApproveDTO dto) { oreqService.reject(id, dto.getComment()); return Result.ok(); }

    /* ============ 出库申请修订 ============ */
    @GetMapping("/outbound/oreq/{id}/revisions")
    public Result<List<BizOutReqRevision>> oreqRevisions(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OREQ_ACCESS_ROLES);
        return Result.ok(revisionService.listByReq(id));
    }

    @GetMapping("/outbound/oreq/revision/{id}")
    public Result<BizOutReqRevision> oreqRevisionDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OREQ_ACCESS_ROLES);
        return Result.ok(revisionService.detail(id));
    }

    @PostMapping("/outbound/oreq/{id}/revision")
    public Result<BizOutReqRevision> oreqRevisionCreate(@PathVariable Long id, @RequestBody BizOutReqRevision dto) {
        SecurityUtil.requireAnyRole("purchaser", "admin");
        return Result.ok(revisionService.create(id, dto));
    }

    @PostMapping("/outbound/oreq/revision/{id}/submit")
    public Result<?> oreqRevisionSubmit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("purchaser", "admin");
        revisionService.submit(id);
        return Result.ok();
    }

    @PostMapping("/outbound/oreq/revision/{id}/approve")
    public Result<?> oreqRevisionApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        revisionService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/outbound/oreq/revision/{id}/reject")
    public Result<?> oreqRevisionReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        revisionService.reject(id, dto.getComment());
        return Result.ok();
    }

    /* ============ 材料出库 ============ */
    @GetMapping("/outbound/out")
    public Result<IPage<BizOut>> outPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Long warehouseId,
                                          @RequestParam(required = false) String sourceReqNo,
                                          @RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) String constructionUnit,
                                          @RequestParam(required = false) String outType,
                                          @RequestParam(required = false) String businessCategory,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder,
                                              @RequestParam(required = false) String reqText) {
        SecurityUtil.requireAnyRole(OUT_ACCESS_ROLES);
        IPage<BizOut> page = outService.page(current, size, status, keyword, warehouseId, sourceReqNo,
                projectId, constructionUnit, outType, businessCategory, startDate, endDate, sortField, sortOrder, reqText);
        hideCostForQuantityOnlyRoles(page.getRecords());
        attachOutCanApprove(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/outbound/out/{id}")
    public Result<BizOut> outDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OUT_ACCESS_ROLES);
        BizOut out = hideCostForQuantityOnlyRoles(outService.detail(id));
        if (out != null && "PENDING".equals(out.getStatus())) {
            out.setCanApprove(workflowService.canApprove("OUT", out.getId()));
        }
        return Result.ok(out);
    }

    @PostMapping("/outbound/out/from-oreq")
    public Result<BizOut> outFromOreq(@RequestBody PushDTO dto) {
        SecurityUtil.requireRole("warehouse");
        return Result.ok(hideCostForQuantityOnlyRoles(outService.fromOreq(dto.getOreqId(), dto.getLines(), dto.getOutType())));
    }

    @PostMapping("/outbound/out/{id}/submit")
    public Result<?> outSubmit(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse"); outService.submit(id); return Result.ok(); }

    @PostMapping("/outbound/out/{id}/withdraw")
    public Result<?> outWithdraw(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse", "admin"); outService.withdraw(id); return Result.ok(); }

    @OpLog(module = "材料出库单", action = "删除")
    @DeleteMapping("/outbound/out/{id}")
    public Result<?> outDelete(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse", "admin"); outService.delete(id); return Result.ok(); }

    @PostMapping("/outbound/out/{id}/approve")
    public Result<?> outApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) { outService.approve(id, dto.getComment()); return Result.ok(); }

    @PostMapping("/outbound/out/{id}/reject")
    public Result<?> outReject(@PathVariable Long id, @RequestBody ApproveDTO dto) { outService.reject(id, dto.getComment()); return Result.ok(); }

    /* ============ 退料 ============ */
    @GetMapping("/outbound/return")
    public Result<IPage<BizReturn>> returnPage(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Long warehouseId,
                                                @RequestParam(required = false) String sourceOutNo,
                                                @RequestParam(required = false) String sourceReqNo,
                                                @RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) String constructionUnit,
                                                @RequestParam(required = false) String outType,
                                                @RequestParam(required = false) String businessCategory,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin");
        IPage<BizReturn> page = returnService.page(current, size, status, keyword, warehouseId, sourceOutNo, sourceReqNo,
                projectId, constructionUnit, outType, businessCategory, startDate, endDate, sortField, sortOrder);
        hideReturnCostForQuantityOnlyRoles(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/outbound/return/{id}")
    public Result<BizReturn> returnDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin");
        return Result.ok(hideReturnCostForQuantityOnlyRoles(returnService.detail(id)));
    }

    @PostMapping("/outbound/return/from-out")
    public Result<BizReturn> returnFromOut(@RequestBody BizReturn dto) {
        SecurityUtil.requireRole("warehouse");
        return Result.ok(hideReturnCostForQuantityOnlyRoles(returnService.fromOut(dto)));
    }

    @PostMapping("/outbound/return/from-oreq")
    public Result<BizReturn> returnFromOreq(@RequestBody BizReturn dto) {
        SecurityUtil.requireRole("warehouse");
        return Result.ok(hideReturnCostForQuantityOnlyRoles(returnService.fromOutReq(dto)));
    }

    @PostMapping("/outbound/return/{id}/submit")
    public Result<?> returnSubmit(@PathVariable Long id) {
        SecurityUtil.requireRole("warehouse");
        returnService.submit(id);
        return Result.ok();
    }

    @PostMapping("/outbound/return/{id}/withdraw")
    public Result<?> returnWithdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        returnService.withdraw(id);
        return Result.ok();
    }

    @OpLog(module = "退料单", action = "删除")
    @DeleteMapping("/outbound/return/{id}")
    public Result<?> returnDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        returnService.delete(id);
        return Result.ok();
    }

    @PostMapping("/outbound/return/{id}/approve")
    public Result<?> returnApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        returnService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/outbound/return/{id}/reject")
    public Result<?> returnReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        returnService.reject(id, dto.getComment());
        return Result.ok();
    }

    /* ============ 借料登记 ============ */
    @GetMapping("/outbound/loan")
    public Result<IPage<BizLoan>> loanPage(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "20") long size,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long warehouseId,
                                           @RequestParam(required = false) String borrower,
                                           @RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        // 仓管(经办)+ 材料会计/财务(审批人)+ 管理员 均可查看借料
        SecurityUtil.requireAnyRole("warehouse", "admin", "material_accountant", "finance");
        IPage<BizLoan> page = loanService.page(current, size, status, keyword, warehouseId, borrower, projectId, startDate, endDate, sortField, sortOrder);
        hideLoanCostForQuantityOnlyRoles(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/outbound/loan/{id}")
    public Result<BizLoan> loanDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin", "material_accountant", "finance");
        BizLoan loan = hideLoanCostForQuantityOnlyRoles(loanService.detail(id));
        // 回填审批权限：手机端详情页据此显示「通过/退回」，不填按钮永远不出现
        if (loan != null && "PENDING".equals(loan.getStatus())) {
            loan.setCanApprove(workflowService.canApprove("LOAN", loan.getId()));
        }
        return Result.ok(loan);
    }

    @PostMapping("/outbound/loan")
    public Result<BizLoan> loanCreate(@RequestBody BizLoan dto) {
        SecurityUtil.requireRole("warehouse");
        return Result.ok(hideLoanCostForQuantityOnlyRoles(loanService.create(dto)));
    }

    @PostMapping("/outbound/loan/{id}/return")
    public Result<BizLoan> loanReturn(@PathVariable Long id, @RequestBody LoanService.LoanReturnDTO dto) {
        SecurityUtil.requireRole("warehouse");
        return Result.ok(hideLoanCostForQuantityOnlyRoles(loanService.registerReturn(id, dto)));
    }

    @OpLog(module = "借料单", action = "删除")
    @DeleteMapping("/outbound/loan/{id}")
    public Result<?> loanDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        loanService.delete(id);
        return Result.ok();
    }

    @PostMapping("/outbound/loan/{id}/submit")
    public Result<?> loanSubmit(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse"); loanService.submit(id); return Result.ok(); }

    @PostMapping("/outbound/loan/{id}/withdraw")
    public Result<?> loanWithdraw(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse", "admin"); loanService.withdraw(id); return Result.ok(); }

    @PostMapping("/outbound/loan/{id}/approve")
    public Result<?> loanApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) { loanService.approve(id, dto.getComment()); return Result.ok(); }

    @PostMapping("/outbound/loan/{id}/reject")
    public Result<?> loanReject(@PathVariable Long id, @RequestBody ApproveDTO dto) { loanService.reject(id, dto.getComment()); return Result.ok(); }

    @Data public static class PushDTO    { Long oreqId; List<OutService.OutLineInput> lines; String outType; }
    @Data public static class ApproveDTO { String comment; }

    private void hideCostForQuantityOnlyRoles(List<BizOut> rows) {
        if (rows == null) return;
        rows.forEach(this::hideCostForQuantityOnlyRoles);
    }

    private BizOut hideCostForQuantityOnlyRoles(BizOut out) {
        if (out == null || SecurityUtil.hasFinanceAccessOrAdmin()) return out;
        out.setTotalAmt(null);
        if (out.getLines() != null) {
            out.getLines().forEach(line -> {
                line.setUnitPrice(null);
                line.setAmount(null);
            });
        }
        return out;
    }

    private void hideReturnCostForQuantityOnlyRoles(java.util.List<BizReturn> rows) {
        if (rows == null) return;
        rows.forEach(this::hideReturnCostForQuantityOnlyRoles);
    }

    private BizReturn hideReturnCostForQuantityOnlyRoles(BizReturn ret) {
        if (ret == null || SecurityUtil.hasFinanceAccessOrAdmin()) return ret;
        ret.setTotalAmt(null);
        if (ret.getLines() != null) {
            ret.getLines().forEach(line -> {
                line.setUnitPrice(null);
                line.setAmount(null);
            });
        }
        return ret;
    }

    private void hideLoanCostForQuantityOnlyRoles(java.util.List<BizLoan> rows) {
        if (rows == null) return;
        rows.forEach(this::hideLoanCostForQuantityOnlyRoles);
    }

    private BizLoan hideLoanCostForQuantityOnlyRoles(BizLoan loan) {
        if (loan == null || SecurityUtil.hasFinanceAccessOrAdmin()) return loan;
        loan.setTotalAmt(null);
        if (loan.getLines() != null) {
            loan.getLines().forEach(line -> {
                line.setUnitPrice(null);
                line.setAmount(null);
            });
        }
        return loan;
    }
}
