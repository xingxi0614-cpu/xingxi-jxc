package com.group.scm.modules.work;

import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.workflow.service.WorkflowService;
import com.group.scm.modules.report.EstimateAging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台「需要你留意」：把"已经卡住别人 / 再拖会出问题"的事主动摆到首页。
 *
 * <p><b>为什么要有它</b>（2026-10-06 定）：盘点单建完没人知道下一步、仓库被冻结；
 * 出库单因账面库存为 0 反复审批不过；入库草稿占着订单数量……这些都不报错，
 * 只有等人撞上才知道。这里把它们算出来，按角色只给"自己能动手处理"的人看。</p>
 *
 * <p><b>口径铁律</b>：</p>
 * <ul>
 *   <li>只读、只回<b>条数和单号</b>，不回任何金额——仓管也能看，不涉及脱敏。</li>
 *   <li>一律按可见账套过滤，取不到可见账套就返回空（fail-closed）。</li>
 *   <li>每一项单独 try/catch：某一项算不出来只少一条提醒，不能让工作台整个打不开。</li>
 *   <li>天数在 Java 里用服务器本地日期算，不用 SQL 的 CURDATE()（数据库时区可能是 UTC，差一天）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkAlertService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    private final PeriodCloseService periodCloseService;
    private final WorkflowService workflowService;

    /** 待审批超过几天算滞留（定 3 天） */
    @Value("${scm.alert.pending-stale-days:3}")
    private int pendingStaleDays;
    /** 草稿超过几天算滞留（定 7 天） */
    @Value("${scm.alert.draft-stale-days:7}")
    private int draftStaleDays;
    /** 月底前几天开始提醒结账（定 5 天） */
    @Value("${scm.alert.close-remind-days:5}")
    private int closeRemindDays;
    /** 部分到货 / 部分出库后多少天没再动算挂着(定 30 天:数量适中,不至于刷屏) */
    @Value("${scm.alert.partial-stale-days:30}")
    private int partialStaleDays;
    /** 提醒里带到列表页的单号最多几张(放在网址里,太多会超长) */
    private static final int LINK_DOCS = 50;

    public List<Map<String, Object>> alerts() {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds == null || orgIds.isEmpty()) return Collections.emptyList();

        boolean admin = SecurityUtil.isAdmin();
        boolean warehouse = SecurityUtil.hasRole("warehouse") || admin;
        boolean finance = SecurityUtil.hasFinanceAccess() || admin;
        boolean purchaser = SecurityUtil.hasRole("purchaser") || admin;

        List<Map<String, Object>> out = new ArrayList<>();
        // 顺序即显示顺序：红(已卡住) → 黄(再拖出问题) → 蓝(提个醒)
        if (warehouse || finance || SecurityUtil.isAccountAdmin()) safely(() -> stocktakeFreeze(out, orgIds, warehouse, finance), "盘点冻结");
        if (warehouse) safely(() -> outShortage(out, orgIds), "出库缺料");
        if (finance) safely(() -> estimateOverdue(out, orgIds), "暂估超期");
        if (finance) safely(() -> closeCountdown(out, orgIds), "结账倒计时");
        safely(() -> staleApprovals(out), "审批滞留");
        if (warehouse) safely(() -> staleRecvDrafts(out, orgIds), "入库草稿滞留");
        if (finance) safely(() -> saleUninvoiced(out, orgIds), "销售未开票");
        if (purchaser) safely(() -> poPartialStale(out, orgIds), "订单部分到货滞留");
        if (purchaser) safely(() -> oreqPartialStale(out, orgIds), "申请部分出库滞留");
        if (finance) safely(() -> reopenedPeriods(out, orgIds), "反结账未重结");
        return out;
    }

    /** 未完成的盘点单会冻结整个仓库（不能入库/出库/转库），每张单独列一条，最多 5 条。 */
    private void stocktakeFreeze(List<Map<String, Object>> out, List<Long> orgIds, boolean warehouse, boolean finance) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.doc_no, s.status, s.created_at, w.name AS wh_name
                FROM biz_stocktake s LEFT JOIN md_warehouse w ON w.id = s.warehouse_id
                WHERE s.deleted = 0 AND s.status IN ('DRAFT', 'SUBMITTED', 'REJECTED') AND s.org_id IN (%s)
                ORDER BY s.created_at ASC LIMIT 5
                """.formatted(ph(orgIds)), orgIds.toArray());
        for (Map<String, Object> r : rows) {
            String status = String.valueOf(r.get("status"));
            boolean waitingFinance = "SUBMITTED".equals(status);
            long days = daysSince(r.get("created_at"));
            String wh = r.get("wh_name") == null ? "仓库" : String.valueOf(r.get("wh_name"));
            String who = waitingFinance ? "轮到材料会计：确认盘盈盘亏"
                    : ("REJECTED".equals(status) ? "轮到仓管：按驳回意见改正实盘数后重新提交" : "轮到仓管：录入实盘数并提交");
            // 轮不到自己的人也要看见（知道仓库为什么动不了），但文字里写清在等谁
            add(out, "red", wh + "正在盘点，已冻结 " + days + " 天",
                    "盘点单 " + r.get("doc_no") + (waitingFinance ? " 已提交、等财务确认" : " 还没提交")
                            + "。冻结期间这个仓库不能入库、出库、转库。",
                    who, "去盘点单", "/inventory/stocktake", Map.of("keyword", String.valueOf(r.get("doc_no"))));
        }
    }

    /** 待审批的材料出库单里，有行的数量大于该仓库当前账面库存 → 审批必然报"库存不足"。 */
    private void outShortage(List<Map<String, Object>> out, List<Long> orgIds) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT o.id) AS docs, COUNT(DISTINCT ol.material_id) AS mats
                FROM biz_out o
                JOIN (SELECT out_id, material_id, SUM(qty) qty FROM biz_out_line GROUP BY out_id, material_id) ol
                  ON ol.out_id = o.id
                LEFT JOIN inv_balance b ON b.material_id = ol.material_id AND b.warehouse_id = o.warehouse_id
                WHERE o.deleted = 0 AND o.status = 'PENDING' AND o.org_id IN (%s)
                  AND ol.qty > IFNULL(b.qty, 0)
                """.formatted(ph(orgIds)), orgIds.toArray());
        long docs = num(r.get("docs"));
        if (docs == 0) return;
        add(out, "red", docs + " 张材料出库单因库存不足审批不过",
                "涉及 " + num(r.get("mats")) + " 种物料，账面库存小于出库数量。多半是货到了但采购入库单还没录或没审批。",
                "轮到仓管：先补录采购入库，再请财务审批出库", "看待审批出库单", "/outbound/out", Map.of("status", "PENDING"));
    }

    /** 月底前 N 天、本月还没结账：把结账检查的异常数和提醒数摆出来。 */
    private void closeCountdown(List<Map<String, Object>> out, List<Long> orgIds) {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        long left = ChronoUnit.DAYS.between(today, ym.atEndOfMonth());
        if (left >= closeRemindDays) return;
        String period = ym.toString();
        List<Object> args = new ArrayList<>(orgIds);
        args.add(period);
        Integer closed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fin_period_close
                WHERE deleted = 0 AND status = 'CLOSED' AND org_id IN (%s) AND period_month = ?
                """.formatted(ph(orgIds)), Integer.class, args.toArray());
        if (closed != null && closed >= orgIds.size()) return;
        Map<String, Object> check = periodCloseService.checkPeriod(period);
        int issues = (Integer) check.get("issueCount");
        int warnings = (Integer) check.get("warningCount");
        String title = issues > 0
                ? ym.getMonthValue() + " 月结账还有 " + issues + " 项异常要处理"
                : ym.getMonthValue() + " 月可以结账了";
        add(out, issues > 0 ? "red" : "yellow", title,
                "离月底还有 " + left + " 天。" + (issues > 0 ? "异常不清完不能结账。" : "没有硬性异常。")
                        + (warnings > 0 ? "另有 " + warnings + " 项提醒建议顺手处理。" : ""),
                "轮到财务：逐项处理后结账", "去月末结账", "/cost/period-close", Map.of());
    }

    /** 轮到我审批、且已经等了 N 天以上的单据。口径直接复用「待我审批」，保证列出来的都真能审。 */
    private void staleApprovals(List<Map<String, Object>> out) {
        LocalDateTime line = LocalDateTime.now().minusDays(pendingStaleDays);
        long stale = 0;
        List<Map<String, Object>> rows = workflowService.pendingApprovals();
        for (Map<String, Object> r : rows) {
            LocalDateTime started = toDateTime(r.get("startedAt"));
            if (started != null && started.isBefore(line)) stale++;
        }
        if (stale == 0) return;
        add(out, "yellow", stale + " 张单据等你审批已超过 " + pendingStaleDays + " 天",
                "你名下一共有 " + rows.size() + " 张待审批。压着不审，后面的出库、入库都动不了。",
                "轮到你：审批或驳回", "去审批", "/work/todo", Map.of());
    }

    /** 草稿和待审批仍占订单可入库量；驳回单已释放。每种状态各自链接到可见筛选。 */
    private void staleRecvDrafts(List<Map<String, Object>> out, List<Long> orgIds) {
        for (String status : List.of("DRAFT", "PENDING")) {
            List<Object> args = new ArrayList<>(orgIds);
            args.add(status);
            args.add(LocalDateTime.now().minusDays(draftStaleDays));
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_recv
                    WHERE deleted = 0 AND org_id IN (%s) AND status = ? AND created_at < ?
                    """.formatted(ph(orgIds)), Integer.class, args.toArray());
            if (n == null || n == 0) continue;
            boolean draft = "DRAFT".equals(status);
            add(out, "yellow", n + " 张采购入库单" + (draft ? "草稿" : "待审批") + "已放了 " + draftStaleDays + " 天以上",
                    "这些单据仍占着采购订单的可入库数量，会导致新建入库单时提示「超过可入库数」。",
                    draft ? "轮到仓管：提交审批，或确认不要了就删除" : "轮到审批人：审批或驳回",
                    "去处理", "/inbound/recv", Map.of("status", status));
        }
    }

    /** 已审批的销售出库还没有已审批的销售发票：收入成本明细表里这部分是空的。 */
    private void saleUninvoiced(List<Map<String, Object>> out, List<Long> orgIds) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(*) AS n, MIN(o.out_date) AS oldest
                FROM biz_sale_out o
                WHERE o.deleted = 0 AND o.status = 'APPROVED' AND o.org_id IN (%s)
                  AND NOT EXISTS (SELECT 1 FROM biz_sale_invoice v
                                  WHERE v.source_out_id = o.id AND v.deleted = 0 AND v.status = 'APPROVED')
                  AND EXISTS (SELECT 1 FROM biz_sale_out_line ol WHERE ol.out_id = o.id
                    AND ol.qty > COALESCE((SELECT SUM(rl.qty) FROM biz_sale_return_line rl
                      JOIN biz_sale_return sr ON sr.id = rl.return_id
                      WHERE rl.source_out_line_id = ol.id AND sr.deleted = 0 AND sr.status = 'APPROVED'), 0))
                """.formatted(ph(orgIds)), orgIds.toArray());
        long n = num(r.get("n"));
        if (n == 0) return;
        add(out, "yellow", n + " 张销售出库还没开票",
                "最早一张已出库 " + daysSince(r.get("oldest")) + " 天。没开票的出库不会进销售收入成本明细表。",
                "轮到财务：录销售发票并审批", "看未开票清单", "/report/sales-income-cost", Map.of("tab", "un"));
    }

    /**
     * 暂估挂账超过 90 天还没开票(2026-10-16 对标财务软件操作手册:超三个月的暂估要落实原因、督促开票)。
     * 口径与暂估清单"挂账天数"标红、月末结账提醒同一处(EstimateAging);只提醒,不拦任何操作。
     */
    private void estimateOverdue(List<Map<String, Object>> out, List<Long> orgIds) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT r.id) AS docs, COALESCE(SUM(%s), 0) AS amt, MIN(r.recv_date) AS oldest
                FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id
                WHERE r.deleted = 0 AND r.status = 'APPROVED' AND r.recv_type = 'ESTIMATE' AND r.org_id IN (%s)
                  AND %s
                """.formatted(EstimateAging.REMAIN_AMT, ph(orgIds), EstimateAging.OVERDUE_LINE), orgIds.toArray());
        long n = num(r.get("docs"));
        if (n == 0) return;
        String amt = new java.math.BigDecimal(String.valueOf(r.get("amt"))).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        add(out, "red", "暂估超过 3 个月还没开票：" + n + " 张，" + amt + " 元",
                "最早一张已挂账 " + daysSince(r.get("oldest")) + " 天。请联系供应商尽快开票，或说明长期暂估的原因。",
                "轮到财务：催票或说明原因", "看暂估清单", "/report/estimate", Map.of("overdue", "1"));
    }

    /*
     * ── 下面两条是 2026-10-06 原型里「采购」那两条提醒的落地(2026-10-20 用户:"按你的理解去做")──
     * 原型写的是「货已到齐但没关闭」「退料后又显示待执行」,照字面都不会发生了:
     *   - 采购订单全部入库时系统自动转「已入库」,没有手工关闭这一步;
     *   - 10-15 起退料默认"不再领",申请不会再被退回「部分出库」;选了"还要再领"是有意留着再领。
     * 原型真正想抓的是"单据一直挂着没人管":订单只到了一部分、剩下的迟迟不来;申请只领了一部分、剩下的没人领也没人改。
     * 这两类只有采购能处理(催供应商 / 修订申请数量),所以只给采购看;转库领料的申请另有到期确认提醒(PickupReminderJob),这里不重复。
     */

    /** 采购订单部分到货后 N 天没再到货(按最后一次已审批入库日期,一次都没到的不算——那是还没到货,不是挂着)。 */
    private void poPartialStale(List<Map<String, Object>> out, List<Long> orgIds) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(LocalDate.now().minusDays(partialStaleDays));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.doc_no, MAX(r.recv_date) AS last_in
                FROM biz_po p JOIN biz_recv r ON r.source_po_id = p.id AND r.deleted = 0 AND r.status = 'APPROVED'
                WHERE p.deleted = 0 AND p.status = 'PARTIAL_RECEIVED' AND p.org_id IN (%s)
                GROUP BY p.id, p.doc_no HAVING MAX(r.recv_date) < ?
                ORDER BY last_in ASC
                """.formatted(ph(orgIds)), args.toArray());
        if (rows.isEmpty()) return;
        add(out, "blue", rows.size() + " 张采购订单只到了一部分货，已超过 " + partialStaleDays + " 天没再到货",
                "最早一张已等 " + daysSince(rows.get(0).get("last_in")) + " 天。剩下的货一直挂在订单上，暂估和应付也跟着悬着。",
                "轮到采购：联系供应商确认补货时间", "看这些订单", "/purchase/po", Map.of("keyword", docNos(rows)));
    }

    /** 出库申请部分出库后 N 天没再出库:剩下的不要了就修订数量(改成已出库数,申请自动变「已出库」),否则一直挂着。 */
    private void oreqPartialStale(List<Map<String, Object>> out, List<Long> orgIds) {
        List<Object> args = new ArrayList<>(orgIds);
        args.add(LocalDate.now().minusDays(partialStaleDays));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT q.doc_no, MAX(o.out_date) AS last_out
                FROM biz_out_req q JOIN biz_out o ON o.source_req_id = q.id AND o.deleted = 0 AND o.status = 'APPROVED'
                WHERE q.deleted = 0 AND q.status = 'PARTIAL_OUT' AND q.fulfillment_mode <> 'TRANSFER' AND q.org_id IN (%s)
                GROUP BY q.id, q.doc_no HAVING MAX(o.out_date) < ?
                ORDER BY last_out ASC
                """.formatted(ph(orgIds)), args.toArray());
        if (rows.isEmpty()) return;
        add(out, "blue", rows.size() + " 张出库申请只领了一部分，剩下的超过 " + partialStaleDays + " 天没人领",
                "最早一张已放了 " + daysSince(rows.get(0).get("last_out")) + " 天。剩下的不要了，就把申请数量修订成已出库数；还要领的，提醒仓管出库。",
                "轮到采购：修订申请或催领料", "看这些申请", "/outbound/req", Map.of("keyword", docNos(rows)));
    }

    private static String docNos(List<Map<String, Object>> rows) {
        return String.join(",", rows.stream().limit(LINK_DOCS).map(r -> String.valueOf(r.get("doc_no"))).toList());
    }

    /** 反结账后一直没有重新结账的月份。 */
    private void reopenedPeriods(List<Map<String, Object>> out, List<Long> orgIds) {
        List<String> months = jdbc.queryForList("""
                SELECT DISTINCT period_month FROM fin_period_close
                WHERE deleted = 0 AND status = 'REOPENED' AND org_id IN (%s)
                ORDER BY period_month
                """.formatted(ph(orgIds)), String.class, orgIds.toArray());
        if (months.isEmpty()) return;
        add(out, "blue", String.join("、", months) + " 反结账后还没重新结账",
                "反结账期间这个月的单据又可以改动，处理完要记得重新结账，否则账一直是敞开的。",
                "轮到财务：处理完后重新结账", "去月末结账", "/cost/period-close", Map.of());
    }

    // ---------------- 小工具 ----------------

    private void add(List<Map<String, Object>> out, String level, String title, String detail,
                     String who, String actionText, String path, Map<String, String> query) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("title", title);
        m.put("detail", detail);
        m.put("who", who);
        m.put("actionText", actionText);
        m.put("path", path);
        m.put("query", query);
        out.add(m);
    }

    private void safely(Runnable r, String name) {
        try {
            r.run();
        } catch (Exception e) {
            // 不向用户回显异常内容；只在服务器日志留痕
            log.warn("工作台提醒「{}」计算失败: {}", name, e.getMessage());
        }
    }

    private static String ph(List<Long> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static LocalDateTime toDateTime(Object o) {
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (o instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (o instanceof LocalDate d) return d.atStartOfDay();
        return null;
    }

    private static long daysSince(Object o) {
        LocalDateTime t = toDateTime(o);
        return t == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now()));
    }
}
