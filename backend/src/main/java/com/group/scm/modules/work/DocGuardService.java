package com.group.scm.modules.work;

import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建单弹窗的「提前提示」：这个日期所在月份是不是已结账、选的仓库是不是正在盘点。
 *
 * <p><b>为什么要有</b>：这两条规则原本只在点保存时由后端拦（{@code PeriodCloseService.assertPeriodOpen}、
 * {@code WaInventoryService.assertWarehouseMovementAllowed}），一线常常是整张单填完才被挡回来
 * （2026-09-30 一天 34 次）。这里把同样的判断提前到填写时告诉用户。</p>
 *
 * <p><b>两条规则拦的时点不一样</b>：结账在建单/改单/提交时就拦，所以确定已结账时可以让前端置灰保存；
 * 盘点冻结只在审批过账（真正动库存）时拦，草稿照样能存，所以只提醒、不置灰。</p>
 *
 * <p><b>这里只是提示，不是防线</b>：真正拦不拦仍以保存时那两处为准，本类不改任何规则、不写任何数据。
 * 所以查不到、拿不准时宁可少提示，也不误拦——只有「确定存不了」才标 blocking。</p>
 *
 * <p>只回月份、公司名、仓库名、盘点单号，不含金额；范围限定在当前用户可见账套内（fail-closed）。</p>
 */
@Service
@RequiredArgsConstructor
public class DocGuardService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 一张单最多涉及转出、转入两个仓库，留点余量；超过的忽略，防止被人塞一长串 id 进来。 */
    private static final int MAX_WAREHOUSES = 10;

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    public List<Map<String, Object>> check(LocalDate date, List<Long> warehouseIds) {
        return check(date, warehouseIds, null, null);
    }

    public List<Map<String, Object>> check(LocalDate date, List<Long> warehouseIds, Long sourceOrgId, String operation) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Long> visible = orgScope.visibleOrgIds();
        if (visible.isEmpty()) return issues;

        // 只认当前用户看得见的仓库；别人账套的仓库 id 传进来等于没传
        List<Map<String, Object>> whs = visibleWarehouses(warehouseIds, visible);

        // 已知来源组织或仓库时只检查实际归属；没有来源时才给操作者账套一个粗略提示。
        Set<Long> scope = new LinkedHashSet<>();
        if (sourceOrgId != null && visible.contains(sourceOrgId)) {
            scope.add(sourceOrgId);
        } else if (sourceOrgId != null || (whs.isEmpty() && warehouseIds != null && warehouseIds.stream().anyMatch(id -> id != null))) {
            // 来源不在可见范围时不拿操作者自己的账套代替，以免错拦另一账套。
            return issues;
        } else if (whs.isEmpty()) {
            Long own = orgScope.currentAccountSetOrgId();
            if (own != null) scope.addAll(orgScope.accountSetOrgIds(own));
        } else {
            for (Map<String, Object> w : whs) scope.add(((Number) w.get("org_id")).longValue());
        }
        scope.retainAll(visible);
        periodIssue(issues, date == null ? LocalDate.now() : date, new ArrayList<>(scope));

        for (Map<String, Object> w : whs) stocktakeIssue(issues, w, "STOCKTAKE_CREATE".equals(operation));
        return issues;
    }

    private List<Map<String, Object>> visibleWarehouses(List<Long> ids, List<Long> visible) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().limit(MAX_WAREHOUSES).toList();
        if (wanted.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(wanted);
        args.addAll(visible);
        return jdbc.queryForList("""
                SELECT id, name, org_id FROM md_warehouse
                WHERE deleted = 0 AND id IN (%s) AND org_id IN (%s)
                """.formatted(ph(wanted.size()), ph(visible.size())), args.toArray());
    }

    private void periodIssue(List<Map<String, Object>> issues, LocalDate date, List<Long> orgIds) {
        if (orgIds.isEmpty()) return;
        String month = YearMonth.from(date).format(PERIOD);
        List<Object> args = new ArrayList<>();
        args.add(month);
        args.addAll(orgIds);
        List<Map<String, Object>> closed = jdbc.queryForList("""
                SELECT DISTINCT c.org_id, o.name FROM fin_period_close c LEFT JOIN sys_org o ON o.id = c.org_id
                WHERE c.period_month = ? AND c.status = 'CLOSED' AND c.deleted = 0 AND c.org_id IN (%s)
                """.formatted(ph(orgIds.size())), args.toArray());
        if (closed.isEmpty()) return;
        // 账套内全部组织都结了 → 这张单一定存不了；只结了一部分 → 说不准这张单归哪个，只提醒不拦
        boolean all = closed.size() >= orgIds.size();
        boolean today = YearMonth.from(date).equals(YearMonth.now());
        String text;
        if (all) {
            text = month + " 已结账，" + (today ? "本月的单据现在存不了。" : "这个日期的单据存不了，请改到未结账的月份。")
                    + "确实要记在 " + month + " 的，请联系财务先反结账。";
        } else {
            List<String> names = closed.stream().map(r -> String.valueOf(r.get("name"))).limit(5).toList();
            text = month + " 有公司已结账（" + String.join("、", names) + "）。这张单如果属于这些公司，保存时会被拦下。";
        }
        issues.add(issue("PERIOD", all, text));
    }

    private void stocktakeIssue(List<Map<String, Object>> issues, Map<String, Object> wh, boolean creatingStocktake) {
        // 状态口径与 WaInventoryService.assertWarehouseMovementAllowed 保持一致，改那边要同步改这里
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT doc_no FROM biz_stocktake
                WHERE warehouse_id = ? AND deleted = 0 AND status IN ('DRAFT', 'SUBMITTED', 'REJECTED')
                ORDER BY id LIMIT 1
                """, wh.get("id"));
        if (active.isEmpty()) return;
        // 普通单据仅在审批过账时拦；新盘点在建草稿前就拦，必须准确提示并置灰。
        issues.add(issue("STOCKTAKE", creatingStocktake, wh.get("name") + "正在盘点（盘点单 " + active.get(0).get("doc_no")
                + (creatingStocktake ? "）。须等本次盘点完成后才能新建盘点单。" : "）。这张单可以先保存，但盘点完成前审批不了、库存不会变。")));
    }

    private static Map<String, Object> issue(String type, boolean blocking, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("blocking", blocking);
        m.put("text", text);
        return m;
    }

    private static String ph(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
