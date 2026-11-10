package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.Result;
import com.group.scm.modules.report.ReportController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 报表中心冒烟测试：逐个报表用多种入参实际执行 SQL，断言能成功返回列表（不抛异常）。
 *
 * <p>报表都是直接拼 SQL 跑 {@code jdbc.queryForList}，最易出问题的是坏字段/语法/动态拼接
 * （尤其收发存汇总按日期动态拼条件）。这里以 admin 登录（所有报表权限自动通过、可见全部账套），
 * 跑在隔离 Docker 测试库上，把每个报表的查询都执行一遍以验证有效性。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true",
        disabledReason = "需要隔离 MySQL 测试库")
@DisplayName("报表中心冒烟测试")
class ReportSmokeTest {

    @Autowired private ReportController report;

    @BeforeEach
    void loginAdmin() {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(99200L);
        StpUtil.getSession().set("roles", List.of("admin"));
        StpUtil.getSession().set("perms", List.of());
        StpUtil.getSession().set("dataScopes", List.of("ALL"));
        StpUtil.getSession().set("orgId", 1L);
    }

    @AfterEach
    void logout() {
        try { if (StpUtil.isLogin()) StpUtil.logout(); } catch (Exception ignored) {}
    }

    private void assertOk(String name, Result<List<Map<String, Object>>> r) {
        assertNotNull(r, name + " 应返回结果");
        assertEquals(0, r.getCode(), name + " 应成功执行，msg=" + r.getMsg());
        assertNotNull(r.getData(), name + " 数据列表不应为 null");
    }

    private Map<String, Object> q(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("RPT-PO: 采购订单跟踪")
    void poTracking() {
        assertOk("采购订单跟踪", report.poTracking(q()));
        assertOk("采购订单跟踪(带条件)",
                report.poTracking(q("status", "APPROVED", "startDate", "2026-01-01", "endDate", "2026-12-31")));
    }

    @Test
    @DisplayName("RPT-EST: 暂估明细")
    void estimate() {
        assertOk("暂估明细", report.estimate(q()));
        assertOk("暂估明细(只看未核销)", report.estimate(q("onlyUnmatched", "true")));
    }

    @Test
    @DisplayName("RPT-BAL: 现存量表")
    void balance() {
        assertOk("现存量表", report.balance(q()));
        assertOk("现存量表(含零结存)", report.balance(q("includeZero", "true")));
    }

    @Test
    @DisplayName("RPT-INOUT: 收发存汇总(多日期组合)")
    void inOutSummary() {
        assertOk("收发存(无日期)", report.inOutSummary(q()));
        assertOk("收发存(仅起始)", report.inOutSummary(q("startDate", "2026-01-01")));
        assertOk("收发存(仅截止)", report.inOutSummary(q("endDate", "2026-12-31")));
        assertOk("收发存(起止)", report.inOutSummary(q("startDate", "2026-01-01", "endDate", "2026-12-31")));
    }

    @Test
    @DisplayName("RPT-OUT: 出库汇总")
    void outSummary() {
        assertOk("出库汇总", report.outSummary(q()));
        assertOk("出库汇总(带日期)", report.outSummary(q("startDate", "2026-01-01", "endDate", "2026-12-31")));
    }

    @Test
    @DisplayName("RPT-PLAN: 工程项目材料计划执行")
    void projectMaterialPlan() {
        assertOk("工程项目材料计划", report.projectMaterialPlan(q()));
        assertOk("工程项目材料计划(指定状态)", report.projectMaterialPlan(q("status", "APPROVED")));
    }
}
