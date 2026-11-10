package com.group.scm.modules.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.purchase.entity.*;
import com.group.scm.modules.purchase.mapper.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购订单服务。
 *
 * <p>关键操作：</p>
 * <ul>
 *   <li>{@link #create} - 采购订单作为采购流程起点直接创建</li>
 *   <li>{@link #fromPr} - 兼容历史采购申请下推订单（新业务入口已停用）</li>
 *   <li>{@link #submit/approve/reject} - 工作流</li>
 *   <li>仓管入库时通过 RecvService 调用 {@link #addReceived} 回写</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PoService {

    private final BizPoMapper poMapper;
    private final BizPoLineMapper poLineMapper;
    private final JdbcTemplate jdbcTemplate;
    private final BizPrMapper prMapper;
    private final BizPrLineMapper prLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final DocRelationService relationService;
    private final PeriodCloseService periodCloseService;
    /** 单价录成金额防错：偏离历史价过大且无理由的行拒绝保存（2026-09-29） */
    private final PoPriceCheckService priceCheck;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    /** 直接创建采购订单。 */
    @Transactional(rollbackFor = Exception.class)
    public BizPo create(BizPo dto) {
        validateRequired(dto);
        Long orgId = SecurityUtil.orgId();
        if (orgId == null) throw new BizException("当前账号缺少账套归属，不能创建采购订单");
        orgScope.assertOrgVisible(orgId);
        orgScope.assertSupplierVisible(dto.getSupplierId());
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
        orgScope.assertSameCompany(orgId, dto.getWarehouseId(), "采购订单");   // 计算审查 U11 同类:入库会照订单仓库入

        dto.setId(null);
        dto.setDocNo(docNoService.next("PO"));
        dto.setSourcePrId(null);
        dto.setSourcePrNo(null);
        dto.setOrgId(orgId);
        if (dto.getOrderDate() == null) dto.setOrderDate(LocalDate.now());
        periodCloseService.assertPeriodOpen(dto.getOrderDate(), dto.getOrgId(), "采购订单", dto.getDocNo());

        Totals totals = calculateLines(dto.getLines());
        priceCheck.enforce(dto.getLines(), null);
        dto.setTotalQty(totals.qty());
        dto.setTotalAmt(totals.amount());
        dto.setReceivedQty(BigDecimal.ZERO);
        dto.setInvoicedQty(BigDecimal.ZERO);
        dto.setTaxAmt(BigDecimal.ZERO);
        dto.setStatus("DRAFT");
        poMapper.insert(dto);
        insertLines(dto.getId(), dto.getLines());
        return dto;
    }

    /** 修改草稿或已驳回的采购订单。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BizPo dto) {
        BizPo cur = requireExisting(id);
        if (!"DRAFT".equals(cur.getStatus()) && !"REJECTED".equals(cur.getStatus())) {
            throw new BizException("只有草稿或已驳回的采购订单可以修改");
        }
        if (!SecurityUtil.isAdmin() && cur.getCreatedBy() != null && !cur.getCreatedBy().equals(SecurityUtil.uid())) {
            throw new BizException("只有制单人或管理员可以修改该采购订单");
        }
        periodCloseService.assertPeriodOpen(cur.getOrderDate(), cur.getOrgId(), "采购订单", cur.getDocNo());
        validateRequired(dto);
        orgScope.assertSupplierVisible(dto.getSupplierId());
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
        orgScope.assertSameCompany(cur.getOrgId(), dto.getWarehouseId(), "采购订单");
        periodCloseService.assertPeriodOpen(dto.getOrderDate(), cur.getOrgId(), "采购订单", cur.getDocNo());

        Totals totals = calculateLines(dto.getLines());
        priceCheck.enforce(dto.getLines(), id);
        dto.setId(id);
        dto.setDocNo(cur.getDocNo());
        dto.setSourcePrId(cur.getSourcePrId());
        dto.setSourcePrNo(cur.getSourcePrNo());
        dto.setOrgId(cur.getOrgId());
        dto.setStatus(cur.getStatus());
        dto.setReceivedQty(cur.getReceivedQty());
        dto.setInvoicedQty(cur.getInvoicedQty());
        dto.setTaxAmt(cur.getTaxAmt() == null ? BigDecimal.ZERO : cur.getTaxAmt());
        dto.setTotalQty(totals.qty());
        dto.setTotalAmt(totals.amount());
        poMapper.updateById(dto);

        poLineMapper.delete(new QueryWrapper<BizPoLine>().eq("po_id", id));
        insertLines(id, dto.getLines());
    }

    /** 由已审批的采购申请下推生成订单 */
    @Transactional(rollbackFor = Exception.class)
    public BizPo fromPr(Long prId, Long warehouseId) {
        BizPr pr = prMapper.selectById(prId);
        if (pr != null) orgScope.assertOrgVisible(pr.getOrgId());
        if (pr != null) orgScope.assertSupplierVisible(pr.getSupplierId());
        if (pr == null) throw new BizException("申请不存在");
        if (!"APPROVED".equals(pr.getStatus())) throw new BizException("只有已审批的申请才能下推");
        periodCloseService.assertPeriodOpen(pr.getApplyDate(), pr.getOrgId(), "采购申请", pr.getDocNo());

        Long targetWarehouseId = warehouseId == null ? defaultWarehouseId() : warehouseId;
        orgScope.assertWarehouseVisible(targetWarehouseId);
        orgScope.assertSameCompany(pr.getOrgId(), targetWarehouseId, "采购申请");
        LocalDate orderDate = LocalDate.now();
        periodCloseService.assertPeriodOpen(orderDate, pr.getOrgId(), "采购订单", null);

        if (prMapper.markConvertedFromApproved(prId) != 1) {
            throw new BizException("采购申请已被下推或状态已变化，请刷新后重试");
        }

        BizPo po = new BizPo();
        po.setDocNo(docNoService.next("PO"));
        po.setSourcePrId(prId);
        po.setSourcePrNo(pr.getDocNo());
        po.setSupplierId(pr.getSupplierId());
        po.setWarehouseId(targetWarehouseId);
        po.setOrgId(pr.getOrgId());
        po.setOrderDate(orderDate);
        po.setTotalQty(pr.getTotalQty());
        po.setTotalAmt(pr.getTotalAmt());
        po.setReceivedQty(BigDecimal.ZERO);
        po.setInvoicedQty(BigDecimal.ZERO);
        po.setTaxAmt(BigDecimal.ZERO);
        po.setStatus("DRAFT");
        poMapper.insert(po);

        // 复制行
        List<BizPrLine> prLines = prLineMapper.selectList(new QueryWrapper<BizPrLine>().eq("pr_id", prId));
        int i = 1;
        for (BizPrLine pl : prLines) {
            BizPoLine line = new BizPoLine();
            line.setPoId(po.getId());
            line.setLineNo(i++);
            line.setSourcePrLineId(pl.getId());
            line.setMaterialId(pl.getMaterialId());
            line.setQty(pl.getQty());
            line.setUnitPrice(pl.getUnitPrice());
            line.setAmount(pl.getAmount());
            line.setReceivedQty(BigDecimal.ZERO);
            line.setInvoicedQty(BigDecimal.ZERO);
            line.setTaxRate(BigDecimal.ZERO);
            line.setTaxAmount(BigDecimal.ZERO);
            line.setAmountExclTax(pl.getAmount());   // 无税：不含税金额 = 金额
            poLineMapper.insert(line);

            // PR 行的 convertedQty 累加
            pl.setConvertedQty(pl.getQty());
            prLineMapper.updateById(pl);
        }
        // 单据链关系
        relationService.addRelation("PR", pr.getId(), pr.getDocNo(),
                "PO", po.getId(), po.getDocNo(),
                "DOWN_PUSH", po.getTotalQty(), po.getTotalAmt());
        return po;
    }

    private Long defaultWarehouseId() {
        List<Long> ids = orgScope.visibleWarehouseIds();
        if (ids.isEmpty()) {
            throw new BizException("请先在基础资料中维护启用仓库，再自动生成采购订单");
        }
        return ids.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizPo po = poMapper.selectById(id);
        if (po != null) orgScope.assertOrgVisible(po.getOrgId());
        if (po == null) throw new BizException("订单不存在");
        if (!"DRAFT".equals(po.getStatus()) && !"REJECTED".equals(po.getStatus())) {
            throw new BizException("当前状态不能提交：" + po.getStatus());
        }
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单", po.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (poMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizPo>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("PO", id, po.getDocNo(), po.getTotalAmt());
    }

    /** 取消提交：提交人在单据被审批前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizPo po = poMapper.selectById(id);
        if (po == null) throw new BizException("订单不存在");
        orgScope.assertOrgVisible(po.getOrgId());
        if (!"PENDING".equals(po.getStatus())) {
            throw new BizException("只有待审批的采购订单可以取消提交");
        }
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单", po.getDocNo());
        wfService.withdraw("PO", id);
        po.setStatus("DRAFT");
        poMapper.updateById(po);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizPo po = poMapper.selectById(id);
        if (po == null) throw new BizException("订单不存在");
        orgScope.assertOrgVisible(po.getOrgId());
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(po.getCreatedBy(), po.getStatus());
        if (!"DRAFT".equals(po.getStatus()) && !"REJECTED".equals(po.getStatus()) && !("CANCELLED".equals(po.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的采购订单可以删除");
        }
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单", po.getDocNo());
        if ((po.getReceivedQty() != null && po.getReceivedQty().signum() > 0)
                || (po.getInvoicedQty() != null && po.getInvoicedQty().signum() > 0)) {
            throw new BizException("已有入库或开票记录的采购订单不能删除");
        }
        poLineMapper.delete(new QueryWrapper<BizPoLine>().eq("po_id", id));
        if (po.getSourcePrId() != null) {
            BizPr pr = prMapper.selectById(po.getSourcePrId());
            if (pr != null && "CONVERTED".equals(pr.getStatus())) {
                periodCloseService.assertPeriodOpen(pr.getApplyDate(), pr.getOrgId(), "采购申请", pr.getDocNo());
                pr.setStatus("APPROVED");
                prMapper.updateById(pr);
                List<BizPrLine> lines = prLineMapper.selectList(new QueryWrapper<BizPrLine>().eq("pr_id", pr.getId()));
                for (BizPrLine line : lines) {
                    line.setConvertedQty(BigDecimal.ZERO);
                    prLineMapper.updateById(line);
                }
            }
        }
        poMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("PO", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizPo po = poMapper.selectById(id);
        if (po == null) throw new BizException("订单不存在");
        orgScope.assertOrgVisible(po.getOrgId());
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单", po.getDocNo());
        wfService.approve("PO", id, comment, (no, bizId) -> {
            BizPo p = poMapper.selectById(bizId);
            p.setStatus("APPROVED");
            poMapper.updateById(p);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizPo po = poMapper.selectById(id);
        if (po == null) throw new BizException("订单不存在");
        orgScope.assertOrgVisible(po.getOrgId());
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单", po.getDocNo());
        wfService.reject("PO", id, comment, (no, bizId) -> {
            BizPo p = poMapper.selectById(bizId);
            p.setStatus("REJECTED");
            poMapper.updateById(p);
        });
    }

    /** 由 RecvService 回调，回写已入库数量 + 自动改状态（进度计数不动历史账，跨月订单不校验订单期间） */
    public void addReceived(Long poId, Long poLineId, BigDecimal qty) {
        if (poLineId != null && poLineMapper.addReceivedQty(poLineId, qty) != 1) {
            throw new BizException("入库数量超过采购订单未入库数量，请刷新订单后重试");
        }
        poMapper.addReceivedQty(poId, qty);
    }

    public void addInvoiced(Long poId, Long poLineId, BigDecimal qty) {
        poMapper.addInvoicedQty(poId, qty);
        if (poLineId != null && poLineMapper.addInvoicedQty(poLineId, qty) != 1) {
            throw new BizException("开票数量超过采购订单行数量，请检查订单与发票数据");
        }
    }

    public BizPo detail(Long id) {
        BizPo po = requireExisting(id);
        po.setLines(poLineMapper.selectList(new QueryWrapper<BizPoLine>().eq("po_id", id).orderByAsc("line_no")));
        return po;
    }

    public IPage<BizPo> page(long current, long size, String status, String keyword, Long supplierId,
                             Long warehouseId, Long materialId, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizPo> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) qw.in("doc_no", _docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        if (supplierId != null) {
            orgScope.assertSupplierVisible(supplierId);
            qw.eq("supplier_id", supplierId);
        }
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        // 按物料查:明细里含这个物料的订单(2026-10-16 查询条件要覆盖表格列)
        if (materialId != null) qw.exists("SELECT 1 FROM biz_po_line pl WHERE pl.po_id = biz_po.id AND pl.material_id = {0}", materialId);
        if (startDate != null) qw.ge("order_date", startDate);
        if (endDate != null) qw.le("order_date", endDate);
        // 列表排序：只认白名单，防止列名被拼进 ORDER BY；不在白名单时回落到默认的按录入倒序
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizPo.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizPo> page = poMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    /** 列表富集：物料摘要(统一格式：编码 名称(规格)，顿号分隔)，与入库单列表同一口径。 */
    private void attachMaterialSummary(List<BizPo> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(BizPo::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        String ph = ids.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT pl.po_id pid, " +
                "GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name, IF(m.spec IS NULL OR m.spec = '', '', CONCAT('(', m.spec, ')'))) " +
                "ORDER BY m.code SEPARATOR '、') summary, " +
                "GROUP_CONCAT(m.code ORDER BY m.code SEPARATOR '、') codes, " +
                "GROUP_CONCAT(m.name ORDER BY m.code SEPARATOR '、') names, " +
                "GROUP_CONCAT(IFNULL(NULLIF(m.spec,''),'-') ORDER BY m.code SEPARATOR '、') specs " +
                "FROM biz_po_line pl JOIN md_material m ON m.id = pl.material_id " +
                "WHERE pl.po_id IN (" + ph + ") GROUP BY pl.po_id";
        java.util.Map<Long, String[]> map = new java.util.HashMap<>();
        jdbcTemplate.query(sql, (java.sql.ResultSet rs) -> {
            map.put(rs.getLong("pid"), new String[]{rs.getString("summary"), rs.getString("codes"), rs.getString("names"), rs.getString("specs")});
        }, ids.toArray());
        for (BizPo r : records) {
            if (r.getId() == null) continue;
            String[] v = map.get(r.getId());
            if (v != null) { r.setMaterialSummary(v[0]); r.setMaterialCodes(v[1]); r.setMaterialNames(v[2]); r.setMaterialSpecs(v[3]); }
        }
    }

    public List<BizPoLine> lines(Long poId) {
        BizPo po = poMapper.selectById(poId);
        if (po == null) return List.of();
        orgScope.assertOrgVisible(po.getOrgId());
        return poLineMapper.selectList(new QueryWrapper<BizPoLine>().eq("po_id", poId).orderByAsc("line_no"));
    }

    private BizPo requireExisting(Long id) {
        BizPo po = poMapper.selectById(id);
        if (po == null) throw new BizException("订单不存在");
        orgScope.assertOrgVisible(po.getOrgId());
        return po;
    }

    /** 税率订正（仅未入库订单）：含税金额不变，按新税率重算不含税金额/税额/不含税单价。已入库/已开票的不允许在此改（走发票核销修正成本）。 */
    @Transactional(rollbackFor = Exception.class)
    public BizPo correctTaxRates(Long id, List<TaxFix> fixes) {
        BizPo po = requireExisting(id);
        if (!List.of("DRAFT", "REJECTED", "APPROVED").contains(po.getStatus()))
            throw new BizException("只有草稿、已驳回或已审批且未入库的订单可以订正税率");
        periodCloseService.assertPeriodOpen(po.getOrderDate(), po.getOrgId(), "采购订单税率订正", po.getDocNo());
        if (po.getReceivedQty() != null && po.getReceivedQty().signum() > 0) {
            throw new BizException("该订单已入库，税率订正仅限未入库订单；已入库的请通过发票核销修正成本");
        }
        if (po.getInvoicedQty() != null && po.getInvoicedQty().signum() > 0) {
            throw new BizException("该订单已开票，不能在此订正税率");
        }
        // 🔴 计算审查 U21:已下推但还没过账的入库单里存着旧的不含税成本,订正后它们仍按旧成本入库,
        //    订单与库存成本对不上。有这类入库单时先让经办人删除/驳回,再订正(锁住订单,防订正与下推同时发生)。
        poMapper.lockStatus(id);
        List<String> pendingRecv = jdbcTemplate.queryForList("""
                SELECT doc_no FROM biz_recv
                WHERE source_po_id = ? AND deleted = 0 AND status IN ('DRAFT','PENDING','REJECTED')
                ORDER BY id
                """, String.class, id);
        if (!pendingRecv.isEmpty()) {
            throw new BizException("该订单还有未过账的入库单 "
                    + String.join("、", pendingRecv)
                    + "，它们按订正前的价格生成。请先删除（待审批的先撤回）后再订正税率");
        }
        if (fixes == null || fixes.isEmpty()) throw new BizException("请提供要订正的明细税率");
        java.util.Map<Long, BigDecimal> rateMap = new java.util.HashMap<>();
        for (TaxFix f : fixes) {
            if (f == null || f.lineId() == null) throw new BizException("请选择要订正的订单明细");
            com.group.scm.common.FinancialInput.taxRate(f.taxRate());
            rateMap.put(f.lineId(), f.taxRate() == null ? BigDecimal.ZERO : f.taxRate());
        }
        List<BizPoLine> lines = poLineMapper.selectList(new QueryWrapper<BizPoLine>().eq("po_id", id));
        if (!lines.stream().map(BizPoLine::getId).toList().containsAll(rateMap.keySet()))
            throw new BizException("税率订正明细不属于当前订单");
        BigDecimal totalTax = BigDecimal.ZERO;
        for (BizPoLine line : lines) {
            if (rateMap.containsKey(line.getId())) {
                line.setTaxRate(rateMap.get(line.getId()));
                computeLineTax(line);            // 含税金额(amount)不变，重算不含税/税额/不含税单价
                poLineMapper.updateById(line);
            }
            totalTax = totalTax.add(line.getTaxAmount() == null ? BigDecimal.ZERO : line.getTaxAmount());
        }
        po.setTaxAmt(totalTax);
        poMapper.updateById(po);
        return detail(id);
    }

    public record TaxFix(Long lineId, BigDecimal taxRate) {}

    private void validateRequired(BizPo dto) {
        if (dto.getSupplierId() == null) throw new BizException("供应商不能为空");
        if (dto.getWarehouseId() == null) throw new BizException("仓库不能为空");
        if (dto.getOrderDate() == null) throw new BizException("下单日期不能为空");
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("订单明细不能为空");
    }

    private Totals calculateLines(List<BizPoLine> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;   // 含税总额
        if (lines != null) {
            for (BizPoLine line : lines) {
                validateLine(line);
                computeLineTax(line);
                totalQty = totalQty.add(line.getQty());
                totalAmt = totalAmt.add(line.getAmount());
            }
        }
        if (totalQty.signum() <= 0) throw new BizException("至少需要一行有效的物料明细");
        return new Totals(totalQty, totalAmt);
    }

    /** 含税金额录入 → 拆出不含税金额、税额、不含税单价（倒挤）。税率为空按 0 处理。 */
    private void computeLineTax(BizPoLine line) {
        BigDecimal qty = line.getQty();
        BigDecimal amount = line.getAmount() == null ? BigDecimal.ZERO : line.getAmount(); // 含税金额
        BigDecimal taxRate = line.getTaxRate() == null ? BigDecimal.ZERO : line.getTaxRate();
        line.setTaxRate(taxRate);
        BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP));
        BigDecimal exclTax = amount.divide(divisor, 2, java.math.RoundingMode.HALF_UP);   // 不含税金额
        line.setAmountExclTax(exclTax);
        line.setTaxAmount(amount.subtract(exclTax));                                       // 税额
        line.setUnitPrice(qty.signum() == 0 ? BigDecimal.ZERO
                : exclTax.divide(qty, 2, java.math.RoundingMode.HALF_UP));                 // 不含税单价（倒挤）
    }

    private void validateLine(BizPoLine line) {
        if (line.getMaterialId() == null) throw new BizException("物料编码不能为空");
        orgScope.assertMaterialVisible(line.getMaterialId());
        if (line.getQty() == null || line.getQty().signum() <= 0) throw new BizException("数量必须大于 0");
        // 0 元行一律拒绝(2026-09-29 定)：0 成本入库会按移动加权平均拉低同物料库存单价，
        // 且"0 元采购订单"本身是审计疑点；供应商赠品走盘盈等其他入库，不走采购订单
        if (line.getAmount() == null || line.getAmount().signum() <= 0)
            throw new BizException("含税金额必须大于 0（采购订单不允许 0 元明细；供应商赠品请走盘盈等其他入库）");
        line.setAmount(com.group.scm.common.FinancialInput.positiveMoney(line.getAmount(), "含税金额"));
        // 税率边界:负数或 >=100 会让 1+税率/100 的倒挤除零/反向,拒绝
        BigDecimal taxRate = line.getTaxRate();
        com.group.scm.common.FinancialInput.taxRate(taxRate);
    }

    private void insertLines(Long poId, List<BizPoLine> lines) {
        if (lines == null) return;
        int i = 1;
        for (BizPoLine line : lines) {
            line.setId(null);
            line.setPoId(poId);
            line.setLineNo(i++);
            line.setSourcePrLineId(null);
            /*
             * 2026-08-05 防御性加固(⚠️ 不是修复一个真实存在的漏洞,如实记录如下)。
             *
             * 审计曾报告:原写法 `if (== null) setZero()` 允许前端在 lines[] 里塞
             * receivedQty/invoicedQty 打穿「不许超订单量」护栏。**该结论经实测已被推翻**:
             * 在隔离环境用【旧写法的 jar】(反编译确认字节码含 ifnonnull 分支)实发请求,
             * 同一行里 remark 能落库、receivedQty=7777 落库仍是 0 —— 上游某处已经挡住了。
             * 具体是哪一层挡的,未能定位(无全局 Jackson 配置、无 Mixin、实体无 @JsonIgnore)。
             *
             * 保留本改动的理由:①把隐含保护写成显式,以后没人会疑惑这两个字段该不该由前端传;
             * ②那道隐形保护万一哪天变了,E2E 里对应的 4 条断言会红;③无副作用 ——
             * insertLines 只被 create/update 调用,update():86-88 只放行 DRAFT/REJECTED,
             * 这两种状态不可能已入库,不会抹掉真实已执行量。
             */
            line.setReceivedQty(BigDecimal.ZERO);
            line.setInvoicedQty(BigDecimal.ZERO);
            if (line.getTaxRate() == null) line.setTaxRate(BigDecimal.ZERO);
            if (line.getTaxAmount() == null) line.setTaxAmount(BigDecimal.ZERO);
            poLineMapper.insert(line);
        }
    }

    private record Totals(BigDecimal qty, BigDecimal amount) {
    }
}
