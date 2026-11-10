package com.group.scm.modules.report;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 集团汇总分析：把各公司(账套)的关键数并排摆出来，供系统管理员一眼看出哪家异常。
 *
 * 口径(改数之前必读，别和别的报表算出两个数)：
 * - 金额除「应付余额」外一律不含税；应付余额是欠供应商的钱，按含税算，和付款登记(含税)才对得上。
 * - 应付余额 = 已审核发票(含税) + 「发票入库」里还没开票的部分(折成含税) − 已登记付款，
 *   与 ReportController 财务核对台账的算法一致。
 * - 已入库未开票 = 所有已审批入库单里还没开票、也没退给供应商的金额(暂估入库 + 发票入库都算)。
 *   注意它比财务核对台账的「暂估未开票」范围大：那边只算暂估入库。这里要回答的是
 *   "货到了、系统里却没有发票的有多少"，所以两类都算。
 * - 库存 / 未开票 / 应付 / 待审批 / 结账进度 是此刻的余额，不随统计期间变；采购额、出库额才按期间。
 * - 待审批按"提交人所在公司"归属：审批流水表本身不带公司，而提交人只能给自己公司建单。
 *
 * 范围：本表不走"顶栏查看公司"的收窄(OrgScopeService)——它本来就是跨公司对比用的，
 * 只在 Controller 处限定系统管理员可访问。
 */
@Service
@RequiredArgsConstructor
public class GroupOverviewService {

    private final JdbcTemplate jdbc;

    /** 不计入汇总的公司编码：测试账套、演示账套的数是假的，混进来会污染集团合计。 */
    @Value("${scm.overview.exclude-org-codes:DEMO}")
    private String excludeOrgCodes;

    private static final int AGING_DAYS = 90;      // 未开票挂账超过这么多天算"久"
    private static final int PENDING_STALE_DAYS = 7;
    private static final int TOP_N = 10;

    private record Company(Long id, String code, String name, int sort) {}

    public Map<String, Object> overview(String period) {
        LocalDate today = LocalDate.now();
        LocalDate from, to;
        String periodLabel;
        switch (period == null ? "month" : period) {
            case "last" -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                from = first; to = first.withDayOfMonth(first.lengthOfMonth());
                periodLabel = first.getYear() + " 年 " + first.getMonthValue() + " 月";
            }
            case "ytd" -> {
                from = today.withDayOfYear(1); to = today;
                periodLabel = today.getYear() + " 年 1 月 – 今天";
            }
            default -> {
                from = today.withDayOfMonth(1); to = today;
                periodLabel = today.getYear() + " 年 " + today.getMonthValue() + " 月（截至今天）";
            }
        }

