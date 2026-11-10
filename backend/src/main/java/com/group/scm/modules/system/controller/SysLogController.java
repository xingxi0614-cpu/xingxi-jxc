package com.group.scm.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.system.entity.SysLog;
import com.group.scm.modules.system.mapper.SysLogMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "操作日志")
@RestController
@RequestMapping("/sys/logs")
@RequiredArgsConstructor
public class SysLogController {

    private final SysLogMapper logMapper;

    @GetMapping
    public Result<IPage<SysLog>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String username,
                                      @RequestParam(required = false) String module,
                                      @RequestParam(required = false) String docNo,
                                      @RequestParam(required = false) Integer success) {
        SecurityUtil.requireAdmin();
        QueryWrapper<SysLog> qw = new QueryWrapper<>();
        if (username != null && !username.isBlank()) qw.like("username", username);
        if (module != null && !module.isBlank())     qw.eq("module", module);
        if (docNo != null && !docNo.isBlank())       qw.eq("doc_no", docNo);
        if (success != null)                          qw.eq("success", success);
        qw.orderByDesc("id");
        return Result.ok(logMapper.selectPage(new Page<>(current, size), qw));
    }
}
