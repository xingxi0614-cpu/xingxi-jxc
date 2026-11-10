package com.group.scm.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.inventory.entity.BizPurchaseReturn;
import com.group.scm.modules.inventory.entity.BizPurchaseReturnLine;
import com.group.scm.modules.inventory.entity.BizRecv;
import com.group.scm.modules.inventory.entity.BizRecvLine;
import com.group.scm.modules.inventory.mapper.BizPurchaseReturnLineMapper;
import com.group.scm.modules.inventory.mapper.BizPurchaseReturnMapper;
import com.group.scm.modules.inventory.mapper.BizRecvLineMapper;
import com.group.scm.modules.inventory.mapper.BizRecvMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购退货单：把采购入库（暂估／发票入库）的货退回供应商。
 *
 * <p><b>为什么要单独做这张单</b>：既有的「取消审批」是整单反审，同一张入库单其他行的货
 * 往往早已领用出库，反审必然触发负库存被拒；而发票核销只能增加 invoiced_qty，
 * 退掉的货永远等不到发票，那一行会永久挂在「暂估未开票」里卡住月末结账。
 * 真实案例：ABC-CR-20260804-0001 共 13 行，第 2 行 300 件要退，整单反审时 7 行会变负库存。</p>
 *
 * <p><b>按行下推，不动同单其他行</b>：退货只写自己那一行的 returned_qty，
 * 其余行照常等发票、照常计入暂估。</p>
 *
 * <h3>三个金额的口径（最容易记错，改之前先读懂）</h3>
 * <ul>
 *   <li><b>recvAmt 原入库不含税额</b>：按退货数量从原入库行金额折算（最后一次退取剩余），
 *       这是红字发票的金额、也是冲销暂估的金额 —— 供应商只认这个数。</li>
 *   <li><b>stockAmt 库存冲减额</b>：🔴 2026-10-16 起（定，对齐主流财务软件 红字采购入库）<b>按 recvAmt 冲库存</b>，
 *       供应商冲多少、库存就减多少；剩下那部分货的单价随之重算。</li>
 *   <li><b>diffAmt 差额 = stockAmt − recvAmt</b>：正常为 0。只有两种兜底情况不为 0：
 *       ① 这次退完公司就没这个物料了 → 冲光剩余金额（不留"无数量有金额"）；
 *       ② 那批货早被高价领走，结存金额不够冲原价 → 最多冲到剩余金额（结存绝不为负）。
 *       差额记一笔 ADJUST_EXPENSE 进当期损益（正数 = 损失），出现在"采购成本差异调整表"里。</li>
 * </ul>
 *
 * <p><b>以前为什么按均价冲</b>（10-16 之前）：库存按退货时的移动加权均价冲，差额只写在单据上、没进任何账。
 * 例：29 仓某物料 6/29 进 200 件@8.27、8/4 进 300 件@7.52 → 混合均价 7.61；
 * 退 300 件冲库存 2281.72，原入库额 2256.64，差额 25.08 挂在单据上。改后库存直接冲 2256.64，
 * 剩下 200 件的单价变成 8.27 附近，更贴近它们自己的进价。</p>
 */
@Service
@RequiredArgsConstructor
public class PurchaseReturnService {

    private final BizPurchaseReturnMapper returnMapper;
    private final BizPurchaseReturnLineMapper returnLineMapper;
    private final BizRecvMapper recvMapper;
    private final BizRecvLineMapper recvLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final DocRelationService relationService;
    private final PeriodCloseService periodCloseService;
    private final RecvLineUsage recvUsage;
    private final SubmitStockGuard stockGuard;
    private final JdbcTemplate jdbcTemplate;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办 */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    /** 库存流水类型：采购退货出库。收发存汇总按 qty 正负判断进出，新增类型不会被漏统计。 */
    private static final String TRANS_TYPE = "OUT_PRET";

    // ────────────────────────── 下推选行 ──────────────────────────

