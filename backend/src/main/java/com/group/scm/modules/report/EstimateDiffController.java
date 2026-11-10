package com.group.scm.modules.report;

import com.group.scm.common.ResultCap;

import com.group.scm.common.BizException;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 采购成本差异调整表(原"暂估差异调整表",2026-10-16 改名)+ 月度入账确认。
 *
 * <p><b>口径沿革</b>:2026-08-21 起暂估差异全额进损益;2026-10-16 起按 B 方案拆分 ——
 * 在库部分调库存单价、已领用部分进当期损益,暂估开票与货票同到同一规则;采购退货按原入库额冲库存,
 * 只有兜底情况才有差额进损益。三种来源都在这张表里,取数口径见 {@link CostDiffSql}。</p>
 *
 * <p><b>为什么要月度确认</b>:本系统是进销存、不出记账凭证 —— 进损益那部分要材料会计到外部账务系统
 * 手工做分录。做没做、做的哪个月、金额对不对,系统盯着:确认了才能结账。
 * 只确认【进损益】部分;进库存部分已在库存金额里,不需要另做分录。</p>
 *
 * <p><b>期间归属口径(定)</b>:发票按【开票日期】、采购退货按【退货日期】,不是审批日期。</p>
 *
 * <p><b>可见范围</b>:只给财务(材料会计/财务负责人/账套管理员/管理员),
 * 差异金额涉及采购价格,采购与仓管岗按内控不给。</p>
 */
