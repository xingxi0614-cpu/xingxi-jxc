package com.group.scm.modules.sales.controller;

import com.group.scm.common.Result;
import com.group.scm.modules.sales.service.SoPublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 网点扫码录销售单(免登录)。
 * 🔴 这是全站第二条免登录业务入口(第一条是领料 /lr-public),
 *    所有防线与它保持一致:token 校验 + 限流 + 行数封顶 + 账套一律取自链接。
 */
@RestController
@RequestMapping("/so-public")
@RequiredArgsConstructor
public class SoPublicController {

    private final SoPublicService service;

    @GetMapping("/context")
    public Result<Map<String, Object>> context(@RequestParam String token) {
        return Result.ok(service.context(token));
    }

    @GetMapping("/materials")
    public Result<List<Map<String, Object>>> materials(@RequestParam String token,
                                                       @RequestParam(required = false) String keyword) {
        return Result.ok(service.searchMaterials(token, keyword));
    }

    @PostMapping("/submit")
    public Result<Map<String, Object>> submit(@RequestBody SoPublicService.SubmitDTO dto) {
        return Result.ok(service.submit(dto));
    }
}