    /**
     * 某张采购入库单里还能退货的行。
     *
     * <p>可退数量取三者的最小值，缺一不可：</p>
     * <ol>
     *   <li>入库行余量 = qty − invoiced_qty − returned_qty（已开票的不能退，得走发票红冲）</li>
     *   <li>减去被草稿/待审退货单占用的数量（否则同一批货能被两张草稿各退一遍）</li>
     *   <li>当前库存结存量（账上没有的货退不了，硬退会变负库存）</li>
     * </ol>
     */
    public List<BizPurchaseReturnLine> returnableLines(Long recvId) {
        BizRecv recv = requireApprovedRecv(recvId);
        List<BizRecvLine> lines = recvLineMapper.selectList(
                new QueryWrapper<BizRecvLine>().eq("recv_id", recvId).orderByAsc("line_no"));
        if (lines.isEmpty()) return List.of();

        Map<Long, BigDecimal> stockMap = stockOf(recv.getWarehouseId(),
                lines.stream().map(BizRecvLine::getMaterialId).distinct().toList());
        Map<Long, String[]> matMap = materialInfo(lines.stream().map(BizRecvLine::getMaterialId).distinct().toList());

        List<BizPurchaseReturnLine> result = new ArrayList<>();
        for (BizRecvLine ln : lines) {
            BigDecimal invoiced = nz(ln.getInvoicedQty());
            BigDecimal returned = nz(ln.getReturnedQty());
            BigDecimal occupied = nz(returnLineMapper.activeReturningQty(ln.getId()));
            BigDecimal remain = ln.getQty().subtract(invoiced).subtract(returned).subtract(occupied);
            BigDecimal stock = stockMap.getOrDefault(ln.getMaterialId(), BigDecimal.ZERO);
            BigDecimal returnable = remain.min(stock);
            if (returnable.signum() <= 0) continue;

            BizPurchaseReturnLine out = new BizPurchaseReturnLine();
            out.setSourceRecvLineId(ln.getId());
            out.setSourcePoLineId(ln.getSourcePoLineId());
            out.setMaterialId(ln.getMaterialId());
            out.setQty(returnable);
            out.setRecvUnitPrice(ln.getUnitPrice());
            out.setRecvAmt(splitRecvAmt(ln, returnable));
            out.setReturnableQty(returnable);
            out.setStockQty(stock);
            String[] m = matMap.get(ln.getMaterialId());
            if (m != null) {
                out.setMaterialCode(m[0]);
                out.setMaterialName(m[1]);
                out.setMaterialSpec(m[2]);
                out.setUnit(m[3]);
            }
            result.add(out);
        }
        return result;
    }

