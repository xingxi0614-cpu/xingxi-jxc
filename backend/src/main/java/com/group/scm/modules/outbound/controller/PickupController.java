package com.group.scm.modules.outbound.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.outbound.service.PickupService;
import com.group.scm.modules.inventory.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PickupController {
    private final PickupService pickup;
    private final TransferService transfers;
    private void viewAccess(){SecurityUtil.requireAnyRole("warehouse","purchaser","field_manager","service_staff","material_accountant","finance","engineering_leader","operation_leader","market_leader","area_manager","branch_manager","tech_manager","operation_repair","admin","account_admin");}
    @GetMapping("/outbound/oreq/{id}/pickup")
    public Result<?> detail(@PathVariable Long id){viewAccess();return Result.ok(pickup.detail(id));}
    @PostMapping("/outbound/oreq/{id}/pickup")
    @OpLog(module="分批领料",action="登记本次领料")
    public Result<?> create(@PathVariable Long id,@RequestBody TransferService.PickupInput input){var t=transfers.fromRequest(id,input);return Result.ok(java.util.Map.of("id",t.getId(),"docNo",t.getDocNo(),"status",t.getStatus()));}
    @PostMapping("/outbound/oreq/{id}/pickup/extend")
    @OpLog(module="分批领料",action="确认继续领取")
    public Result<?> extend(@PathVariable Long id,@RequestBody Decision input){viewAccess();pickup.extend(id,input.dueDate,input.reason,input.legacyChecked);return Result.ok();}
    @PostMapping("/outbound/oreq/{id}/pickup/close")
    @OpLog(module="分批领料",action="申请剩余不领")
    public Result<?> close(@PathVariable Long id,@RequestBody Decision input){viewAccess();pickup.requestClose(id,input.lineIds,input.reason,input.legacyChecked);return Result.ok();}
    @PostMapping("/outbound/oreq/{id}/pickup/close/{eventId}")
    @OpLog(module="分批领料",action="核实剩余不领")
    public Result<?> review(@PathVariable Long id,@PathVariable Long eventId,@RequestBody Decision input){pickup.reviewClose(id,eventId,input.approve,input.reason);return Result.ok();}
    @PostMapping("/inventory/transfer/{id}/handover")
    @OpLog(module="分批领料",action="确认实际交接")
    public Result<?> handover(@PathVariable Long id,@RequestBody TransferService.HandoverInput input){transfers.confirmHandover(id,input);return Result.ok();}
    @PostMapping("/inventory/transfer/{id}/cancel-pickup")
    @OpLog(module="分批领料",action="取消未交接领料")
    public Result<?> cancel(@PathVariable Long id,@RequestBody Decision input){transfers.cancelPickup(id,input.reason);return Result.ok();}
    public static class Decision {public LocalDate dueDate;public String reason;public boolean legacyChecked;public List<Long> lineIds;public boolean approve;}
}
