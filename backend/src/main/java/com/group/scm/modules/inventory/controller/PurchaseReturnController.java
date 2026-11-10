package com.group.scm.modules.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.inventory.entity.BizPurchaseReturn;
import com.group.scm.modules.inventory.entity.BizPurchaseReturnLine;
import com.group.scm.modules.inventory.service.PurchaseReturnService;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 采购退货单。
 *
 * <p>权限门控与采购入库单同口径：写操作仅仓管/管理员；读可放宽到采购与财务
 * （他们要核对退货金额与红字发票）。金额对纯数量角色脱敏，与入库单一致。</p>
 */
@RestController
@RequestMapping("/inbound/purchase-return")
@RequiredArgsConstructor
public class PurchaseReturnController {

    private final PurchaseReturnService service;
    private final WorkflowService workflowService;

    /**
     * 回填「当前登录人是不是本节点审批人」。
     *
     * <p>前端的审批按钮判定是 {@code row.canApprove === true}，后端不回填就永远是 null，
     * 于是待审批的退货单在列表和详情里都看不到审批入口——2026-08-26 那张
     * ABC-CT-20260826-0001 就是这么卡住的。与 RecvController 同一写法，别各写各的。</p>
     */
    private void attachCanApprove(List<BizPurchaseReturn> records) {
        if (records == null) return;
        for (BizPurchaseReturn ret : records) {
            if (ret != null && "PENDING".equals(ret.getStatus())) {
                ret.setCanApprove(workflowService.canApprove("PRET", ret.getId()));
            }
        }
    }

    /** 某张入库单里还能退的行（含可退数量与当前库存），下推选行用 */
    @GetMapping("/returnable/{recvId}")
    public Result<List<BizPurchaseReturnLine>> returnable(@PathVariable Long recvId) {
        SecurityUtil.requireAnyRole("warehouse", "admin", "material_accountant", "finance");
        return Result.ok(hideCost(service.returnableLines(recvId)));
    }

    @PostMapping("/from-recv")
    public Result<BizPurchaseReturn> fromRecv(@RequestBody PushDTO dto) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        return Result.ok(hideCost(service.fromRecv(dto.recvId, dto.returnDate, dto.returner,
                dto.reason, dto.remark, dto.lines)));
    }

    @PostMapping("/{id}/submit")
    public Result<?> submit(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        service.submit(id);
        return Result.ok();
    }

    @PostMapping("/{id}/withdraw")
    public Result<?> withdraw(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        service.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        service.approve(id, dto.comment);
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        service.reject(id, dto.comment);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAnyRole("warehouse", "admin");
        service.delete(id);
        return Result.ok();
    }

    @GetMapping
    public Result<IPage<BizPurchaseReturn>> page(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long supplierId,
                                                 @RequestParam(required = false) Long warehouseId,
                                                 @RequestParam(required = false) String sourceRecvNo,
                                                 @RequestParam(required = false) Long materialId,
                                                 @RequestParam(required = false) String recvType,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        IPage<BizPurchaseReturn> page = service.page(current, size, status, keyword,
                supplierId, warehouseId, sourceRecvNo, materialId, recvType, startDate, endDate, sortField, sortOrder);
        page.getRecords().forEach(this::hideCost);
        attachCanApprove(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/{id}")
    public Result<BizPurchaseReturn> detail(@PathVariable Long id) {
        BizPurchaseReturn ret = hideCost(service.detail(id));
        if (ret != null && "PENDING".equals(ret.getStatus())) {
            ret.setCanApprove(workflowService.canApprove("PRET", ret.getId()));
        }
        return Result.ok(ret);
    }

    @Data public static class PushDTO {
        Long recvId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate;
        String returner;
        String reason;
        String remark;
        List<PurchaseReturnService.ReturnLineInput> lines;
    }
    @Data public static class ApproveDTO { String comment; }

    /** 仅仓管等纯数量角色脱敏；采购在采购订单上本就能看到价，与入库单同口径 */
    private BizPurchaseReturn hideCost(BizPurchaseReturn ret) {
        if (ret == null || SecurityUtil.hasFinanceAccessOrAdmin() || SecurityUtil.hasRole("purchaser")) return ret;
        ret.setTotalStockAmt(null);
        ret.setTotalRecvAmt(null);
        ret.setTotalDiffAmt(null);
        if (ret.getLines() != null) {
            ret.getLines().forEach(line -> {
                line.setRecvUnitPrice(null);
                line.setRecvAmt(null);
                line.setStockUnitPrice(null);
                line.setStockAmt(null);
                line.setDiffAmt(null);
            });
        }
        return ret;
    }

    private List<BizPurchaseReturnLine> hideCost(List<BizPurchaseReturnLine> lines) {
        if (lines == null || SecurityUtil.hasFinanceAccessOrAdmin() || SecurityUtil.hasRole("purchaser")) return lines;
        lines.forEach(line -> {
            line.setRecvUnitPrice(null);
            line.setRecvAmt(null);
            line.setStockUnitPrice(null);
            line.setStockAmt(null);
            line.setDiffAmt(null);
        });
        return lines;
    }
}
