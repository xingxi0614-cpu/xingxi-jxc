package com.group.scm.modules.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.inventory.entity.BizRecv;
import com.group.scm.modules.inventory.service.RecvService;
import com.group.scm.modules.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "采购入库")
@RestController
@RequestMapping("/inbound/recv")
@RequiredArgsConstructor
public class RecvController {

    private final RecvService recvService;
    private final WorkflowService workflowService;
    private static final String[] RECV_ACCESS_ROLES = {
            "warehouse", "material_accountant", "finance", "admin", "tech_manager",
            "purchaser" // 采购：仅查看+打印采购入库单（写操作 fromPo/submit/approve/delete 仍各自限定 warehouse/admin 等，采购无权）
    };

    /** 给"待审批"单据标注当前用户是否可审批，前端据此显示审批/驳回按钮 */
    private void attachCanApprove(List<BizRecv> records) {
        if (records == null) return;
        for (BizRecv recv : records) {
            if (recv != null && "PENDING".equals(recv.getStatus())) {
                recv.setCanApprove(workflowService.canApprove("RECV", recv.getId()));
            }
        }
    }

    @GetMapping
    public Result<IPage<BizRecv>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String recvType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(required = false) Long warehouseId,
                                        @RequestParam(required = false) String sourcePoNo,
                                        @RequestParam(required = false) Long materialId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                              @RequestParam(required = false) String sortField,
                                              @RequestParam(required = false) String sortOrder) {
        SecurityUtil.requireAnyRole(RECV_ACCESS_ROLES);
        IPage<BizRecv> page = recvService.page(current, size, status, recvType, keyword, supplierId, warehouseId, sourcePoNo, materialId, startDate, endDate, sortField, sortOrder);
        hideCostForQuantityOnlyRoles(page.getRecords());
        attachCanApprove(page.getRecords());
        return Result.ok(page);
    }

    @GetMapping("/{id}")
    public Result<BizRecv> detail(@PathVariable Long id) {
        SecurityUtil.requireAnyRole(RECV_ACCESS_ROLES);
        BizRecv recv = hideCostForQuantityOnlyRoles(recvService.detail(id));
        if (recv != null && "PENDING".equals(recv.getStatus())) {
            recv.setCanApprove(workflowService.canApprove("RECV", recv.getId()));
        }
        return Result.ok(recv);
    }

    @PostMapping("/from-po")
    public Result<BizRecv> fromPo(@RequestBody PushDTO dto) {
        SecurityUtil.requireAnyRole("warehouse");
        return Result.ok(hideCostForQuantityOnlyRoles(recvService.fromPo(dto.getPoId(), dto.getRecvType(), dto.getLines(), dto.getRemark())));
    }

    @PostMapping("/{id}/submit")
    public Result<?> submit(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse"); recvService.submit(id); return Result.ok(); }

    @PostMapping("/{id}/withdraw")
    public Result<?> withdraw(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse", "admin"); recvService.withdraw(id); return Result.ok(); }

    @OpLog(module = "采购入库单", action = "删除")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) { SecurityUtil.requireAnyRole("warehouse", "admin"); recvService.delete(id); return Result.ok(); }

    @PostMapping("/{id}/approve")
    public Result<?> approve(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        recvService.approve(id, dto.getComment());
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    public Result<?> reject(@PathVariable Long id, @RequestBody ApproveDTO dto) {
        recvService.reject(id, dto.getComment());
        return Result.ok();
    }

    @GetMapping("/estimates-for-invoicing")
    public Result<List<BizRecv>> estimatesForInvoicing(@RequestParam(required = false) Long supplierId) {
        SecurityUtil.requireFinanceAccess();
        return Result.ok(recvService.listEstimateForInvoicing(supplierId));
    }

    @Data public static class PushDTO    { Long poId; String recvType; String remark; List<RecvService.RecvLineInput> lines; }
    @Data public static class ApproveDTO { String comment; }

    private void hideCostForQuantityOnlyRoles(List<BizRecv> rows) {
        if (rows == null) return;
        rows.forEach(this::hideCostForQuantityOnlyRoles);
    }

    private BizRecv hideCostForQuantityOnlyRoles(BizRecv recv) {
        // 采购在采购订单上本就能看到不含税/含税价，入库为其下游同价，故采购也可见金额；仅仓管等纯数量角色脱敏
        if (recv == null || SecurityUtil.hasFinanceAccessOrAdmin() || SecurityUtil.hasRole("purchaser")) return recv;
        recv.setTotalAmt(null);
        recv.setTaxAmt(null);
        recv.setTotalAmtInclTax(null);
        if (recv.getLines() != null) {
            recv.getLines().forEach(line -> {
                line.setUnitPrice(null);
                line.setAmount(null);
                line.setTaxRate(null);
                line.setTaxAmount(null);
                line.setAmountInclTax(null);
            });
        }
        return recv;
    }
}
