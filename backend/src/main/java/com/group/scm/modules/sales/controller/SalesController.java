package com.group.scm.modules.sales.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.sales.entity.*;
import com.group.scm.modules.sales.service.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 销售管理：销售订单 / 销售出库 / 销售发票 / 收款登记。 */
@Tag(name = "销售管理")
@RestController
@RequestMapping("/sale")
@RequiredArgsConstructor
public class SalesController {

    private final SoService soService;
    private final SaleOutService saleOutService;
    private final SaleOutExportService saleOutExportService;
    private final SaleInvoiceService saleInvoiceService;
    private final ReceiptService receiptService;
    private final SaleReturnService saleReturnService;
    private final SalesQueryService salesQueryService;
    private final SalesReportService salesReportService;
    private final SalesIncomeCostService salesIncomeCostService;
    private final WorkflowService workflowService;

    private static final String[] SALE_READ_ROLES = {
            "purchaser", "warehouse", "material_accountant", "account_admin", "admin"
    };
    private static final String[] SO_EDIT_ROLES = {
            "purchaser", "material_accountant", "account_admin", "admin"
    };
    private static final String[] OUT_EDIT_ROLES = {
            "warehouse", "account_admin", "admin"
    };
    private static final String[] RETURN_EDIT_ROLES = {
            "warehouse", "material_accountant", "account_admin", "admin"
    };

    @Data public static class ApproveDTO { String comment; }

