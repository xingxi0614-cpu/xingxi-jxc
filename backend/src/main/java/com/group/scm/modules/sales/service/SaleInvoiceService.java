package com.group.scm.modules.sales.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.sales.entity.*;
import com.group.scm.modules.sales.mapper.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 销售发票服务。参照销售出库开票，金额取订单售价(价税合计)，按税率倒挤不含税/销项税额；
 * 审批通过即确认收入与应收。
 */
@Service
@RequiredArgsConstructor
public class SaleInvoiceService {

    private final BizSaleInvoiceMapper invMapper;
    private final BizSaleInvoiceLineMapper invLineMapper;
    private final BizSaleOutMapper outMapper;
    private final BizSaleOutLineMapper outLineMapper;
    private final BizSoLineMapper soLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final PeriodCloseService periodCloseService;
    private final SoService soService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    /**
     * 销售模块税率统一 13%（2026-10-01 定锁死：账面按"商品销售"13% 入账）。
     * 建票/改票一律用它，前端传来的税率直接忽略 —— 防止手滑填成 9%/6% 把收入和销项税算错。
     * 以后真有别的税率业务，改配置 scm.sales.tax-rate 或在这里放开，不要在页面上开口子。
     */
    @org.springframework.beans.factory.annotation.Value("${scm.sales.tax-rate:13}")
    private BigDecimal salesTaxRate;

    public record InvoiceReq(Long outId, String invoiceType, String invoiceNo, LocalDate invoiceDate,
                             BigDecimal taxRate, String remark) {}

