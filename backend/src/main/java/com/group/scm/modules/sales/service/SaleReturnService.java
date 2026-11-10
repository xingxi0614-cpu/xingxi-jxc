package com.group.scm.modules.sales.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.sales.entity.*;
import com.group.scm.modules.sales.mapper.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售退货服务。参照已审批销售出库单退货：审批通过时按原出库成本把货退回库存(回冲成本)，
 * 并记录应冲收入(价税合计)用于冲减应收 / 材料会计开红字发票。
 */
@Service
@RequiredArgsConstructor
public class SaleReturnService {

    private final BizSaleReturnMapper returnMapper;
    private final BizSaleReturnLineMapper returnLineMapper;
    private final BizSaleOutMapper outMapper;
    private final BizSaleOutLineMapper outLineMapper;
    private final BizSoLineMapper soLineMapper;
    private final BizSoMapper soMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final PeriodCloseService periodCloseService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    public record ReturnLineInput(Long outLineId, BigDecimal qty, String remark) {}
    public record ReturnReq(Long outId, List<ReturnLineInput> lines, String remark) {}

    @Transactional(rollbackFor = Exception.class)
    public BizSaleReturn fromSaleOut(ReturnReq req) {
        if (req == null || req.outId() == null) throw new BizException("请选择销售出库单");
        lockSource(req.outId());
        BizSaleOut out = outMapper.selectById(req.outId());
        if (out == null) throw new BizException("销售出库单不存在");
        orgScope.assertOrgVisible(out.getOrgId());
        if (!"APPROVED".equals(out.getStatus())) throw new BizException("只有已审批的销售出库单可以退货");
        if (req.lines() == null || req.lines().isEmpty()) throw new BizException("请至少选择一行退货");
        // 并发防线:悲观锁定源出库单行,串行化并发退货,后者阻塞到前者提交后 returnedQty 才含前者数量,防超退
        outMapper.lockById(req.outId());
        LocalDate retDate = LocalDate.now();
        periodCloseService.assertPeriodOpen(retDate, out.getOrgId(), "销售退货单", null);

        BizSaleReturn ret = new BizSaleReturn();
        ret.setDocNo(docNoService.next("SALE_RETURN"));
        ret.setOrgId(out.getOrgId());
        ret.setSourceOutId(out.getId());
        ret.setSourceOutNo(out.getDocNo());
        ret.setWarehouseId(out.getWarehouseId());
        ret.setCustomerId(out.getCustomerId());
        ret.setCustomerUnit(out.getCustomerUnit());
        ret.setCustomerName(out.getCustomerName());
        ret.setReturnDate(retDate);
        ret.setStatus("DRAFT");
        ret.setTotalQty(BigDecimal.ZERO);
        ret.setTotalAmt(BigDecimal.ZERO);
        ret.setRefundAmt(BigDecimal.ZERO);
        ret.setRemark(req.remark());
        returnMapper.insert(ret);

        BigDecimal totalQty = BigDecimal.ZERO, totalCost = BigDecimal.ZERO, totalRefund = BigDecimal.ZERO;
        int i = 1;
        for (ReturnLineInput in : req.lines()) {
            BizSaleOutLine ol = outLineMapper.selectById(in.outLineId());
            if (ol == null || !out.getId().equals(ol.getOutId())) throw new BizException("出库行不存在或不属于当前出库单");
            if (in.qty() == null || in.qty().signum() <= 0) throw new BizException("退货数量必须大于 0");
            BigDecimal returned = returnedQty(ol.getId());
            BigDecimal remain = ol.getQty().subtract(returned);
            if (in.qty().compareTo(remain) > 0) throw new BizException("退货数量 " + in.qty() + " 超过可退 " + remain);

            // 成本：按原出库单价，成本额按出库行成本比例精确回冲
            BigDecimal costPrice = ol.getUnitPrice() == null ? BigDecimal.ZERO : ol.getUnitPrice();
            BigDecimal costAmount = costAmount(ol, in.qty(), null);
            // 售价：取来源订单行价税合计单价
            BigDecimal saleUnit = BigDecimal.ZERO;
            if (ol.getSourceSoLineId() != null) {
                BizSoLine sl = soLineMapper.selectById(ol.getSourceSoLineId());
                if (sl != null && sl.getQty() != null && sl.getQty().signum() > 0)
                    saleUnit = sl.getAmount().divide(sl.getQty(), 6, RoundingMode.HALF_UP);
            }
            BigDecimal saleAmount = saleAmount(ol, in.qty(), null);

            BizSaleReturnLine ln = new BizSaleReturnLine();
            ln.setReturnId(ret.getId());
            ln.setLineNo(i++);
            ln.setSourceOutLineId(ol.getId());
            ln.setMaterialId(ol.getMaterialId());
            ln.setQty(in.qty());
            ln.setUnitPrice(costPrice);
            ln.setAmount(costAmount);
            ln.setSaleTaxPrice(saleUnit.setScale(4, RoundingMode.HALF_UP));
            ln.setSaleAmount(saleAmount);
            ln.setRemark(in.remark());
            returnLineMapper.insert(ln);

            totalQty = totalQty.add(in.qty());
            totalCost = totalCost.add(costAmount);
            totalRefund = totalRefund.add(saleAmount);
        }
        ret.setTotalQty(totalQty);
        ret.setTotalAmt(totalCost);
        ret.setRefundAmt(totalRefund);
        returnMapper.updateById(ret);
        return ret;
    }

