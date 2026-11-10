package com.group.scm.modules.system.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.system.service.DemoResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 演示账套复位（每日 04:10 自动执行；管理员也可在推广演示前手工触发）。 */
@Tag(name = "演示账套")
@RestController
@RequestMapping("/system/demo")
@RequiredArgsConstructor
public class DemoResetController {

    private final DemoResetService demoResetService;

    @OpLog(module = "演示账套", action = "手工复位")
    @PostMapping("/reset")
    public Result<String> reset() {
        SecurityUtil.requireAdmin();
        return Result.ok(demoResetService.reset());
    }
}