    @Transactional(rollbackFor = Exception.class)
    public BizSaleInvoice fromSaleOut(InvoiceReq req) {
        if (req == null || req.outId() == null) throw new BizException("请选择销售出库单");
        BizSaleOut out = outMapper.selectById(req.outId());
        if (out == null) throw new BizException("销售出库单不存在");
        orgScope.assertOrgVisible(out.getOrgId());
        if (!"APPROVED".equals(out.getStatus())) throw new BizException("只有已审批的销售出库单可以开票");
        // 并发防线:先悲观锁定源出库单行,串行化两个并发开票;后者阻塞到前者提交后才做下面的查重
        List<BizSaleInvoice> existing = invMapper.selectList(new QueryWrapper<BizSaleInvoice>()
                .eq("source_out_id", req.outId()).ne("status", "REJECTED").last("FOR UPDATE"));
        if (!existing.isEmpty()) throw new BizException("该销售出库单已开票，不能重复开票");
        LocalDate invDate = req.invoiceDate() != null ? req.invoiceDate() : LocalDate.now();
        periodCloseService.assertPeriodOpen(invDate, out.getOrgId(), "销售发票", null);

        List<BizSaleOutLine> outLines = outLineMapper.selectList(
                new QueryWrapper<BizSaleOutLine>().eq("out_id", out.getId()).orderByAsc("line_no"));
        if (outLines.isEmpty()) throw new BizException("销售出库单无明细");

        BizSaleInvoice inv = new BizSaleInvoice();
        inv.setDocNo(docNoService.next("SALE_INV"));
        inv.setOrgId(out.getOrgId());
        inv.setSourceOutId(out.getId());
        inv.setSourceOutNo(out.getDocNo());
        inv.setCustomerId(out.getCustomerId());
        inv.setCustomerUnit(out.getCustomerUnit());
        inv.setCustomerName(out.getCustomerName());
        inv.setInvoiceType(req.invoiceType() != null ? req.invoiceType() : "增值税专用发票");
        inv.setInvoiceNo(req.invoiceNo());
        inv.setInvoiceDate(invDate);
        inv.setStatus("DRAFT");
        inv.setRemark(req.remark());
        inv.setTotalQty(BigDecimal.ZERO);
        inv.setAmount(BigDecimal.ZERO);
        inv.setTaxAmt(BigDecimal.ZERO);
        inv.setTotalAmt(BigDecimal.ZERO);
        invMapper.insert(inv);

        BigDecimal totalQty = BigDecimal.ZERO, totalExcl = BigDecimal.ZERO, totalTax = BigDecimal.ZERO, total = BigDecimal.ZERO;
        int i = 1;
        for (BizSaleOutLine ol : outLines) {
            BizSoLine sl = ol.getSourceSoLineId() == null ? null : soLineMapper.selectById(ol.getSourceSoLineId());
            BigDecimal amount = invoiceAmountFor(sl, ol.getQty()); // 含税
            BigDecimal taxRate = salesTaxRate; // 锁死 13%，不取前端/订单税率（见 salesTaxRate 注释）
            BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            BigDecimal excl = amount.divide(divisor, 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.subtract(excl);

            BizSaleInvoiceLine il = new BizSaleInvoiceLine();
            il.setInvoiceId(inv.getId());
            il.setLineNo(i++);
            il.setMaterialId(ol.getMaterialId());
            il.setQty(ol.getQty());
            il.setTaxRate(taxRate);
            il.setAmountExclTax(excl);
            il.setTaxAmount(tax);
            il.setAmount(amount);
            invLineMapper.insert(il);

            totalQty = totalQty.add(ol.getQty());
            totalExcl = totalExcl.add(excl);
            totalTax = totalTax.add(tax);
            total = total.add(amount);
        }
        inv.setTotalQty(totalQty);
        inv.setAmount(totalExcl);
        inv.setTaxAmt(totalTax);
        inv.setTotalAmt(total);
        invMapper.updateById(inv);
        return inv;
    }

    /**
     * 本次开票含税额(2026-10-18,计算审查 U24,定「原金额用量账」):从订单行含税额的【剩余】里分,
     * 剩余 = 订单行含税额 − 本订单行其它有效发票(草稿/待审/已审,不含已驳回)已开的含税额;
     * 本次把订单行剩余数量开完 → 拿走全部剩余,否则按剩余比例分。
     * 以前每张按"含税单价×数量"各自四舍五入:3 件 100 元分三张出库开票,各 33.33、合计 99.99,全额退货后应收留 −0.01。
     */
    private BigDecimal invoiceAmountFor(BizSoLine sl, BigDecimal qty) {
        if (sl == null || sl.getQty() == null || sl.getQty().signum() <= 0 || sl.getAmount() == null) return BigDecimal.ZERO.setScale(2);
        // 锁订单行再读已开额:同一订单行的两张出库同时开票时,后到的读到前一张提交后的剩余,不会两张都"拿走剩余"
        jdbc.queryForList("SELECT id FROM biz_so_line WHERE id = ? FOR UPDATE", sl.getId());
        java.util.Map<String, Object> used = jdbc.queryForMap("""
                SELECT COALESCE(SUM(il.qty), 0) qty, COALESCE(SUM(il.amount), 0) amt
                FROM biz_sale_invoice v
                JOIN biz_sale_invoice_line il ON il.invoice_id = v.id
                JOIN biz_sale_out_line ol ON ol.out_id = v.source_out_id AND ol.line_no = il.line_no
                WHERE v.deleted = 0 AND v.status <> 'REJECTED' AND ol.source_so_line_id = ?
                LOCK IN SHARE MODE
                """, sl.getId());
        BigDecimal usedQty = (BigDecimal) used.get("qty"), usedAmt = (BigDecimal) used.get("amt");
        BigDecimal remainingQty = sl.getQty().subtract(usedQty);
        BigDecimal remainingAmt = sl.getAmount().subtract(usedAmt).setScale(2, RoundingMode.HALF_UP);
        if (remainingQty.signum() <= 0 || remainingAmt.signum() < 0 || qty.compareTo(remainingQty) > 0) {
            // 数量或金额异常(历史手工调整等):退回原口径,不卡开票
            return sl.getAmount().multiply(qty).divide(sl.getQty(), 2, RoundingMode.HALF_UP);
        }
        if (qty.compareTo(remainingQty) == 0) return remainingAmt;
        return remainingAmt.multiply(qty).divide(remainingQty, 2, RoundingMode.HALF_UP).min(remainingAmt);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, InvoiceReq req) {
        BizSaleInvoice inv = requireExisting(id);
        if (!"DRAFT".equals(inv.getStatus()) && !"REJECTED".equals(inv.getStatus()))
            throw new BizException("只有草稿或已驳回的销售发票可以修改");
        inv.setInvoiceType(req.invoiceType());
        inv.setInvoiceNo(req.invoiceNo());
        if (req.invoiceDate() != null) inv.setInvoiceDate(req.invoiceDate());
        inv.setRemark(req.remark());
        // 税率锁死 13%：改票时若历史票不是 13%，顺手按 13% 重算；前端传的税率忽略
        boolean rateOff = invLineMapper.selectList(new QueryWrapper<BizSaleInvoiceLine>().eq("invoice_id", id))
                .stream().anyMatch(l -> l.getTaxRate() == null || l.getTaxRate().compareTo(salesTaxRate) != 0);
        if (rateOff) {
            List<BizSaleInvoiceLine> lns = invLineMapper.selectList(new QueryWrapper<BizSaleInvoiceLine>().eq("invoice_id", id));
            BigDecimal totalExcl = BigDecimal.ZERO, totalTax = BigDecimal.ZERO, total = BigDecimal.ZERO;
            BigDecimal divisor = BigDecimal.ONE.add(salesTaxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            for (BizSaleInvoiceLine il : lns) {
                BigDecimal excl = il.getAmount().divide(divisor, 2, RoundingMode.HALF_UP);
                il.setTaxRate(salesTaxRate);
                il.setAmountExclTax(excl);
                il.setTaxAmount(il.getAmount().subtract(excl));
                invLineMapper.updateById(il);
                totalExcl = totalExcl.add(excl);
                totalTax = totalTax.add(il.getTaxAmount());
                total = total.add(il.getAmount());
            }
            inv.setAmount(totalExcl);
            inv.setTaxAmt(totalTax);
            inv.setTotalAmt(total);
        }
        invMapper.updateById(inv);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        // 与建单同序：先锁来源、再锁发票，避免驳回重提与另建发票互相等待。
        Long sourceOutId = invMapper.sourceOutId(id);
        if (sourceOutId != null) outMapper.lockById(sourceOutId);
        BizSaleInvoice inv = requireExisting(id);
        if (!"DRAFT".equals(inv.getStatus()) && !"REJECTED".equals(inv.getStatus()))
            throw new BizException("当前状态不能提交");
        // 防重复开票(补建单查重的漏洞):被驳回后本单重提前,若同一出库单已另有非驳回发票,禁止重提
        if (inv.getSourceOutId() != null) {
            List<BizSaleInvoice> other = invMapper.selectList(new QueryWrapper<BizSaleInvoice>()
                    .eq("source_out_id", inv.getSourceOutId()).ne("status", "REJECTED").ne("id", id).last("FOR UPDATE"));
            if (!other.isEmpty()) throw new BizException("该销售出库单已有其他有效发票，不能重复开票");
        }
        periodCloseService.assertPeriodOpen(inv.getInvoiceDate(), inv.getOrgId(), "销售发票", inv.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (invMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSaleInvoice>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("SALE_INV", id, inv.getDocNo(), inv.getTotalAmt());
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizSaleInvoice inv = requireExisting(id);
        if (!"PENDING".equals(inv.getStatus())) throw new BizException("只有待审批的销售发票可以取消提交");
        wfService.withdraw("SALE_INV", id);
        inv.setStatus("DRAFT");
        invMapper.updateById(inv);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizSaleInvoice inv = requireExisting(id);
        periodCloseService.assertPeriodOpen(inv.getInvoiceDate(), inv.getOrgId(), "销售发票", inv.getDocNo());
        wfService.approve("SALE_INV", id, comment, (no, bizId) -> {
            BizSaleInvoice e = invMapper.selectById(bizId);
            // 🔴 计算审查 U08:来源出库单取消审批后没有有效发货,驳回过的发票重提以前还能确认收入和应收。
            if (e.getSourceOutId() != null && !"APPROVED".equals(outMapper.lockStatus(e.getSourceOutId()))) {
                throw new BizException("来源销售出库单当前不是已审批状态（可能已取消审批），本发票不能过账。请驳回后删除本单");
            }
            e.setStatus("APPROVED");
            invMapper.updateById(e);
            // 审批通过才回写订单已开票（草稿/驳回/删除不计，避免虚增）
            BizSaleOut srcOut = e.getSourceOutId() == null ? null : outMapper.selectById(e.getSourceOutId());
            if (srcOut != null) {
                List<BizSaleOutLine> outLines = outLineMapper.selectList(
                        new QueryWrapper<BizSaleOutLine>().eq("out_id", srcOut.getId()));
                for (BizSaleOutLine ol : outLines) {
                    soService.addInvoice(srcOut.getSourceSoId(), ol.getSourceSoLineId(), ol.getQty());
                }
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizSaleInvoice inv = requireExisting(id);
        wfService.reject("SALE_INV", id, comment, (no, bizId) -> {
            BizSaleInvoice e = invMapper.selectById(bizId);
            e.setStatus("REJECTED");
            invMapper.updateById(e);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizSaleInvoice inv = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(inv.getCreatedBy(), inv.getStatus());
        if (!"DRAFT".equals(inv.getStatus()) && !"REJECTED".equals(inv.getStatus()))
            throw new BizException("只有草稿或已驳回的销售发票可以删除");
        invLineMapper.delete(new QueryWrapper<BizSaleInvoiceLine>().eq("invoice_id", id));
        invMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("SALE_INV", id);
    }

    public BizSaleInvoice detail(Long id) {
        BizSaleInvoice inv = requireExisting(id);
        inv.setLines(invLineMapper.selectList(new QueryWrapper<BizSaleInvoiceLine>().eq("invoice_id", id).orderByAsc("line_no")));
        return inv;
    }

    public IPage<BizSaleInvoice> page(long current, long size, String status, String keyword, Long customerId,
                                      LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizSaleInvoice> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (keyword != null && !keyword.isBlank())
            {
                java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
                if (_docNos != null) qw.in("doc_no", _docNos);
                else qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("invoice_no", keyword).or().like("source_out_no", keyword));
            }
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("invoice_date", startDate);
        if (endDate != null) qw.le("invoice_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizSaleInvoice.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        return invMapper.selectPage(new Page<>(current, size), qw);
    }

    private BizSaleInvoice requireExisting(Long id) {
        BizSaleInvoice inv = invMapper.selectById(id);
        if (inv == null) throw new BizException("销售发票不存在");
        orgScope.assertOrgVisible(inv.getOrgId());
        return inv;
    }
}
