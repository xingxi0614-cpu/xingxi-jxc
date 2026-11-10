package com.group.scm.modules.report;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "集团汇总分析")
@RestController
@RequestMapping("/report/group-overview")
@RequiredArgsConstructor
public class GroupOverviewController {

    private final GroupOverviewService service;

    /**
     * 跨公司的金额对比，只给系统管理员：这里必须在后端拦——
     * 菜单不给别的角色只是"看不见入口"，不等于"调不了接口"。
     */
    @Operation(summary = "各公司关键指标对比 + 三个排行(仅系统管理员)")
    @GetMapping
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "month") String period) {
        SecurityUtil.requireRole("admin");
        return Result.ok(service.overview(period));
    }
}
