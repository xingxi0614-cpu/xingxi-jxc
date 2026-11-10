package com.group.scm.modules.materialreq.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.materialreq.entity.LrLink;
import com.group.scm.modules.materialreq.service.LrLinkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "领料申请提交链接")
@RestController
@RequestMapping("/material-req/link")
@RequiredArgsConstructor
public class LrLinkController {

    private final LrLinkService service;

    @GetMapping
    public Result<List<LrLink>> list(@RequestParam(required = false) Long orgId,
                                     @RequestParam(required = false) String linkType) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.list(orgId, linkType));
    }

    @PostMapping
    public Result<LrLink> create(@RequestBody LrLink dto) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.create(dto));
    }

    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody LrLink dto) {
        SecurityUtil.requireAccountAdmin();
        service.update(id, dto);
        return Result.ok();
    }

    @PostMapping("/{id}/regenerate")
    public Result<LrLink> regenerate(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.regenerate(id));
    }

    @OpLog(module = "领料提交链接", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        service.delete(id);
        return Result.ok();
    }
}