    /* ==================== 客户档案下拉 ==================== */
    @GetMapping("/customers")
    public Result<List<Map<String, Object>>> customers(@RequestParam(required = false) String keyword) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        return Result.ok(soService.customers(keyword));
    }

    /* ==================== ① 销售订单 ==================== */
    @GetMapping("/order")
    public Result<IPage<BizSo>> orderPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Long customerId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        IPage<BizSo> page = soService.page(current, size, status, keyword, customerId, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(so -> { if ("PENDING".equals(so.getStatus())) so.setCanApprove(workflowService.canApprove("SO", so.getId())); });
        return Result.ok(page);
    }

    @GetMapping("/order/available")
    public Result<IPage<BizSo>> orderAvailable(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long customerId) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        return Result.ok(soService.availableForOut(current, size, keyword, customerId));
    }

    @GetMapping("/order/{id}")
    public Result<BizSo> orderDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        BizSo so = soService.detail(id);
        if (so != null && "PENDING".equals(so.getStatus()))
            so.setCanApprove(workflowService.canApprove("SO", so.getId()));
        return Result.ok(so);
    }

    @OpLog(module = "销售订单", action = "新增")
    @PostMapping("/order")
    public Result<BizSo> orderCreate(@RequestBody BizSo dto) {
        SecurityUtil.requireAnyRole(SO_EDIT_ROLES);
        return Result.ok(soService.create(dto));
    }

    @OpLog(module = "销售订单", action = "修改")
    @PutMapping("/order/{id}")
    public Result<?> orderUpdate(@PathVariable Long id, @RequestBody BizSo dto) {
        SecurityUtil.requireAnyRole(SO_EDIT_ROLES);
        soService.update(id, dto);
        return Result.ok();
    }

    @PostMapping("/order/{id}/submit")
    public Result<?> orderSubmit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SO_EDIT_ROLES);
        soService.submit(id);
        return Result.ok();
    }

    @PostMapping("/order/{id}/withdraw")
    public Result<?> orderWithdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SO_EDIT_ROLES);
        soService.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/order/{id}/approve")
    public Result<?> orderApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        soService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/order/{id}/reject")
    public Result<?> orderReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        soService.reject(id, dto.getComment());
        return Result.ok();
    }

    @OpLog(module = "销售订单", action = "删除")
    @DeleteMapping("/order/{id}")
    public Result<?> orderDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SO_EDIT_ROLES);
        soService.delete(id);
        return Result.ok();
    }

    /* ==================== ② 销售出库 ==================== */
    @GetMapping("/out/export")
    public org.springframework.http.ResponseEntity<byte[]> outExport(SalesReportFilter f) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        byte[] data = saleOutExportService.export(f.getStatus(), f.getKeyword(), f.getCustomerId(), f.getStartDate(), f.getEndDate());
        String name = java.net.URLEncoder.encode("销售出库-物料单据明细查询.xlsx", java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @GetMapping("/out")
    public Result<IPage<BizSaleOut>> outPage(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long customerId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        IPage<BizSaleOut> page = saleOutService.page(current, size, status, keyword, customerId, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(this::hideSaleOutCost);
        page.getRecords().forEach(o -> { if ("PENDING".equals(o.getStatus())) o.setCanApprove(workflowService.canApprove("SALE_OUT", o.getId())); });
        return Result.ok(page);
    }

    @GetMapping("/out/available-invoice")
    public Result<List<BizSaleOut>> outAvailableForInvoice(@RequestParam(required = false) String keyword) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        List<BizSaleOut> rows = saleOutService.availableForInvoice(keyword);
        rows.forEach(this::hideSaleOutCost);
        return Result.ok(rows);
    }

    @GetMapping("/out/{id}")
    public Result<BizSaleOut> outDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        BizSaleOut out = hideSaleOutCost(saleOutService.detail(id));
        if (out != null && "PENDING".equals(out.getStatus()))
            out.setCanApprove(workflowService.canApprove("SALE_OUT", out.getId()));
        return Result.ok(out);
    }

    @OpLog(module = "销售出库", action = "生成")
    @PostMapping("/out/from-so")
    public Result<BizSaleOut> outFromSo(@RequestBody SaleOutReq req) {
        SecurityUtil.requireAnyRole(OUT_EDIT_ROLES);
        return Result.ok(hideSaleOutCost(saleOutService.fromSo(req.getSoId(), req.getWarehouseId(), req.getLines())));
    }

    /** 与材料出库同一口径：非财务角色(如纯仓管)读取时成本金额置空，防止成本泄露。 */
    private BizSaleOut hideSaleOutCost(BizSaleOut out) {
        if (out == null || SecurityUtil.hasFinanceAccessOrAdmin()) return out;
        out.setTotalAmt(null);
        if (out.getLines() != null) out.getLines().forEach(l -> { l.setUnitPrice(null); l.setAmount(null); });
        return out;
    }

    private BizSaleReturn hideSaleReturnCost(BizSaleReturn r) {
        if (r == null || SecurityUtil.hasFinanceAccessOrAdmin()) return r;
        r.setTotalAmt(null);
        if (r.getLines() != null) r.getLines().forEach(l -> { l.setUnitPrice(null); l.setAmount(null); });
        return r;
    }

    @PostMapping("/out/{id}/submit")
    public Result<?> outSubmit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OUT_EDIT_ROLES);
        saleOutService.submit(id);
        return Result.ok();
    }

    @PostMapping("/out/{id}/withdraw")
    public Result<?> outWithdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OUT_EDIT_ROLES);
        saleOutService.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/out/{id}/approve")
    public Result<?> outApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleOutService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/out/{id}/reject")
    public Result<?> outReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleOutService.reject(id, dto.getComment());
        return Result.ok();
    }

    @OpLog(module = "销售出库", action = "删除")
    @DeleteMapping("/out/{id}")
    public Result<?> outDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(OUT_EDIT_ROLES);
        saleOutService.delete(id);
        return Result.ok();
    }

    /* ==================== ③ 销售发票 ==================== */
    @GetMapping("/invoice")
    public Result<IPage<BizSaleInvoice>> invoicePage(@RequestParam(defaultValue = "1") long current,
                                                     @RequestParam(defaultValue = "20") long size,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Long customerId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        IPage<BizSaleInvoice> page = saleInvoiceService.page(current, size, status, keyword, customerId, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(v -> { if ("PENDING".equals(v.getStatus())) v.setCanApprove(workflowService.canApprove("SALE_INV", v.getId())); });
        return Result.ok(page);
    }

    @GetMapping("/invoice/{id}")
    public Result<BizSaleInvoice> invoiceDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        BizSaleInvoice inv = saleInvoiceService.detail(id);
        if (inv != null && "PENDING".equals(inv.getStatus()))
            inv.setCanApprove(workflowService.canApprove("SALE_INV", inv.getId()));
        return Result.ok(inv);
    }

    @OpLog(module = "销售发票", action = "维护")
    @PostMapping("/invoice/from-out")
    public Result<BizSaleInvoice> invoiceFromOut(@RequestBody InvoiceReqDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(saleInvoiceService.fromSaleOut(new SaleInvoiceService.InvoiceReq(
                dto.getOutId(), dto.getInvoiceType(), dto.getInvoiceNo(), dto.getInvoiceDate(), dto.getTaxRate(), dto.getRemark())));
    }

    @OpLog(module = "销售发票", action = "修改")
    @PutMapping("/invoice/{id}")
    public Result<?> invoiceUpdate(@PathVariable Long id, @RequestBody InvoiceReqDTO dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        saleInvoiceService.update(id, new SaleInvoiceService.InvoiceReq(
                null, dto.getInvoiceType(), dto.getInvoiceNo(), dto.getInvoiceDate(), dto.getTaxRate(), dto.getRemark()));
        return Result.ok();
    }

    @PostMapping("/invoice/{id}/submit")
    public Result<?> invoiceSubmit(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        saleInvoiceService.submit(id);
        return Result.ok();
    }

    @PostMapping("/invoice/{id}/withdraw")
    public Result<?> invoiceWithdraw(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        saleInvoiceService.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/invoice/{id}/approve")
    public Result<?> invoiceApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleInvoiceService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/invoice/{id}/reject")
    public Result<?> invoiceReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleInvoiceService.reject(id, dto.getComment());
        return Result.ok();
    }

    @OpLog(module = "销售发票", action = "删除")
    @DeleteMapping("/invoice/{id}")
    public Result<?> invoiceDelete(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        saleInvoiceService.delete(id);
        return Result.ok();
    }

    /* ==================== ④ 收款登记 ==================== */
    @GetMapping("/receipt")
    public Result<List<BizReceipt>> receiptList(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long customerId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(receiptService.list(orgId, customerId, startDate, endDate));
    }

    /** 收款合计(全量,不受列表 500 笔上限影响),权限与收款列表相同 */
    @GetMapping("/receipt/summary")
    public Result<Map<String, Object>> receiptSummary(@RequestParam(required = false) Long orgId,
                                                     @RequestParam(required = false) Long customerId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(receiptService.summary(orgId, customerId, startDate, endDate));
    }

    @GetMapping("/receipt/ar-balance")
    public Result<List<Map<String, Object>>> arBalance(@RequestParam(required = false) Long orgId) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(receiptService.arBalance(orgId));
    }

    @OpLog(module = "收款登记", action = "登记")
    @PostMapping("/receipt")
    public Result<BizReceipt> receiptCreate(@RequestBody BizReceipt dto) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(receiptService.create(dto));
    }

    @OpLog(module = "收款登记", action = "删除")
    @DeleteMapping("/receipt/{id}")
    public Result<?> receiptDelete(@PathVariable Long id) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        receiptService.delete(id);
        return Result.ok();
    }

    /* ==================== ⑤ 销售退货 ==================== */
    @GetMapping("/return")
    public Result<IPage<BizSaleReturn>> returnPage(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "20") long size,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Long customerId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        IPage<BizSaleReturn> page = saleReturnService.page(current, size, status, keyword, customerId, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(this::hideSaleReturnCost);
        page.getRecords().forEach(r -> { if ("PENDING".equals(r.getStatus())) r.setCanApprove(workflowService.canApprove("SALE_RETURN", r.getId())); });
        return Result.ok(page);
    }

    @GetMapping("/return/{id}")
    public Result<BizSaleReturn> returnDetail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        BizSaleReturn r = hideSaleReturnCost(saleReturnService.detail(id));
        if (r != null && "PENDING".equals(r.getStatus()))
            r.setCanApprove(workflowService.canApprove("SALE_RETURN", r.getId()));
        return Result.ok(r);
    }

    @OpLog(module = "销售退货", action = "生成")
    @PostMapping("/return/from-out")
    public Result<BizSaleReturn> returnFromOut(@RequestBody ReturnReqDTO dto) {
        SecurityUtil.requireAnyRole(RETURN_EDIT_ROLES);
        return Result.ok(hideSaleReturnCost(saleReturnService.fromSaleOut(new SaleReturnService.ReturnReq(dto.getOutId(), dto.getLines(), dto.getRemark()))));
    }

    @PostMapping("/return/{id}/submit")
    public Result<?> returnSubmit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(RETURN_EDIT_ROLES);
        saleReturnService.submit(id);
        return Result.ok();
    }

    @PostMapping("/return/{id}/withdraw")
    public Result<?> returnWithdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(RETURN_EDIT_ROLES);
        saleReturnService.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/return/{id}/approve")
    public Result<?> returnApprove(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleReturnService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/return/{id}/reject")
    public Result<?> returnReject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        saleReturnService.reject(id, dto.getComment());
        return Result.ok();
    }

    @OpLog(module = "销售退货", action = "删除")
    @DeleteMapping("/return/{id}")
    public Result<?> returnDelete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(RETURN_EDIT_ROLES);
        saleReturnService.delete(id);
        return Result.ok();
    }

    /* ==================== 执行查询 ==================== */
    @GetMapping("/exec/order")
    public Result<List<Map<String, Object>>> orderExec(@RequestParam(defaultValue = "false") boolean onlyUnshipped,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        return Result.ok(salesQueryService.orderExecution(onlyUnshipped, keyword, startDate, endDate));
    }

    @GetMapping("/exec/out")
    public Result<List<Map<String, Object>>> outExec(@RequestParam(defaultValue = "false") boolean onlyUninvoiced,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireAnyRole(SALE_READ_ROLES);
        return Result.ok(salesQueryService.outExecution(onlyUninvoiced, keyword, startDate, endDate));
    }

    /* ==================== 明细查询报表（带成本/售价列，与菜单授权同口径：不含纯仓管） ==================== */
    private static final String[] SALE_REPORT_ROLES = {
            "purchaser", "material_accountant", "finance", "finance_manager", "account_admin", "admin"
    };

    @GetMapping("/report/order")
    public Result<List<Map<String, Object>>> reportOrder(SalesReportFilter f) {
        SecurityUtil.requireAnyRole(SALE_REPORT_ROLES);
        return Result.ok(salesReportService.orderQuery(f.toFilter()));
    }

    @GetMapping("/report/out")
    public Result<List<Map<String, Object>>> reportOut(SalesReportFilter f) {
        SecurityUtil.requireAnyRole(SALE_REPORT_ROLES);
        return Result.ok(salesReportService.outQuery(f.toFilter()));
    }

    @GetMapping("/report/invoice")
    public Result<List<Map<String, Object>>> reportInvoice(SalesReportFilter f) {
        SecurityUtil.requireAnyRole(SALE_REPORT_ROLES);
        return Result.ok(salesReportService.invoiceQuery(f.toFilter()));
    }

    @GetMapping("/report/return")
    public Result<List<Map<String, Object>>> reportReturn(SalesReportFilter f) {
        SecurityUtil.requireAnyRole(SALE_REPORT_ROLES);
        return Result.ok(salesReportService.returnQuery(f.toFilter()));
    }

    /** 销售收入成本明细表：发票收入 × 出库成本配对，按月汇总；带毛利，与销售明细查询同一授权口径 */
    @GetMapping("/report/income-cost")
    public Result<Map<String, Object>> reportIncomeCost(@RequestParam(required = false) Long companyId,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityUtil.requireAnyRole(SALE_REPORT_ROLES);
        return Result.ok(salesIncomeCostService.report(companyId, startDate, endDate));
    }

    /* ==================== DTO ==================== */
    @Data public static class SalesReportFilter {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate endDate;
        private Long customerId;
        private Long materialId;
        private String status;
        private String keyword;
        SalesReportService.Filter toFilter() {
            return new SalesReportService.Filter(startDate, endDate, customerId, materialId, status, keyword);
        }
    }

    @Data public static class ReturnReqDTO {
        private Long outId;
        private List<SaleReturnService.ReturnLineInput> lines;
        private String remark;
    }

    @Data public static class SaleOutReq {
        private Long soId;
        private Long warehouseId;
        private List<SaleOutService.OutLineInput> lines;
    }

    @Data public static class InvoiceReqDTO {
        private Long outId;
        private String invoiceType;
        private String invoiceNo;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate invoiceDate;
        private BigDecimal taxRate;
        private String remark;
    }
}
