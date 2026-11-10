package com.group.scm.modules.inventory.controller;

import com.group.scm.common.ResultCap;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.workflow.service.WorkflowService;
import com.group.scm.common.aop.OpLog;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.inventory.entity.BizTransfer;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.mapper.InvBalanceMapper;
import com.group.scm.modules.inventory.mapper.InvTransactionMapper;
import com.group.scm.modules.inventory.service.OpeningStockImportService;
import com.group.scm.modules.inventory.service.TransferService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.master.service.MasterImportTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Tag(name = "库存查询")
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final WorkflowService workflowService;
    private final InvBalanceMapper balanceMapper;
    private final InvTransactionMapper transMapper;
    private final WaInventoryService waService;
    private final TransferService transferService;
    private final OrgScopeService orgScope;
    private final OpeningStockImportService openingStockImportService;
    private final MasterImportTemplateService importTemplateService;
    private final JdbcTemplate jdbc;

    @GetMapping("/balance")
    public Result<List<Map<String, Object>>> balance(@RequestParam(required = false) Long matId,
                                                      @RequestParam(required = false) Long whId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "0") Integer includeZero) {
        SecurityUtil.requirePerm("inv.bal");
        if (whId != null) orgScope.assertWarehouseVisible(whId);
        var rows = balanceMapper.queryWithName(matId, whId, keywordTerms(keyword), orgScope.visibleOrgIds(), includeZero);
        hideCostForQuantityOnlyRoles(rows);
        return Result.ok(rows);
    }

    /**
     * 现存量关键字(对标财务软件一次查多个物料)：
     * 逗号/顿号/分号/换行分隔 = 多组，命中任一组即列出("M10001,M10002")；
     * 组内空格分隔 = 每个词都要命中编码、名称或规格之一("角铁 150" 找 150 规格的角铁)。
     * % _ \ 转义成普通字符，防止用户输入变成通配符；最多 20 组、每组 5 个词、每词 50 字，挡住超长输入。
     */
    static List<List<String>> keywordTerms(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return java.util.Arrays.stream(keyword.split("[,，、;；\\n]+"))
                .map(group -> java.util.Arrays.stream(group.trim().split("[\\s\u3000]+"))
                        .filter(t -> !t.isEmpty()).distinct().limit(5)
                        .map(t -> t.length() > 50 ? t.substring(0, 50) : t)
                        .map(t -> t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
                        .toList())
                .filter(words -> !words.isEmpty()).distinct().limit(20)
                .toList();
    }

    @GetMapping("/transfer")
    public Result<IPage<BizTransfer>> transferPage(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "20") long size,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Long fromWarehouseId,
                                                   @RequestParam(required = false) Long toWarehouseId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin");
        IPage<BizTransfer> page = transferService.page(current, size, status, keyword, fromWarehouseId, toWarehouseId,
                startDate, endDate, sortField, sortOrder);
        hideTransferCostForQuantityOnlyRoles(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/transfer/{id}")
    public Result<BizTransfer> transferDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin");
        BizTransfer transfer = transferService.detail(id);
        hideTransferCostForQuantityOnlyRole(transfer);
        // 回填审批权限：手机端详情页据此显示「通过/退回」，不填按钮永远不出现
        if (transfer != null && "PENDING".equals(transfer.getStatus())) {
            transfer.setCanApprove(workflowService.canApprove("TRANSFER", transfer.getId()));
        }
        return Result.ok(transfer);
    }

    @PostMapping("/transfer")
    public Result<BizTransfer> transferCreate(@RequestBody BizTransfer dto) {
        SecurityUtil.requireRole("warehouse");
        BizTransfer transfer = transferService.create(dto);
        hideTransferCostForQuantityOnlyRole(transfer);
        return Result.ok(transfer);
    }

    @PutMapping("/transfer/{id}")
    public Result<BizTransfer> transferUpdate(@PathVariable Long id, @RequestBody BizTransfer dto) {
        SecurityUtil.requireRole("warehouse");
        BizTransfer transfer = transferService.update(id, dto);
        hideTransferCostForQuantityOnlyRole(transfer);
        return Result.ok(transfer);
    }

    @PostMapping("/transfer/{id}/submit")
    public Result<?> transferSubmit(@PathVariable Long id) {
        SecurityUtil.requireRole("warehouse");
        transferService.submit(id);
        return Result.ok();
    }

    /** 期初库存 Excel 导入（新账套开账用，账套管理员）。 */
    @OpLog(module = "库存", action = "期初库存导入")
    @PostMapping(value = "/opening/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<OpeningStockImportService.ImportResult> openingImport(@RequestParam Long warehouseId,
                                                                        @RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(openingStockImportService.importExcel(warehouseId, file));
    }

    @GetMapping("/opening/import-template")
    public ResponseEntity<byte[]> openingImportTemplate() {
        SecurityUtil.requireAccountAdmin();
        String encoded = URLEncoder.encode("期初库存导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(importTemplateService.openingStockTemplate());
    }

    @PostMapping("/transfer/{id}/withdraw")
    public Result<?> transferWithdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        transferService.withdraw(id);
        return Result.ok();
    }

    @OpLog(module = "库存调拨单", action = "删除")
    @DeleteMapping("/transfer/{id}")
    public Result<?> transferDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        transferService.delete(id);
        return Result.ok();
    }

    @PostMapping("/transfer/{id}/approve")
    public Result<?> transferApprove(@PathVariable Long id, @RequestBody(required = false) ApproveDTO dto) {
        transferService.approve(id, dto == null ? null : dto.comment);
        return Result.ok();
    }

    @PostMapping("/transfer/{id}/reject")
    public Result<?> transferReject(@PathVariable Long id, @RequestBody(required = false) ApproveDTO dto) {
        transferService.reject(id, dto == null ? null : dto.comment);
        return Result.ok();
    }

    @GetMapping("/balance/{matId}/{whId}")
    public Result<?> single(@PathVariable Long matId, @PathVariable Long whId) {
        SecurityUtil.requirePerm("inv.bal");
        orgScope.assertWarehouseVisible(whId);
        InvBalance balance = waService.currentBalance(matId, whId);
        if (balance != null && !SecurityUtil.hasFinanceAccessOrAdmin()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("materialId", balance.getMaterialId());
            view.put("warehouseId", balance.getWarehouseId());
            view.put("qty", balance.getQty());
            view.put("updatedAt", balance.getUpdatedAt());
            return Result.ok(view);
        }
        return Result.ok(balance);
    }

    @GetMapping("/transactions")
    public Result<List<Map<String, Object>>> transactions(@RequestParam(required = false) Long matId,
                                                           @RequestParam(required = false) Long whId,
                                                           @RequestParam(required = false) String transType,
                                                           @RequestParam(required = false) String bizDocType,
                                                           @RequestParam(required = false) String bizDocNo,
                                                           @RequestParam(required = false) Long supplierId,
                                                           @RequestParam(required = false) Long projectId,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                           @RequestParam(defaultValue = "200") int limit) {
        SecurityUtil.requirePerm("inv.trans");
        if (whId != null) orgScope.assertWarehouseVisible(whId);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        var rows = transMapper.queryRecent(matId, whId, transType, bizDocType, bizDocNo, supplierId, projectId,
                startDate, endDate, orgScope.visibleOrgIds(), safeLimit);
        hideCostForQuantityOnlyRoles(rows);
        return Result.ok(rows);
    }

    /**
     * 出入库流水分页(电脑端,2026-10-19)。
     * 上面那个接口只给「最近 N 条」(最多 200),实际流水早已远超这个数,更早的在页面上根本翻不到,用户反馈"没有下一页"。
     * 这里按条件真分页,另外返回全部查询结果(不只本页)的条数和入库/出库/净额合计。
     * 权限、公司隔离、仓管看不到金额,三条与上面完全一致。
     */
    @GetMapping("/transactions/page")
    public Result<Map<String, Object>> transactionsPage(@RequestParam(defaultValue = "1") long current,
                                                        @RequestParam(defaultValue = "50") int size,
                                                        @RequestParam(required = false) Long matId,
                                                        @RequestParam(required = false) Long whId,
                                                        @RequestParam(required = false) String transType,
                                                        @RequestParam(required = false) String bizDocType,
                                                        @RequestParam(required = false) String bizDocNo,
                                                        @RequestParam(required = false) Long supplierId,
                                                        @RequestParam(required = false) Long projectId,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requirePerm("inv.trans");
        if (whId != null) orgScope.assertWarehouseVisible(whId);
        int safeSize = Math.max(1, Math.min(size, 500));
        long safeCurrent = Math.max(1, current);
        var orgIds = orgScope.visibleOrgIds();
        Map<String, Object> totals = new java.util.LinkedHashMap<>(transMapper.queryTotals(matId, whId, transType, bizDocType, bizDocNo,
                supplierId, projectId, startDate, endDate, orgIds));
        var rows = transMapper.queryPage(matId, whId, transType, bizDocType, bizDocNo, supplierId, projectId,
                startDate, endDate, orgIds, (safeCurrent - 1) * safeSize, safeSize);
        hideCostForQuantityOnlyRoles(rows);
        if (!SecurityUtil.hasFinanceAccessOrAdmin()) {
            // 合计里的金额同样只给能看成本的岗位;数量照给
            totals.put("inAmount", null);
            totals.put("outAmount", null);
            totals.put("netAmount", null);
            totals.put("tailAmt", null);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("records", rows);
        body.put("total", totals.get("total"));
        body.put("current", safeCurrent);
        body.put("size", safeSize);
        body.put("totals", totals);
        return Result.ok(body);
    }

    /**
     * 财务触发：刷新库存成本参考。
     * 根据当前结存数量和金额重新计算所有物料的结存单价。
     */
    @PostMapping("/recalc")
    public Result<Map<String, Object>> recalc(@RequestBody(required = false) Map<String, Object> params) {
        SecurityUtil.requireFinanceAccess();
        var whIds = orgScope.visibleWarehouseIds();
        var list = whIds.isEmpty()
                ? List.<InvBalance>of()
                : balanceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InvBalance>().in("warehouse_id", whIds));
        int updated = 0;
        for (InvBalance b : list) {
            if (b.getQty() != null && b.getQty().signum() > 0
                    && b.getAmount() != null && b.getAmount().signum() >= 0) {
                BigDecimal newPrice = b.getAmount().divide(b.getQty(), 2, RoundingMode.HALF_UP);
                if (newPrice.compareTo(b.getAvgPrice()) != 0) {
                    b.setAvgPrice(newPrice);
                    balanceMapper.updateById(b);
                    updated++;
                }
            }
        }
        return Result.ok(Map.of("total", list.size(), "updated", updated, "msg", "库存参考刷新完成"));
    }

    /**
     * 库存预警设置：列出物料 + 当前现存量(按可见账套汇总) + 最低/最高库存。
     * scope=active(默认) 只回有货或已设阈值的物料；scope=all 回全部（供搜索补设阈值）。
     */
    @GetMapping("/stock-limit")
    public Result<List<Map<String, Object>>> stockLimitList(@RequestParam(required = false) String keyword,
                                                            @RequestParam(defaultValue = "active") String scope) {
        SecurityUtil.requirePerm("inv.alert");
        List<Long> orgs = new java.util.ArrayList<>(orgScope.visibleOrgIds());
        if (orgs.isEmpty()) return Result.ok(new java.util.ArrayList<>());
        String ph = String.join(",", java.util.Collections.nCopies(orgs.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM (SELECT m.id materialId, m.code, m.name, m.spec, m.unit, "
          + "m.safety_qty safetyQty, m.max_qty maxQty, "
          + "COALESCE((SELECT SUM(b.qty) FROM inv_balance b JOIN md_warehouse w ON w.id=b.warehouse_id "
          + "WHERE b.material_id=m.id AND w.deleted=0 AND w.org_id IN (" + ph + ")),0) qty "
          + "FROM md_material m WHERE m.deleted=0 AND (m.org_id IN (" + ph + ") OR m.is_shared=1)");
        args.addAll(orgs);
        args.addAll(orgs);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (m.code LIKE ? OR m.name LIKE ? OR m.spec LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(") t");
        if (!"all".equalsIgnoreCase(scope)) sql.append(" WHERE t.qty>0 OR t.safetyQty>0 OR t.maxQty>0");
        sql.append(" ORDER BY (t.qty>0) DESC, t.code LIMIT 800");
        return Result.ok(ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 800));
    }

    /** 批量保存物料的最低/最高库存（采购/仓管/账套管理员/系统管理员）。 */
    @OpLog(module = "库存预警", action = "设置上下限")
    @PostMapping("/stock-limit")
    public Result<?> stockLimitSave(@RequestBody List<Map<String, Object>> items) {
        SecurityUtil.requireAnyRole("admin", "account_admin", "purchaser", "warehouse");
        if (items == null || items.isEmpty()) return Result.ok(Map.of("updated", 0));
        List<Long> orgs = new java.util.ArrayList<>(orgScope.visibleOrgIds());
        if (orgs.isEmpty()) return Result.fail(4003, "无可维护的账套");
        String ph = String.join(",", java.util.Collections.nCopies(orgs.size(), "?"));
        int updated = 0;
        for (Map<String, Object> it : items) {
            Long id = toLongVal(it.get("materialId"));
            if (id == null) continue;
            BigDecimal min = toBdVal(it.get("safetyQty"));
            BigDecimal max = toBdVal(it.get("maxQty"));
            if (min != null && min.signum() < 0) return Result.fail(400, "最低库存不能为负数");
            if (max != null && max.signum() < 0) return Result.fail(400, "最高库存不能为负数");
            if (min != null && min.signum() > 0 && max != null && max.signum() > 0 && max.compareTo(min) < 0)
                return Result.fail(400, "最高库存不能小于最低库存（物料ID " + id + "）");
            List<Object> args = new java.util.ArrayList<>();
            args.add(min);
            args.add(max);
            args.add(id);
            args.addAll(orgs);
            updated += jdbc.update(
                "UPDATE md_material SET safety_qty=?, max_qty=? WHERE id=? AND deleted=0 AND (org_id IN (" + ph + ") OR is_shared=1)",
                args.toArray());
        }
        return Result.ok(Map.of("updated", updated));
    }

    private static Long toLongVal(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static BigDecimal toBdVal(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private void hideCostForQuantityOnlyRoles(List<Map<String, Object>> rows) {
        if (SecurityUtil.hasFinanceAccessOrAdmin()) return;
        for (Map<String, Object> row : rows) {
            row.remove("amount");
            row.remove("avg_price");
            row.remove("avgPrice");
            row.remove("unit_price");
            row.remove("unitPrice");
            row.remove("bal_amount");
            row.remove("balAmount");
            row.remove("bal_avg_price");
            row.remove("balAvgPrice");
            // 2026-10-15 按公司核算新增的金额列,同样只给财务/管理员看
            row.remove("org_bal_amount");
            row.remove("orgBalAmount");
            row.remove("tail_amt");
            row.remove("tailAmt");
        }
    }

    private void hideTransferCostForQuantityOnlyRoles(List<BizTransfer> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (BizTransfer row : rows) hideTransferCostForQuantityOnlyRole(row);
    }

    private void hideTransferCostForQuantityOnlyRole(BizTransfer transfer) {
        if (transfer == null || SecurityUtil.hasFinanceAccessOrAdmin()) return;
        transfer.setTotalAmt(null);
        if (transfer.getLines() != null) {
            transfer.getLines().forEach(line -> {
                line.setUnitPrice(null);
                line.setAmount(null);
            });
        }
    }

    public static class ApproveDTO {
        public String comment;
    }
}
