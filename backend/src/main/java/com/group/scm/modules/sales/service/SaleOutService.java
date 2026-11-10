package com.group.scm.modules.sales.service;

import com.group.scm.common.ResultCap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
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
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 销售出库服务。参照销售订单生成，实发≤应发；审批通过时按移动加权成本扣库存、结转销售成本。
 */
@Service
@RequiredArgsConstructor
public class SaleOutService {

    private final BizSaleOutMapper outMapper;
    private final BizSaleOutLineMapper outLineMapper;
    private final BizSoMapper soMapper;
    private final BizSoLineMapper soLineMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final PeriodCloseService periodCloseService;
    private final SoService soService;
    private final SalesOutAvailability outAvailability;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final com.group.scm.modules.inventory.service.SubmitStockGuard stockGuard;

    public record OutLineInput(Long soLineId, BigDecimal qty, String remark) {}

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BizSaleOut fromSo(Long soId, Long warehouseId, List<OutLineInput> inputs) {
        BizSo so = soMapper.selectById(soId);
        if (so == null) throw new BizException("销售订单不存在");
        orgScope.assertOrgVisible(so.getOrgId());
        if (!"APPROVED".equals(so.getStatus()) && !"PARTIAL_OUT".equals(so.getStatus()))
            throw new BizException("订单未审批或已全部出库");
        if (warehouseId == null) throw new BizException("请选择出库仓库");
        orgScope.assertWarehouseVisible(warehouseId);
        orgScope.assertSameCompany(so.getOrgId(), warehouseId, "销售订单");   // 计算审查 U11
        if (inputs == null || inputs.isEmpty()) throw new BizException("请至少选择一行出库");
        validateQuantities(soId, inputs, null);
        LocalDate outDate = LocalDate.now();
        periodCloseService.assertPeriodOpen(outDate, so.getOrgId(), "销售出库单", null);

        BizSaleOut out = new BizSaleOut();
        out.setDocNo(docNoService.next("SALE_OUT"));
        out.setOrgId(so.getOrgId());
        out.setSourceSoId(soId);
        out.setSourceSoNo(so.getDocNo());
        out.setWarehouseId(warehouseId);
        out.setCustomerId(so.getCustomerId());
        out.setCustomerUnit(so.getCustomerUnit());
        out.setCustomerName(so.getCustomerName());
        out.setCustomerPhone(so.getCustomerPhone());
        out.setShipAddress(so.getShipAddress());
        out.setDeptId(so.getDeptId());
        out.setDeptName(so.getDeptName());
        out.setSalesPerson(so.getSalesPerson());
        out.setBizFlow("普通销售");
        out.setOutType("普通销售出库");
        out.setOutDate(outDate);
        out.setStatus("DRAFT");
        out.setTotalAmt(BigDecimal.ZERO);
        outMapper.insert(out);

        BigDecimal totalQty = BigDecimal.ZERO;
        int i = 1;
        for (OutLineInput in : inputs) {
            BizSoLine sl = soLineMapper.selectById(in.soLineId());
            if (sl == null) throw new BizException("订单行不存在");
            if (!soId.equals(sl.getSoId())) throw new BizException("订单行不属于当前销售订单：" + in.soLineId());
            if (in.qty() == null || in.qty().signum() <= 0) throw new BizException("出库数量必须大于 0");
            BigDecimal used = sl.getOutQty() == null ? BigDecimal.ZERO : sl.getOutQty();
            BigDecimal remain = sl.getQty().subtract(used);
            if (in.qty().compareTo(remain) > 0)
                throw new BizException("第 " + sl.getLineNo() + " 行 " + com.group.scm.common.MaterialLabel.of(jdbcTemplate, sl.getMaterialId())
                        + "：本次出库 " + com.group.scm.common.MaterialLabel.qty(in.qty()) + " 超过订单未出数量 " + com.group.scm.common.MaterialLabel.qty(remain.max(BigDecimal.ZERO)));

            BizSaleOutLine ln = new BizSaleOutLine();
            ln.setOutId(out.getId());
            ln.setLineNo(i++);
            ln.setSourceSoLineId(sl.getId());
            ln.setMaterialId(sl.getMaterialId());
            ln.setQty(in.qty());
            ln.setUnitPrice(BigDecimal.ZERO);
            ln.setAmount(BigDecimal.ZERO);
            ln.setRemark(in.remark());
            outLineMapper.insert(ln);
            totalQty = totalQty.add(in.qty());
        }
        out.setTotalQty(totalQty);
        outMapper.updateById(out);
        return out;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void submit(Long id) {
        BizSaleOut o = requireExisting(id);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("当前状态不能提交");
        // DocumentMapper 在写事务中锁定订单头，和生成出库串行；驳回后重提须重新取得额度。
        BizSo source = soMapper.selectById(o.getSourceSoId());
        if (source == null || !List.of("APPROVED", "PARTIAL_OUT").contains(source.getStatus()))
            throw new BizException("来源销售订单未审批或已全部出库");
        List<BizSaleOutLine> lines = outLineMapper.selectList(new QueryWrapper<BizSaleOutLine>().eq("out_id", id));
        validateQuantities(source.getId(), lines.stream().map(l -> new OutLineInput(l.getSourceSoLineId(), l.getQty(), l.getRemark())).toList(), id);
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "销售出库单", o.getDocNo());
        // 提交时就查库存（账面 − 其他待审批占用），见 SubmitStockGuard
        stockGuard.assertAvailable(com.group.scm.modules.inventory.service.SubmitStockGuard.SALE_OUT, id, o.getWarehouseId(),
                lines.stream().map(l -> new com.group.scm.modules.inventory.service.SubmitStockGuard.Need(l.getMaterialId(), l.getQty())).toList(), null);
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (outMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSaleOut>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("SALE_OUT", id, o.getDocNo(), o.getTotalAmt());
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizSaleOut o = requireExisting(id);
        if (!"PENDING".equals(o.getStatus())) throw new BizException("只有待审批的销售出库单可以取消提交");
        wfService.withdraw("SALE_OUT", id);
        o.setStatus("DRAFT");
        outMapper.updateById(o);
    }

    /** 审批通过 → 按当前移动加权单价扣库存，结转销售成本，回写订单已出库 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizSaleOut o = requireExisting(id);
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "销售出库单", o.getDocNo());
        wfService.approve("SALE_OUT", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (outMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSaleOut>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizSaleOut out = outMapper.selectById(bizId);
            // 计算审查 U11:过账时再核一次同公司(建单后改过仓库、或历史草稿都在这里拦住)
            orgScope.assertSameCompany(out.getOrgId(), out.getWarehouseId(), "销售出库单");
            // 🔴 计算审查 U07:销售订单取消审批(退回草稿)后,待审出库以前照样扣库存,订单还被改成"已出库"。
            if (out.getSourceSoId() != null) {
                String soStatus = soMapper.lockStatus(out.getSourceSoId());
                if (!java.util.Set.of("APPROVED", "PARTIAL_OUT", "OUT").contains(soStatus)) {
                    throw new BizException("来源销售订单当前不是已审批状态（可能已取消审批），本出库单不能过账。请驳回本单，待订单重新审批后再处理");
                }
            }
            List<BizSaleOutLine> lns = outLineMapper.selectList(new QueryWrapper<BizSaleOutLine>().eq("out_id", bizId));
            BigDecimal totalAmt = BigDecimal.ZERO;
            for (BizSaleOutLine ln : lns) {
                BigDecimal[] r = waService.outbound(ln.getMaterialId(), out.getWarehouseId(),
                        ln.getQty(), "SALE_OUT", out.getId(), out.getDocNo(), ln.getId(), "销售出库");
                ln.setUnitPrice(r[0]);
                ln.setAmount(r[1]);
                outLineMapper.updateById(ln);
                totalAmt = totalAmt.add(r[1]);
                soService.addOut(out.getSourceSoId(), ln.getSourceSoLineId(), ln.getQty());
            }
            out.setTotalAmt(totalAmt);
            out.setStatus("APPROVED");
            outMapper.updateById(out);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizSaleOut o = requireExisting(id);
        wfService.reject("SALE_OUT", id, comment, (no, bizId) -> {
            BizSaleOut out = outMapper.selectById(bizId);
            out.setStatus("REJECTED");
            outMapper.updateById(out);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizSaleOut o = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(o.getCreatedBy(), o.getStatus());
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("只有草稿或已驳回的销售出库单可以删除");
        outLineMapper.delete(new QueryWrapper<BizSaleOutLine>().eq("out_id", id));
        outMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("SALE_OUT", id);
    }

    public BizSaleOut detail(Long id) {
        BizSaleOut o = requireExisting(id);
        o.setLines(outLineMapper.selectList(new QueryWrapper<BizSaleOutLine>().eq("out_id", id).orderByAsc("line_no")));
        return o;
    }

    public IPage<BizSaleOut> page(long current, long size, String status, String keyword, Long customerId,
                                  LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizSaleOut> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (keyword != null && !keyword.isBlank())
            {
                java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
                if (_docNos != null) qw.in("doc_no", _docNos);
                else qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("source_so_no", keyword));
            }
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("out_date", startDate);
        if (endDate != null) qw.le("out_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizSaleOut.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizSaleOut> page = outMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    /** 供销售发票参照：已审批、仍有未开票的销售出库单 */
    public List<BizSaleOut> availableForInvoice(String keyword) {
        QueryWrapper<BizSaleOut> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        qw.eq("status", "APPROVED");
        qw.apply("NOT EXISTS (SELECT 1 FROM biz_sale_invoice i WHERE i.source_out_id = biz_sale_out.id AND i.deleted = 0 AND i.status <> 'REJECTED')");
        if (keyword != null && !keyword.isBlank())
            {
                java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
                if (_docNos != null) qw.in("doc_no", _docNos);
                else qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("source_so_no", keyword));
            }
        qw.orderByDesc("id").last("LIMIT 200");
        List<BizSaleOut> rows = outMapper.selectList(qw);
        attachMaterialSummary(rows);
        return ResultCap.mark(rows, 200);
    }

