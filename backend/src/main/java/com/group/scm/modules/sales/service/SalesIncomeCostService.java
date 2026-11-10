package com.group.scm.modules.sales.service;

import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售收入成本明细表（2026-10-01 定口径，打印给财务签字用）。
 *
 * <p><b>范围</b>：只取销售模块 —— 销售发票(收入) + 来源销售出库(成本) + 销售退货(冲减)。
 * 账面口径是"商品销售"。材料出库/领料（维修改造等）走的是另一条链，<b>不在本表</b>，
 * 也不要拿「出库类型→科目」对照来分类（那张表管的是材料出库，别混）。</p>
 *
 * <p><b>收入</b>：已审批销售发票的不含税金额，按<b>开票日期</b>归月（审批通过即确认收入，见 SaleInvoiceService）。</p>
 *
 * <p><b>成本</b>：发票来源出库单里同物料的出库成本（出库审批时按移动加权结转，已落在 biz_sale_out_line.amount）。
 * 一张销售发票必定对应一整张出库单（SaleInvoiceService.fromSaleOut 按出库单整单开票、且一单只能开一张有效发票）。
 * 展示按「发票 × 物料」汇总；退货金额按建票时保留的行号与来源出库行配对，以免同物料不同售价错位。
 * 历史行号无法对应时拒绝静默出错表，请先核对源单。</p>
 *
 * <p><b>退货</b>：已审批销售退货按<b>退货日期冲在当月</b>（定：已结账月份不回头改）。
 * 收入及税额以原发票逐行金额按退货数量累计分摊，尾笔吸收分币；成本取审批回冲额。</p>
 *
 * <p><b>税率</b>：销售模块统一 13%（scm.sales.tax-rate），新发票建单已锁死。
 * 表里若遇到不是 13% 的历史发票，照实列出并打 rateWarn 标记让财务核对，绝不在报表里改数。</p>
 *
 * <p><b>公司</b>：账套下挂着部门级组织（如"技术部"），用户要求表头只体现公司。
 * 所以按组织树往上找到 COMPANY 级，把同一公司下各部门的数据合在一起出表。</p>
 */
