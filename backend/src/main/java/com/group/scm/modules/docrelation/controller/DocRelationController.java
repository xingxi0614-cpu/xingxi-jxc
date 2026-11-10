package com.group.scm.modules.docrelation.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.docrelation.service.DocRelationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "单据链查询")
@RestController
@RequestMapping("/doc-chain")
@RequiredArgsConstructor
public class DocRelationController {

    private final DocRelationService service;

    @GetMapping
    public Result<Map<String, Object>> chain(@RequestParam String docNo) {
        // 任意已登录用户均可查单据链（全局拦截器已强制登录）；
        // 可见范围由组织数据权限(org-scope)与金额脱敏(财务/管理员可见金额)控制。
        return Result.ok(service.buildChain(docNo));
    }
}
