package com.group.scm.modules.workflow.controller;

import com.group.scm.common.Result;
import com.group.scm.modules.workflow.entity.WfRecord;
import com.group.scm.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "审批流通用接口")
@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService wfService;

    @GetMapping("/records")
    public Result<List<WfRecord>> records(@RequestParam String bizNo) {
        return Result.ok(wfService.getRecords(bizNo));
    }

    /** 单据完整审批流（已批/当前/待批各节点 + 申请人），供详情页随手查看进度。 */
    @GetMapping("/flow")
    public Result<java.util.Map<String, Object>> flow(@RequestParam String bizNo) {
        return Result.ok(wfService.getApprovalFlow(bizNo));
    }

    /** 待我审批的单据：按单据真实状态查，不依赖通知是否还在（手机端「待我审」用）。 */
    @GetMapping("/pending-approvals")
    public Result<List<java.util.Map<String, Object>>> pendingApprovals() {
        return Result.ok(wfService.pendingApprovals());
    }

    /** 我发起的单据（跨类型聚合），按状态筛：草稿/已驳回 —— 手机端「我的草稿 / 我的退回」用。 */
    @GetMapping("/my-documents")
    public Result<List<java.util.Map<String, Object>>> myDocuments(@RequestParam(defaultValue = "DRAFT,REJECTED") String status) {
        return Result.ok(wfService.myDocuments(status));
    }

    /** 申请撤销审批：仅在已审批单据上发起 */
    @PostMapping("/cancel-request")
    public Result<?> requestCancel(@RequestBody CancelReqDTO dto) {
        return Result.ok(wfService.requestCancel(dto.getBizType(), dto.getBizId(), dto.getBizNo(), dto.getReason()));
    }

    @GetMapping("/cancel-requests")
    public Result<List<java.util.Map<String, Object>>> pendingCancelRequests(@RequestParam String keyword) {
        return Result.ok(wfService.pendingCancelRequests(keyword));
    }

    /** 原审批人处理撤销申请 */
    @PostMapping("/cancel-request/{id}/process")
    public Result<?> processCancel(@PathVariable Long id, @RequestBody ProcessDTO dto) {
        wfService.processCancel(id, dto.isApprove(), dto.getComment(), null);
        return Result.ok();
    }

    @Data public static class CancelReqDTO { String bizType; Long bizId; String bizNo; String reason; }
    @Data public static class ProcessDTO   { boolean approve; String comment; }
}
