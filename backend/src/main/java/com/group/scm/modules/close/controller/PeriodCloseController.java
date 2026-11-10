package com.group.scm.modules.close.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.Idempotent;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.close.service.PeriodCloseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "库存期间锁定与检查")
@RestController
@RequestMapping("/period-close")
@RequiredArgsConstructor
public class PeriodCloseController {

    private final PeriodCloseService service;

    /**
     * 工作台提醒：已审批未完成出库 / 暂估未核销 的待办条数。
     * 只读计数，权限不收紧到财务——仓库人员也要看见自己欠着多少条没出库。
     */
    @GetMapping("/reminders")
    public Result<Map<String, Object>> reminders() {
        return Result.ok(service.reminders());
    }

    @GetMapping("/records")
    public Result<List<Map<String, Object>>> records(@RequestParam(required = false) String periodMonth) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(service.records(periodMonth));
    }

    @PostMapping("/check")
    public Result<Map<String, Object>> check(@RequestBody PeriodCloseDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(service.checkPeriod(dto == null ? null : dto.getPeriodMonth()));
    }

    @Idempotent(intervalSeconds = 5)
    @OpLog(module = "月末结账", action = "执行结账")
    @PostMapping("/close")
    public Result<Map<String, Object>> close(@RequestBody PeriodCloseDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(service.closePeriod(dto == null ? null : dto.getPeriodMonth(), dto == null ? null : dto.getRemark()));
    }

    @Idempotent(intervalSeconds = 5)
    @OpLog(module = "月末结账", action = "反结账（重新打开期间）")
    @PostMapping("/reopen")
    public Result<?> reopen(@RequestBody PeriodCloseDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        service.reopenPeriod(dto == null ? null : dto.getPeriodMonth());
        return Result.ok();
    }

    @Data
    public static class PeriodCloseDTO {
        private String periodMonth;
        private String remark;
    }
}
