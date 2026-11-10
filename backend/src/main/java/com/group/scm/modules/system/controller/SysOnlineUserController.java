package com.group.scm.modules.system.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.system.service.OnlineSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/sys/online-users")
@RequiredArgsConstructor
public class SysOnlineUserController {

    private final OnlineSessionService onlineSessionService;

    @GetMapping("/summary")
    public Result<?> summary() {
        return Result.ok(onlineSessionService.summary());
    }

    @GetMapping
    public Result<?> list() {
        SecurityUtil.requireAdmin();
        return Result.ok(Map.of(
                "summary", onlineSessionService.summary(),
                "records", onlineSessionService.list()
        ));
    }

    @OpLog(module = "在线管理", action = "踢出用户")
    @PostMapping("/{userId}/kick")
    public Result<?> kick(@PathVariable Long userId) {
        SecurityUtil.requireAdmin();
        onlineSessionService.kick(userId);
        return Result.ok(Map.of("kicked", true));
    }
}
