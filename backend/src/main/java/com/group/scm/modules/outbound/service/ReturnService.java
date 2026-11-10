package com.group.scm.modules.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.outbound.entity.BizOut;
import com.group.scm.modules.outbound.entity.BizOutLine;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import com.group.scm.modules.outbound.entity.BizReturn;
import com.group.scm.modules.outbound.entity.BizReturnLine;
import com.group.scm.modules.outbound.mapper.BizOutLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqMapper;
import com.group.scm.modules.outbound.mapper.BizReturnLineMapper;
import com.group.scm.modules.outbound.mapper.BizReturnMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ReturnService {

    private final BizReturnMapper returnMapper;
    private final BizReturnLineMapper returnLineMapper;
    private final BizOutMapper outMapper;
    private final BizOutLineMapper outLineMapper;
    private final BizOutReqMapper reqMapper;
    private final BizOutReqLineMapper reqLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final DocRelationService relationService;
    private final PeriodCloseService periodCloseService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final com.group.scm.modules.inventory.service.InvLedgerSupport ledger;
    private final OutReqIntegrity integrity;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BizReturn fromOut(BizReturn dto) {
        if (dto.getSourceOutId() == null) throw new BizException("请选择原材料出库单");
        integrity.lockSourceForOut(dto.getSourceOutId());
        BizOut out = requireApprovedOut(dto.getSourceOutId());
        lockSourceOutsApproved(List.of(out.getId()));
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("退料明细不能为空");
        LocalDate returnDate = dto.getReturnDate() == null ? LocalDate.now() : dto.getReturnDate();
        periodCloseService.assertPeriodOpen(returnDate, out.getOrgId(), "退料单", null);
        Long returnWarehouseId = dto.getWarehouseId() == null ? out.getWarehouseId() : dto.getWarehouseId();
        orgScope.assertWarehouseVisible(returnWarehouseId);
        assertSameCompany(returnWarehouseId, out.getWarehouseId());

        BizReturn ret = new BizReturn();
        ret.setDocNo(docNoService.next("RETURN"));
        ret.setSourceOutId(out.getId());
        ret.setSourceOutNo(out.getDocNo());
        ret.setSourceReqId(out.getSourceReqId());
        ret.setSourceReqNo(out.getSourceReqNo());
        ret.setWarehouseId(returnWarehouseId);
        ret.setDeptId(out.getDeptId());
        ret.setOrgId(out.getOrgId());
        ret.setProjectId(out.getProjectId());
        ret.setProjectName(out.getProjectName());
        ret.setConstructionUnit(out.getConstructionUnit());
        ret.setOutType(out.getOutType());
        ret.setBusinessCategory(out.getBusinessCategory());
        ret.setReturner(dto.getReturner() == null || dto.getReturner().isBlank() ? out.getMaterialReceiver() : dto.getReturner());
        ret.setReturnDate(returnDate);
        ret.setStatus("DRAFT");
        ret.setRemark(dto.getRemark());
        ret.setTotalQty(BigDecimal.ZERO);
        ret.setTotalAmt(BigDecimal.ZERO);
        returnMapper.insert(ret);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int i = 1;
        for (BizReturnLine input : dto.getLines()) {
            BizReturnLine line = buildReturnLine(out, input);
            line.setReturnId(ret.getId());
            line.setLineNo(i++);
            returnLineMapper.insert(line);
            totalQty = totalQty.add(line.getQty());
            totalAmt = totalAmt.add(line.getAmount());
        }
        ret.setTotalQty(totalQty);
        ret.setTotalAmt(totalAmt);
        ret.setReissue(checkReissue(dto.getReissue(), out.getSourceReqId(), ret.getId()));
        returnMapper.updateById(ret);
        return ret;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BizReturn fromOutReq(BizReturn dto) {
        if (dto.getSourceReqId() == null) throw new BizException("请选择原出库申请");
        BizOutReq req = requireReturnableReq(dto.getSourceReqId());
        lockSourceOutsApproved(outMapper.selectList(new QueryWrapper<BizOut>().select("id")
                .eq("source_req_id", req.getId()).eq("status", "APPROVED")).stream().map(BizOut::getId).toList());
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("退料明细不能为空");
        LocalDate returnDate = dto.getReturnDate() == null ? LocalDate.now() : dto.getReturnDate();
        periodCloseService.assertPeriodOpen(returnDate, req.getOrgId(), "退料单", null);
        Long returnWarehouseId = dto.getWarehouseId() == null ? req.getWarehouseId() : dto.getWarehouseId();
        orgScope.assertWarehouseVisible(returnWarehouseId);
        assertSameCompany(returnWarehouseId, req.getWarehouseId());

        BizReturn ret = new BizReturn();
        ret.setDocNo(docNoService.next("RETURN"));
        ret.setSourceReqId(req.getId());
        ret.setSourceReqNo(req.getDocNo());
        ret.setWarehouseId(returnWarehouseId);
        ret.setDeptId(req.getDeptId());
        ret.setOrgId(req.getOrgId());
        ret.setProjectId(req.getProjectId());
        ret.setProjectName(req.getProjectName());
        ret.setConstructionUnit(req.getConstructionUnit());
        ret.setOutType(req.getOutType());
        ret.setBusinessCategory(req.getBusinessCategory());
        ret.setReturner(dto.getReturner() == null || dto.getReturner().isBlank() ? req.getMaterialApplicant() : dto.getReturner());
        ret.setReturnDate(returnDate);
        ret.setStatus("DRAFT");
        ret.setRemark(dto.getRemark());
        ret.setTotalQty(BigDecimal.ZERO);
        ret.setTotalAmt(BigDecimal.ZERO);
        returnMapper.insert(ret);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int lineNo = 1;
        for (BizReturnLine input : dto.getLines()) {
            for (BizReturnLine line : buildReturnLinesFromReq(req, input)) {
                line.setReturnId(ret.getId());
                line.setLineNo(lineNo++);
                returnLineMapper.insert(line);
                totalQty = totalQty.add(line.getQty());
                totalAmt = totalAmt.add(line.getAmount());
            }
        }
        if (totalQty.signum() <= 0) throw new BizException("退料数量必须大于 0");
        ret.setTotalQty(totalQty);
        ret.setTotalAmt(totalAmt);
        ret.setReissue(checkReissue(dto.getReissue(), req.getId(), ret.getId()));
        returnMapper.updateById(ret);
        return ret;
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizReturn ret = requireExisting(id);
        if (!"DRAFT".equals(ret.getStatus()) && !"REJECTED".equals(ret.getStatus())) {
            throw new BizException("当前退料单状态不允许提交");
        }
        lockSourceOutsApproved(sourceOutIdsOf(id)); // 驳回后重提:原出库单可能已被取消审批
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "退料单", ret.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (returnMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizReturn>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("RETURN", id, ret.getDocNo(), BigDecimal.ZERO);
    }

    /** 取消提交：提交人在退料单审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizReturn ret = requireExisting(id);
        if (!"PENDING".equals(ret.getStatus())) throw new BizException("只有待审批的退料单可以取消提交");
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "退料单", ret.getDocNo());
        wfService.withdraw("RETURN", id);
        ret.setStatus("DRAFT");
        returnMapper.updateById(ret);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizReturn ret = requireExisting(id);
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "退料单", ret.getDocNo());
        lockSourceOutsApproved(sourceOutIdsOf(id)); // 过账前最后一道:原出库单必须仍是已审批
        wfService.approve("RETURN", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (returnMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizReturn>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizReturn next = returnMapper.selectById(bizId);
            // 兜底:修复前建的草稿也可能退进别家公司仓库,审批过账前再拦一次
            Long sourceWh = next.getSourceOutId() != null
                    ? outMapper.selectById(next.getSourceOutId()).getWarehouseId()
                    : reqMapper.selectById(next.getSourceReqId()).getWarehouseId();
            assertSameCompany(next.getWarehouseId(), sourceWh);
            List<BizReturnLine> lines = returnLineMapper.selectList(
                    new QueryWrapper<BizReturnLine>().eq("return_id", bizId).orderByAsc("line_no"));
            BigDecimal totalAmt = BigDecimal.ZERO;
            for (BizReturnLine line : lines) {
                // 退料必须按原出库价回库；单价缺失时拒绝过账（按 0 元入库会稀释移动加权均价）
                if (line.getUnitPrice() == null) {
                    throw new BizException("退料行缺少单价（应为原出库单价），无法过账，请检查退料单明细");
                }
                BizOutLine source = outLineMapper.selectOne(new QueryWrapper<BizOutLine>()
                        .eq("id", line.getSourceOutLineId()).last("FOR UPDATE"));
                if (source == null || source.getAmount() == null) throw new BizException("原出库成本记录缺失");
                var posted = returnLineMapper.postedReturnCost(source.getId());
                BigDecimal usedQty = (BigDecimal) posted.get("qty");
                BigDecimal usedAmt = (BigDecimal) posted.get("amount");
                BigDecimal exact = com.group.scm.common.CostAllocation.remaining(source.getQty(), source.getAmount(),
                        usedQty, usedAmt, line.getQty());
                line.setAmount(exact);
                returnLineMapper.updateById(line);
                waService.inbound(line.getMaterialId(), next.getWarehouseId(), line.getQty(),
                        line.getUnitPrice(), exact,
                        "RETURN", "RETURN", next.getId(), next.getDocNo(), line.getId(), "退料入库");
                totalAmt = totalAmt.add(line.getAmount() == null ? BigDecimal.ZERO : line.getAmount());
            }
            next.setTotalAmt(totalAmt);
            next.setStatus("APPROVED");
            returnMapper.updateById(next);
            if (Integer.valueOf(1).equals(next.getReissue())) releaseForReissue(next, lines);
            if (next.getSourceOutId() != null) {
                relationService.addRelation("OUT", next.getSourceOutId(), next.getSourceOutNo(),
                        "RETURN", next.getId(), next.getDocNo(),
                        "RETURN", next.getTotalQty(), totalAmt);
            } else {
                relationService.addRelation("OREQ", next.getSourceReqId(), next.getSourceReqNo(),
                        "RETURN", next.getId(), next.getDocNo(),
                        "RETURN", next.getTotalQty(), totalAmt);
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizReturn ret = requireExisting(id);
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "退料单", ret.getDocNo());
        wfService.reject("RETURN", id, comment, (no, bizId) -> {
            BizReturn next = returnMapper.selectById(bizId);
            next.setStatus("REJECTED");
            returnMapper.updateById(next);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizReturn ret = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(ret.getCreatedBy(), ret.getStatus());
        if (!"DRAFT".equals(ret.getStatus()) && !"REJECTED".equals(ret.getStatus()) && !("CANCELLED".equals(ret.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的退料单可以删除");
        }
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "退料单", ret.getDocNo());
        returnLineMapper.delete(new QueryWrapper<BizReturnLine>().eq("return_id", id));
        returnMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("RETURN", id);
    }

    public BizReturn detail(Long id) {
        BizReturn ret = returnMapper.selectById(id);
        if (ret == null) return null;
        orgScope.assertOrgVisible(ret.getOrgId());
        List<BizReturnLine> lines = returnLineMapper.selectList(
                new QueryWrapper<BizReturnLine>().eq("return_id", id).orderByAsc("line_no"));
        ret.setLines(lines);
        return ret;
    }

    public IPage<BizReturn> page(long current, long size, String status, String keyword, Long warehouseId,
                                 String sourceOutNo, String sourceReqNo, Long projectId, String constructionUnit, String outType,
                                 String businessCategory, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizReturn> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) qw.in("doc_no", _docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        if (sourceOutNo != null && !sourceOutNo.isBlank()) qw.like("source_out_no", sourceOutNo);
        if (sourceReqNo != null && !sourceReqNo.isBlank()) qw.like("source_req_no", sourceReqNo);
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (projectId != null) qw.eq("project_id", projectId);
        if (constructionUnit != null && !constructionUnit.isBlank()) qw.like("construction_unit", constructionUnit.trim());
        if (outType != null && !outType.isBlank()) qw.eq("out_type", outType);
        if (businessCategory != null && !businessCategory.isBlank()) qw.eq("business_category", businessCategory);
        if (startDate != null) qw.ge("return_date", startDate);
        if (endDate != null) qw.le("return_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizReturn.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        return returnMapper.selectPage(new Page<>(current, size), qw);
    }

    private BizReturnLine buildReturnLine(BizOut out, BizReturnLine input) {
        if (input.getSourceOutLineId() == null) throw new BizException("退料行必须关联原出库明细");
        if (input.getQty() == null || input.getQty().signum() <= 0) throw new BizException("退料数量必须大于 0");
        BizOutLine outLine = outLineMapper.selectById(input.getSourceOutLineId());
        if (outLine == null || !out.getId().equals(outLine.getOutId())) {
            throw new BizException("退料行不属于当前原出库单");
        }
        BigDecimal returned = returnLineMapper.sumActiveReturnQtyByOutLine(outLine.getId());
        if (returned == null) returned = BigDecimal.ZERO;
        BigDecimal remain = outLine.getQty().subtract(returned);
        if (input.getQty().compareTo(remain) > 0) {
            throw new BizException("退料数量超过原出库剩余可退数量，剩余：" + remain);
        }
        BigDecimal price = outLine.getUnitPrice() == null ? BigDecimal.ZERO : outLine.getUnitPrice();
        BizReturnLine line = new BizReturnLine();
        line.setSourceOutLineId(outLine.getId());
        line.setSourceReqLineId(outLine.getSourceReqLineId());
        line.setMaterialId(outLine.getMaterialId());
        line.setQty(input.getQty());
        line.setUnitPrice(price);
        line.setAmount(outLine.getAmount().multiply(input.getQty()).divide(outLine.getQty(), 2, RoundingMode.HALF_UP));
        line.setRemark(input.getRemark());
        return line;
    }

    private List<BizReturnLine> buildReturnLinesFromReq(BizOutReq req, BizReturnLine input) {
        if (input.getSourceReqLineId() == null) throw new BizException("退料行必须关联原出库申请明细");
        if (input.getQty() == null || input.getQty().signum() <= 0) throw new BizException("退料数量必须大于 0");
        BizOutReqLine reqLine = reqLineMapper.selectById(input.getSourceReqLineId());
        if (reqLine == null || !req.getId().equals(reqLine.getOutReqId())) {
            throw new BizException("退料行不属于当前原出库申请");
        }
        BigDecimal approvedOut = outLineMapper.sumApprovedOutQtyByReqLine(reqLine.getId());
        if (approvedOut == null) approvedOut = BigDecimal.ZERO;
        BigDecimal returned = returnLineMapper.sumActiveReturnQtyByReqLine(reqLine.getId());
        if (returned == null) returned = BigDecimal.ZERO;
        BigDecimal remain = approvedOut.subtract(returned);
        if (input.getQty().compareTo(remain) > 0) {
            throw new BizException("退料数量超过出库申请已出库未退数量，剩余：" + remain);
        }

        BigDecimal need = input.getQty();
        List<BizReturnLine> lines = new ArrayList<>();
        for (BizOutLine outLine : outLineMapper.findApprovedLinesByReqLine(reqLine.getId())) {
            if (need.signum() <= 0) break;
            BigDecimal lineReturned = returnLineMapper.sumActiveReturnQtyByOutLine(outLine.getId());
            if (lineReturned == null) lineReturned = BigDecimal.ZERO;
            BigDecimal lineRemain = outLine.getQty().subtract(lineReturned);
            if (lineRemain.signum() <= 0) continue;
            BigDecimal qty = need.min(lineRemain);
            BigDecimal price = outLine.getUnitPrice() == null ? BigDecimal.ZERO : outLine.getUnitPrice();
            BizReturnLine line = new BizReturnLine();
            line.setSourceOutLineId(outLine.getId());
            line.setSourceReqLineId(reqLine.getId());
            line.setMaterialId(outLine.getMaterialId());
            line.setQty(qty);
            line.setUnitPrice(price);
            line.setAmount(outLine.getAmount().multiply(qty).divide(outLine.getQty(), 2, RoundingMode.HALF_UP));
            line.setRemark(input.getRemark());
            lines.add(line);
            need = need.subtract(qty);
        }
        if (need.signum() > 0) throw new BizException("退料数量超过原出库剩余可退数量，剩余不足：" + need);
        return lines;
    }

    /**
     * "退回后还要再领"(2026-10-15 定,默认不再领 = 以前的行为)。
     * 只有直接出库/旧模式的出库申请能选;转库领料模式另有一套余量算法(transferred/closed),不支持。
     */
    private Integer checkReissue(Integer reissue, Long reqId, Long returnId) {
        if (!Integer.valueOf(1).equals(reissue)) return 0;
        if (reqId == null) throw new BizException("原出库单没有关联出库申请，不能选择「退回后还要再领」");
        BizOutReq req = reqMapper.selectById(reqId);
        if (req == null) throw new BizException("原出库申请不存在");
        if ("TRANSFER".equals(req.getFulfillmentMode())) {
            throw new BizException("转库领料方式的出库申请不支持「退回后还要再领」，请重新提申请");
        }
        Long missing = returnLineMapper.selectCount(new QueryWrapper<BizReturnLine>()
                .eq("return_id", returnId).isNull("source_req_line_id"));
        if (missing != null && missing > 0) throw new BizException("有退料行没有关联出库申请明细，不能选择「退回后还要再领」");
        return 1;
    }

    /**
     * 审批通过且选了"再领":把退回数量让回原申请(out_qty 减、reissue_qty 加),申请状态随之回到"部分出库"。
     * 并发防线:每行条件更新 out_qty − 数量 ≥ 0,不满足影响 0 行即整单回滚(照抄全站 CAS 写法)。
     */
    private void releaseForReissue(BizReturn ret, List<BizReturnLine> lines) {
        BizOutReq req = reqMapper.selectById(ret.getSourceReqId());
        if (req == null || !List.of("APPROVED", "PARTIAL_OUT", "OUT").contains(req.getStatus())) {
            throw new BizException("原出库申请已关闭或作废，不能按「退回后还要再领」回补，请改为不再领后重新建退料单");
        }
        for (BizReturnLine line : lines) {
            if (reqLineMapper.releaseForReissue(line.getSourceReqLineId(), line.getQty()) != 1) {
                throw new BizException("原出库申请的已出数量已变化（可能被并发处理），请刷新后重试");
            }
        }
        reqMapper.recomputeProgress(req.getId());
    }

    /**
     * 退回仓库必须与原出库(或原申请)仓库同属一家公司(代码审查发现)。
     * 存货按公司核算后,退进别家公司的仓库 = 把 A 公司领出的成本退进 B 公司的账,单据和报表却仍算 A 公司;
     * 能看到多家公司的账号(如系统管理员)才可能选到。比较的是仓库所属公司,单据上的组织可能是部门。
     */
    private void assertSameCompany(Long returnWarehouseId, Long sourceWarehouseId) {
        if (sourceWarehouseId == null) return;
        if (!ledger.orgOf(returnWarehouseId).equals(ledger.orgOf(sourceWarehouseId))) {
            throw new BizException("退回仓库必须是原出库单所在公司的仓库；公司之间调货请走销售/采购");
        }
    }

    /** 退料单各行对应的原出库单(按出库申请退料时可能跨多张出库单)。 */
    private List<Long> sourceOutIdsOf(Long returnId) {
        List<Long> outLineIds = returnLineMapper.selectList(new QueryWrapper<BizReturnLine>()
                        .select("source_out_line_id").eq("return_id", returnId))
                .stream().map(BizReturnLine::getSourceOutLineId).filter(Objects::nonNull).distinct().toList();
        if (outLineIds.isEmpty()) return List.of();
        return outLineMapper.selectBatchIds(outLineIds).stream().map(BizOutLine::getOutId).distinct().toList();
    }

    /**
     * 锁住原出库单并复核仍是已审批(代码复查)。取消出库审批(WorkflowService.cancelMaterialOut)
     * 也是先锁同一行、再查有没有退料,两边排队执行。不锁的话会漏两种情况:
     * ① 边建退料边取消出库,两边都看不到对方还没提交的数据,都放行;
     * ② 退料被驳回→取消原出库(库存已冲回)→退料重提再审批,库存又加一次 = 凭空多出库存。
     * 建单、提交、审批三处都调;按 id 顺序加锁,多张出库单时不会互相卡死。
     */
    private void lockSourceOutsApproved(List<Long> outIds) {
        if (outIds.isEmpty()) return;
        List<BizOut> outs = outMapper.selectList(new QueryWrapper<BizOut>()
                .in("id", outIds).orderByAsc("id").last("FOR UPDATE"));
        if (outs.size() != outIds.size()) throw new BizException("原出库单不存在或已删除，本退料单不能再提交或过账");
        for (BizOut o : outs) {
            if (!"APPROVED".equals(o.getStatus())) {
                throw new BizException("原出库单 " + o.getDocNo() + " 已取消审批，本退料单不能再提交或过账；请删除本退料单，按实际情况重新处理");
            }
        }
    }

    private BizOut requireApprovedOut(Long id) {
        BizOut out = outMapper.selectById(id);
        if (out == null) throw new BizException("原出库单不存在");
        orgScope.assertOrgVisible(out.getOrgId());
        if (!"APPROVED".equals(out.getStatus())) throw new BizException("只有已审批的材料出库单可以退料");
        return out;
    }

    private BizOutReq requireReturnableReq(Long id) {
        BizOutReq req = reqMapper.selectById(id);
        if (req == null) throw new BizException("原出库申请不存在");
        orgScope.assertOrgVisible(req.getOrgId());
        if (!List.of("PARTIAL_OUT", "OUT").contains(req.getStatus())) {
            throw new BizException("只有已产生材料出库的出库申请可以退料");
        }
        return req;
    }

    private BizReturn requireExisting(Long id) {
        integrity.lockSourceForReturn(id);
        BizReturn ret = returnMapper.selectById(id);
        if (ret == null) throw new BizException("退料单不存在");
        orgScope.assertOrgVisible(ret.getOrgId());
        return ret;
    }
}
