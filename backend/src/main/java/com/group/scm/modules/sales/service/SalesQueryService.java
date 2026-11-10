package com.group.scm.modules.sales.service;

import com.group.scm.common.ResultCap;

import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 销售执行查询：销售订单执行(已收款未安装) / 销售出库执行(已出库未开票)。
 */
@Service
@RequiredArgsConstructor
public class SalesQueryService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    /** 销售订单执行查询。onlyUnshipped=true 时只看「已收款未安装(未出完)」。 */
    public List<Map<String, Object>> orderExecution(boolean onlyUnshipped, String keyword, LocalDate startDate, LocalDate endDate) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        String ph = orgIds.stream().map(x -> "?").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder(
                "SELECT id, doc_no, order_date, order_type, " +
                "COALESCE(NULLIF(customer_name,''), customer_unit) customer, sales_person, " +
                "total_qty, out_qty, invoice_qty, receipt_amount, total_amt, status " +
                "FROM biz_so WHERE deleted = 0 AND status IN ('APPROVED','PARTIAL_OUT','OUT') AND org_id IN (" + ph + ") ");
        List<Object> args = new ArrayList<>(orgIds);
        if (onlyUnshipped) sql.append("AND out_qty < total_qty AND receipt_amount > 0 ");
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (doc_no LIKE ? OR customer_name LIKE ? OR customer_unit LIKE ?) ");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
        if (startDate != null) { sql.append("AND order_date >= ? "); args.add(startDate); }
        if (endDate != null) { sql.append("AND order_date <= ? "); args.add(endDate); }
        sql.append("ORDER BY order_date DESC, id DESC LIMIT 1000");
        List<Map<String, Object>> rows = ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 1000);
        for (Map<String, Object> r : rows) {
            java.math.BigDecimal tot = num(r.get("total_qty")), out = num(r.get("out_qty"));
            r.put("wait_out_qty", tot.subtract(out));
        }
        return rows;
    }

    /** 销售出库执行查询。onlyUninvoiced=true 时只看「已出库未开票」。 */
    public List<Map<String, Object>> outExecution(boolean onlyUninvoiced, String keyword, LocalDate startDate, LocalDate endDate) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        String ph = orgIds.stream().map(x -> "?").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder(
                "SELECT o.id, o.doc_no, o.source_so_no, o.out_date, " +
                "COALESCE(NULLIF(o.customer_name,''), o.customer_unit) customer, o.sales_person, " +
                "o.total_qty, o.total_amt, " +
                // 2026-10-15:补已审批销售退货(数量/回冲成本),执行查询要能看出净出库,同材料出库扣退料的口径
                "(SELECT COALESCE(SUM(r.total_qty),0) FROM biz_sale_return r WHERE r.source_out_id = o.id AND r.deleted = 0 AND r.status = 'APPROVED') return_qty, " +
                "(SELECT COALESCE(SUM(r.total_amt),0) FROM biz_sale_return r WHERE r.source_out_id = o.id AND r.deleted = 0 AND r.status = 'APPROVED') return_amt, " +
                "(SELECT COUNT(*) FROM biz_sale_invoice i WHERE i.source_out_id = o.id AND i.deleted = 0 AND i.status <> 'REJECTED') inv_cnt " +
                "FROM biz_sale_out o WHERE o.deleted = 0 AND o.status = 'APPROVED' AND o.org_id IN (" + ph + ") ");
        List<Object> args = new ArrayList<>(orgIds);
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (o.doc_no LIKE ? OR o.customer_name LIKE ? OR o.source_so_no LIKE ?) ");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
        if (startDate != null) { sql.append("AND o.out_date >= ? "); args.add(startDate); }
        if (endDate != null) { sql.append("AND o.out_date <= ? "); args.add(endDate); }
        if (onlyUninvoiced) sql.append("AND NOT EXISTS (SELECT 1 FROM biz_sale_invoice i2 WHERE i2.source_out_id = o.id AND i2.deleted = 0 AND i2.status <> 'REJECTED') ");
        sql.append("ORDER BY o.out_date DESC, o.id DESC LIMIT 1000");
        List<Map<String, Object>> rows = ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 1000);
        boolean canSeeCost = com.group.scm.common.SecurityUtil.hasFinanceAccessOrAdmin();
        for (Map<String, Object> r : rows) {
            long cnt = r.get("inv_cnt") == null ? 0 : ((Number) r.get("inv_cnt")).longValue();
            r.put("invoiced", cnt > 0);
            r.put("net_qty", num(r.get("total_qty")).subtract(num(r.get("return_qty"))));
            if (!canSeeCost) { r.put("total_amt", null); r.put("return_amt", null); } // 与材料出库同口径：纯仓管不见成本
        }
        return rows;
    }

    private java.math.BigDecimal num(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal b) return b;
        return new java.math.BigDecimal(o.toString());
    }
}
