package com.group.scm.modules.invoice.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.workflow.service.WorkflowService;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.invoice.entity.BizInvoice;
import com.group.scm.modules.invoice.service.InvoiceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "采购发票")
@RestController
@RequestMapping("/invoice")
@RequiredArgsConstructor
public class InvoiceController {

    private final WorkflowService workflowService;
    private final InvoiceService invoiceService;

    @GetMapping
    public Result<IPage<BizInvoice>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long supplierId,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(invoiceService.page(current, size, status, supplierId, keyword, startDate, endDate, sortField, sortOrder));
    }

    @GetMapping("/{id}")
    public Result<BizInvoice> detail(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        BizInvoice inv = invoiceService.detail(id);
        // 回填审批权限：手机端详情页据此显示「通过/退回」，不填按钮永远不出现
        if (inv != null && "PENDING".equals(inv.getStatus())) {
            inv.setCanApprove(workflowService.canApprove("INV", inv.getId()));
        }
        return Result.ok(inv);
    }

    @PostMapping
    public Result<BizInvoice> create(@RequestBody BizInvoice dto) {
        SecurityUtil.requireFinanceAccess();
        return Result.ok(invoiceService.create(dto));
    }

    @PostMapping("/{id}/submit")
    public Result<?> submit(@PathVariable Long id) { SecurityUtil.requireFinanceAccess(); invoiceService.submit(id); return Result.ok(); }

    @PostMapping("/{id}/withdraw")
    public Result<?> withdraw(@PathVariable Long id) { SecurityUtil.requireFinanceAccessOrAdmin(); invoiceService.withdraw(id); return Result.ok(); }

    @OpLog(module = "发票", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) { SecurityUtil.requireFinanceAccessOrAdmin(); invoiceService.delete(id); return Result.ok(); }

    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        invoiceService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        invoiceService.reject(id, dto.getComment());
        return Result.ok();
    }

    @Data public static class ApproveDTO { String comment; }
}
