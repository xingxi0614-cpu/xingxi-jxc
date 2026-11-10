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
 * 销售明细查询报表：销售订单/出库/发票/退货，按物料明细行输出，供查询与导出。
 */
@Service
@RequiredArgsConstructor
public class SalesReportService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    public record Filter(LocalDate startDate, LocalDate endDate, Long customerId, Long materialId, String status, String keyword) {}

    /** 销售订单明细查询 */
    public List<Map<String, Object>> orderQuery(Filter f) {
        List<Object> args = new ArrayList<>();
        String scope = orgScopeClause("s", args);
        if (scope == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT s.doc_no, s.order_date biz_date, s.order_type, " +
                "COALESCE(NULLIF(s.customer_name,''), s.customer_unit) customer, s.sales_person, s.status, " +
                "m.code material_code, m.name material_name, m.spec, m.unit, " +
                "l.qty, l.tax_price, l.tax_rate, l.amount, l.amount_excl_tax, l.tax_amount, l.out_qty, l.invoice_qty " +
                "FROM biz_so s JOIN biz_so_line l ON l.so_id = s.id JOIN md_material m ON m.id = l.material_id " +
                "WHERE s.deleted = 0 AND " + scope + " ");
        appendCommon(sql, args, f, "s", "s.order_date");
        sql.append("ORDER BY s.order_date DESC, s.id DESC, l.line_no LIMIT 5000");
        return ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 5000);
    }

    /** 销售出库明细查询 */
    public List<Map<String, Object>> outQuery(Filter f) {
        List<Object> args = new ArrayList<>();
        String scope = orgScopeClause("o", args);
        if (scope == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT o.doc_no, o.source_so_no, o.out_date biz_date, " +
                "COALESCE(NULLIF(o.customer_name,''), o.customer_unit) customer, o.sales_person, o.status, " +
                "m.code material_code, m.name material_name, m.spec, m.unit, " +
                "l.qty, l.unit_price, l.amount " +
                "FROM biz_sale_out o JOIN biz_sale_out_line l ON l.out_id = o.id JOIN md_material m ON m.id = l.material_id " +
                "WHERE o.deleted = 0 AND " + scope + " ");
        appendCommon(sql, args, f, "o", "o.out_date");
        sql.append("ORDER BY o.out_date DESC, o.id DESC, l.line_no LIMIT 5000");
        return ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 5000);
    }

    /** 销售发票明细查询 */
    public List<Map<String, Object>> invoiceQuery(Filter f) {
        List<Object> args = new ArrayList<>();
        String scope = orgScopeClause("v", args);
        if (scope == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT v.doc_no, v.invoice_type, v.invoice_no, v.source_out_no, v.invoice_date biz_date, " +
                "COALESCE(NULLIF(v.customer_name,''), v.customer_unit) customer, v.status, " +
                "m.code material_code, m.name material_name, m.spec, m.unit, " +
                "l.qty, l.tax_rate, l.amount_excl_tax, l.tax_amount, l.amount " +
                "FROM biz_sale_invoice v JOIN biz_sale_invoice_line l ON l.invoice_id = v.id JOIN md_material m ON m.id = l.material_id " +
                "WHERE v.deleted = 0 AND " + scope + " ");
        appendCommon(sql, args, f, "v", "v.invoice_date");
        sql.append("ORDER BY v.invoice_date DESC, v.id DESC, l.line_no LIMIT 5000");
        return ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 5000);
    }

    /** 销售退货明细查询 */
    public List<Map<String, Object>> returnQuery(Filter f) {
        List<Object> args = new ArrayList<>();
        String scope = orgScopeClause("r", args);
        if (scope == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT r.doc_no, r.source_out_no, r.return_date biz_date, " +
                "COALESCE(NULLIF(r.customer_name,''), r.customer_unit) customer, r.status, " +
                "m.code material_code, m.name material_name, m.spec, m.unit, " +
                "l.qty, l.unit_price, l.amount, l.sale_amount " +
                "FROM biz_sale_return r JOIN biz_sale_return_line l ON l.return_id = r.id JOIN md_material m ON m.id = l.material_id " +
                "WHERE r.deleted = 0 AND " + scope + " ");
        appendCommon(sql, args, f, "r", "r.return_date");
        sql.append("ORDER BY r.return_date DESC, r.id DESC, l.line_no LIMIT 5000");
        return ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 5000);
    }

    private String orgScopeClause(String alias, List<Object> args) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return null;
        String ph = orgIds.stream().map(x -> "?").collect(Collectors.joining(","));
        args.addAll(orgIds);
        return alias + ".org_id IN (" + ph + ")";
    }

    private void appendCommon(StringBuilder sql, List<Object> args, Filter f, String alias, String dateCol) {
        if (f.startDate() != null) { sql.append("AND ").append(dateCol).append(" >= ? "); args.add(f.startDate()); }
        if (f.endDate() != null) { sql.append("AND ").append(dateCol).append(" <= ? "); args.add(f.endDate()); }
        if (f.customerId() != null) { sql.append("AND ").append(alias).append(".customer_id = ? "); args.add(f.customerId()); }
        if (f.materialId() != null) { sql.append("AND l.material_id = ? "); args.add(f.materialId()); }
        if (f.status() != null && !f.status().isBlank()) { sql.append("AND ").append(alias).append(".status = ? "); args.add(f.status()); }
        if (f.keyword() != null && !f.keyword().isBlank()) {
            sql.append("AND (").append(alias).append(".doc_no LIKE ? OR ").append(alias).append(".customer_name LIKE ? OR ").append(alias).append(".customer_unit LIKE ?) ");
            String kw = "%" + f.keyword().trim() + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
    }
}
