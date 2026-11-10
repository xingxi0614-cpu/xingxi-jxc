package com.group.scm.modules.system.controller;

import com.group.scm.common.Result;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.system.service.SystemInitializationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "正式启用初始化")
@RestController
@RequestMapping("/sys/initialization")
@RequiredArgsConstructor
public class SystemInitializationController {

    private final SystemInitializationService initializationService;

    @GetMapping("/preview")
    public Result<Map<String, Object>> preview() {
        return Result.ok(initializationService.preview());
    }

    @OpLog(module = "系统初始化", action = "正式启用初始化（清空账套）")
    @PostMapping("/formal-reset")
    public Result<Map<String, Object>> formalReset(@RequestBody FormalResetDTO dto) {
        return Result.ok(initializationService.formalReset(dto == null ? null : dto.confirmText));
    }

    public static class FormalResetDTO {
        public String confirmText;
    }
}
