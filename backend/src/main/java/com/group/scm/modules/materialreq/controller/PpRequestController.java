package com.group.scm.modules.materialreq.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.materialreq.entity.PpRequest;
import com.group.scm.modules.materialreq.service.PpRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "采购计划收件箱")
@RestController
@RequestMapping("/material-req/pp")
@RequiredArgsConstructor
public class PpRequestController {

    private final PpRequestService service;
    private static final String[] VIEW_ROLES = {"purchaser", "account_admin", "admin"};

    @GetMapping
    public Result<IPage<PpRequest>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        return Result.ok(service.page(current, size, status, keyword));
    }

    /** 公司整体采购计划汇总（多份提报按物料合并）。statuses 逗号分隔，默认 SUBMITTED,CONFIRMED。 */
    @GetMapping("/summary")
    public Result<java.util.Map<String, Object>> summary(@RequestParam(required = false) Long orgId,
                                                         @RequestParam(required = false) String statuses,
                                                         @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
                                                         @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        List<String> sts = statuses == null || statuses.isBlank() ? null : List.of(statuses.split(","));
        return Result.ok(service.summary(orgId, sts, startDate, endDate));
    }

    @GetMapping("/{id}")
    public Result<PpRequest> detail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        return Result.ok(service.detail(id));
    }

    @PostMapping("/{id}/confirm")
    public Result<?> confirm(@PathVariable Long id, @RequestBody ConfirmDTO dto) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.confirm(id, dto.getLines());
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody RejectDTO dto) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.reject(id, dto.getReason());
        return Result.ok();
    }

    @OpLog(module = "采购计划申请", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.delete(id);
        return Result.ok();
    }

    @Data
    public static class ConfirmDTO {
        private List<PpRequestService.LineQty> lines;
    }

    @Data
    public static class RejectDTO {
        private String reason;
    }
}
