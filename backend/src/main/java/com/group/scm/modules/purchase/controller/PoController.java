package com.group.scm.modules.purchase.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.purchase.entity.BizPo;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "采购订单")
@RestController
@RequestMapping("/purchase/po")
@RequiredArgsConstructor
public class PoController {

    private final PoService poService;
    private final WorkflowService workflowService;
    private final com.group.scm.modules.purchase.service.PoPriceCheckService priceCheckService;

    /** 给"待审批"单据标注当前用户是否可审批，前端据此显示审批/驳回按钮 */
    private void attachCanApprove(List<BizPo> records) {
        if (records == null) return;
        for (BizPo po : records) {
            if (po != null && "PENDING".equals(po.getStatus())) {
                po.setCanApprove(workflowService.canApprove("PO", po.getId()));
            }
        }
    }
    private static final String[] PO_ACCESS_ROLES = {
            "purchaser", "warehouse", "finance", "admin",
            "material_accountant", "finance_manager", "deputy_general_manager", "general_manager"
    };

    @GetMapping
    public Result<IPage<BizPo>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long supplierId,
                                      @RequestParam(required = false) Long warehouseId,
                                      @RequestParam(required = false) Long materialId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(PO_ACCESS_ROLES);
        IPage<BizPo> page = poService.page(current, size, status, keyword, supplierId, warehouseId, materialId, startDate, endDate, sortField, sortOrder);
        hideCostForWarehouse(page.getRecords());
        attachCanApprove(page.getRecords());
        return Result.ok(page);
    }

    /**
     * 历史参考含税单价（录单时提示"单价是不是填进了金额栏"）。只返回每个物料一个中位数，
     * 不含供应商/公司。只有"仓管且不是采购员"才返回空（成本脱敏）——
     * 采购员本来就是填价格的人；小公司一人兼采购+仓管很常见，若按"有仓管角色就屏蔽"，
     * 前端拿不到参考价不弹窗、后端却照拦，用户会卡在一条报错上（2026-09-29 E2E 实测发现）。
     */
    @GetMapping("/price-ref")
    public Result<java.util.Map<Long, com.group.scm.modules.purchase.service.PoPriceCheckService.PriceRef>> priceRef(
            @RequestParam List<Long> materialIds, @RequestParam(required = false) Long excludePoId) {
        SecurityUtil.requireAnyRole(PO_ACCESS_ROLES);
        if (SecurityUtil.hasRole("warehouse") && !SecurityUtil.hasRole("purchaser") && !SecurityUtil.isAdmin())
            return Result.ok(java.util.Map.of());
        return Result.ok(priceCheckService.refs(materialIds, excludePoId));
    }

    @PostMapping
    public Result<BizPo> create(@RequestBody BizPo dto) {
        SecurityUtil.requireAnyRole("purchaser");
        return Result.ok(poService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody BizPo dto) {
        SecurityUtil.requireAnyRole("purchaser", "admin");
        poService.update(id, dto);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<BizPo> detail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(PO_ACCESS_ROLES);
        BizPo po = poService.detail(id);
        hideCostForWarehouse(po);
        if (po != null && "PENDING".equals(po.getStatus())) {
            po.setCanApprove(workflowService.canApprove("PO", po.getId()));
        }
        return Result.ok(po);
    }

    @PostMapping("/{id}/submit")
    public Result<?> submit(@PathVariable Long id) { SecurityUtil.requireAnyRole("purchaser"); poService.submit(id); return Result.ok(); }

    @PostMapping("/{id}/withdraw")
    public Result<?> withdraw(@PathVariable Long id) { SecurityUtil.requireAnyRole("purchaser", "admin"); poService.withdraw(id); return Result.ok(); }

    @OpLog(module = "采购订单", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) { SecurityUtil.requireAnyRole("purchaser", "admin"); poService.delete(id); return Result.ok(); }

    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        poService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        poService.reject(id, dto.getComment());
        return Result.ok();
    }

    /** 税率订正（仅未入库订单）：财务/管理员可改税率，系统重算不含税成本 */
    @PostMapping("/{id}/correct-tax")
    public Result<BizPo> correctTax(@PathVariable Long id, @RequestBody java.util.List<PoService.TaxFix> fixes) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        return Result.ok(poService.correctTaxRates(id, fixes));
    }

    @lombok.Data public static class ApproveDTO { String comment; }

    private void hideCostForWarehouse(java.util.List<BizPo> rows) {
        if (rows == null) return;
        rows.forEach(this::hideCostForWarehouse);
    }

    private void hideCostForWarehouse(BizPo po) {
        if (po == null || !SecurityUtil.hasRole("warehouse")) return;
        po.setTotalAmt(null);
        po.setTaxAmt(null);
        if (po.getLines() == null) return;
        po.getLines().forEach(line -> {
            line.setUnitPrice(null);
            line.setTaxRate(null);
            line.setAmount(null);
            line.setAmountExclTax(null);   // 不含税金额同属成本，仓库角色一并屏蔽（含税/不含税切片新增字段）
            line.setTaxAmount(null);
            line.setPriceCheckRef(null);   // 参考价与理由里都是价格信息，同属成本
            line.setPriceCheckNote(null);
        });
    }
}
