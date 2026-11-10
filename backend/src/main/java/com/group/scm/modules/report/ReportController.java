package com.group.scm.modules.report;

import com.group.scm.common.ResultCap;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表中心：所有报表必须先提交查询条件，再返回结果，避免一次性加载全量数据。
 */
@Tag(name = "报表")
@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class ReportController {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    private final com.group.scm.modules.inventory.service.InvLedgerSupport invLedger;

    @PostMapping("/po-tracking")
    public Result<List<Map<String, Object>>> poTracking(@RequestBody Map<String, Object> q) {
        SecurityUtil.requireAnyPerm("rpt.po", "po.track");
        StringBuilder sql = new StringBuilder("""
            SELECT po.doc_no, po.order_date, s.name supplier_name, m.code material_code, m.name material_name, m.spec material_spec,
                   pl.qty, pl.received_qty, (pl.qty - pl.received_qty) remaining_qty,
                   pl.unit_price, pl.amount, po.status
            FROM biz_po po
            JOIN biz_po_line pl ON pl.po_id = po.id
            LEFT JOIN md_supplier s ON s.id = po.supplier_id
            LEFT JOIN md_material m ON m.id = pl.material_id
            WHERE po.deleted = 0
        """);
        List<Object> params = new ArrayList<>();
        appendOrgScope(sql, params, "po.org_id");
        if (q.get("supplierId") != null) { sql.append(" AND po.supplier_id = ?"); params.add(q.get("supplierId")); }
        if (q.get("materialId") != null) { sql.append(" AND pl.material_id = ?"); params.add(q.get("materialId")); }
        // 2026-10-16 查询条件要覆盖表格列:订单号
        String poNo = q.get("poNo") == null ? "" : q.get("poNo").toString().trim();
        if (!poNo.isEmpty()) { sql.append(" AND po.doc_no LIKE ?"); params.add("%" + poNo + "%"); }
        if (q.get("status") != null && !q.get("status").toString().isBlank()) {
            sql.append(" AND po.status = ?"); params.add(q.get("status"));
        } else {
            // 状态留空(全部)时默认排除「已作废」单据；草稿/待审批/已驳回等在途单据仍可查到
            // （订单跟踪是单据全生命周期视图，不应因尚未审批而查不到——已删除单据由 po.deleted=0 过滤）
            sql.append(" AND po.status <> 'CANCELLED'");
        }
        if (q.get("startDate") != null) { sql.append(" AND po.order_date >= ?"); params.add(q.get("startDate")); }
        if (q.get("endDate") != null)   { sql.append(" AND po.order_date <= ?"); params.add(q.get("endDate")); }
        sql.append(" ORDER BY po.id DESC LIMIT 5000");
        return Result.ok(maskAmountForQuantityOnlyRoles(ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000)));
    }

    /**
     * 财务核对台账（发票口径，用于与财务软件手工对账）：
     * 按供应商汇总已开票(APPROVED/MATCHED)的 不含税(原材料)/税额(进项税)/含税(应付)，
     * 另给「当前暂估未开票(不含税)」与「期末原材料库存(不含税成本)」两个对照数。
     */
    @PostMapping("/finance-reconcile")
    public Result<Map<String, Object>> financeReconcile(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.reconcile");
        // 可见账套：管理员/集团用户可见多公司；传 orgId 则筛该公司(账套)，不传=全部可见公司汇总
        List<Long> visible = orgScope.visibleOrgIds();
        List<Long> effOrgIds = visible;
        Long selOrg = toLong(q.get("orgId"));
        if (selOrg != null) {
            if (!visible.contains(selOrg)) throw new com.group.scm.common.BizException("无权查看该公司数据");
            List<Long> comp = orgScope.accountSetOrgIds(selOrg);
            effOrgIds = comp.stream().filter(visible::contains).collect(java.util.stream.Collectors.toList());
        }
        if (effOrgIds.isEmpty()) effOrgIds = java.util.List.of(-1L);
        String orgPh = String.join(",", java.util.Collections.nCopies(effOrgIds.size(), "?"));

        // ① 已录入并审核的发票（暂估→核销后的发票，发票口径）
        StringBuilder invSql = new StringBuilder(
            "SELECT i.supplier_id, s.name supplier_name, COUNT(*) inv_cnt, "
          + "COALESCE(SUM(i.net_amt),0) net_amt, COALESCE(SUM(i.tax_amt),0) tax_amt, COALESCE(SUM(i.total_amt),0) total_amt "
          + "FROM biz_invoice i LEFT JOIN md_supplier s ON s.id = i.supplier_id "
          + "WHERE i.deleted = 0 AND i.status IN ('APPROVED','MATCHED') AND i.org_id IN (" + orgPh + ")");
        List<Object> invParams = new ArrayList<>(effOrgIds);
        if (q.get("supplierId") != null) { invSql.append(" AND i.supplier_id = ?"); invParams.add(q.get("supplierId")); }
        if (q.get("startDate") != null)  { invSql.append(" AND i.invoice_date >= ?"); invParams.add(q.get("startDate")); }
        if (q.get("endDate") != null)    { invSql.append(" AND i.invoice_date <= ?"); invParams.add(q.get("endDate")); }
        invSql.append(" GROUP BY i.supplier_id, s.name");
        List<Map<String, Object>> invRows = jdbc.queryForList(invSql.toString(), invParams.toArray());

        // ② 发票入库（货票同到 recv_type=INVOICE、已审批、未再单独核销发票）——发票已随货到，
        //    属当期应付/进项税发生额，一并计入应付-物资款/进项税/原材料。含税额按来源采购订单行含税金额等比分摊（与入库单明细口径一致），税额=含税-不含税。
        StringBuilder recvSql = new StringBuilder(
            "SELECT r.supplier_id, s.name supplier_name, COUNT(DISTINCT r.id) inv_cnt, "
          // 🔴 2026-10-18(计算审查 U23):以前 WHERE 按"还有未开票余量"筛行,SUM 却取整行金额——
          //    货票同到 10 件、其中 5 件已另开发票核销,期间发生额把发票 50 和整行 100 都算上(150,应为 100)。
          //    改为只计未开票余量部分,与下方「累计应付余额」同一口径。
          + "COALESCE(SUM(ROUND(rl.amount * (rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0)) / rl.qty, 2)),0) net_amt, "
          // 计算审查 U32:入库行已存含税额(按订单含税额分摊并收尾)时直接用它,分批入库含税、税额才守恒
          + "COALESCE(SUM(CASE WHEN rl.amount_incl_tax IS NOT NULL "
          + "                  THEN ROUND(rl.amount_incl_tax * (rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0)) / rl.qty, 2) "
          + "                  WHEN pl.id IS NOT NULL AND pl.qty > 0 AND pl.amount IS NOT NULL "
          + "                  THEN ROUND(pl.amount * (rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0)) / pl.qty, 2) "
          + "                  ELSE ROUND(rl.amount * (rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0)) / rl.qty * (1 + COALESCE(pl.tax_rate,0)/100), 2) END),0) total_amt "
          + "FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id "
          + "LEFT JOIN biz_po_line pl ON pl.id = rl.source_po_line_id "
          + "LEFT JOIN md_supplier s ON s.id = r.supplier_id "
          + "WHERE r.deleted = 0 AND r.recv_type = 'INVOICE' AND r.status = 'APPROVED' AND rl.qty > COALESCE(rl.invoiced_qty,0) + COALESCE(rl.returned_qty,0) /* 同上:只看还有未开票余量的行;退给供应商的部分不再等发票 */ "
          + "AND r.org_id IN (" + orgPh + ")");
        List<Object> recvParams = new ArrayList<>(effOrgIds);
        if (q.get("supplierId") != null) { recvSql.append(" AND r.supplier_id = ?"); recvParams.add(q.get("supplierId")); }
        if (q.get("startDate") != null)  { recvSql.append(" AND r.recv_date >= ?"); recvParams.add(q.get("startDate")); }
        if (q.get("endDate") != null)    { recvSql.append(" AND r.recv_date <= ?"); recvParams.add(q.get("endDate")); }
        recvSql.append(" GROUP BY r.supplier_id, s.name");
        List<Map<String, Object>> recvRows = jdbc.queryForList(recvSql.toString(), recvParams.toArray());

        // 两个来源按供应商合并
        java.util.LinkedHashMap<Long, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : invRows) {
            Map<String, Object> agg = mergeSupplierRow(merged, toLong(row.get("supplier_id")), row.get("supplier_name"));
            addLong(agg, "inv_cnt", ((Number) row.get("inv_cnt")).longValue());
            addBd(agg, "net_amt", toBd(row.get("net_amt")));
            addBd(agg, "tax_amt", toBd(row.get("tax_amt")));
            addBd(agg, "total_amt", toBd(row.get("total_amt")));
        }
        for (Map<String, Object> row : recvRows) {
            Map<String, Object> agg = mergeSupplierRow(merged, toLong(row.get("supplier_id")), row.get("supplier_name"));
            java.math.BigDecimal net = toBd(row.get("net_amt"));
            java.math.BigDecimal total = toBd(row.get("total_amt"));
            addLong(agg, "inv_cnt", ((Number) row.get("inv_cnt")).longValue());
            addBd(agg, "net_amt", net);
            addBd(agg, "tax_amt", total.subtract(net));
            addBd(agg, "total_amt", total);
        }
        // ③ 已付款（期间，按付款日期过滤）
        StringBuilder paySql = new StringBuilder(
            "SELECT p.supplier_id, s.name supplier_name, COALESCE(SUM(p.amount),0) pay_amt "
          + "FROM biz_payment p LEFT JOIN md_supplier s ON s.id = p.supplier_id "
          + "WHERE p.deleted = 0 AND p.org_id IN (" + orgPh + ")");
        List<Object> payParams = new ArrayList<>(effOrgIds);
        if (q.get("supplierId") != null) { paySql.append(" AND p.supplier_id = ?"); payParams.add(q.get("supplierId")); }
        if (q.get("startDate") != null)  { paySql.append(" AND p.pay_date >= ?"); payParams.add(q.get("startDate")); }
        if (q.get("endDate") != null)    { paySql.append(" AND p.pay_date <= ?"); payParams.add(q.get("endDate")); }
        paySql.append(" GROUP BY p.supplier_id, s.name");
        for (Map<String, Object> row : jdbc.queryForList(paySql.toString(), payParams.toArray())) {
            Map<String, Object> agg = mergeSupplierRow(merged, toLong(row.get("supplier_id")), row.get("supplier_name"));
            addBd(agg, "pay_amt", toBd(row.get("pay_amt")));
        }

        // ④ 应付余额（累计口径，不随期间变）：累计应付发生额(发票+发票入库含税) - 累计付款
        Map<Long, java.math.BigDecimal[]> cum = new java.util.LinkedHashMap<>(); // [发生额, 付款]
        Map<Long, Object> cumNames = new java.util.LinkedHashMap<>();
        String cumSupCond = q.get("supplierId") != null ? " AND x.supplier_id = ?" : "";
        Object supArg = q.get("supplierId");
        java.util.function.BiConsumer<List<Map<String, Object>>, Integer> collect = (rows, idx) -> {
            for (Map<String, Object> row : rows) {
                Long sid = toLong(row.get("supplier_id"));
                Long key = sid == null ? -1L : sid;
                cum.computeIfAbsent(key, k -> new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO});
                cum.get(key)[idx] = cum.get(key)[idx].add(toBd(row.get("v")));
                cumNames.putIfAbsent(key, row.get("supplier_name"));
            }
        };
        List<Object> cumBase = new ArrayList<>(effOrgIds);
        if (supArg != null) cumBase.add(supArg);
        collect.accept(jdbc.queryForList(
            "SELECT x.supplier_id, s.name supplier_name, COALESCE(SUM(x.total_amt),0) v FROM biz_invoice x "
          + "LEFT JOIN md_supplier s ON s.id = x.supplier_id "
          + "WHERE x.deleted = 0 AND x.status IN ('APPROVED','MATCHED') AND x.org_id IN (" + orgPh + ")" + cumSupCond
          + " GROUP BY x.supplier_id, s.name", cumBase.toArray()), 0);
        // 发票入库的应付发生额按「行级未开票余量」计算（qty - invoiced_qty），
        // 不再用整单 invoice_id IS NULL 判断——部分开票时旧口径会把整张入库单从应付里剔掉、只加回发票部分，导致应付余额少计
        collect.accept(jdbc.queryForList(
            "SELECT x.supplier_id, s.name supplier_name, "
          + "COALESCE(SUM(CASE WHEN rl.amount_incl_tax IS NOT NULL "
          + "                  THEN ROUND(rl.amount_incl_tax * (rl.qty - COALESCE(rl.invoiced_qty, 0) - COALESCE(rl.returned_qty, 0)) / rl.qty, 2) "
          + "                  WHEN pl.id IS NOT NULL AND pl.qty > 0 AND pl.amount IS NOT NULL "
          + "                  THEN ROUND(pl.amount * (rl.qty - COALESCE(rl.invoiced_qty, 0) - COALESCE(rl.returned_qty, 0)) / pl.qty, 2) "
          + "                  ELSE ROUND(rl.amount * (rl.qty - COALESCE(rl.invoiced_qty, 0) - COALESCE(rl.returned_qty, 0)) / rl.qty * (1 + COALESCE(pl.tax_rate,0)/100), 2) END),0) v "
          + "FROM biz_recv x JOIN biz_recv_line rl ON rl.recv_id = x.id AND rl.qty > 0 "
          + "LEFT JOIN biz_po_line pl ON pl.id = rl.source_po_line_id "
          + "LEFT JOIN md_supplier s ON s.id = x.supplier_id "
          + "WHERE x.deleted = 0 AND x.recv_type = 'INVOICE' AND x.status = 'APPROVED' "
          + "AND x.org_id IN (" + orgPh + ")" + cumSupCond
          + " GROUP BY x.supplier_id, s.name", cumBase.toArray()), 0);
        collect.accept(jdbc.queryForList(
            "SELECT x.supplier_id, s.name supplier_name, COALESCE(SUM(x.amount),0) v FROM biz_payment x "
          + "LEFT JOIN md_supplier s ON s.id = x.supplier_id "
          + "WHERE x.deleted = 0 AND x.org_id IN (" + orgPh + ")" + cumSupCond
          + " GROUP BY x.supplier_id, s.name", cumBase.toArray()), 1);
        java.math.BigDecimal balanceTotal = java.math.BigDecimal.ZERO;
        for (Map.Entry<Long, java.math.BigDecimal[]> e : cum.entrySet()) {
            java.math.BigDecimal balance = e.getValue()[0].subtract(e.getValue()[1]);
            balanceTotal = balanceTotal.add(balance);
            Long sid = e.getKey() == -1L ? null : e.getKey();
            Map<String, Object> agg = mergeSupplierRow(merged, sid, cumNames.get(e.getKey()));
            agg.put("balance_amt", balance);
        }

        List<Map<String, Object>> bySupplier = new ArrayList<>(merged.values());
        bySupplier.sort((a, b) -> toBd(b.get("total_amt")).compareTo(toBd(a.get("total_amt"))));

        java.math.BigDecimal material = java.math.BigDecimal.ZERO, tax = java.math.BigDecimal.ZERO, payable = java.math.BigDecimal.ZERO, paid = java.math.BigDecimal.ZERO;
        long invCnt = 0;
        for (Map<String, Object> row : bySupplier) {
            material = material.add(toBd(row.get("net_amt")));
            tax = tax.add(toBd(row.get("tax_amt")));
            payable = payable.add(toBd(row.get("total_amt")));
            paid = paid.add(toBd(row.get("pay_amt")));
            invCnt += ((Number) row.get("inv_cnt")).longValue();
        }

        // 暂估未开票额:按行级未开票余量(qty-invoiced_qty)计算,不用整单 invoice_id IS NULL——
        // 部分开票时 markRecvInvoicedIfComplete 也会给整单写 invoice_id,旧口径会把整张暂估单剔除、漏计剩余未开部分
        java.math.BigDecimal estimateUninvoiced = toBd(jdbc.queryForMap(
                "SELECT COALESCE(SUM(ROUND(rl.amount * (rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0)) / rl.qty, 2)),0) v "
              + "FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id AND rl.qty > 0 "
              + "WHERE r.deleted=0 AND r.recv_type='ESTIMATE' AND r.status='APPROVED' AND r.org_id IN (" + orgPh + ")",
                effOrgIds.toArray()).get("v"));

        java.math.BigDecimal inventoryValue = toBd(jdbc.queryForMap(
                "SELECT COALESCE(SUM(b.amount),0) v FROM inv_balance b JOIN md_warehouse w ON w.id=b.warehouse_id WHERE w.org_id IN (" + orgPh + ")",
                effOrgIds.toArray()).get("v"));

        // 可选公司列表（当前用户可见的公司/账套，供前端筛选下拉）
        List<Map<String, Object>> companies = new ArrayList<>();
        if (!visible.isEmpty()) {
            String vph = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
            companies = jdbc.queryForList(
                    "SELECT id, name FROM sys_org WHERE deleted=0 AND status=1 AND type='COMPANY' AND id IN (" + vph + ") ORDER BY sort, id",
                    visible.toArray());
        }

        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("materialAmt", material);
        summary.put("inputTax", tax);
        summary.put("payableAmt", payable);
        summary.put("paidAmt", paid);
        summary.put("payableBalance", balanceTotal);
        summary.put("invoiceCount", invCnt);
        summary.put("estimateUninvoiced", estimateUninvoiced);
        summary.put("inventoryValue", inventoryValue);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("bySupplier", bySupplier);
        result.put("summary", summary);
        result.put("companies", companies);
        return Result.ok(result);
    }

    /**
     * 低库存预警：账套内现存量汇总 < 物料最低库存(safety_qty) 的清单，工作台「库存告急」卡片用。
     * 只返回数量不带成本（对仓管等仅数量角色安全）；按可见账套隔离。
     */
    @PostMapping("/low-stock")
    public Result<List<Map<String, Object>>> lowStock() {
        List<Long> visible = new ArrayList<>(orgScope.visibleOrgIds());
        // 演示账套的告急物料只给演示号自己看，别混进管理员/生产账套的预警里
        Long demoOrg = toLong(jdbc.queryForList(
                "SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1", String.class)
                .stream().findFirst().orElse(null));
        if (demoOrg != null && !demoOrg.equals(com.group.scm.common.SecurityUtil.orgId())) visible.remove(demoOrg);
        if (visible.isEmpty()) return Result.ok(new ArrayList<>());
        String ph = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT m.id material_id, m.code, m.name, m.spec, m.unit, o.name org_name, "
          + "SUM(b.qty) qty, m.safety_qty "
          + "FROM inv_balance b "
          + "JOIN md_warehouse w ON w.id = b.warehouse_id AND w.deleted = 0 AND w.org_id IN (" + ph + ") "
          + "JOIN md_material m ON m.id = b.material_id AND m.deleted = 0 AND m.safety_qty > 0 "
          + "LEFT JOIN sys_org o ON o.id = w.org_id "
          + "GROUP BY w.org_id, o.name, m.id, m.code, m.name, m.spec, m.unit, m.safety_qty "
          + "HAVING SUM(b.qty) < m.safety_qty "
          + "ORDER BY SUM(b.qty) / m.safety_qty ASC LIMIT 200",
            visible.toArray());
        return Result.ok(rows);
    }

    /**
     * 库存双向预警：缺货(SHORTAGE，现存 < 最低库存，含零库存)+ 超储(OVERSTOCK，现存 > 最高库存)。
     * 只统计设了最低或最高阈值的物料，按可见账套汇总现存量。返回字段带 alert_type。
     */
    @PostMapping("/stock-alert")
    public Result<List<Map<String, Object>>> stockAlert() {
        List<Long> visible = new ArrayList<>(orgScope.visibleOrgIds());
        // 演示账套的预警只给演示号自己看
        Long demoOrg = toLong(jdbc.queryForList(
                "SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1", String.class)
                .stream().findFirst().orElse(null));
        if (demoOrg != null && !demoOrg.equals(com.group.scm.common.SecurityUtil.orgId())) visible.remove(demoOrg);
        if (visible.isEmpty()) return Result.ok(new ArrayList<>());
        String ph = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
        Object[] args = new Object[visible.size() * 2];
        for (int i = 0; i < visible.size(); i++) {
            args[i] = visible.get(i);
            args[i + visible.size()] = visible.get(i);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.* FROM (SELECT m.id material_id, m.code, m.name, m.spec, m.unit, o.name org_name, "
          + "m.safety_qty, m.max_qty, "
          + "COALESCE((SELECT SUM(b.qty) FROM inv_balance b JOIN md_warehouse w ON w.id=b.warehouse_id "
          + "WHERE b.material_id=m.id AND w.deleted=0 AND w.org_id IN (" + ph + ")),0) qty "
          + "FROM md_material m LEFT JOIN sys_org o ON o.id=m.org_id "
          + "WHERE m.deleted=0 AND (m.safety_qty>0 OR m.max_qty>0) AND (m.org_id IN (" + ph + ") OR m.is_shared=1)) t "
          + "WHERE (t.safety_qty>0 AND t.qty < t.safety_qty) OR (t.max_qty>0 AND t.qty > t.max_qty) "
          + "ORDER BY CASE WHEN t.safety_qty>0 AND t.qty < t.safety_qty THEN 0 ELSE 1 END, "
          + "CASE WHEN t.safety_qty>0 THEN t.qty / t.safety_qty ELSE 999 END ASC LIMIT 300",
            args);
        for (Map<String, Object> r : rows) {
            Object minO = r.get("safety_qty");
            java.math.BigDecimal qty = new java.math.BigDecimal(String.valueOf(r.get("qty")));
            java.math.BigDecimal min = minO == null ? null : new java.math.BigDecimal(String.valueOf(minO));
            boolean shortage = min != null && min.signum() > 0 && qty.compareTo(min) < 0;
            r.put("alert_type", shortage ? "SHORTAGE" : "OVERSTOCK");
        }
        return Result.ok(rows);
    }

    /**
     * 出库成本科目汇总：按期间+公司,把材料出库(APPROVED)金额按 出库类型→会计科目 归集,
     * 退料(APPROVED)以负数冲减。给材料会计月末照科目金额去财务软件记账用。
     * 返回 bySubject(科目汇总,记账口径) + byType(出库类型明细) + companies + summary。
     */
    @PostMapping("/out-cost-by-subject")
    public Result<Map<String, Object>> outCostBySubject(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.outSummary");
        List<Long> visible = orgScope.visibleOrgIds();
        List<Long> effOrgIds = visible;
        Long selOrg = toLong(q.get("orgId"));
        if (selOrg != null) {
            if (!visible.contains(selOrg)) throw new com.group.scm.common.BizException("无权查看该公司数据");
            List<Long> comp = orgScope.accountSetOrgIds(selOrg);
            effOrgIds = comp.stream().filter(visible::contains).collect(java.util.stream.Collectors.toList());
        }
        if (effOrgIds.isEmpty()) effOrgIds = java.util.List.of(-1L);
        String orgPh = String.join(",", java.util.Collections.nCopies(effOrgIds.size(), "?"));

        // 材料出库(正) + 退料(负) 合并,LEFT JOIN 映射表拿科目;未映射归"（未配置科目）"
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(m.subject,'（未配置科目）') subject, m.category, x.out_type, "
          + "SUM(x.amt) amount, COUNT(*) doc_cnt "
          + "FROM ("
          + "  SELECT COALESCE(out_type,'（未填出库类型）') out_type, total_amt amt, org_id, out_date dt FROM biz_out WHERE deleted=0 AND status='APPROVED' "
          + "  UNION ALL "
          + "  SELECT COALESCE(out_type,'（未填出库类型）') out_type, -total_amt amt, org_id, return_date dt FROM biz_return WHERE deleted=0 AND status='APPROVED' "
          + ") x LEFT JOIN md_out_type_subject m ON m.out_type = x.out_type AND m.deleted = 0 "
          + "WHERE x.org_id IN (" + orgPh + ")");
        List<Object> params = new ArrayList<>(effOrgIds);
        if (q.get("startDate") != null) { sql.append(" AND x.dt >= ?"); params.add(q.get("startDate")); }
        if (q.get("endDate") != null)   { sql.append(" AND x.dt <= ?"); params.add(q.get("endDate")); }
        sql.append(" GROUP BY subject, m.category, x.out_type ORDER BY subject, x.out_type");
        List<Map<String, Object>> byType = jdbc.queryForList(sql.toString(), params.toArray());

        // 科目汇总(记账口径)
        java.util.LinkedHashMap<String, java.math.BigDecimal> subjectMap = new java.util.LinkedHashMap<>();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (Map<String, Object> r : byType) {
            String subj = (String) r.get("subject");
            java.math.BigDecimal amt = toBd(r.get("amount"));
            subjectMap.merge(subj, amt, java.math.BigDecimal::add);
            total = total.add(amt);
        }
        List<Map<String, Object>> bySubject = new ArrayList<>();
        for (Map.Entry<String, java.math.BigDecimal> e : subjectMap.entrySet()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("subject", e.getKey());
            m.put("amount", e.getValue());
            bySubject.add(m);
        }

        List<Map<String, Object>> companies = new ArrayList<>();
        if (!visible.isEmpty()) {
            String vph = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
            companies = jdbc.queryForList(
                    "SELECT id, name FROM sys_org WHERE deleted=0 AND status=1 AND type='COMPANY' AND id IN (" + vph + ") ORDER BY sort, id",
                    visible.toArray());
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("bySubject", bySubject);
        out.put("byType", byType);
        out.put("companies", companies);
        out.put("total", total);
        return Result.ok(out);
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(o.toString()); } catch (Exception e) { return null; }
    }

    private java.math.BigDecimal toBd(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal b) return b;
        return new java.math.BigDecimal(o.toString());
    }

    /** 按供应商合并对账行：不存在则初始化一行(供应商名/计数/三项金额置零)。key: 供应商id(null→-1)。 */
    private Map<String, Object> mergeSupplierRow(java.util.LinkedHashMap<Long, Map<String, Object>> merged, Long sid, Object name) {
        return merged.computeIfAbsent(sid == null ? -1L : sid, k -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("supplier_id", sid);
            m.put("supplier_name", name);
            m.put("inv_cnt", 0L);
            m.put("net_amt", java.math.BigDecimal.ZERO);
            m.put("tax_amt", java.math.BigDecimal.ZERO);
            m.put("total_amt", java.math.BigDecimal.ZERO);
            m.put("pay_amt", java.math.BigDecimal.ZERO);
            m.put("balance_amt", java.math.BigDecimal.ZERO);
            return m;
        });
    }
    private void addLong(Map<String, Object> agg, String key, long v) {
        agg.put(key, ((Number) agg.get(key)).longValue() + v);
    }
    private void addBd(Map<String, Object> agg, String key, java.math.BigDecimal v) {
        agg.put(key, toBd(agg.get(key)).add(v));
    }

    @PostMapping("/estimate")
    public Result<List<Map<String, Object>>> estimate(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.est");
        StringBuilder sql = new StringBuilder("""
            SELECT r.doc_no, r.recv_date, s.name supplier_name, m.code material_code, m.name material_name, m.spec material_spec,
                   rl.qty, rl.amount, rl.returned_qty,
                   rl.invoiced_qty + COALESCE(rl.returned_qty,0) >= rl.qty /* 2026-08-18:改行级。整单 invoice_id 在部分开票时也会被写上,旧口径把「已核销」标错;2026-08-25:退货部分同样算已了结 */ is_matched,
                   /* 2026-10-16财务软件手册对标:挂账天数 + 还没开票部分的金额(超 90 天标红、工作台/结账提醒同口径,见 EstimateAging) */
                   DATEDIFF(CURDATE(), r.recv_date) aging_days,
                   """ + EstimateAging.REMAIN_AMT + """
                    remain_amount
            FROM biz_recv r
            JOIN biz_recv_line rl ON rl.recv_id = r.id
            LEFT JOIN md_supplier s ON s.id = r.supplier_id
            LEFT JOIN md_material m ON m.id = rl.material_id
            WHERE r.deleted = 0 AND r.recv_type = 'ESTIMATE' AND r.status = 'APPROVED'
        """);
        List<Object> params = new ArrayList<>();
        appendOrgScope(sql, params, "r.org_id");
        if (q.get("supplierId") != null) { sql.append(" AND r.supplier_id = ?"); params.add(q.get("supplierId")); }
        if (q.get("materialId") != null) { sql.append(" AND rl.material_id = ?"); params.add(q.get("materialId")); }
        // 2026-10-16 查询条件要覆盖表格列:入库单号、入库日期
        String recvNo = q.get("recvNo") == null ? "" : q.get("recvNo").toString().trim();
        if (!recvNo.isEmpty()) { sql.append(" AND r.doc_no LIKE ?"); params.add("%" + recvNo + "%"); }
        if (q.get("startDate") != null && !q.get("startDate").toString().isBlank()) { sql.append(" AND r.recv_date >= ?"); params.add(q.get("startDate").toString()); }
        if (q.get("endDate") != null && !q.get("endDate").toString().isBlank()) { sql.append(" AND r.recv_date <= ?"); params.add(q.get("endDate").toString()); }
        // 🔴 2026-10-18(计算审查 U34):「只看待核销」以前用整单 invoice_id IS NULL,部分开票也会写整单 invoice_id,
        //    还剩 90 没开票的行被一起过滤掉。改为和上面 is_matched 列同一个行级口径。
        if ("true".equals(String.valueOf(q.get("onlyUnmatched"))))
            sql.append(" AND rl.invoiced_qty + COALESCE(rl.returned_qty,0) < rl.qty");
        if ("true".equals(String.valueOf(q.get("overdueOnly")))) sql.append(" AND " + EstimateAging.OVERDUE_LINE);
        sql.append(" ORDER BY r.id DESC LIMIT 5000");
        return Result.ok(maskAmountForQuantityOnlyRoles(ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000)));
    }

    /** 现存量表：只展示实时数量，不展示金额、单价和成本。 */
    @PostMapping("/balance")
    public Result<List<Map<String, Object>>> balance(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.bal");
        StringBuilder sql = new StringBuilder("""
            SELECT w.name wh_name, m.code material_code, m.name material_name, m.spec, m.unit, b.qty
            FROM inv_balance b
            JOIN md_material m ON m.id = b.material_id
            JOIN md_warehouse w ON w.id = b.warehouse_id
            WHERE 1=1
        """);
        List<Object> params = new ArrayList<>();
        appendOrgScope(sql, params, "w.org_id");
        Long warehouseId = longValue(q.get("warehouseId"));
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            sql.append(" AND b.warehouse_id = ?");
            params.add(warehouseId);
        }
        if (q.get("catId") != null)       { sql.append(" AND m.cat_id = ?"); params.add(q.get("catId")); }
        if (q.get("materialId") != null)  { sql.append(" AND b.material_id = ?"); params.add(q.get("materialId")); }
        if (q.get("matCode") != null && !q.get("matCode").toString().isBlank()) { sql.append(" AND m.code LIKE ?"); params.add("%"+q.get("matCode")+"%"); }
        if (!"true".equals(String.valueOf(q.get("includeZero")))) sql.append(" AND b.qty <> 0");
        sql.append(" ORDER BY w.name, m.code LIMIT 5000");
        return Result.ok(ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000));
    }

    /**
     * 收发存汇总表：按物料和仓库汇总期初、收入、发出和结存。
     *
     * 🔴 2026-10-15 口径修正(结存金额与库存余额对不上):
     *  ① 暂估差异 ADJUST_EXPENSE 按会计政策「全额进损益、不动库存」(见 WaInventoryService),
     *     原来却被算进期初/结存金额 → 收发存结存 ≠ 实际库存。现在整类剔除。
     *  ② 收/发方向按单据类型判定(定「按惯例」)：
     *     退料 RETURN、销售退货 SALE_RETURN、借料归还 LOAN_RETURN = 发出红字(冲减发出)，
     *     采购退货 PRET = 收入红字(冲减收入)。原来按数量正负分，退料被记成「收入」，收入发出同时虚增。
     *     同一张单的反审 REVERSE 跟原单同一侧，自然冲回。
     *     转库/盘点等其余类型仍按正负：正 = 收入、负 = 发出；数量为 0 的成本调整(ADJUST)记收入金额。
     *  ③ 销售出库/销售退货/采购退货/盘点按单据日期归期(原来按审批时间)，与材料出库一致。
     * 恒等式：期初 + 收入 − 发出 = 结存，且结存 = inv_balance(E2E 有断言)。
     *
     * 🔴 2026-10-15 存货按公司核算:统计口径 dim = ORG(按公司,金额以此为准)/ WH(按仓库)。
     *    切换后默认 ORG:同一公司各仓流水合并成一行,转库在公司口径下同时计收入和发出(财务软件 同款,金额相等相反);
     *    结存 = 公司成本池(各仓参考金额之和)。切换后按仓库看只给数量,金额置空——
     *    仓库不再单独核算成本,按仓累计的金额没有意义(会出现数量为 0 金额不为 0 的怪账)。
     */
    @PostMapping("/in-out-summary")
    public Result<List<Map<String, Object>>> inOutSummary(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.inout");
        String startDate = text(q.get("startDate"));
        String endDate = text(q.get("endDate"));
        boolean orgMode = invLedger.orgMode();
        String dim = text(q.get("dim"));
        boolean byOrg = dim == null ? orgMode : "ORG".equalsIgnoreCase(dim);
        StringBuilder sql = new StringBuilder("""
            SELECT m.name mat_name, m.code mat_code, '' batch_no, m.spec mat_spec, '' mat_model,
                   '' mat_brand, '' mat_level, m.unit mat_unit, __DIM_NAME__ wh_name,
                   SUM(CASE WHEN %s THEN t.qty ELSE 0 END) open_qty,
                   SUM(CASE WHEN %s THEN t.amount ELSE 0 END) open_amt,
                   SUM(CASE WHEN (%s) AND t.side = 'IN' THEN t.qty ELSE 0 END) in_qty,
                   SUM(CASE WHEN (%s) AND t.side = 'IN' THEN t.amount ELSE 0 END) in_amt,
                   SUM(CASE WHEN (%s) AND t.side = 'OUT' THEN -t.qty ELSE 0 END) out_qty,
                   SUM(CASE WHEN (%s) AND t.side = 'OUT' THEN -t.amount ELSE 0 END) out_amt,
                   SUM(CASE WHEN %s THEN t.qty ELSE 0 END) close_qty,
                   SUM(CASE WHEN %s THEN t.amount ELSE 0 END) close_amt
            FROM (
                __INV_FLOWS__
            ) t
            JOIN md_material m ON m.id = t.material_id
            JOIN md_warehouse w ON w.id = t.warehouse_id
            JOIN sys_org og ON og.id = w.org_id
            WHERE 1=1
        """.replace("__INV_FLOWS__", InvFlowSql.FLOWS).replace("__DIM_NAME__", byOrg ? "og.name" : "w.name").formatted(
                startDate == null ? "0=1" : "t.biz_date < ?",
                startDate == null ? "0=1" : "t.biz_date < ?",
                periodCondition(startDate, endDate),
                periodCondition(startDate, endDate),
                periodCondition(startDate, endDate),
                periodCondition(startDate, endDate),
                endDate == null ? "1=1" : "t.biz_date <= ?",
                endDate == null ? "1=1" : "t.biz_date <= ?"
        ));
        List<Object> params = new ArrayList<>();
        if (startDate != null) { params.add(startDate); params.add(startDate); }
        addPeriodParams(params, startDate, endDate);
        addPeriodParams(params, startDate, endDate);
        addPeriodParams(params, startDate, endDate);
        addPeriodParams(params, startDate, endDate);
        if (endDate != null) { params.add(endDate); params.add(endDate); }
        appendOrgScope(sql, params, "w.org_id");
        Long warehouseId = longValue(q.get("warehouseId"));
        if (warehouseId != null && !byOrg) {   // 按公司口径不按单个仓库筛(转库会只剩半边)
            orgScope.assertWarehouseVisible(warehouseId);
            sql.append(" AND t.warehouse_id = ?");
            params.add(warehouseId);
        }
        if (q.get("materialId") != null)  { sql.append(" AND t.material_id = ?"); params.add(q.get("materialId")); }
        sql.append(byOrg ? " GROUP BY m.id, w.org_id ORDER BY m.code, og.name LIMIT 5000"
                         : " GROUP BY m.id, w.id ORDER BY m.code, w.name LIMIT 5000");
        List<Map<String, Object>> rows = ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000);
        if (orgMode && !byOrg) {
            for (Map<String, Object> r : rows) {
                for (String k : List.of("open_amt", "in_amt", "out_amt", "close_amt")) r.put(k, null);
            }
        }
        return Result.ok(maskAmountForQuantityOnlyRoles(rows));
    }

    /**
     * 出库汇总表：按物料、出库类型、工程项目汇总材料出库，并扣减退料。
     *
     * 🔴 2026-10-15 业务反馈：此表原来只累加材料出库，不扣退料，和「工程项目材料计划执行表」
     *    (实际出库 = 出库 − 退料)对不上。现在出库与退料分列，净出库 = 出库 − 退料。
     * 口径：出库按出库日期、退料按退料日期落期间(同「出库成本科目汇总」)；
     *       退料归到它来源那张出库单的 出库类型/工程项目 上，保证和原出库行合成一行。
     */
    @PostMapping("/out-summary")
    public Result<List<Map<String, Object>>> outSummary(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.outSummary");
        StringBuilder sql = new StringBuilder("""
            SELECT x.out_type, m.code material_code, m.name material_name, m.spec, m.unit,
                   COALESCE(p.name, x.project_name) project_name,
                   SUM(x.out_qty) out_qty,
                   CASE WHEN SUM(x.out_qty) = 0 THEN 0 ELSE ROUND(SUM(x.out_amt) / SUM(x.out_qty), 2) END out_unit_price,
                   SUM(x.out_amt) out_amount,
                   SUM(x.ret_qty) return_qty,
                   SUM(x.ret_amt) return_amount,
                   SUM(x.out_qty) - SUM(x.ret_qty) net_qty,
                   SUM(x.out_amt) - SUM(x.ret_amt) net_amount
            FROM (
                SELECT o.out_type, ol.material_id, o.project_id, o.project_name, o.warehouse_id, o.org_id,
                       o.out_date biz_date, ol.qty out_qty, ol.amount out_amt, 0 ret_qty, 0 ret_amt
                FROM biz_out o
                JOIN biz_out_line ol ON ol.out_id = o.id
                WHERE o.deleted = 0 AND o.status IN ('APPROVED','OUT')
                UNION ALL
                SELECT COALESCE(so.out_type, rt.out_type), rl.material_id,
                       COALESCE(so.project_id, rt.project_id), COALESCE(so.project_name, rt.project_name),
                       rt.warehouse_id, rt.org_id, rt.return_date, 0, 0, rl.qty, rl.amount
                FROM biz_return rt
                JOIN biz_return_line rl ON rl.return_id = rt.id
                LEFT JOIN biz_out_line sol ON sol.id = rl.source_out_line_id
                LEFT JOIN biz_out so ON so.id = sol.out_id
                WHERE rt.deleted = 0 AND rt.status = 'APPROVED'
            ) x
            LEFT JOIN md_material m ON m.id = x.material_id
            LEFT JOIN md_project p ON p.id = x.project_id
            WHERE 1 = 1
        """);
        List<Object> params = new ArrayList<>();
        appendOrgScope(sql, params, "x.org_id");
        Long warehouseId = longValue(q.get("warehouseId"));
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            sql.append(" AND x.warehouse_id = ?");
            params.add(warehouseId);
        }
        if (q.get("materialId") != null)  { sql.append(" AND x.material_id = ?"); params.add(q.get("materialId")); }
        if (q.get("projectId") != null)   { sql.append(" AND x.project_id = ?"); params.add(q.get("projectId")); }
        if (q.get("outType") != null && !q.get("outType").toString().isBlank()) { sql.append(" AND x.out_type = ?"); params.add(q.get("outType")); }
        if (q.get("startDate") != null)   { sql.append(" AND x.biz_date >= ?"); params.add(q.get("startDate")); }
        if (q.get("endDate") != null)     { sql.append(" AND x.biz_date <= ?"); params.add(q.get("endDate")); }
        sql.append(" GROUP BY x.out_type, m.id, x.project_id, x.project_name ORDER BY m.code, project_name LIMIT 5000");
        return Result.ok(maskAmountForQuantityOnlyRoles(ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000)));
    }

    /** 工程项目材料计划执行表：数据来源于出库申请单明细。 */
    /*
     * 「待执行」口径(2026-10-15 定):申请数 − 实际出库 + 【选了"退回后还要再领"的】退料。
     * 以前把所有退料都算成待执行,可默认"不再领"的退料系统并不允许按原申请再出,报表与出库页对不上。
     */
    @PostMapping("/project-material-plan")
    public Result<List<Map<String, Object>>> projectMaterialPlan(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.projectPlan");
        StringBuilder sql = new StringBuilder("""
            SELECT r.doc_no request_no,
                   COALESCE(p.name, r.project_name) project_name,
                   '' supplier_name,
                   m.code material_code,
                   m.name material_name,
                   m.spec,
                   m.unit unit_name,
                   r.out_type out_type_name,
                   r.remark,
                   SUM(COALESCE(rl.original_qty, rl.qty - COALESCE(rl.revised_qty, 0))) plan_qty,
                   SUM(COALESCE(rl.revised_qty, rl.qty - COALESCE(rl.original_qty, rl.qty))) change_qty,
                   SUM(rl.qty) changed_qty,
                   COALESCE(SUM(actual.actual_out_qty), 0) gross_out_qty,
                   COALESCE(SUM(ret.return_qty), 0) return_qty,
                   COALESCE(SUM(actual.actual_out_qty), 0) - COALESCE(SUM(ret.return_qty), 0) actual_out_qty,
                   CASE WHEN COALESCE(SUM(actual.actual_out_qty), 0) - COALESCE(SUM(ret.return_qty), 0) = 0 THEN NULL
                        ELSE ROUND((COALESCE(SUM(actual.actual_amount), 0) - COALESCE(SUM(ret.return_amount), 0))
                             / (COALESCE(SUM(actual.actual_out_qty), 0) - COALESCE(SUM(ret.return_qty), 0)), 2)
                   END unit_price,
                   COALESCE(SUM(actual.actual_amount), 0) - COALESCE(SUM(ret.return_amount), 0) amount,
                   SUM(rl.transferred_qty) transferred_qty,
                   SUM(rl.closed_qty) closed_qty,
                   SUM(CASE WHEN r.fulfillment_mode='TRANSFER' THEN GREATEST(rl.qty-rl.transferred_qty-rl.closed_qty,0) ELSE rl.qty-COALESCE(actual.actual_out_qty,0)+COALESCE(rl.reissue_qty,0) END) available_qty,
                   r.apply_date,
                   r.status,
                   r.business_category,
                   w.name warehouse_name,
                   u.name applicant_name
            FROM biz_out_req r
            JOIN biz_out_req_line rl ON rl.out_req_id = r.id
            LEFT JOIN (
                SELECT ol.source_req_line_id,
                       SUM(ol.qty) actual_out_qty,
                       SUM(ol.amount) actual_amount
                FROM biz_out_line ol
                JOIN biz_out o ON o.id = ol.out_id AND o.deleted = 0 AND o.status = 'APPROVED'
                WHERE ol.source_req_line_id IS NOT NULL
                GROUP BY ol.source_req_line_id
            ) actual ON actual.source_req_line_id = rl.id
            LEFT JOIN (
                SELECT rel.source_req_line_id,
                       SUM(rel.qty) return_qty,
                       SUM(rel.amount) return_amount
                FROM biz_return_line rel
                JOIN biz_return ret ON ret.id = rel.return_id AND ret.deleted = 0 AND ret.status = 'APPROVED'
                WHERE rel.source_req_line_id IS NOT NULL
                GROUP BY rel.source_req_line_id
            ) ret ON ret.source_req_line_id = rl.id
            LEFT JOIN md_project p ON p.id = r.project_id
            LEFT JOIN md_material m ON m.id = rl.material_id
            LEFT JOIN md_warehouse w ON w.id = r.warehouse_id
            LEFT JOIN sys_user u ON u.id = r.applicant_id
            WHERE r.deleted = 0
        """);
        List<Object> params = new ArrayList<>();
        appendOrgScope(sql, params, "r.org_id");
        if (q.get("status") != null && !q.get("status").toString().isBlank()) {
            sql.append(" AND r.status = ?");
            params.add(q.get("status"));
        } else {
            // 同采购订单跟踪：默认只排除「已作废」，草稿/待审批/已驳回等在途出库申请仍可查到
            sql.append(" AND r.status <> 'CANCELLED'");
        }
        Long warehouseId = longValue(q.get("warehouseId"));
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            sql.append(" AND r.warehouse_id = ?");
            params.add(warehouseId);
        }
        if (q.get("projectId") != null)   { sql.append(" AND r.project_id = ?"); params.add(q.get("projectId")); }
        if (q.get("materialId") != null)  { sql.append(" AND rl.material_id = ?"); params.add(q.get("materialId")); }
        if (q.get("outType") != null && !q.get("outType").toString().isBlank()) { sql.append(" AND r.out_type = ?"); params.add(q.get("outType")); }
        if (q.get("businessCategory") != null && !q.get("businessCategory").toString().isBlank()) { sql.append(" AND r.business_category = ?"); params.add(q.get("businessCategory")); }
        if (q.get("startDate") != null)   { sql.append(" AND r.apply_date >= ?"); params.add(q.get("startDate")); }
        if (q.get("endDate") != null)     { sql.append(" AND r.apply_date <= ?"); params.add(q.get("endDate")); }
        if ("true".equals(String.valueOf(q.get("onlyRemaining")))) {
            sql.append(" AND (CASE WHEN r.fulfillment_mode='TRANSFER' THEN rl.qty-rl.transferred_qty-rl.closed_qty ELSE rl.qty-COALESCE(actual.actual_out_qty,0)+COALESCE(rl.reissue_qty,0) END) > 0");
        }
        sql.append("""
            GROUP BY r.id, p.name, r.project_name, m.id, r.out_type, r.remark, r.apply_date,
                     r.status, r.business_category, w.name, u.name
            ORDER BY r.apply_date DESC, r.doc_no DESC, m.code
            LIMIT 5000
        """);
        return Result.ok(maskAmountForQuantityOnlyRoles(ResultCap.mark(jdbc.queryForList(sql.toString(), params.toArray()), 5000)));
    }

    private void appendOrgScope(StringBuilder sql, List<Object> params, String column) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(orgIds.size(), "?")))
                .append(")");
        params.addAll(orgIds);
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String text = value.toString();
        if (text.isBlank()) return null;
        return Long.parseLong(text);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String periodCondition(String startDate, String endDate) {
        List<String> parts = new ArrayList<>();
        if (startDate != null) parts.add("t.biz_date >= ?");
        if (endDate != null) parts.add("t.biz_date <= ?");
        return parts.isEmpty() ? "1=1" : String.join(" AND ", parts);
    }

    private void addPeriodParams(List<Object> params, String startDate, String endDate) {
        if (startDate != null) params.add(startDate);
        if (endDate != null) params.add(endDate);
    }

    /**
     * 🔴 2026-08-18 安全审计修复:报表也要做金额脱敏。
     *
     * 曾发现:warehouse(仓管)角色可能被授予 rpt.po / rpt.est / rpt.inout /
     * rpt.outSummary / rpt.projectPlan 等报表权限码。
     * 而单据列表早就为仓管抹掉了金额(PoController.hideCostForWarehouse、
     * InventoryController.hideCostForQuantityOnlyRoles),报表这一路却原样吐出 ——
     * 仓管点「采购订单跟踪」就能看到供应商 + 采购单价 + 金额,
     * 正是采购订单页面专门为他遮掉的那批数字。
     *
     * 对国有企业:采购单价属招投标议价信息,仓管岗按内控本就不该看。
     *
     * ⚠️ 不能用「把报表菜单从仓管角色收回」来修 —— 仓管看收发存【数量】是正当需求,
     *    收回等于砍功能。正确做法是照抄既有口径,只摘掉金额列。
     */
    private List<Map<String, Object>> maskAmountForQuantityOnlyRoles(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        if (SecurityUtil.hasFinanceAccessOrAdmin()) return rows;
        for (Map<String, Object> row : rows) {
            row.remove("amount");        row.remove("amt");
            row.remove("remain_amount");
            row.remove("unit_price");    row.remove("unitPrice");
            row.remove("out_amount");    row.remove("outAmount");
            row.remove("out_unit_price");row.remove("outUnitPrice");
            row.remove("open_amt");      row.remove("openAmt");
            row.remove("in_amt");        row.remove("inAmt");
            row.remove("out_amt");       row.remove("outAmt");
            row.remove("close_amt");     row.remove("closeAmt");
            row.remove("total_amt");     row.remove("totalAmt");
            row.remove("amount_excl_tax");row.remove("amountExclTax");
            row.remove("avg_price");     row.remove("avgPrice");
            row.remove("return_amount"); row.remove("net_amount");
        }
        return rows;
    }

}
