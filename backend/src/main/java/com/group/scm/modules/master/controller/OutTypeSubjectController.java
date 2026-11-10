package com.group.scm.modules.master.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.master.entity.MdOutTypeSubject;
import com.group.scm.modules.master.service.OutTypeSubjectService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 出库类型-会计科目映射(管理:账套管理员;读取:登录用户,供单据显示对应科目)。 */
@Tag(name = "出库类型科目映射")
@RestController
@RequestMapping("/master/out-type-subject")
@RequiredArgsConstructor
public class OutTypeSubjectController {

    private final OutTypeSubjectService service;

    /** 列表——登录用户可读(单据表单要用它显示对应科目)。 */
    @GetMapping
    public Result<List<MdOutTypeSubject>> list() {
        return Result.ok(service.list());
    }

    @OpLog(module = "出库类型科目映射", action = "保存")
    @PostMapping
    public Result<MdOutTypeSubject> save(@RequestBody MdOutTypeSubject dto) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(service.save(dto));
    }

    @OpLog(module = "出库类型科目映射", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        service.delete(id);
        return Result.ok();
    }
}
