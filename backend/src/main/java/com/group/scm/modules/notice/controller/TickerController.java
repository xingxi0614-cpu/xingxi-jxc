package com.group.scm.modules.notice.controller;

import com.group.scm.common.Result;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.notice.entity.SysTicker;
import com.group.scm.modules.notice.service.TickerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 顶栏滚动通知。未登录由全局 Sa-Token 拦截器挡住(本路径不在放行白名单)。
 * /live 对所有登录用户开放(只给本公司、显示期内的);其余全部只限系统管理员,在 TickerService 强制。
 */
@Tag(name = "滚动通知")
@RestController
@RequestMapping("/ticker")
@RequiredArgsConstructor
public class TickerController {

    private final TickerService service;

    @GetMapping("/live")
    public Result<List<Map<String, Object>>> live() {
        return Result.ok(service.live());
    }

    @GetMapping
    public Result<List<SysTicker>> list() {
        return Result.ok(service.adminList());
    }

    @GetMapping("/companies")
    public Result<List<Map<String, Object>>> companies() {
        return Result.ok(service.companies());
    }

    @OpLog(module = "滚动通知", action = "发布")
    @PostMapping
    public Result<SysTicker> create(@RequestBody SysTicker dto) {
        return Result.ok(service.create(dto));
    }

    @OpLog(module = "滚动通知", action = "修改")
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody SysTicker dto) {
        service.update(id, dto);
        return Result.ok();
    }

    @OpLog(module = "滚动通知", action = "启用/停用")
    @PostMapping("/{id}/status")
    public Result<?> status(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        service.setStatus(id, Boolean.TRUE.equals(body.get("enabled")));
        return Result.ok();
    }

    @OpLog(module = "滚动通知", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> remove(@PathVariable Long id) {
        service.remove(id);
        return Result.ok();
    }
}