    // ────────────────────────── 建单 ──────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public BizPurchaseReturn fromRecv(Long recvId, LocalDate returnDate, String returner,
                                      String reason, String remark, List<ReturnLineInput> inputs) {
        BizRecv recv = requireApprovedRecv(recvId);
        if (inputs == null || inputs.isEmpty()) throw new BizException("请至少选择一行退货");

        LocalDate bizDate = returnDate == null ? LocalDate.now() : returnDate;
        // 业务日期填的是实物实际拉走那天，可能早于今天，所以按 bizDate 而非今天校验期间
        periodCloseService.assertPeriodOpen(bizDate, recv.getOrgId(), "采购退货单", null);
        // 跨月退货允许：退货按【退货日期】出库入账，只登记入库行的已退数量（进度计数），不动入库单所在期间的账
        orgScope.assertWarehouseVisible(recv.getWarehouseId());

        BizPurchaseReturn ret = new BizPurchaseReturn();
        ret.setDocNo(docNoService.next("PRET"));
        ret.setSourceRecvId(recv.getId());
        ret.setSourceRecvNo(recv.getDocNo());
        ret.setSourcePoId(recv.getSourcePoId());
        ret.setSourcePoNo(recv.getSourcePoNo());
        ret.setRecvType(recv.getRecvType());
        ret.setSupplierId(recv.getSupplierId());
        ret.setWarehouseId(recv.getWarehouseId());
        ret.setOrgId(recv.getOrgId());
        ret.setReturnDate(bizDate);
        ret.setReturner(returner);
        ret.setReason(reason);
        ret.setRemark(remark);
        ret.setStatus("DRAFT");
        ret.setTotalQty(BigDecimal.ZERO);
        ret.setTotalStockAmt(BigDecimal.ZERO);
        ret.setTotalRecvAmt(BigDecimal.ZERO);
        ret.setTotalDiffAmt(BigDecimal.ZERO);
        returnMapper.insert(ret);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalRecvAmt = BigDecimal.ZERO;
        int lineNo = 1;
        for (ReturnLineInput in : inputs) {
            if (in.qty == null || in.qty.signum() <= 0) throw new BizException("退货数量必须大于 0");
            BizRecvLine src = recvLineMapper.selectById(in.recvLineId);
            if (src == null) throw new BizException("入库行不存在：" + in.recvLineId);
            if (!recvId.equals(src.getRecvId())) throw new BizException("入库行不属于当前入库单：" + in.recvLineId);

            BigDecimal remain = src.getQty()
                    .subtract(nz(src.getInvoicedQty()))
                    .subtract(nz(src.getReturnedQty()))
                    .subtract(nz(returnLineMapper.activeReturningQty(src.getId())));
            if (in.qty.compareTo(remain) > 0) {
                throw new BizException("行 " + src.getLineNo() + " 退货 " + in.qty
                        + " 超过可退数量 " + remain + "。已开票数量不能退（需走发票红冲），已有草稿/待审退货单也会占用额度");
            }
            BigDecimal stock = stockOf(recv.getWarehouseId(), List.of(src.getMaterialId()))
                    .getOrDefault(src.getMaterialId(), BigDecimal.ZERO);
            if (in.qty.compareTo(stock) > 0) {
                throw new BizException("行 " + src.getLineNo() + " 退货 " + in.qty
                        + " 超过当前库存 " + stock + "，请先确认实物与账面是否一致");
            }

            BizPurchaseReturnLine line = new BizPurchaseReturnLine();
            line.setReturnId(ret.getId());
            line.setLineNo(lineNo++);
            line.setSourceRecvLineId(src.getId());
            line.setSourcePoLineId(src.getSourcePoLineId());
            line.setMaterialId(src.getMaterialId());
            line.setQty(in.qty);
            line.setRecvUnitPrice(src.getUnitPrice());
            line.setRecvAmt(splitRecvAmt(src, in.qty));
            line.setRemark(in.remark);
            returnLineMapper.insert(line);

            totalQty = totalQty.add(in.qty);
            totalRecvAmt = totalRecvAmt.add(line.getRecvAmt());
        }
        ret.setTotalQty(totalQty);
        ret.setTotalRecvAmt(totalRecvAmt);
        returnMapper.updateById(ret);
        return ret;
    }

    // ────────────────────────── 流转 ──────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizPurchaseReturn ret = requireExisting(id);
        if (!"DRAFT".equals(ret.getStatus()) && !"REJECTED".equals(ret.getStatus())) {
            throw new BizException("当前退货单状态不允许提交");
        }
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "采购退货单", ret.getDocNo());
        // 提交时就查库存（账面 − 其他待审批占用），见 SubmitStockGuard：退货要从仓里拿得出货才退得了
        stockGuard.assertAvailable(SubmitStockGuard.PRET, id, ret.getWarehouseId(),
                returnLineMapper.selectList(new QueryWrapper<BizPurchaseReturnLine>().eq("return_id", id)).stream()
                        .map(l -> new SubmitStockGuard.Need(l.getMaterialId(), l.getQty())).toList(),
                "请先核对仓库实存（入库单审批通过才算库存），或减少退货数量；占用的单据审批或撤回后会释放。");
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (returnMapper.update(null, new UpdateWrapper<BizPurchaseReturn>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("PRET", id, ret.getDocNo(), nz(ret.getTotalRecvAmt()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizPurchaseReturn ret = requireExisting(id);
        if (!"PENDING".equals(ret.getStatus())) throw new BizException("只有待审批的退货单可以取消提交");
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "采购退货单", ret.getDocNo());
        wfService.withdraw("PRET", id);
        ret.setStatus("DRAFT");
        returnMapper.updateById(ret);
    }

    /**
     * 审批通过 → 过账：减库存 + 登记 returned_qty + 回退采购订单已入库数。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizPurchaseReturn ret = requireExisting(id);
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "采购退货单", ret.getDocNo());
        wfService.approve("PRET", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (returnMapper.update(null, new UpdateWrapper<BizPurchaseReturn>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizPurchaseReturn next = returnMapper.selectById(bizId);
            List<BizPurchaseReturnLine> lines = returnLineMapper.selectList(
                    new QueryWrapper<BizPurchaseReturnLine>().eq("return_id", bizId).orderByAsc("line_no"));
            if (lines.isEmpty()) throw new BizException("退货单没有明细，无法过账");

            BigDecimal totalStock = BigDecimal.ZERO;
            BigDecimal totalRecv = BigDecimal.ZERO;
            // 本张退货各行已分走的原入库额:同一入库行出现多行时,后面的行要扣掉前面行分走的(P0 复查 P2)
            RecvLineUsage.DocAllocation docAlloc = new RecvLineUsage.DocAllocation();
            for (BizPurchaseReturnLine line : lines) {
                // ⓪ 🔴 2026-10-18(计算审查 U13):加锁按「原金额用量账」重算原入库额,不再照搬建草稿时的数。
                //    以前收尾减的是"理论已退额"而不是实际已退额,而且中间别的退货/发票审批了也不重算,
                //    3 件 0.10 元分三张各退 1 件,退完库存还残留 0.01。
                //    加锁顺序与采购发票统一:公司成本锁 → 入库行(以前退货是先改入库行再拿公司锁,与发票相反,并发会死锁)。
                waService.lockCompanyCostByWarehouse(next.getWarehouseId());
                RecvLineUsage.Line src = recvUsage.lock(line.getSourceRecvLineId());
                if (line.getQty().compareTo(src.remainingQty()) > 0) {
                    throw new BizException("入库行可退数量不足（可能已被开票或已被另一张退货单占用），请刷新后重试");
                }
                BigDecimal exactRecvAmt = recvUsage.allocateForDoc(docAlloc, src, null, next.getId(), line.getQty());
                if (line.getRecvAmt() == null || exactRecvAmt.compareTo(line.getRecvAmt()) != 0) {
                    line.setRecvAmt(exactRecvAmt);
                    returnLineMapper.updateById(line);
                }
                // ① 先占额度：CAS 条件更新，退货总量不得超过「入库量 − 已开票量」。
                //    放在减库存之前，抢不到额度就整笔回滚，避免库存已减、额度没登记上。
                if (recvLineMapper.addReturnedQty(line.getSourceRecvLineId(), line.getQty()) != 1) {
                    throw new BizException("入库行可退数量不足（可能已被开票或已被另一张退货单占用），请刷新后重试");
                }
                // ② 减库存：按原入库额冲减（兜底规则见类注释 / CostMath#outAtAmount）
                BigDecimal recvAmt = nz(line.getRecvAmt());
                BigDecimal[] r = waService.outboundAtAmount(line.getMaterialId(), next.getWarehouseId(),
                        line.getQty(), recvAmt, TRANS_TYPE, "PRET", next.getId(), next.getDocNo(), line.getId(),
                        "采购退货：" + next.getSourceRecvNo());
                BigDecimal stockAmt = r[1];
                BigDecimal diff = r[2];
                line.setStockUnitPrice(r[0]);
                line.setStockAmt(stockAmt);
                line.setDiffAmt(diff);
                returnLineMapper.updateById(line);
                // 兜底差额进当期损益（正常情况 diff = 0，不写）
                waService.postExpense(line.getMaterialId(), next.getWarehouseId(), diff, "PRET", next.getId(),
                        next.getDocNo(), line.getId(), "采购退货 " + next.getSourceRecvNo()
                                + "：库存不足以按原入库额 " + recvAmt + " 冲减，实际冲 " + stockAmt + "，差额 " + diff + " 计入当期损益");

                // ③ 回退采购订单已入库数，退掉的量可以重新入库
                subtractPoReceived(line.getSourcePoLineId(), line.getQty());

                totalStock = totalStock.add(stockAmt);
                totalRecv = totalRecv.add(nz(line.getRecvAmt()));
            }
            recomputePoReceiveStatus(next.getSourcePoId());

            next.setStatus("APPROVED");
            next.setTotalStockAmt(totalStock);
            next.setTotalRecvAmt(totalRecv);
            next.setTotalDiffAmt(totalStock.subtract(totalRecv).setScale(2, RoundingMode.HALF_UP));
            returnMapper.updateById(next);

            relationService.addRelation("RECV", next.getSourceRecvId(), next.getSourceRecvNo(),
                    "PRET", next.getId(), next.getDocNo(),
                    "RETURN", next.getTotalQty(), totalRecv);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizPurchaseReturn ret = requireExisting(id);
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "采购退货单", ret.getDocNo());
        wfService.reject("PRET", id, comment, (no, bizId) -> {
            BizPurchaseReturn next = returnMapper.selectById(bizId);
            next.setStatus("REJECTED");
            returnMapper.updateById(next);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizPurchaseReturn ret = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(ret.getCreatedBy(), ret.getStatus());
        if (!"DRAFT".equals(ret.getStatus()) && !"REJECTED".equals(ret.getStatus())) {
            throw new BizException("只有草稿或已驳回的退货单可以删除");
        }
        periodCloseService.assertPeriodOpen(ret.getReturnDate(), ret.getOrgId(), "采购退货单", ret.getDocNo());
        returnLineMapper.delete(new QueryWrapper<BizPurchaseReturnLine>().eq("return_id", id));
        returnMapper.deleteById(id);
        notifService.closeBusinessTodos("PRET", id);
    }

    // 取消审批（反审）统一在 WorkflowService.cancelPurchaseReturn 实现 ——
    // 与其他单据同一条路径（走「取消审批申请 → 原审批人裁决」），且避免与 WorkflowService 循环依赖。

    // ────────────────────────── 查询 ──────────────────────────

    public IPage<BizPurchaseReturn> page(long current, long size, String status, String keyword,
                                         Long supplierId, Long warehouseId, String sourceRecvNo,
                                         Long materialId, String recvType,
                                         LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizPurchaseReturn> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        var docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        if (docNos != null) qw.in("doc_no", docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword.trim());
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (sourceRecvNo != null && !sourceRecvNo.isBlank()) qw.like("source_recv_no", sourceRecvNo);
        // 2026-10-16 查询条件要覆盖表格列:物料(明细含该物料)、入库类型(来源入库单的暂估/发票入库)
        if (materialId != null) qw.exists("SELECT 1 FROM biz_purchase_return_line l WHERE l.return_id = biz_purchase_return.id AND l.material_id = {0}", materialId);
        if (recvType != null && !recvType.isBlank()) qw.eq("recv_type", recvType);
        if (startDate != null) qw.ge("return_date", startDate);
        if (endDate != null) qw.le("return_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizPurchaseReturn.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizPurchaseReturn> page = returnMapper.selectPage(new Page<>(current, size), qw);
        attachNames(page.getRecords());
        return page;
    }

    public BizPurchaseReturn detail(Long id) {
        BizPurchaseReturn ret = returnMapper.selectById(id);
        if (ret == null) return null;
        orgScope.assertOrgVisible(ret.getOrgId());
        List<BizPurchaseReturnLine> lines = returnLineMapper.selectList(
                new QueryWrapper<BizPurchaseReturnLine>().eq("return_id", id).orderByAsc("line_no"));
        Map<Long, String[]> matMap = materialInfo(lines.stream().map(BizPurchaseReturnLine::getMaterialId).distinct().toList());
        for (BizPurchaseReturnLine line : lines) {
            String[] m = matMap.get(line.getMaterialId());
            if (m != null) {
                line.setMaterialCode(m[0]);
                line.setMaterialName(m[1]);
                line.setMaterialSpec(m[2]);
                line.setUnit(m[3]);
            }
        }
        ret.setLines(lines);
        attachNames(List.of(ret));
        return ret;
    }

    // ────────────────────────── 内部工具 ──────────────────────────

    private BizRecv requireApprovedRecv(Long recvId) {
        BizRecv recv = recvMapper.selectById(recvId);
        if (recv == null) throw new BizException("采购入库单不存在");
        orgScope.assertOrgVisible(recv.getOrgId());
        if (!"APPROVED".equals(recv.getStatus())) {
            throw new BizException("只有已审批的采购入库单可以退货（当前状态：" + recv.getStatus() + "）");
        }
        return recv;
    }

    private BizPurchaseReturn requireExisting(Long id) {
        BizPurchaseReturn ret = returnMapper.selectById(id);
        if (ret == null) throw new BizException("采购退货单不存在");
        orgScope.assertOrgVisible(ret.getOrgId());
        return ret;
    }

    /**
     * 从入库行金额里按数量等比切出退货部分。
     * <p>整行全退时用「行金额」兜底，消除多次部分退货各自四舍五入攒出的分位尾差，
     * 保证退完之后累计退货额 = 入库行金额，与冲暂估的口径分毫对齐。</p>
     */
    private BigDecimal splitRecvAmt(BizRecvLine ln, BigDecimal qty) {
        // 草稿上的参考值;审批过账时会加锁按最新剩余重算(见 approve,计算审查 U13)
        if (ln.getQty() == null || ln.getQty().signum() == 0) return BigDecimal.ZERO;
        RecvLineUsage.Line cur = recvUsage.peek(ln.getId());
        if (qty.compareTo(cur.remainingQty()) > 0) {
            // 可退量的校验在调用方;这里只给参考值,按原始比例估一个,不抛错
            return nz(ln.getAmount()).multiply(qty).divide(ln.getQty(), 2, RoundingMode.HALF_UP);
        }
        return recvUsage.allocate(cur, recvUsage.usedAmount(ln.getId()), qty);
    }

    /** 当前结存量，物料 → qty。 */
    private Map<Long, BigDecimal> stockOf(Long warehouseId, List<Long> materialIds) {
        Map<Long, BigDecimal> map = new HashMap<>();
        if (materialIds == null || materialIds.isEmpty()) return map;
        String ph = String.join(",", java.util.Collections.nCopies(materialIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(warehouseId);
        args.addAll(materialIds);
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT material_id, qty FROM inv_balance WHERE warehouse_id = ? AND material_id IN (" + ph + ")",
                args.toArray())) {
            map.put(((Number) row.get("material_id")).longValue(), toBd(row.get("qty")));
        }
        return map;
    }

    /** 物料 → [code, name, spec, unit] */
    private Map<Long, String[]> materialInfo(List<Long> materialIds) {
        Map<Long, String[]> map = new HashMap<>();
        if (materialIds == null || materialIds.isEmpty()) return map;
        String ph = String.join(",", java.util.Collections.nCopies(materialIds.size(), "?"));
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id, code, name, spec, unit FROM md_material WHERE id IN (" + ph + ")",
                materialIds.toArray())) {
            map.put(((Number) row.get("id")).longValue(), new String[]{
                    str(row.get("code")), str(row.get("name")), str(row.get("spec")), str(row.get("unit"))});
        }
        return map;
    }

    private void attachNames(List<BizPurchaseReturn> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (BizPurchaseReturn r : rows) {
            if (r.getSupplierId() != null) {
                r.setSupplierName(queryStr("SELECT name FROM md_supplier WHERE id = ?", r.getSupplierId()));
            }
            if (r.getWarehouseId() != null) {
                r.setWarehouseName(queryStr("SELECT name FROM md_warehouse WHERE id = ?", r.getWarehouseId()));
            }
            r.setMaterialSummary(queryStr(
                    "SELECT GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name, "
                  + "IF(m.spec IS NULL OR m.spec = '', '', CONCAT('(', m.spec, ')'))) ORDER BY m.code SEPARATOR '、') "
                  + "FROM biz_purchase_return_line l JOIN md_material m ON m.id = l.material_id WHERE l.return_id = ?",
                    r.getId()));
        }
    }

    /** 回退采购订单行已入库数（退货）。用 GREATEST 兜底，历史脏数据不至于把它压成负数。 */
    private void subtractPoReceived(Long poLineId, BigDecimal qty) {
        if (poLineId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_po_line
                SET received_qty = GREATEST(COALESCE(received_qty, 0) - ?, 0)
                WHERE id = ?
                """, qty, poLineId);
    }

    /** 反审时恢复采购订单行已入库数。 */
    private void addPoReceived(Long poLineId, BigDecimal qty) {
        if (poLineId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_po_line
                SET received_qty = COALESCE(received_qty, 0) + ?
                WHERE id = ?
                """, qty, poLineId);
    }

    /**
     * 按行汇总重算采购订单的已入库数与状态。
     * <p>🔴 SET 子句从左到右执行：status 的 CASE 里用的是子查询（读的是 biz_po_line 的最新值），
     * 与 received_qty 的赋值互不影响，不会踩「同一行里前一个赋值影响后一个」的坑。</p>
     */
    private void recomputePoReceiveStatus(Long poId) {
        if (poId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_po po
                SET received_qty = (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id),
                    status = CASE
                      WHEN (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id) <= 0 THEN 'APPROVED'
                      WHEN (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id) >= po.total_qty THEN 'RECEIVED'
                      ELSE 'PARTIAL_RECEIVED'
                    END,
                    updated_at = NOW()
                WHERE po.id = ? AND po.deleted = 0
                  AND po.status IN ('APPROVED', 'PARTIAL_RECEIVED', 'RECEIVED')
                """, poId);
    }

    private String queryStr(String sql, Object... args) {
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    /** 建单入参：只认入库行 ID + 数量，单价金额一律由服务端按入库行推算，不接受前端传值。 */
    public static class ReturnLineInput {
        public Long recvLineId;
        public BigDecimal qty;
        public String remark;
    }
}
