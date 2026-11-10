package com.group.scm.modules.report;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
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

/** 存货明细账(按公司)。权限同收发存汇总(rpt.inout,2026-10-16 定);金额对仓管等岗位脱敏在服务层。 */
@Tag(name = "存货明细账")
@RestController
@RequestMapping("/report/inventory-ledger")
@RequiredArgsConstructor
public class InventoryLedgerController {

    private final InventoryLedgerService service;

    @Operation(summary = "存货明细账:一家公司、一种物料、一段期间逐笔收发存")
    @GetMapping
    public Result<Map<String, Object>> ledger(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long materialId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) List<String> docTypes) {
        SecurityUtil.requirePerm("rpt.inout");
        return Result.ok(service.ledger(orgId, materialId, startDate, endDate, warehouseId,
                docTypes == null ? null : new java.util.HashSet<>(docTypes)));
    }

    @Operation(summary = "当前账号可查看的公司")
    @GetMapping("/companies")
    public Result<List<Map<String, Object>>> companies() {
        SecurityUtil.requirePerm("rpt.inout");
        return Result.ok(service.companies());
    }
}
