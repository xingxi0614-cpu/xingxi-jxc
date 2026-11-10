package com.group.scm.modules.materialreq.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.materialreq.entity.LrPicker;
import com.group.scm.modules.materialreq.service.LrPickerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "领料人档案")
@RestController
@RequestMapping("/material-req/picker")
@RequiredArgsConstructor
public class LrPickerController {

    private final LrPickerService service;

    @GetMapping
    public Result<List<LrPicker>> list(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) String keyword) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.list(orgId, keyword));
    }

    @PostMapping
    public Result<LrPicker> create(@RequestBody LrPicker dto) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.create(dto));
    }

    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody LrPicker dto) {
        SecurityUtil.requireAccountAdmin();
        service.update(id, dto);
        return Result.ok();
    }

    @OpLog(module = "领料人", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        service.delete(id);
        return Result.ok();
    }
}
