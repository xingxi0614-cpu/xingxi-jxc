package com.group.scm.modules.invoice.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.invoice.entity.BizPayment;
import com.group.scm.modules.invoice.service.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 付款登记（应付-物资款核销台账，财务权限）。 */
@Tag(name = "付款登记")
@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public Result<List<BizPayment>> list(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(paymentService.list(orgId, supplierId, startDate, endDate));
    }

    @OpLog(module = "付款登记", action = "登记")
    @PostMapping
    public Result<BizPayment> create(@RequestBody BizPayment dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(paymentService.create(dto));
    }

    @OpLog(module = "付款登记", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        paymentService.delete(id);
        return Result.ok();
    }
}