        // ── 组织 → 所属公司 ──
        Map<Long, Company> companies = new LinkedHashMap<>();
        Map<Long, Long> orgToCompany = orgToCompany(companies);
        List<Long> orgIds = new ArrayList<>(orgToCompany.keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodLabel", periodLabel);
        out.put("lastFullMonth", today.withDayOfMonth(1).minusMonths(1).toString().substring(0, 7));
        out.put("agingDays", AGING_DAYS);
        out.put("pendingStaleDays", PENDING_STALE_DAYS);
        if (orgIds.isEmpty()) {
            out.put("companies", List.of()); out.put("idle", List.of());
            out.put("estimateTop", List.of()); out.put("pendingTop", List.of()); out.put("stockTop", List.of());
            return out;
        }
        String in = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        Object[] ids = orgIds.toArray();

        // ── 各项指标：先按原始组织汇总，再折到公司 ──
        Map<Long, Map<String, BigDecimal>> m = new HashMap<>();
        roll(m, orgToCompany, "stock", "SELECT w.org_id o, COALESCE(SUM(b.amount),0) v FROM inv_balance b "
                + "JOIN md_warehouse w ON w.id = b.warehouse_id WHERE w.org_id IN (" + in + ") GROUP BY w.org_id", ids);
        roll(m, orgToCompany, "stockRows", "SELECT w.org_id o, COUNT(*) v FROM inv_balance b "
                + "JOIN md_warehouse w ON w.id = b.warehouse_id WHERE b.qty <> 0 AND w.org_id IN (" + in + ") GROUP BY w.org_id", ids);
        // 采购额：期间内已审批生效的订单(待审批/草稿/作废不算)，行上没存不含税额的老数据按税率折算
        roll(m, orgToCompany, "buy", "SELECT p.org_id o, COALESCE(SUM(COALESCE(pl.amount_excl_tax, "
                + "ROUND(pl.amount / (1 + COALESCE(pl.tax_rate,0)/100), 2))),0) v "
                + "FROM biz_po p JOIN biz_po_line pl ON pl.po_id = p.id "
                + "WHERE p.deleted = 0 AND p.status IN ('APPROVED','PARTIAL_RECEIVED','RECEIVED') "
                + "AND p.order_date BETWEEN ? AND ? AND p.org_id IN (" + in + ") GROUP BY p.org_id", with(ids, from, to));
        // 出库额：期间内已审批(=已扣库存、已过账成本)的材料出库单，减去期间内已审批退料(按退料日期)。
        // 2026-10-15:原来只算出库不扣退料,与出库汇总表/计划执行表口径不一致。roll 对同一 key 累加,负数即冲减。
        roll(m, orgToCompany, "out", "SELECT org_id o, COALESCE(SUM(total_amt),0) v FROM biz_out "
                + "WHERE deleted = 0 AND status = 'APPROVED' AND out_date BETWEEN ? AND ? AND org_id IN (" + in + ") GROUP BY org_id",
                with(ids, from, to));
        roll(m, orgToCompany, "out", "SELECT org_id o, -COALESCE(SUM(total_amt),0) v FROM biz_return "
                + "WHERE deleted = 0 AND status = 'APPROVED' AND return_date BETWEEN ? AND ? AND org_id IN (" + in + ") GROUP BY org_id",
                with(ids, from, to));
        roll(m, orgToCompany, "docs", "SELECT org_id o, COUNT(*) v FROM (SELECT org_id FROM biz_po WHERE deleted = 0 "
                + "UNION ALL SELECT org_id FROM biz_recv WHERE deleted = 0 UNION ALL SELECT org_id FROM biz_out WHERE deleted = 0) t "
                + "WHERE org_id IN (" + in + ") GROUP BY org_id", ids);

        // 已入库未开票(不含税)：行级余量 = 入库数 − 已开票 − 已退供应商
        String remQty = "(rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0))";
        String recvFrom = "FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id AND rl.qty > 0 "
                + "WHERE r.deleted = 0 AND r.status = 'APPROVED' AND r.org_id IN (" + in + ") ";
        roll(m, orgToCompany, "uninvoiced", "SELECT r.org_id o, COALESCE(SUM(ROUND(rl.amount * " + remQty + " / rl.qty, 2)),0) v "
                + recvFrom + "AND " + remQty + " > 0 GROUP BY r.org_id", ids);
        roll(m, orgToCompany, "uninvoicedOld", "SELECT r.org_id o, COALESCE(SUM(ROUND(rl.amount * " + remQty + " / rl.qty, 2)),0) v "
                + "FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id AND rl.qty > 0 "
                + "WHERE r.deleted = 0 AND r.status = 'APPROVED' AND r.recv_date < ? AND r.org_id IN (" + in + ") "
                + "AND " + remQty + " > 0 GROUP BY r.org_id", with(ids, today.minusDays(AGING_DAYS)));
        // 发票已录比例的分子分母：已开票金额 / 应开票金额(扣掉退货)
        roll(m, orgToCompany, "recvDue", "SELECT r.org_id o, COALESCE(SUM(ROUND(rl.amount * (rl.qty - COALESCE(rl.returned_qty,0)) / rl.qty, 2)),0) v "
                + recvFrom + "GROUP BY r.org_id", ids);
        roll(m, orgToCompany, "recvInvoiced", "SELECT r.org_id o, COALESCE(SUM(ROUND(rl.amount * LEAST(COALESCE(rl.invoiced_qty,0), "
                + "rl.qty - COALESCE(rl.returned_qty,0)) / rl.qty, 2)),0) v " + recvFrom + "GROUP BY r.org_id", ids);

        // 应付余额(含税)，三段与财务核对台账一致
        roll(m, orgToCompany, "payable", "SELECT org_id o, COALESCE(SUM(total_amt),0) v FROM biz_invoice "
                + "WHERE deleted = 0 AND status IN ('APPROVED','MATCHED') AND org_id IN (" + in + ") GROUP BY org_id", ids);
        roll(m, orgToCompany, "payable", "SELECT x.org_id o, COALESCE(SUM(CASE WHEN pl.id IS NOT NULL AND pl.qty > 0 AND pl.amount IS NOT NULL "
                + "THEN ROUND(pl.amount * " + remQty + " / pl.qty, 2) "
                + "ELSE ROUND(rl.amount * " + remQty + " / rl.qty * (1 + COALESCE(pl.tax_rate,0)/100), 2) END),0) v "
                + "FROM biz_recv x JOIN biz_recv_line rl ON rl.recv_id = x.id AND rl.qty > 0 "
                + "LEFT JOIN biz_po_line pl ON pl.id = rl.source_po_line_id "
                + "WHERE x.deleted = 0 AND x.recv_type = 'INVOICE' AND x.status = 'APPROVED' AND x.org_id IN (" + in + ") GROUP BY x.org_id", ids);
        roll(m, orgToCompany, "paid", "SELECT org_id o, COALESCE(SUM(amount),0) v FROM biz_payment "
                + "WHERE deleted = 0 AND org_id IN (" + in + ") GROUP BY org_id", ids);
        roll(m, orgToCompany, "payCount", "SELECT org_id o, COUNT(*) v FROM biz_payment "
                + "WHERE deleted = 0 AND org_id IN (" + in + ") GROUP BY org_id", ids);

        roll(m, orgToCompany, "pending", "SELECT u.org_id o, COUNT(*) v FROM wf_instance i JOIN sys_user u ON u.id = i.applicant_id "
                + "WHERE i.status = 'PROCESSING' AND u.org_id IN (" + in + ") GROUP BY u.org_id", ids);
        roll(m, orgToCompany, "pendingStale", "SELECT u.org_id o, COUNT(*) v FROM wf_instance i JOIN sys_user u ON u.id = i.applicant_id "
                + "WHERE i.status = 'PROCESSING' AND i.started_at < ? AND u.org_id IN (" + in + ") GROUP BY u.org_id",
                with(ids, today.minusDays(PENDING_STALE_DAYS).atStartOfDay()));

        Map<Long, String> closedTo = new HashMap<>();
        jdbc.query("SELECT org_id, MAX(period_month) pm FROM fin_period_close WHERE deleted = 0 AND status = 'CLOSED' "
                + "AND org_id IN (" + in + ") GROUP BY org_id", rs -> {
            Long co = orgToCompany.get(rs.getLong("org_id"));
            String pm = rs.getString("pm");
            if (co != null && pm != null) closedTo.merge(co, pm, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }, ids);

        // ── 组装：有数据的公司进表，没任何单据和库存的只列名字 ──
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> idle = new ArrayList<>();
        for (Company c : companies.values()) {
            Map<String, BigDecimal> v = m.getOrDefault(c.id(), Map.of());
            boolean inUse = num(v, "docs").signum() > 0 || num(v, "stockRows").signum() > 0;
            if (!inUse) { idle.add(c.name()); continue; }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.id()); r.put("code", c.code()); r.put("name", c.name());
            r.put("stock", num(v, "stock"));
            r.put("buy", num(v, "buy"));
            r.put("out", num(v, "out"));
            r.put("uninvoiced", num(v, "uninvoiced"));
            r.put("uninvoicedOld", num(v, "uninvoicedOld"));
            // 一笔付款都没登记过的公司：应付余额算出来只是"发生额"，会被误读成欠款巨大，所以给 null 让前端显示"未录付款"
            r.put("payable", num(v, "payCount").signum() > 0 ? num(v, "payable").subtract(num(v, "paid")) : null);
            r.put("pending", num(v, "pending").intValue());
            r.put("pendingStale", num(v, "pendingStale").intValue());
            r.put("closedTo", closedTo.get(c.id()));
            BigDecimal due = num(v, "recvDue");
            r.put("invoiceRate", due.signum() > 0
                    ? num(v, "recvInvoiced").multiply(BigDecimal.valueOf(100)).divide(due, 0, RoundingMode.HALF_UP).intValue()
                    : null);   // 没有任何已审批入库 → 谈不上开票比例
            rows.add(r);
        }
        rows.sort((a, b) -> ((BigDecimal) b.get("stock")).compareTo((BigDecimal) a.get("stock")));
        out.put("companies", rows);
        out.put("idle", idle);

        // 排行只在"在用公司"的组织范围内取
        Set<Long> activeCos = rows.stream().map(r -> (Long) r.get("id")).collect(Collectors.toSet());
        List<Long> activeOrgs = orgIds.stream().filter(o -> activeCos.contains(orgToCompany.get(o))).toList();
        Map<Long, String> coName = companies.values().stream().collect(Collectors.toMap(Company::id, Company::name));
        out.put("estimateTop", activeOrgs.isEmpty() ? List.of() : estimateTop(activeOrgs, orgToCompany, coName, remQty));
        out.put("pendingTop", activeOrgs.isEmpty() ? List.of() : pendingTop(activeOrgs, orgToCompany, coName));
        out.put("stockTop", activeOrgs.isEmpty() ? List.of() : stockTop(activeOrgs, orgToCompany));
        return out;
    }