    private BigDecimal returnedQty(Long outLineId) {
        BigDecimal q = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(l.qty),0) FROM biz_sale_return_line l JOIN biz_sale_return r ON r.id = l.return_id " +
                        "WHERE l.source_out_line_id = ? AND r.deleted = 0 AND r.status NOT IN ('REJECTED','CANCELLED') FOR UPDATE",
                BigDecimal.class, outLineId);
        return q == null ? BigDecimal.ZERO : q;
    }

    /** 先锁订单再锁出库；同一订单的不同出库批次也共享退款金额的尾差。 */
    private void lockSource(Long outId) {
        List<Long> orderIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT sl.so_id FROM biz_sale_out_line ol
                JOIN biz_so_line sl ON sl.id = ol.source_so_line_id
                WHERE ol.out_id = ? ORDER BY sl.so_id
                """, Long.class, outId);
        for (Long id : orderIds) soMapper.selectById(id);
        outMapper.lockById(outId);
    }

    private void lockReturnSource(Long id) {
        List<Long> sources = jdbcTemplate.queryForList(
                "SELECT source_out_id FROM biz_sale_return WHERE id = ? AND deleted = 0", Long.class, id);
        if (!sources.isEmpty() && sources.get(0) != null) lockSource(sources.get(0));
    }

    private BigDecimal allocate(BigDecimal sourceQty, BigDecimal sourceAmount, BigDecimal qty,
                                BigDecimal usedQty, BigDecimal usedAmount) {
        int comparison = usedQty.add(qty).compareTo(sourceQty);
        if (comparison > 0) throw new BizException("退货数量超过来源可退量，请刷新后重试");
        BigDecimal remaining = sourceAmount.subtract(usedAmount);
        if (remaining.signum() < 0) throw new BizException("来源累计退货金额异常，请先核对历史退货记录");
        return comparison == 0 ? remaining
                : sourceAmount.multiply(qty).divide(sourceQty, 2, RoundingMode.HALF_UP).min(remaining);
    }

    private BigDecimal costAmount(BizSaleOutLine source, BigDecimal qty, Long excludeLine) {
        Map<String, Object> used = usedAmounts("l.source_out_line_id = ?", source.getId(), excludeLine);
        return allocate(source.getQty(), source.getAmount(), qty,
                (BigDecimal) used.get("qty"), (BigDecimal) used.get("amount"));
    }

    private BigDecimal saleAmount(BizSaleOutLine source, BigDecimal qty, Long excludeLine) {
        if (source.getSourceSoLineId() == null) return BigDecimal.ZERO;
        BizSoLine order = soLineMapper.selectById(source.getSourceSoLineId());
        if (order == null || order.getQty().signum() <= 0) return BigDecimal.ZERO;
        Map<String, Object> used = usedAmounts("ol.source_so_line_id = ?", order.getId(), excludeLine);
        return allocate(order.getQty(), order.getAmount(), qty,
                (BigDecimal) used.get("qty"), (BigDecimal) used.get("sale_amount"));
    }

    private Map<String, Object> usedAmounts(String condition, Long source, Long excludeLine) {
        return jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(l.qty),0) qty, COALESCE(SUM(l.amount),0) amount,
                       COALESCE(SUM(l.sale_amount),0) sale_amount
                FROM biz_sale_return_line l JOIN biz_sale_return r ON r.id = l.return_id
                JOIN biz_sale_out_line ol ON ol.id = l.source_out_line_id
                WHERE r.deleted = 0 AND r.status NOT IN ('REJECTED','CANCELLED')
                  AND """ + " " + condition + " AND (? IS NULL OR l.id <> ?) FOR UPDATE", source, excludeLine, excludeLine);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        lockReturnSource(id);
        BizSaleReturn r = requireExisting(id);
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) throw new BizException("当前状态不能提交");
        periodCloseService.assertPeriodOpen(r.getReturnDate(), r.getOrgId(), "销售退货单", r.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (returnMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSaleReturn>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("SALE_RETURN", id, r.getDocNo(), r.getTotalAmt());
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        lockReturnSource(id);
        BizSaleReturn r = requireExisting(id);
        if (!"PENDING".equals(r.getStatus())) throw new BizException("只有待审批的销售退货单可以取消提交");
        wfService.withdraw("SALE_RETURN", id);
        r.setStatus("DRAFT");
        returnMapper.updateById(r);
    }

    /** 审批通过 → 按原成本把货退回库存(回冲成本) */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        lockReturnSource(id);
        BizSaleReturn r = requireExisting(id);
        periodCloseService.assertPeriodOpen(r.getReturnDate(), r.getOrgId(), "销售退货单", r.getDocNo());
        wfService.approve("SALE_RETURN", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (returnMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSaleReturn>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizSaleReturn next = returnMapper.selectById(bizId);
            // 🔴 计算审查 U08:来源出库单取消审批后(库存已加回),以前驳回过的退货重提还能过账,库存又加一次。
            if (next.getSourceOutId() != null && !"APPROVED".equals(outMapper.lockStatus(next.getSourceOutId()))) {
                throw new BizException("来源销售出库单当前不是已审批状态（可能已取消审批），本退货单不能过账。请驳回后删除本单");
            }
            List<BizSaleReturnLine> lns = returnLineMapper.selectList(
                    new QueryWrapper<BizSaleReturnLine>().eq("return_id", bizId).orderByAsc("line_no"));
            BigDecimal costTotal = BigDecimal.ZERO, refundTotal = BigDecimal.ZERO;
            for (BizSaleReturnLine ln : lns) {
                // 审批时复查累计退货量(含本单,已置APPROVED)不超出库量——防"驳回后重开B、再重提A"两单叠加超退
                if (ln.getSourceOutLineId() != null) {
                    BizSaleOutLine ol = outLineMapper.selectById(ln.getSourceOutLineId());
                    if (ol != null && returnedQty(ln.getSourceOutLineId()).compareTo(ol.getQty()) > 0) {
                        throw new BizException("退货数量超过该出库行可退量,可能已有其他退货单,请作废本单后重开");
                    }
                    if (ol == null) throw new BizException("来源销售出库明细不存在");
                    // 驳回后其他单据可能释放/占用尾差，过账前以当前有效明细重新分摊。
                    ln.setAmount(costAmount(ol, ln.getQty(), ln.getId()));
                    ln.setSaleAmount(saleAmount(ol, ln.getQty(), ln.getId()));
                    returnLineMapper.updateById(ln);
                }
                costTotal = costTotal.add(ln.getAmount());
                refundTotal = refundTotal.add(ln.getSaleAmount());
                waService.inbound(ln.getMaterialId(), next.getWarehouseId(), ln.getQty(),
                        ln.getUnitPrice() == null ? BigDecimal.ZERO : ln.getUnitPrice(),
                        ln.getAmount(), "SALE_RETURN", "SALE_RETURN", next.getId(), next.getDocNo(), ln.getId(), "销售退货入库");
            }
            next.setStatus("APPROVED");
            next.setTotalAmt(costTotal);
            next.setRefundAmt(refundTotal);
            returnMapper.updateById(next);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        lockReturnSource(id);
        BizSaleReturn r = requireExisting(id);
        wfService.reject("SALE_RETURN", id, comment, (no, bizId) -> {
            BizSaleReturn next = returnMapper.selectById(bizId);
            next.setStatus("REJECTED");
            returnMapper.updateById(next);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        lockReturnSource(id);
        BizSaleReturn r = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(r.getCreatedBy(), r.getStatus());
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus()))
            throw new BizException("只有草稿或已驳回的销售退货单可以删除");
        returnLineMapper.delete(new QueryWrapper<BizSaleReturnLine>().eq("return_id", id));
        returnMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("SALE_RETURN", id);
    }

    public BizSaleReturn detail(Long id) {
        BizSaleReturn r = requireExisting(id);
        r.setLines(returnLineMapper.selectList(new QueryWrapper<BizSaleReturnLine>().eq("return_id", id).orderByAsc("line_no")));
        return r;
    }

    public IPage<BizSaleReturn> page(long current, long size, String status, String keyword, Long customerId,
                                     LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizSaleReturn> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (keyword != null && !keyword.isBlank())
            {
                java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
                if (_docNos != null) qw.in("doc_no", _docNos);
                else qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("source_out_no", keyword));
            }
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("return_date", startDate);
        if (endDate != null) qw.le("return_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizSaleReturn.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizSaleReturn> page = returnMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    private void attachMaterialSummary(List<BizSaleReturn> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(BizSaleReturn::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        String ph = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT l.return_id rid, " +
                "GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name) ORDER BY m.code SEPARATOR '、') summary, " +
                "GROUP_CONCAT(m.code ORDER BY m.code SEPARATOR '、') codes, " +
                "GROUP_CONCAT(m.name ORDER BY m.code SEPARATOR '、') names, " +
                "GROUP_CONCAT(IFNULL(NULLIF(m.spec,''),'-') ORDER BY m.code SEPARATOR '、') specs " +
                "FROM biz_sale_return_line l JOIN md_material m ON m.id = l.material_id " +
                "WHERE l.return_id IN (" + ph + ") GROUP BY l.return_id";
        Map<Long, String[]> map = new HashMap<>();
        jdbcTemplate.query(sql, (java.sql.ResultSet rs) -> { map.put(rs.getLong("rid"), new String[]{rs.getString("summary"), rs.getString("codes"), rs.getString("names"), rs.getString("specs")}); }, ids.toArray());
        for (BizSaleReturn r : records) {
            if (r.getId() == null) continue;
            String[] v = map.get(r.getId());
            if (v != null) { r.setMaterialSummary(v[0]); r.setMaterialCodes(v[1]); r.setMaterialNames(v[2]); r.setMaterialSpecs(v[3]); }
        }
    }

    private BizSaleReturn requireExisting(Long id) {
        BizSaleReturn r = returnMapper.selectById(id);
        if (r == null) throw new BizException("销售退货单不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        return r;
    }
}
