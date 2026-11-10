package com.group.scm.modules.userpref.controller;

import com.group.scm.common.Result;
import com.group.scm.modules.userpref.service.UserPrefService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户个人偏好。全部走登录态识别身份,没有任何接口接受 userId 参数
 * —— 每个人只能读写自己的,不存在越权看别人桌面的入口。
 *
 * 不加 @OpLog:这些是高频的界面偏好(拖一下卡片就写一次),记操作日志会把日志刷爆,
 * 而且它既不涉及金额也不涉及权限,没有审计价值。
 */
@Tag(name = "个人偏好")
@RestController
@RequestMapping("/user-pref")
@RequiredArgsConstructor
public class UserPrefController {

    private final UserPrefService service;

    /** 我的全部偏好(进工作台调一次) */
    @GetMapping
    public Result<Map<String, String>> myAll() {
        return Result.ok(service.myAll());
    }

    /** 写一条。body: { "key": "fav.pages", "value": "{...}" } */
    @PutMapping
    public Result<?> put(@RequestBody Map<String, String> body) {
        service.put(body.get("key"), body.get("value"));
        return Result.ok();
    }

    /** 删一条(恢复默认) */
    @DeleteMapping("/{key}")
    public Result<?> remove(@PathVariable String key) {
        service.remove(key);
        return Result.ok();
    }
}
