package com.group.scm.modules.work;

import com.group.scm.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "工作台异常提醒")
@RestController
@RequestMapping("/work")
@RequiredArgsConstructor
public class WorkAlertController {

    private final WorkAlertService service;
    private final DocGuardService docGuardService;

    /**
     * 工作台「需要你留意」。登录即可调用（全局登录拦截），不再额外限角色：
     * 给谁看哪几条由 service 按角色决定，且只回条数与单号、按可见账套过滤、不含金额。
     */
    @GetMapping("/alerts")
    public Result<List<Map<String, Object>>> alerts() {
        return Result.ok(service.alerts());
    }

    /**
     * 建单弹窗的提前提示：该日期所在月份是否已结账、所选仓库是否正在盘点。
     * 登录即可调用；只读、只在可见账套内查、不含金额。真正拦不拦仍以保存时的后端校验为准。
     */
    @GetMapping("/doc-guard")
    public Result<List<Map<String, Object>>> docGuard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) List<Long> warehouseIds,
            @RequestParam(required = false) Long sourceOrgId,
            @RequestParam(required = false) String operation) {
        return Result.ok(docGuardService.check(date, warehouseIds, sourceOrgId, operation));
    }
}
