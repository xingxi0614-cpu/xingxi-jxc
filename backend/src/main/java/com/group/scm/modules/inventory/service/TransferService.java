package com.group.scm.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.entity.BizTransfer;
import com.group.scm.modules.inventory.entity.BizTransferLine;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.mapper.BizTransferLineMapper;
import com.group.scm.modules.inventory.mapper.BizTransferMapper;
import com.group.scm.modules.master.entity.MdWarehouse;
import com.group.scm.modules.master.mapper.MdWarehouseMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final com.group.scm.modules.outbound.service.PickupService pickup;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final BizTransferMapper transferMapper;
    private final BizTransferLineMapper lineMapper;
    private final MdWarehouseMapper warehouseMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final PeriodCloseService periodCloseService;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final SubmitStockGuard stockGuard;

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BizTransfer create(BizTransfer dto) {
        if (dto.getSourceReqId()!=null) throw new BizException("请从申请单登记本次领料");
        BizTransfer transfer = buildTransfer(dto, new BizTransfer());
        transfer.setDocNo(docNoService.next("TRANSFER"));
        transfer.setStatus("DRAFT");
        transfer.setTotalQty(BigDecimal.ZERO);
        transfer.setTotalAmt(BigDecimal.ZERO);
        transfer.setCreatedBy(SecurityUtil.uid());
        transferMapper.insert(transfer);
        saveLines(transfer, dto.getLines());
        return detail(transfer.getId());
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BizTransfer update(Long id, BizTransfer dto) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        if (!"DRAFT".equals(transfer.getStatus()) && !"REJECTED".equals(transfer.getStatus())) {
            throw new BizException("当前转库单状态不允许修改");
        }
        if (transfer.getSourceReqId()!=null) throw new BizException("关联领料请取消本次记录后重新登记，不能改成其他仓库或申请");
        buildTransfer(dto, transfer);
        transfer.setStatus("DRAFT");
        transfer.setUpdatedBy(SecurityUtil.uid());
        transferMapper.updateById(transfer);
        lineMapper.delete(new QueryWrapper<BizTransferLine>().eq("transfer_id", id));
        saveLines(transfer, dto.getLines());
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void submit(Long id) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        if (!"DRAFT".equals(transfer.getStatus()) && !"REJECTED".equals(transfer.getStatus())) {
            throw new BizException("当前转库单状态不允许提交");
        }
        assertTransferPeriodOpen(transfer);
        requireLines(id);
        if(transfer.getSourceReqId()!=null) {
            pickup.activate(transfer.getSourceReqId(),false);
            pickup.assertNoClose(transfer.getSourceReqId());
            // 驳回后额度已释放，重新提交必须重新占用，不能挤占后续批次。
            var allowance=pickup.lines(transfer.getSourceReqId());
            for(var line:requireLines(id)) {
                var row=allowance.stream().filter(x->java.util.Objects.equals(((Number)x.get("id")).longValue(),line.getSourceReqLineId())).findFirst().orElseThrow(()->new BizException("申请物料已变化"));
                var available=com.group.scm.modules.outbound.service.PickupService.n(row.get("availableQty"));
                if("DRAFT".equals(transfer.getStatus())) available=available.add(line.getQty());
                if(line.getQty().compareTo(available)>0) throw new BizException("剩余可领数量已变化，请取消本次记录后重新登记");
            }
        }
        // 提交时就查转出仓库存（账面 − 其他待审批/待交接占用），见 SubmitStockGuard。建单时那道检查只看账面、且是建单那一刻的数
        stockGuard.assertAvailable(SubmitStockGuard.TRANSFER, id, transfer.getFromWarehouseId(),
                requireLines(id).stream().map(l -> new SubmitStockGuard.Need(l.getMaterialId(), l.getQty())).toList(), null);
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (transferMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizTransfer>()
                .eq("id", id).in("status", "DRAFT", "REJECTED")
                .set("status", "PENDING").set("updated_by", SecurityUtil.uid())) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("TRANSFER", id, transfer.getDocNo(), transfer.getTotalAmt());
    }

    /** 取消提交：提交人在转库单审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void withdraw(Long id) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        if (!"PENDING".equals(transfer.getStatus())) throw new BizException("只有待审批的转库单可以取消提交");
        assertTransferPeriodOpen(transfer);
        wfService.withdraw("TRANSFER", id);
        transfer.setStatus("DRAFT");
        transfer.setUpdatedBy(SecurityUtil.uid());
        transferMapper.updateById(transfer);
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void approve(Long id, String comment) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        assertTransferPeriodOpen(transfer);
        wfService.approve("TRANSFER", id, comment, (no, bizId) -> {
            if (transfer.getSourceReqId()==null) applyApprovedTransfer(bizId);
            else {
                if(jdbc.update("UPDATE biz_transfer SET status='READY' WHERE id=? AND status='PENDING'",bizId)!=1)
                    throw new BizException("单据已被处理，请刷新");
                notifService.pushTodoToUser(transfer.getCreatedBy(),"PICKUP","转库已批准，请核实实际交接："+transfer.getDocNo(),"TRANSFER",transfer.getDocNo(),bizId);
            }
        });
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reject(Long id, String comment) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        assertTransferPeriodOpen(transfer);
        wfService.reject("TRANSFER", id, comment, (no, bizId) -> {
            BizTransfer next = transferMapper.selectById(bizId);
            next.setStatus("REJECTED");
            next.setUpdatedBy(SecurityUtil.uid());
            transferMapper.updateById(next);
        });
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(Long id) {
        pickup.lockTransfer(id);
        BizTransfer transfer = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(transfer.getCreatedBy(), transfer.getStatus());
        if (!"DRAFT".equals(transfer.getStatus()) && !"REJECTED".equals(transfer.getStatus()) && !("CANCELLED".equals(transfer.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的转库单可以删除");
        }
        assertTransferPeriodOpen(transfer);
        lineMapper.delete(new QueryWrapper<BizTransferLine>().eq("transfer_id", id));
        transferMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("TRANSFER", id);
    }

    public BizTransfer detail(Long id) {
        BizTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) return null;
        assertTransferVisible(transfer);
        transfer.setLines(lineMapper.selectList(new QueryWrapper<BizTransferLine>()
                .eq("transfer_id", id)
                .orderByAsc("line_no")));
        return transfer;
    }

    public IPage<BizTransfer> page(long current, long size, String status, String keyword,
                                   Long fromWarehouseId, Long toWarehouseId,
                                   LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizTransfer> qw = new QueryWrapper<>();
        List<Long> visibleWarehouseIds = orgScope.visibleWarehouseIds();
        if (visibleWarehouseIds.isEmpty()) {
            qw.apply("1 = 0");
        } else {
            qw.and(w -> w.in("from_warehouse_id", visibleWarehouseIds)
                    .or()
                    .in("to_warehouse_id", visibleWarehouseIds));
        }
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) qw.in("doc_no", _docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword.trim());
        if (fromWarehouseId != null) {
            orgScope.assertWarehouseVisible(fromWarehouseId);
            qw.eq("from_warehouse_id", fromWarehouseId);
        }
        if (toWarehouseId != null) {
            orgScope.assertWarehouseVisible(toWarehouseId);
            qw.eq("to_warehouse_id", toWarehouseId);
        }
        if (startDate != null) qw.ge("transfer_date", startDate);
        if (endDate != null) qw.le("transfer_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizTransfer.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        return transferMapper.selectPage(new Page<>(current, size), qw);
    }

    /** 由申请生成本次转库并沿用原审批。申请锁覆盖检查、插入及提交整个事务。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BizTransfer fromRequest(Long reqId, PickupInput input) {
        SecurityUtil.requireRole("warehouse");
        var req=pickup.activate(reqId,input.legacyChecked);
        pickup.assertNoClose(reqId);
        if(input.lines==null || input.lines.isEmpty()) throw new BizException("请填写本次领取数量");
        BizTransfer dto=new BizTransfer();
        dto.setFromWarehouseId(req.getWarehouseId());dto.setToWarehouseId(input.toWarehouseId);
        dto.setTransferDate(LocalDate.now());dto.setTransferer(req.getMaterialApplicant());
        dto.setRemark("按申请分批领料："+req.getDocNo());
        BizTransfer transfer=buildTransfer(dto,new BizTransfer());
        if(!requireWarehouse(input.toWarehouseId,"转入仓库").getOrgId().equals(req.getOrgId())) throw new BizException("申请领料必须转入同一账套的仓库");
        transfer.setSourceReqId(reqId);transfer.setSourceReqNo(req.getDocNo());
        transfer.setDocNo(docNoService.next("TRANSFER"));transfer.setStatus("DRAFT");transfer.setCreatedBy(SecurityUtil.uid());
        var available=pickup.lines(reqId);var seen=new HashSet<Long>();var lines=new java.util.ArrayList<BizTransferLine>();
        for(PickupLine in:input.lines) {
            if(in.reqLineId==null || !seen.add(in.reqLineId)) throw new BizException("申请物料不能重复");
            var row=available.stream().filter(x->((Number)x.get("id")).longValue()==in.reqLineId).findFirst().orElseThrow(()->new BizException("物料不属于此申请"));
            if(in.qty==null || in.qty.signum()<=0 || in.qty.scale()>4 || in.qty.compareTo(com.group.scm.modules.outbound.service.PickupService.n(row.get("availableQty")))>0) throw new BizException("本次数量须大于零，且不能超过剩余可领取数量");
            BizTransferLine line=new BizTransferLine();line.setSourceReqLineId(in.reqLineId);line.setMaterialId(((Number)row.get("materialId")).longValue());line.setQty(in.qty);lines.add(line);
        }
        transferMapper.insert(transfer);saveLines(transfer,lines);submit(transfer.getId());
        pickup.event(reqId,"PICKUP","RECORDED","登记本次领料："+transfer.getDocNo(),null,null);
        return detail(transfer.getId());
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void confirmHandover(Long id,HandoverInput input) {
        SecurityUtil.requireRole("warehouse");pickup.lockTransfer(id);
        var transfer=requireExisting(id);assertTransferVisible(transfer);
        if(transfer.getSourceReqId()==null || !"READY".equals(transfer.getStatus())) throw new BizException("只有已批准待交接的关联领料可确认");
        if(input.receiver==null || input.receiver.isBlank() || input.receiver.trim().length()>100) throw new BizException("请填写实际接收人（100字以内）");
        pickup.assertNoClose(transfer.getSourceReqId());
        var lines=requireLines(id);
        if(input.lines==null || input.lines.size()!=lines.size()) throw new BizException("请逐项确认实际交接数量");
        var seen=new HashSet<Long>();BigDecimal total=BigDecimal.ZERO;
        for(PickupLine actual:input.lines) {
            if(actual.reqLineId==null || !seen.add(actual.reqLineId))throw new BizException("交接明细不能重复");
            var line=lines.stream().filter(l->actual.reqLineId.equals(l.getSourceReqLineId())).findFirst().orElseThrow(()->new BizException("交接物料不匹配"));
            if(actual.qty==null || actual.qty.signum()<0 || actual.qty.scale()>4 || actual.qty.compareTo(line.getQty())>0) throw new BizException("实际交接不能超过已批准数量");
            line.setQty(actual.qty);lineMapper.updateById(line);total=total.add(actual.qty);
        }
        if(total.signum()<=0) throw new BizException("本次没有交接材料，请取消本次领料");
        jdbc.update("DELETE FROM biz_transfer_line WHERE transfer_id=? AND qty=0",id);
        // 实际交接当日记账，不将审批日期冒充交接日期。
        transfer.setTransferDate(LocalDate.now());transfer.setTotalQty(total);transfer.setStatus("PENDING");
        transfer.setHandoverBy(SecurityUtil.uid());transfer.setHandoverAt(java.time.LocalDateTime.now());transfer.setHandoverReceiver(input.receiver.trim());
        transferMapper.updateById(transfer);applyApprovedTransfer(id);
        notifService.closeBusinessTodos("TRANSFER",id);
        pickup.posted(transfer.getSourceReqId(),id);
        pickup.event(transfer.getSourceReqId(),"HANDOVER","APPROVED","已交接："+transfer.getDocNo()+"；接收人："+input.receiver.trim(),null,null);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancelPickup(Long id,String reason) {
        SecurityUtil.requireRole("warehouse");pickup.lockTransfer(id);var transfer=requireExisting(id);assertTransferVisible(transfer);
        if(transfer.getSourceReqId()==null || !java.util.List.of("DRAFT","REJECTED","READY").contains(transfer.getStatus())) throw new BizException("请先撤回待审批记录；已经交接的记录不能直接取消");
        if(reason==null || reason.isBlank() || reason.length()>200)throw new BizException("请填写200字以内的取消原因");
        jdbc.update("UPDATE biz_transfer SET status='CANCELLED',updated_by=? WHERE id=?",SecurityUtil.uid(),id);
        notifService.closeBusinessTodos("TRANSFER",id);
        pickup.event(transfer.getSourceReqId(),"CANCEL_PICKUP","APPROVED",transfer.getDocNo()+"："+reason,null,null);
    }
    public static class PickupInput {public Long toWarehouseId;public boolean legacyChecked;public java.util.List<PickupLine> lines;}
    public static class PickupLine {public Long reqLineId;public BigDecimal qty;}
    public static class HandoverInput {public String receiver;public java.util.List<PickupLine> lines;}

    private BizTransfer buildTransfer(BizTransfer dto, BizTransfer transfer) {
        if (dto == null) throw new BizException("转库单不能为空");
        MdWarehouse from = requireWarehouse(dto.getFromWarehouseId(), "转出仓库");
        MdWarehouse to = requireWarehouse(dto.getToWarehouseId(), "转入仓库");
        if (from.getId().equals(to.getId())) throw new BizException("转出仓库和转入仓库不能相同");
        // 2026-10-15 存货按公司核算:转库只在同一公司内挪数量;公司之间调货是法人间买卖,走销售/采购
        if (!from.getOrgId().equals(to.getOrgId())) throw new BizException("转出仓库和转入仓库不属于同一公司，不能转库；公司之间调货请走销售/采购");
        LocalDate date = dto.getTransferDate() == null ? LocalDate.now() : dto.getTransferDate();
        periodCloseService.assertPeriodOpen(date, from.getOrgId(), "库存转库单", transfer.getDocNo());
        periodCloseService.assertPeriodOpen(date, to.getOrgId(), "库存转库单", transfer.getDocNo());

        transfer.setFromWarehouseId(from.getId());
        transfer.setToWarehouseId(to.getId());
        transfer.setOrgId(from.getOrgId());
        transfer.setTransferDate(date);
        transfer.setTransferer(clean(dto.getTransferer()));
        transfer.setRemark(dto.getRemark());
        return transfer;
    }

    private void saveLines(BizTransfer transfer, List<BizTransferLine> inputLines) {
        if (inputLines == null || inputLines.isEmpty()) throw new BizException("转库明细不能为空");
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        Set<Long> materialIds = new HashSet<>();
        int lineNo = 1;
        for (BizTransferLine input : inputLines) {
            if (input.getMaterialId() == null) continue;
            if (!materialIds.add(input.getMaterialId())) {
                throw new BizException("同一物料请合并为一条转库明细");
            }
            if (input.getQty() == null || input.getQty().signum() <= 0) throw new BizException("转库数量必须大于 0");
            orgScope.assertMaterialVisible(input.getMaterialId());
            InvBalance balance = waService.currentBalance(input.getMaterialId(), transfer.getFromWarehouseId());
            if (balance.getQty().compareTo(input.getQty()) < 0) {
                throw new BizException("转出仓库库存不足：" + com.group.scm.common.MaterialLabel.of(jdbc, input.getMaterialId())
                        + "，当前库存 " + com.group.scm.common.MaterialLabel.qty(balance.getQty()) + "，本次转库 " + com.group.scm.common.MaterialLabel.qty(input.getQty()));
            }
            BigDecimal amount = input.getQty().multiply(balance.getAvgPrice() == null ? BigDecimal.ZERO : balance.getAvgPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            BizTransferLine line = new BizTransferLine();
            line.setTransferId(transfer.getId());
            line.setLineNo(lineNo++);
            line.setMaterialId(input.getMaterialId());
            line.setQty(input.getQty());
            line.setSourceReqLineId(transfer.getSourceReqId()==null ? null : input.getSourceReqLineId());
            line.setUnitPrice(balance.getAvgPrice() == null ? BigDecimal.ZERO : balance.getAvgPrice());
            line.setAmount(amount);
            line.setRemark(input.getRemark());
            lineMapper.insert(line);
            totalQty = totalQty.add(line.getQty());
            totalAmt = totalAmt.add(amount);
        }
        if (totalQty.signum() <= 0) throw new BizException("请至少填写一条有效转库明细");
        transfer.setTotalQty(totalQty);
        transfer.setTotalAmt(totalAmt);
        transferMapper.updateById(transfer);
    }

    private void applyApprovedTransfer(Long transferId) {
        // 并发防线（业务层第二道）：过账只允许执行一次
        if (transferMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizTransfer>()
                .eq("id", transferId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
            throw new BizException("转库单已被处理，请刷新后重试");
        }
        BizTransfer transfer = requireExisting(transferId);
        List<BizTransferLine> lines = requireLines(transferId);
        assertTransferPeriodOpen(transfer);
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (BizTransferLine line : lines) {
            BigDecimal[] priceAndAmt = waService.transfer(line.getMaterialId(), transfer.getFromWarehouseId(),
                    transfer.getToWarehouseId(), line.getQty(), "TRANSFER", transfer.getId(), transfer.getDocNo(),
                    line.getId(), line.getRemark());
            line.setUnitPrice(priceAndAmt[0]);
            line.setAmount(priceAndAmt[1]);
            lineMapper.updateById(line);
            totalAmt = totalAmt.add(priceAndAmt[1]);
        }
        transfer.setTotalAmt(totalAmt);
        transfer.setStatus("APPROVED");
        transfer.setUpdatedBy(SecurityUtil.uid());
        transferMapper.updateById(transfer);
    }

    private List<BizTransferLine> requireLines(Long transferId) {
        List<BizTransferLine> lines = lineMapper.selectList(new QueryWrapper<BizTransferLine>()
                .eq("transfer_id", transferId)
                .orderByAsc("line_no"));
        if (lines == null || lines.isEmpty()) throw new BizException("转库明细不能为空");
        return lines;
    }

    private BizTransfer requireExisting(Long id) {
        BizTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) throw new BizException("转库单不存在");
        assertTransferVisible(transfer);
        return transfer;
    }

    private void assertTransferVisible(BizTransfer transfer) {
        if (!warehouseVisible(transfer.getFromWarehouseId()) && !warehouseVisible(transfer.getToWarehouseId())) {
            throw new BizException(4003, "无权查看该转库单");
        }
    }

    private boolean warehouseVisible(Long warehouseId) {
        try {
            orgScope.assertWarehouseVisible(warehouseId);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private void assertTransferPeriodOpen(BizTransfer transfer) {
        MdWarehouse from = requireWarehouse(transfer.getFromWarehouseId(), "转出仓库");
        MdWarehouse to = requireWarehouse(transfer.getToWarehouseId(), "转入仓库");
        periodCloseService.assertPeriodOpen(transfer.getTransferDate(), from.getOrgId(), "库存转库单", transfer.getDocNo());
        periodCloseService.assertPeriodOpen(transfer.getTransferDate(), to.getOrgId(), "库存转库单", transfer.getDocNo());
    }

    private MdWarehouse requireWarehouse(Long warehouseId, String label) {
        if (warehouseId == null) throw new BizException(label + "不能为空");
        orgScope.assertWarehouseVisible(warehouseId);
        MdWarehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
            throw new BizException(label + "不存在或已停用");
        }
        return warehouse;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
