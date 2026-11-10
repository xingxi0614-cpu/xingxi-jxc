package com.group.scm.modules.materialreq.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.materialreq.entity.LrRequest;
import com.group.scm.modules.materialreq.service.LrRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "领料申请收件箱")
@RestController
@RequestMapping("/material-req/request")
@RequiredArgsConstructor
public class LrRequestController {

    private final LrRequestService service;
    private static final String[] VIEW_ROLES = {
            "purchaser", "warehouse", "material_accountant", "account_admin", "admin"
    };

    @GetMapping
    public Result<IPage<LrRequest>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        return Result.ok(service.page(current, size, status, keyword));
    }

    @GetMapping("/{id}")
    public Result<LrRequest> detail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        return Result.ok(service.detail(id));
    }

    @PostMapping("/{id}/confirm")
    public Result<?> confirm(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.confirm(id);
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody RejectDTO dto) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.reject(id, dto.getReason());
        return Result.ok();
    }

    @OpLog(module = "领料申请", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(VIEW_ROLES);
        service.delete(id);
        return Result.ok();
    }

    @Data
    public static class RejectDTO {
        private String reason;
    }
}
