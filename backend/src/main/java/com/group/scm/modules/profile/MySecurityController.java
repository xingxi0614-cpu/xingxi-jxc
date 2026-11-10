package com.group.scm.modules.profile;

import com.group.scm.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 个人资料里的「账号与安全」:只读、要求登录,只返回本人数据。 */
@Tag(name = "个人账号与安全")
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class MySecurityController {

    private final MySecurityService service;

    /** 我最近的登录时间/IP + 我最近做过的 10 条操作 */
    @GetMapping("/security")
    public Result<Map<String, Object>> security() {
        return Result.ok(service.security());
    }
}