    private void attachMaterialSummary(List<BizSaleOut> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(BizSaleOut::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        String ph = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT l.out_id oid, " +
                "GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name, IF(m.spec IS NULL OR m.spec = '', '', CONCAT('(', m.spec, ')'))) " +
                "ORDER BY m.code SEPARATOR '、') summary, " +
                "GROUP_CONCAT(m.code ORDER BY m.code SEPARATOR '、') codes, " +
                "GROUP_CONCAT(m.name ORDER BY m.code SEPARATOR '、') names, " +
                "GROUP_CONCAT(IFNULL(NULLIF(m.spec,''),'-') ORDER BY m.code SEPARATOR '、') specs " +
                "FROM biz_sale_out_line l JOIN md_material m ON m.id = l.material_id " +
                "WHERE l.out_id IN (" + ph + ") GROUP BY l.out_id";
        Map<Long, String[]> map = new HashMap<>();
        jdbcTemplate.query(sql, (java.sql.ResultSet rs) -> { map.put(rs.getLong("oid"), new String[]{rs.getString("summary"), rs.getString("codes"), rs.getString("names"), rs.getString("specs")}); }, ids.toArray());
        for (BizSaleOut r : records) {
            if (r.getId() == null) continue;
            String[] v = map.get(r.getId());
            if (v != null) { r.setMaterialSummary(v[0]); r.setMaterialCodes(v[1]); r.setMaterialNames(v[2]); r.setMaterialSpecs(v[3]); }
        }
    }

    private BizSaleOut requireExisting(Long id) {
        BizSaleOut o = outMapper.selectById(id);
        if (o == null) throw new BizException("销售出库单不存在");
        orgScope.assertOrgVisible(o.getOrgId());
        return o;
    }

    private void validateQuantities(Long soId, List<OutLineInput> inputs, Long excludeOutId) {
        if (inputs.isEmpty()) throw new BizException("请至少选择一行出库");
        Map<Long, BigDecimal> reserved = outAvailability.reserved(soId, excludeOutId);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (OutLineInput input : inputs) {
            if (input == null || input.soLineId() == null || !seen.add(input.soLineId()))
                throw new BizException("订单行不能为空或重复，请刷新后重试");
            if (input.qty() == null || input.qty().signum() <= 0 || input.qty().stripTrailingZeros().scale() > 4)
                throw new BizException("出库数量必须大于0，且最多4位小数");
            BizSoLine line = soLineMapper.selectById(input.soLineId());
            if (line == null || !soId.equals(line.getSoId())) throw new BizException("订单行不属于当前销售订单");
            BigDecimal held = reserved.getOrDefault(line.getId(), BigDecimal.ZERO);
            BigDecimal available = line.getQty().subtract(SalesOutAvailability.zero(line.getOutQty())).subtract(held);
            if (input.qty().compareTo(available) > 0)
                throw new BizException("第 " + line.getLineNo() + " 行可出库数量不足：本次 "
                        + com.group.scm.common.MaterialLabel.qty(input.qty()) + "，可用 "
                        + com.group.scm.common.MaterialLabel.qty(available.max(BigDecimal.ZERO))
                        + "（其他草稿/待审批占用 " + com.group.scm.common.MaterialLabel.qty(held) + "）");
        }
    }
}