@Service
@RequiredArgsConstructor
public class SalesIncomeCostService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 一次最多查 24 个月，防止误选超大区间把库拖慢 */
    private static final long MAX_MONTHS = 24;

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    @Value("${scm.sales.tax-rate:13}")
    private BigDecimal salesTaxRate;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> report(Long companyId, LocalDate start, LocalDate end) {
        if (start == null || end == null) throw new BizException("请选择开票期间");
        if (end.isBefore(start)) throw new BizException("结束日期不能早于开始日期");
        if (ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1)) >= MAX_MONTHS)
            throw new BizException("一次最多查询 " + MAX_MONTHS + " 个月");

        List<Long> visible = orgScope.visibleOrgIds();
        if (visible.isEmpty()) throw new BizException("当前账号没有可查询的公司");
        Map<Long, Map<String, Object>> orgs = new HashMap<>();
        for (Map<String, Object> o : jdbc.queryForList("SELECT id, parent_id, name, type FROM sys_org WHERE deleted = 0"))
            orgs.put(((Number) o.get("id")).longValue(), o);

        // 可见组织 → 所属公司；同一公司的部门合并
        Map<Long, List<Long>> orgsByCompany = new LinkedHashMap<>();
        for (Long id : visible.stream().sorted().toList())
            orgsByCompany.computeIfAbsent(companyOf(id, orgs), k -> new ArrayList<>()).add(id);
        List<Map<String, Object>> companies = orgsByCompany.keySet().stream()
                .map(id -> Map.<String, Object>of("id", id, "name", nameOf(id, orgs))).toList();
        Long company = companyId != null ? companyId : orgsByCompany.keySet().iterator().next();
        List<Long> orgIds = orgsByCompany.get(company);
        // 只能查自己看得见的公司（越权传别家 companyId 一律拒绝）
        if (orgIds == null) throw new BizException("无权查看该公司数据");

        SalesReturnAllocation allocation = new SalesReturnAllocation(jdbc, orgIds, start, end);
        List<Map<String, Object>> detail = new ArrayList<>(invoiceRows(orgIds, start, end, allocation));
        detail.addAll(returnRows(orgIds, start, end, allocation));
        detail.sort(Comparator.comparing((Map<String, Object> r) -> r.get("biz_date").toString())
                .thenComparing(r -> r.get("doc_no").toString()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", Map.of("id", company, "name", nameOf(company, orgs)));
        result.put("companies", companies);
        result.put("taxRate", salesTaxRate);
        result.put("startDate", start);
        result.put("endDate", end);
        result.put("detail", detail);
        result.put("summary", monthly(detail));
        result.put("total", totals(detail, "合计"));
        result.put("uninvoiced", uninvoiced(orgIds, end));
        result.put("unapprovedInvoices", unapprovedInvoices(orgIds, start, end));
        return result;
    }

    /** 已审批销售发票，按「发票 × 物料」汇总收入，并配上来源出库单同物料的成本 */
    private List<Map<String, Object>> invoiceRows(List<Long> orgIds, LocalDate start, LocalDate end, SalesReturnAllocation allocation) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(start);
        args.add(end);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.id doc_id, l.material_id, v.doc_no, v.invoice_no, v.invoice_date biz_date, v.source_out_no,
                       COALESCE(NULLIF(v.customer_name,''), v.customer_unit) customer,
                       m.code material_code, m.name material_name, m.spec, m.unit,
                       SUM(l.qty) qty, SUM(l.amount_excl_tax) revenue, SUM(l.tax_amount) tax, SUM(l.amount) total,
                       MIN(l.tax_rate) min_rate, MAX(l.tax_rate) max_rate,
                       (SELECT COALESCE(SUM(ol.amount), 0) FROM biz_sale_out_line ol
                         WHERE ol.out_id = v.source_out_id AND ol.material_id = l.material_id) cost
                FROM biz_sale_invoice v
                JOIN biz_sale_invoice_line l ON l.invoice_id = v.id
                JOIN md_material m ON m.id = l.material_id
                WHERE v.deleted = 0 AND v.status = 'APPROVED'
                  AND v.org_id IN (%s) AND v.invoice_date >= ? AND v.invoice_date <= ?
                GROUP BY v.id, l.material_id, m.id
                ORDER BY v.invoice_date, v.id, MIN(l.line_no)
                """.formatted(ph(orgIds.size())), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("kind", "INVOICE");
            SalesReturnAllocation.Amount early = allocation.beforeInvoice.getOrDefault(
                    new SalesReturnAllocation.Key(((Number) r.get("doc_id")).longValue(), ((Number) r.remove("material_id")).longValue()),
                    SalesReturnAllocation.ZERO);
            // 先退后开票：已退数量和原票对应收入在开票月抵掉，退货月从未确认收入不作负数。
            r.put("qty", dec(r.get("qty")).subtract(early.qty()));
            r.put("revenue", dec(r.get("revenue")).subtract(early.revenue()));
            r.put("tax", dec(r.get("tax")).subtract(early.tax()));
            r.put("total", dec(r.get("revenue")).add(dec(r.get("tax"))));
            r.put("cost", dec(r.get("cost")).subtract(early.cost()));
            BigDecimal min = dec(r.remove("min_rate")), max = dec(r.remove("max_rate"));
            r.put("tax_rate", max);
            r.put("min_tax_rate", min);
            r.put("max_tax_rate", max);
            r.put("mixed_rate", min.compareTo(max) != 0);
            r.put("rate_warn", min.compareTo(salesTaxRate) != 0 || max.compareTo(salesTaxRate) != 0);
            fillProfit(r);
        }
        return rows;
    }

    /** 已审批销售退货：按退货日期冲在当月，收入/税额/成本/数量全部取负 */
    private List<Map<String, Object>> returnRows(List<Long> orgIds, LocalDate start, LocalDate end, SalesReturnAllocation allocation) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(start);
        args.add(end);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id doc_id, l.material_id, r.doc_no, '' invoice_no, r.return_date biz_date, r.source_out_no,
                       COALESCE(NULLIF(r.customer_name,''), r.customer_unit) customer,
                       m.code material_code, m.name material_name, m.spec, m.unit,
                       SUM(l.qty) qty, SUM(l.sale_amount) sale_amount, SUM(l.amount) cost
                FROM biz_sale_return r
                JOIN biz_sale_return_line l ON l.return_id = r.id
                JOIN md_material m ON m.id = l.material_id
                WHERE r.deleted = 0 AND r.status = 'APPROVED'
                  AND r.org_id IN (%s) AND r.return_date >= ? AND r.return_date <= ?
                GROUP BY r.id, l.material_id, m.id
                ORDER BY r.return_date, r.id, MIN(l.line_no)
                """.formatted(ph(orgIds.size())), args.toArray());
        rows.removeIf(r -> !allocation.afterInvoice.containsKey(new SalesReturnAllocation.Key(
                ((Number) r.get("doc_id")).longValue(), ((Number) r.get("material_id")).longValue())));
        for (Map<String, Object> r : rows) {
            SalesReturnAllocation.Amount amount = allocation.afterInvoice.get(new SalesReturnAllocation.Key(
                    ((Number) r.get("doc_id")).longValue(), ((Number) r.remove("material_id")).longValue()));
            r.remove("sale_amount");
            r.put("kind", "RETURN");
            r.put("qty", amount.qty().negate());
            r.put("revenue", amount.revenue().negate());
            r.put("tax", amount.tax().negate());
            r.put("total", amount.revenue().add(amount.tax()).negate());
            r.put("cost", amount.cost().negate());
            r.put("tax_rate", amount.maxRate());
            r.put("min_tax_rate", amount.minRate());
            r.put("max_tax_rate", amount.maxRate());
            r.put("mixed_rate", amount.minRate().compareTo(amount.maxRate()) != 0);
            r.put("rate_warn", amount.minRate().compareTo(salesTaxRate) != 0
                    || amount.maxRate().compareTo(salesTaxRate) != 0);
            fillProfit(r);
        }
        return rows;
    }

    /** 已审批出库、但还没有已审批发票的（收入可能漏记）；已全部退货的不再列 */
    private List<Map<String, Object>> uninvoiced(List<Long> orgIds, LocalDate end) {
        List<Object> args = new ArrayList<>();
        args.add(end); // 已退成本截至期末
        args.add(end); // 发票状态截至期末
        args.add(end); // 已退数量截至期末
        args.addAll(orgIds);
        args.add(end); // 出库截至期末
        args.add(end); // 已审批发票截至期末
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.id, o.doc_no, o.out_date, COALESCE(NULLIF(o.customer_name,''), o.customer_unit) customer,
                       (SELECT COUNT(*) FROM biz_sale_out_line ol WHERE ol.out_id = o.id) line_count,
                       o.total_amt - COALESCE((SELECT SUM(r.total_amt) FROM biz_sale_return r
                          WHERE r.source_out_id = o.id AND r.deleted = 0 AND r.status = 'APPROVED' AND r.return_date <= ?), 0) cost,
                       (SELECT v.status FROM biz_sale_invoice v WHERE v.source_out_id = o.id AND v.deleted = 0
                          AND v.status <> 'REJECTED' AND v.invoice_date <= ? ORDER BY v.id DESC LIMIT 1) invoice_status,
                       (SELECT COALESCE(SUM(ol.qty - COALESCE((SELECT SUM(rl.qty) FROM biz_sale_return_line rl
                         JOIN biz_sale_return sr ON sr.id = rl.return_id WHERE rl.source_out_line_id = ol.id
                         AND sr.deleted = 0 AND sr.status = 'APPROVED' AND sr.return_date <= ?),0)),0)
                         FROM biz_sale_out_line ol WHERE ol.out_id = o.id) remaining_qty
                FROM biz_sale_out o
                WHERE o.deleted = 0 AND o.status = 'APPROVED' AND o.org_id IN (%s) AND o.out_date <= ?
                  AND NOT EXISTS (SELECT 1 FROM biz_sale_invoice v WHERE v.source_out_id = o.id
                                   AND v.deleted = 0 AND v.status = 'APPROVED' AND v.invoice_date <= ?)
                ORDER BY o.out_date, o.id
                """.formatted(ph(orgIds.size())), args.toArray());
        rows.removeIf(r -> dec(r.get("remaining_qty")).signum() <= 0);
        // 已出库天数在应用侧算：数据库时区可能是 UTC(测试库就是)，用 CURDATE() 会多算/少算一天
        LocalDate today = end;
        for (Map<String, Object> r : rows) {
            Object d = r.get("out_date");
            LocalDate out = d instanceof java.sql.Date sd ? sd.toLocalDate() : LocalDate.parse(String.valueOf(d));
            r.put("days", ChronoUnit.DAYS.between(out, today));
        }
        return rows;
    }

    /** 期间内已开但还没审批通过的发票张数：不计入收入，页面上提醒一句 */
    private int unapprovedInvoices(List<Long> orgIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(start);
        args.add(end);
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_sale_invoice
                WHERE deleted = 0 AND status IN ('DRAFT','PENDING') AND org_id IN (%s)
                  AND invoice_date >= ? AND invoice_date <= ?
                """.formatted(ph(orgIds.size())), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 按月汇总：发票张数只数发票（退货不算张数），退货冲减单列一栏 */
    private List<Map<String, Object>> monthly(List<Map<String, Object>> detail) {
        Map<String, List<Map<String, Object>>> byMonth = new TreeMap<>();
        for (Map<String, Object> r : detail)
            byMonth.computeIfAbsent(r.get("biz_date").toString().substring(0, 7), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        byMonth.forEach((month, rows) -> out.add(totals(rows, month)));
        return out;
    }

    private Map<String, Object> totals(List<Map<String, Object>> rows, String label) {
        BigDecimal revenue = sum(rows, "revenue", null), tax = sum(rows, "tax", null), cost = sum(rows, "cost", null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", label);
        m.put("invoice_count", rows.stream().filter(r -> "INVOICE".equals(r.get("kind")))
                .map(r -> r.get("doc_id")).collect(Collectors.toSet()).size());
        m.put("revenue", revenue);
        m.put("return_revenue", sum(rows, "revenue", "RETURN"));
        m.put("tax", tax);
        m.put("total", revenue.add(tax));
        m.put("cost", cost);
        BigDecimal profit = revenue.subtract(cost);
        m.put("profit", profit);
        m.put("margin", margin(profit, revenue));
        return m;
    }

    private void fillProfit(Map<String, Object> r) {
        BigDecimal revenue = dec(r.get("revenue")), profit = revenue.subtract(dec(r.get("cost")));
        r.put("profit", profit);
        r.put("margin", margin(profit, revenue));
    }

    /** 毛利率(%)，保留 1 位；收入为 0 时返回 null（页面显示"—"，不做除零） */
    private static BigDecimal margin(BigDecimal profit, BigDecimal revenue) {
        if (revenue.signum() == 0) return null;
        return profit.multiply(HUNDRED).divide(revenue, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key, String kind) {
        return rows.stream().filter(r -> kind == null || kind.equals(r.get("kind")))
                .map(r -> dec(r.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    /** 沿组织树往上找到公司级(type=COMPANY)；找不到就用最顶上那一级 */
    private static Long companyOf(Long id, Map<Long, Map<String, Object>> orgs) {
        Long cur = id;
        for (int guard = 0; guard < 20 && cur != null; guard++) {
            Map<String, Object> o = orgs.get(cur);
            if (o == null) return cur;
            if ("COMPANY".equals(o.get("type"))) return cur;
            Object parent = o.get("parent_id");
            Long pid = parent == null ? null : ((Number) parent).longValue();
            if (pid == null || pid == 0 || !orgs.containsKey(pid)) return cur;
            cur = pid;
        }
        return id;
    }

    private static String nameOf(Long id, Map<Long, Map<String, Object>> orgs) {
        Map<String, Object> o = orgs.get(id);
        return o == null ? String.valueOf(id) : String.valueOf(o.get("name"));
    }

    private static String ph(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
