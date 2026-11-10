package com.group.scm.modules.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.inventory.entity.BizStocktake;
import com.group.scm.modules.inventory.entity.BizStocktakeLine;
import com.group.scm.modules.inventory.service.StocktakeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 盘点单（仓管建单/录实盘/提交，财务确认→自动盘盈盘亏调整）。 */
@Tag(name = "盘点单")
@RestController
@RequestMapping("/stocktake")
@RequiredArgsConstructor
public class StocktakeController {

    private final StocktakeService service;

    @GetMapping
    public Result<IPage<BizStocktake>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long warehouseId,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String creatorName,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
                                            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance_manager", "account_admin", "admin");
        var page = service.page(current, size, status, warehouseId, keyword, creatorName, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(this::hideCost);
        return Result.ok(page);
    }

    @GetMapping("/{id}")
    public Result<BizStocktake> detail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance_manager", "account_admin", "admin");
        return Result.ok(hideCost(service.detail(id)));
    }

    @OpLog(module = "盘点单", action = "创建")
    @PostMapping
    public Result<BizStocktake> create(@RequestBody CreateDTO dto) {
        SecurityUtil.requireAnyRole("warehouse", "account_admin", "admin");
        return Result.ok(hideCost(service.create(dto.warehouseId, dto.takeDate, Boolean.TRUE.equals(dto.includeZero), dto.remark)));
    }

    @OpLog(module = "盘点单", action = "保存实盘数", recordBody = false)
    @PutMapping("/{id}/actual")
    public Result<?> saveActual(@PathVariable Long id, @RequestBody List<BizStocktakeLine> lines) {
        SecurityUtil.requireAnyRole("warehouse", "account_admin", "admin");
        service.saveActual(id, lines);
        return Result.ok();
    }

    @OpLog(module = "盘点单", action = "导入实盘数", recordBody = false)
    @PostMapping(value = "/{id}/import-actual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> importActual(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAnyRole("warehouse", "account_admin", "admin");
        return Result.ok(service.importActual(id, file));
    }

    /** 盘点表下载（blind=1 盲盘：不含账面数）。 */
    @GetMapping("/{id}/sheet")
    public ResponseEntity<byte[]> sheet(@PathVariable Long id, @RequestParam(defaultValue = "0") int blind) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance_manager", "account_admin", "admin");
        byte[] body = service.exportSheet(id, blind == 1);
        String encoded = URLEncoder.encode("盘点表.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @OpLog(module = "盘点单", action = "提交")
    @PostMapping("/{id}/submit")
    public Result<?> submit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "account_admin", "admin");
        service.submit(id);
        return Result.ok();
    }

    @OpLog(module = "盘点单", action = "确认(盘盈盘亏调整)")
    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        service.approve(id);
        return Result.ok();
    }

    @OpLog(module = "盘点单", action = "驳回")
    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody(required = false) RejectDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        service.reject(id, dto == null ? null : dto.reason);
        return Result.ok();
    }

    @OpLog(module = "盘点单", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "account_admin", "admin");
        service.delete(id);
        return Result.ok();
    }

    private BizStocktake hideCost(BizStocktake doc) {
        if (doc != null && SecurityUtil.hasRole("warehouse")) {
            doc.setGainAmt(null); doc.setLossAmt(null);
            if (doc.getLines() != null) doc.getLines().forEach(line -> {
                line.setUnitPrice(null); line.setDiffAmt(null);
            });
        }
        return doc;
    }

    @Data public static class CreateDTO { Long warehouseId; @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate takeDate; Boolean includeZero; String remark; }
    @Data public static class RejectDTO { String reason; }
}