    /** 挂账最久的未开票入库单(按入库日期从早到晚)。 */
    private List<Map<String, Object>> estimateTop(List<Long> orgs, Map<Long, Long> orgToCompany, Map<Long, String> coName, String remQty) {
        String in = String.join(",", Collections.nCopies(orgs.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.doc_no docNo, r.org_id orgId, s.name supplier, DATEDIFF(CURDATE(), r.recv_date) days, "
              + "SUM(ROUND(rl.amount * " + remQty + " / rl.qty, 2)) amount "
              + "FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id AND rl.qty > 0 "
              + "LEFT JOIN md_supplier s ON s.id = r.supplier_id "
              + "WHERE r.deleted = 0 AND r.status = 'APPROVED' AND " + remQty + " > 0 AND r.org_id IN (" + in + ") "
              + "GROUP BY r.id, r.doc_no, r.org_id, s.name, r.recv_date ORDER BY r.recv_date ASC, r.id ASC LIMIT " + TOP_N,
                orgs.toArray());
        rows.forEach(r -> r.put("company", coName.get(orgToCompany.get(((Number) r.remove("orgId")).longValue()))));
        return rows;
    }

    /** 压得最久的待审批单据。 */
    private List<Map<String, Object>> pendingTop(List<Long> orgs, Map<Long, Long> orgToCompany, Map<Long, String> coName) {
        String in = String.join(",", Collections.nCopies(orgs.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.biz_no docNo, i.biz_type bizType, i.total_amt amount, i.next_approver_role nextRole, "
              + "DATEDIFF(CURDATE(), i.started_at) days, u.org_id orgId "
              + "FROM wf_instance i JOIN sys_user u ON u.id = i.applicant_id "
              + "WHERE i.status = 'PROCESSING' AND u.org_id IN (" + in + ") ORDER BY i.started_at ASC, i.id ASC LIMIT " + TOP_N,
                orgs.toArray());
        rows.forEach(r -> r.put("company", coName.get(orgToCompany.get(((Number) r.remove("orgId")).longValue()))));
        return rows;
    }

    /** 库存金额最大的物料(跨公司合并)，并数出有几家公司持有。 */
    private List<Map<String, Object>> stockTop(List<Long> orgs, Map<Long, Long> orgToCompany) {
        String in = String.join(",", Collections.nCopies(orgs.size(), "?"));
        Map<Long, Map<String, Object>> agg = new LinkedHashMap<>();
        Map<Long, Set<Long>> holders = new HashMap<>();
        jdbc.query("SELECT b.material_id mid, m.code, m.name, m.spec, w.org_id, SUM(b.amount) amt "
                + "FROM inv_balance b JOIN md_warehouse w ON w.id = b.warehouse_id JOIN md_material m ON m.id = b.material_id "
                + "WHERE b.qty <> 0 AND w.org_id IN (" + in + ") GROUP BY b.material_id, m.code, m.name, m.spec, w.org_id", rs -> {
            Long mid = rs.getLong("mid");
            Map<String, Object> a = agg.computeIfAbsent(mid, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("amount", BigDecimal.ZERO);
                return x;
            });
            a.put("code", rs.getString("code")); a.put("name", rs.getString("name")); a.put("spec", rs.getString("spec"));
            a.put("amount", ((BigDecimal) a.get("amount")).add(rs.getBigDecimal("amt")));
            holders.computeIfAbsent(mid, k -> new java.util.HashSet<>()).add(orgToCompany.get(rs.getLong("org_id")));
        }, orgs.toArray());
        agg.forEach((mid, a) -> a.put("companyCount", holders.get(mid).size()));
        return agg.values().stream()
                .sorted((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")))
                .limit(TOP_N).toList();
    }

    /**
     * 每个启用的组织 → 它所属公司的 id；同时把纳入汇总的公司填进 companies(按 sort,id)。
     * 所属公司 = 往上找到的最顶层 COMPANY(与 OrgScopeService 的账套根口径一致)；
     * 排除名单里的公司连同它下面的部门一起不返回。
     */
    private Map<Long, Long> orgToCompany(Map<Long, Company> companies) {
        Set<String> excluded = Arrays.stream(excludeOrgCodes.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        record Org(Long id, Long parentId, String type, String code, String name, int sort) {}
        Map<Long, Org> orgs = new LinkedHashMap<>();
        jdbc.query("SELECT id, parent_id, type, code, name, sort FROM sys_org WHERE deleted = 0 AND status = 1 ORDER BY sort, id",
                rs -> { orgs.put(rs.getLong("id"), new Org(rs.getLong("id"), rs.getLong("parent_id"), rs.getString("type"),
                        rs.getString("code"), rs.getString("name"), rs.getInt("sort"))); });
        Map<Long, Long> map = new LinkedHashMap<>();
        for (Org o : orgs.values()) {
            Org cursor = o, company = "COMPANY".equalsIgnoreCase(o.type()) ? o : null;
            int guard = 0;
            while (cursor.parentId() != null && cursor.parentId() != 0 && orgs.containsKey(cursor.parentId()) && guard++ < 32) {
                cursor = orgs.get(cursor.parentId());
                if ("COMPANY".equalsIgnoreCase(cursor.type())) company = cursor;
            }
            if (company == null || excluded.contains(company.code())) continue;
            map.put(o.id(), company.id());
            companies.putIfAbsent(company.id(), new Company(company.id(), company.code(), company.name(), company.sort()));
        }
        return map;
    }

    /** 跑一条"按组织汇总"的 SQL(列名 o=组织id, v=数值)，折到公司后累加进 m[公司][key]。 */
    private void roll(Map<Long, Map<String, BigDecimal>> m, Map<Long, Long> orgToCompany, String key, String sql, Object[] args) {
        jdbc.query(sql, rs -> {
            Long co = orgToCompany.get(rs.getLong("o"));
            BigDecimal v = rs.getBigDecimal("v");
            if (co != null && v != null) m.computeIfAbsent(co, k -> new HashMap<>()).merge(key, v, BigDecimal::add);
        }, args);
    }

    private static BigDecimal num(Map<String, BigDecimal> v, String key) {
        return v.getOrDefault(key, BigDecimal.ZERO);
    }

    /** 拼参数：extra(日期等)在前、组织 id 在后——用到它的 SQL 都必须把日期条件写在 org_id IN (...) 之前。 */
    private static Object[] with(Object[] orgIds, Object... extra) {
        Object[] all = new Object[extra.length + orgIds.length];
        System.arraycopy(extra, 0, all, 0, extra.length);
        System.arraycopy(orgIds, 0, all, extra.length, orgIds.length);
        return all;
    }
}