@RestController
@RequestMapping("/report/estimate-diff")
@RequiredArgsConstructor
public class EstimateDiffController {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    /** 差异明细:按开票日期归属期间,列到每一笔 */
    @PostMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestBody Map<String, Object> q) {
        SecurityUtil.requirePerm("rpt.estDiff");
        List<Long> orgs = new ArrayList<>(orgScope.visibleOrgIds());
        if (orgs.isEmpty()) return Result.ok(new ArrayList<>());
        String in = String.join(",", Collections.nCopies(orgs.size(), "?"));

        // 一行 = 一张单据的一个物料的一次过账(取消审批的冲回单列一行):总差额 = 进库存 + 进损益
        StringBuilder sql = new StringBuilder("""
            SELECT DATE_FORMAT(f.biz_date, '%Y-%m') period_month,
                   f.biz_date invoice_date, f.doc_no invoice_no, f.source_type, f.is_reverse,
                   s.name supplier_name, m.code material_code, m.name material_name,
                   f.org_id, og.name org_name,
                   ROUND(SUM(f.amount), 2) total_amount,
                   ROUND(SUM(CASE WHEN f.in_stock = 1 THEN f.amount ELSE 0 END), 2) in_stock_amount,
                   ROUND(SUM(CASE WHEN f.in_stock = 0 THEN f.amount ELSE 0 END), 2) diff_amount,
                   GROUP_CONCAT(f.remark ORDER BY f.id SEPARATOR ' / ') remark,
                   MAX(f.created_at) posted_at
            FROM (""" + CostDiffSql.FLOWS + """
            ) f
            LEFT JOIN sys_org og ON og.id = f.org_id
            LEFT JOIN md_material m ON m.id = f.material_id
            LEFT JOIN md_supplier s ON s.id = f.supplier_id
            WHERE f.org_id IN (""" + in + ")");
        List<Object> args = new ArrayList<>(orgs);
        Object pm = q.get("periodMonth");
        if (pm != null && !pm.toString().isBlank()) {
            sql.append(" AND DATE_FORMAT(f.biz_date, '%Y-%m') = ?");
            args.add(pm.toString());
        }
        // 2026-10-20 查询面板第二批:单据号 / 供应商 / 物料(编码或名称)。一律参数化传值,不拼进 SQL
        String kw = Objects.toString(q.get("keyword"), "").trim();
        if (!kw.isEmpty()) {
            sql.append(" AND f.doc_no LIKE ?");
            args.add("%" + kw + "%");
        }
        Object sup = q.get("supplierId");
        if (sup != null && !sup.toString().isBlank()) {
            sql.append(" AND f.supplier_id = ?");
            args.add(Long.valueOf(sup.toString()));
        }
        String mat = Objects.toString(q.get("material"), "").trim();
        if (!mat.isEmpty()) {
            sql.append(" AND (m.code LIKE ? OR m.name LIKE ?)");
            args.add("%" + mat + "%");
            args.add("%" + mat + "%");
        }
        sql.append(" GROUP BY f.biz_doc_type, f.biz_doc_id, f.material_id, f.warehouse_id, f.is_reverse,"
                + " f.biz_date, f.doc_no, f.source_type, f.supplier_id, f.org_id, og.name, s.name, m.code, m.name");
        sql.append(" ORDER BY f.biz_date DESC, MAX(f.id) DESC LIMIT 5000");
        return Result.ok(ResultCap.mark(jdbc.queryForList(sql.toString(), args.toArray()), 5000));
    }

    /** 按月汇总 + 每月的确认状态(这是财务做分录时真正要看的那张表) */
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary() {
        SecurityUtil.requirePerm("rpt.estDiff");
        List<Long> orgs = new ArrayList<>(orgScope.visibleOrgIds());
        if (orgs.isEmpty()) return Result.ok(new ArrayList<>());
        String in = String.join(",", Collections.nCopies(orgs.size(), "?"));
        List<Object> args = new ArrayList<>(orgs);
        args.addAll(orgs);

        // 左边:各月实际差异;右边:该月的确认记录。两边一比就知道「确认后又新增了多少」
        String sql = """
            SELECT d.period_month, d.org_id, og.name org_name,
                   d.diff_amount, d.in_stock_amount, d.entry_count,
                   c.diff_amount confirmed_amount, c.confirmed_name, c.confirmed_at, c.remark confirm_remark,
                   CASE WHEN c.id IS NULL THEN 'PENDING'
                        WHEN ABS(d.diff_amount - c.diff_amount) > 0.005 THEN 'CHANGED'
                        ELSE 'CONFIRMED' END confirm_status
            FROM (
              SELECT DATE_FORMAT(f.biz_date, '%Y-%m') period_month, f.org_id,
                     ROUND(SUM(CASE WHEN f.in_stock = 0 THEN f.amount ELSE 0 END),2) diff_amount,
                     ROUND(SUM(CASE WHEN f.in_stock = 1 THEN f.amount ELSE 0 END),2) in_stock_amount,
                     SUM(CASE WHEN f.in_stock = 0 THEN 1 ELSE 0 END) entry_count
              FROM (""" + CostDiffSql.FLOWS + """
              ) f
              WHERE f.org_id IN (""" + in + """
              )
              GROUP BY DATE_FORMAT(f.biz_date, '%Y-%m'), f.org_id
            ) d
            LEFT JOIN sys_org og ON og.id = d.org_id
            LEFT JOIN fin_estimate_diff_confirm c
                   ON c.org_id = d.org_id AND c.period_month = d.period_month AND c.deleted = 0
            WHERE d.org_id IN (""" + in + """
            )
            ORDER BY d.period_month DESC, d.org_id
            """;
        return Result.ok(jdbc.queryForList(sql, args.toArray()));
    }

    /** 材料会计在外部账做完分录后,回来点这个 */
    @OpLog(module = "采购成本差异", action = "确认入账")
    @PostMapping("/confirm")
    public Result<?> confirm(@RequestBody Map<String, Object> body) {
        SecurityUtil.requirePerm("rpt.estDiff");
        String period = Objects.toString(body.get("periodMonth"), "");
        if (!period.matches("\\d{4}-\\d{2}")) throw new BizException("请选择要确认的月份(格式 YYYY-MM)");
        /*
         * 🔴 确认的是【某公司某月】,而不是【某人某月】。
         * org 必须与差异的归属口径一致 —— 差异是按【仓库所属账套】(md_warehouse.org_id)算的,
         * 而操作人可能挂在子部门下(实测:仓库在 org=10,会计在 org=34)。
         * 用操作人的 org 存,会导致"确认了但拦截解除不了"。
         * 所以:前端从汇总行传 orgId(那张表本就按 org 分组);没传则退回当前用户的账套。
         */
        Long orgId = body.get("orgId") != null
                ? Long.valueOf(body.get("orgId").toString())
                : SecurityUtil.orgId();
        if (orgId == null) throw new BizException("请指定要确认的公司(账套)");
        orgScope.assertOrgVisible(orgId);

        // 重新算一次当下的真实差异,而不是信前端传来的数 —— 防止页面停留太久后按了个过期的数
        Map<String, Object> cur = jdbc.queryForMap("""
            SELECT COALESCE(ROUND(SUM(f.amount),2),0) amt, COUNT(*) cnt
            FROM (""" + CostDiffSql.FLOWS + """
            ) f
            WHERE f.in_stock = 0 AND f.org_id = ? AND DATE_FORMAT(f.biz_date, '%Y-%m') = ?
            """, orgId, period);
        BigDecimal amt = cur.get("amt") == null ? BigDecimal.ZERO : new BigDecimal(cur.get("amt").toString());
        int cnt = cur.get("cnt") == null ? 0 : Integer.parseInt(cur.get("cnt").toString());

        String name = Objects.toString(jdbc.query("SELECT name FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, SecurityUtil.uid()), "");
        // 幂等:同一账套同一月份只有一条,重复确认视为「按当前金额重新确认」
        int n = jdbc.update("""
            UPDATE fin_estimate_diff_confirm
            SET diff_amount = ?, entry_count = ?, confirmed_by = ?, confirmed_name = ?,
                confirmed_at = NOW(), remark = ?
            WHERE org_id = ? AND period_month = ? AND deleted = 0
            """, amt, cnt, SecurityUtil.uid(), name,
            Objects.toString(body.get("remark"), null), orgId, period);
        if (n == 0) {
            jdbc.update("""
                INSERT INTO fin_estimate_diff_confirm
                  (org_id, period_month, diff_amount, entry_count, confirmed_by, confirmed_name, confirmed_at, remark)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
                """, orgId, period, amt, cnt, SecurityUtil.uid(), name,
                Objects.toString(body.get("remark"), null));
        }
        return Result.ok(Map.of("periodMonth", period, "diffAmount", amt, "entryCount", cnt));
    }
}
