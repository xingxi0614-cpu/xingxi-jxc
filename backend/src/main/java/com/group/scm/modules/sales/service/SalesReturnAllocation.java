package com.group.scm.modules.sales.service;

import com.group.scm.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表退货分摊：以原票行的净额/税额为上限，按来源出库行累计退货数量分摊。
 * 每笔金额只取其之前的退货累计，后来的退货只能吸收剩余分币，不能改写已结账月份。
 * 发票前的退货在开票月从原票扣掉；从未开票的退货不冲确认收入。
 */
final class SalesReturnAllocation {
    record Key(long documentId, long materialId) {}
    record Amount(BigDecimal qty, BigDecimal revenue, BigDecimal tax, BigDecimal cost,
                  BigDecimal minRate, BigDecimal maxRate) {
        Amount plus(Amount b) {
            return new Amount(qty.add(b.qty), revenue.add(b.revenue), tax.add(b.tax), cost.add(b.cost),
                    minRate.min(b.minRate), maxRate.max(b.maxRate));
        }
    }
    static final Amount ZERO = new Amount(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    final Map<Key, Amount> beforeInvoice = new HashMap<>();
    final Map<Key, Amount> afterInvoice = new HashMap<>();

    private record Original(long invoiceId, String invoiceNo, long materialId, LocalDate invoiceDate,
                            LocalDateTime approvedAt, BigDecimal qty, BigDecimal net, BigDecimal tax, BigDecimal rate) {}

    SalesReturnAllocation(JdbcTemplate jdbc, List<Long> orgIds, LocalDate start, LocalDate end) {
        String ph = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        Map<Long, Original> originals = new HashMap<>();
        Map<Long, Set<LocalDate>> invoiceDatesByOut = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT source_out_id, invoice_date FROM biz_sale_invoice
                WHERE deleted = 0 AND status = 'APPROVED' AND org_id IN (%s)
                """.formatted(ph), orgIds.toArray()))
            invoiceDatesByOut.computeIfAbsent(num(row.get("source_out_id")), ignored -> new HashSet<>())
                    .add(date(row.get("invoice_date")));
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT ol.id out_line_id, v.source_out_id, v.id invoice_id, v.doc_no invoice_no,
                       v.invoice_date, il.material_id,
                       (SELECT wi.finished_at FROM wf_instance wi WHERE wi.biz_type = 'SALE_INV'
                         AND wi.biz_id = v.id AND wi.status = 'APPROVED' ORDER BY wi.id DESC LIMIT 1) invoice_approved_at,
                       il.qty, il.amount_excl_tax net, il.tax_amount tax, il.tax_rate rate
                FROM biz_sale_invoice v JOIN biz_sale_invoice_line il ON il.invoice_id = v.id
                JOIN biz_sale_out_line ol ON ol.out_id = v.source_out_id
                  AND ol.line_no = il.line_no AND ol.material_id = il.material_id
                WHERE v.deleted = 0 AND v.status = 'APPROVED'
                  AND v.org_id IN (%s)
                """.formatted(ph), orgIds.toArray())) {
            originals.put(num(row.get("out_line_id")), new Original(num(row.get("invoice_id")),
                    String.valueOf(row.get("invoice_no")), num(row.get("material_id")), date(row.get("invoice_date")),
                    timeOrNull(row.get("invoice_approved_at")), dec(row.get("qty")),
                    dec(row.get("net")), dec(row.get("tax")), dec(row.get("rate"))));
        }
        Map<Long, BigDecimal> usedQty = new HashMap<>();
        Map<Long, BigDecimal> usedNet = new HashMap<>();
        Map<Long, BigDecimal> usedTax = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT r.id return_id, r.doc_no return_no, r.return_date, r.source_out_id, r.source_out_no,
                       (SELECT wr.finished_at FROM wf_instance wr WHERE wr.biz_type = 'SALE_RETURN'
                         AND wr.biz_id = r.id AND wr.status = 'APPROVED' ORDER BY wr.id DESC LIMIT 1) return_approved_at,
                       l.source_out_line_id out_line_id,
                       l.qty, l.amount cost, l.sale_amount refund
                FROM biz_sale_return r JOIN biz_sale_return_line l ON l.return_id = r.id
                WHERE r.deleted = 0 AND r.status = 'APPROVED'
                  AND r.org_id IN (%s)
                ORDER BY r.return_date, r.id, l.line_no, l.id
                """.formatted(ph), orgIds.toArray())) {
            Long outLineId = row.get("out_line_id") == null ? null : num(row.get("out_line_id"));
            Original original = outLineId == null ? null : originals.get(outLineId);
            LocalDate returnDate = date(row.get("return_date"));
            if (original == null) {
                Set<LocalDate> sourceInvoiceDates = invoiceDatesByOut.getOrDefault(num(row.get("source_out_id")), Set.of());
                if (!sourceInvoiceDates.isEmpty() && (inWindow(returnDate, start, end)
                        || sourceInvoiceDates.stream().anyMatch(d -> inWindow(d, start, end))))
                    throw new BizException("退货单 " + row.get("return_no") + " 的来源出库行与已审批销售发票不对应（出库单 "
                            + row.get("source_out_no") + "）。请核对原票行和退货来源行；报表暂停签字");
                continue; // 发票前退货：没有已确认收入可冲。
            }
            BigDecimal priorQty = usedQty.getOrDefault(outLineId, BigDecimal.ZERO);
            BigDecimal nextQty = priorQty.add(dec(row.get("qty")));
            if (nextQty.compareTo(original.qty) > 0) throw new BizException("退货累计数量超过原发票数量，请先核对销售单据");
            // 最后一笔直接拿原票余额；之前的分摊按累计占比计算，早期金额不受未来退货影响。
            BigDecimal cumulativeNet = allocate(original.net, nextQty, original.qty);
            BigDecimal net = cumulativeNet.subtract(usedNet.getOrDefault(outLineId, BigDecimal.ZERO));
            // 🔴 2026-10-18(计算审查 U35):税额 = 这笔退货的含税冲减额(退货单上已审批的 sale_amount,与应收冲减同一个数) − 净额。
            //    以前净额、税额各自按累计数量分摊再相加,部分退货时与应收冲减差一分(100 元的票两次各退 1/3:
            //    应收冲 33.33+33.33,报表冲 66.67)。退货单没记冲减额的老数据,仍按原比例分税额。
            BigDecimal refund = row.get("refund") == null ? null : dec(row.get("refund"));
            BigDecimal cumulativeTax = allocate(original.tax, nextQty, original.qty);
            BigDecimal tax = refund != null && refund.signum() > 0
                    ? refund.subtract(net)
                    : cumulativeTax.subtract(usedTax.getOrDefault(outLineId, BigDecimal.ZERO));
            if (refund != null && refund.signum() > 0) cumulativeTax = usedTax.getOrDefault(outLineId, BigDecimal.ZERO).add(tax);
            usedQty.put(outLineId, nextQty);
            usedNet.put(outLineId, cumulativeNet);
            usedTax.put(outLineId, cumulativeTax);
            Amount amount = new Amount(dec(row.get("qty")), net, tax, dec(row.get("cost")), original.rate, original.rate);
            // 票/退货 updated_at 可能仍是建单或提交时刻；只信最终审批实例的完成时刻。
            LocalDateTime returnApprovedAt = timeOrNull(row.get("return_approved_at"));
            boolean relevant = inWindow(original.invoiceDate, start, end) || inWindow(returnDate, start, end);
            if (original.approvedAt == null || returnApprovedAt == null
                    || original.approvedAt.isEqual(returnApprovedAt)) {
                if (relevant) throw new BizException("销售发票 " + original.invoiceNo + " 与退货单 "
                        + row.get("return_no") + " 审批先后无法确定，请财务核对历史审批记录；报表暂停签字");
                continue;
            }
            boolean early = returnApprovedAt.isBefore(original.approvedAt);
            int businessOrder = YearMonth.from(returnDate).compareTo(YearMonth.from(original.invoiceDate));
            if ((businessOrder < 0 && !early) || (businessOrder > 0 && early)) {
                if (relevant) throw new BizException("销售收入成本报表存在跨月审批反序：退货单 " + row.get("return_no")
                        + "（" + returnDate + "）与发票 " + original.invoiceNo + "（" + original.invoiceDate
                        + "）的业务月份和审批先后相反。请财务核对原单及结账期间并人工处理；报表暂停签字");
                continue;
            }
            Map<Key, Amount> target = early ? beforeInvoice : afterInvoice;
            Key key = new Key(early ? original.invoiceId : num(row.get("return_id")), original.materialId);
            target.merge(key, amount, Amount::plus);
        }
    }

    private static BigDecimal allocate(BigDecimal total, BigDecimal used, BigDecimal all) {
        if (all.signum() <= 0) throw new BizException("原发票数量异常，请先核对销售单据");
        return used.compareTo(all) == 0 ? total : total.multiply(used).divide(all, 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal dec(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
    private static long num(Object value) { return ((Number) value).longValue(); }
    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(value.toString());
    }
    private static boolean inWindow(LocalDate value, LocalDate start, LocalDate end) {
        return !value.isBefore(start) && !value.isAfter(end);
    }
    private static LocalDateTime timeOrNull(Object value) {
        if (value == null) return null;
        return value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
