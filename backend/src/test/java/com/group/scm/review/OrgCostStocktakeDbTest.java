package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.mapper.BizStocktakeLineMapper;
import com.group.scm.modules.inventory.mapper.BizStocktakeMapper;
import com.group.scm.modules.inventory.mapper.InvBalanceMapper;
import com.group.scm.modules.inventory.mapper.InvTransactionMapper;
import com.group.scm.modules.inventory.service.CostMath;
import com.group.scm.modules.inventory.service.InvLedgerSupport;
import com.group.scm.modules.inventory.service.OrgCostEngine;
import com.group.scm.modules.inventory.service.StocktakeService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.notif.service.NotifService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL RR current-read and cross-warehouse posting; never allowed against shared databases. */
@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class OrgCostStocktakeDbTest {
    private static final long ORG = 994810, MAT = 994810, TAKE = 994810;
    private static final long WH = 994811, OTHER_WH = 994812;
    @Autowired JdbcTemplate jdbc;
    @Autowired StocktakeService stocktake;
    @Autowired WaInventoryService wa;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ApplicationContext context;
    private Long cutoverId;

    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @BeforeEach void seedDedicatedFixture() {
        TestDatabaseGuard.requireEnvironment();
        jdbc.update("INSERT INTO sys_org(id,parent_id,code,name,type) VALUES(?,0,'AUDIT-COST-ORG','Cost test','COMPANY')", ORG);
        jdbc.update("INSERT INTO inv_cost_lock(org_id) VALUES(?)", ORG);
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,?,?,'SECONDARY',?)",
                    ORG+i, "AUDIT-COST-WH-"+i, "Cost test "+i, ORG);
        }
        jdbc.update("INSERT INTO md_material(id,code,name,org_id) VALUES(?,'AUDIT-COST-MAT','Cost test',?)", MAT, ORG);
        jdbc.update("INSERT INTO inv_cost_pool(org_id,material_id,qty,amount,avg_price) VALUES(?,?,10,100,10)", ORG, MAT);
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO inv_balance(material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,?,?,?)",
                    MAT, ORG+i, i==2 ? 10 : 0, i==2 ? 100 : 0, i==2 ? 10 : 0);
        }
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> connection.prepareStatement(
                "INSERT INTO inv_cost_cutover(action,scope_after,remark,flow_id_at) VALUES('CUTOVER','ORG','Isolated cost regression',0)",
                Statement.RETURN_GENERATED_KEYS), keys);
        cutoverId = keys.getKey().longValue();
    }

    @AfterEach void cleanupDedicatedFixture() {
        TestDatabaseGuard.requireEnvironment();
        jdbc.update("DELETE FROM inv_transaction WHERE warehouse_id BETWEEN ? AND ?", WH, ORG+4);
        jdbc.update("DELETE FROM biz_stocktake_line WHERE stocktake_id=?", TAKE);
        jdbc.update("DELETE FROM biz_stocktake WHERE id=?", TAKE);
        jdbc.update("DELETE FROM inv_balance WHERE warehouse_id BETWEEN ? AND ?", WH, ORG+4);
        jdbc.update("DELETE FROM inv_cost_pool WHERE org_id=?", ORG);
        jdbc.update("DELETE FROM md_material WHERE id IN (?,?)", MAT, MAT+10);
        jdbc.update("DELETE FROM md_warehouse WHERE id BETWEEN ? AND ?", WH, ORG+4);
        jdbc.update("DELETE FROM inv_cost_lock WHERE org_id=?", ORG);
        jdbc.update("DELETE FROM sys_org WHERE id=?", ORG);
        if (cutoverId != null) jdbc.update("DELETE FROM inv_cost_cutover WHERE id=?", cutoverId);
    }

    @Test void zeroWarehouseGainUsesLatestCompanyPriceDespiteEarlierRepeatableReadSnapshot() throws Exception {
        jdbc.update("INSERT INTO biz_stocktake(id,doc_no,org_id,warehouse_id,take_date,status) VALUES(?,'AUDIT-COST-TAKE',?,?,'2038-02-01','SUBMITTED')",
                TAKE, ORG, WH);
        jdbc.update("INSERT INTO biz_stocktake_line(id,stocktake_id,line_no,material_id,book_qty,unit_price,actual_qty) VALUES(?,?,1,?,0,0,2)",
                TAKE, TAKE, MAT);
        assertThrows(BizException.class, () -> wa.inbound(MAT, WH, n("1"), n("10"),
                "IN_ESTIMATE", "REVIEW_COST", 1L, "AUDIT-FROZEN", null, ""));
        var snapshotTaken = new CountDownLatch(1);
        var receiptCommitted = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        var approval = workers.submit(() -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), new MockHttpServletResponse()));
            StpUtil.login(ORG+100);
            StpUtil.getSession().set("roles", List.of("admin"));
            StpUtil.getSession().set("orgId", ORG);
            try {
                var transaction = new TransactionTemplate(txManager);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                transaction.execute(status -> {
                    eq("100", jdbc.queryForObject("SELECT amount FROM inv_cost_pool WHERE org_id=? AND material_id=?", BigDecimal.class, ORG, MAT));
                    snapshotTaken.countDown();
                    await(receiptCommitted);
                    // The ordinary read stays old even after the other warehouse committed.
                    eq("100", jdbc.queryForObject("SELECT amount FROM inv_cost_pool WHERE org_id=? AND material_id=?", BigDecimal.class, ORG, MAT));
                    stocktake.approve(TAKE);
                    return null;
                });
            } finally {
                StpUtil.logout(); RequestContextHolder.resetRequestAttributes();
            }
        });
        var receipt = workers.submit(() -> {
            await(snapshotTaken);
            try {
                // The other warehouse remains usable while WH is frozen; company price changes 10 -> 20.
                wa.inbound(MAT, OTHER_WH, n("10"), n("30"), "IN_ESTIMATE", "REVIEW_COST", 2L, "AUDIT-COST-RECEIPT", null, "");
            } finally { receiptCommitted.countDown(); }
        });
        try {
            receipt.get(20, TimeUnit.SECONDS);
            approval.get(20, TimeUnit.SECONDS);
            eq("40", jdbc.queryForObject("SELECT gain_amt FROM biz_stocktake WHERE id=?", BigDecimal.class, TAKE));
            eq("20", jdbc.queryForObject("SELECT unit_price FROM biz_stocktake_line WHERE stocktake_id=?", BigDecimal.class, TAKE));
            eq("22", jdbc.queryForObject("SELECT qty FROM inv_cost_pool WHERE org_id=? AND material_id=?", BigDecimal.class, ORG, MAT));
            eq("440", jdbc.queryForObject("SELECT amount FROM inv_cost_pool WHERE org_id=? AND material_id=?", BigDecimal.class, ORG, MAT));
            eq("40", jdbc.queryForObject("SELECT amount FROM inv_balance WHERE warehouse_id=? AND material_id=?", BigDecimal.class, WH, MAT));
        } finally {
            snapshotTaken.countDown(); receiptCommitted.countDown();
            approval.cancel(true); receipt.cancel(true); workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test void cutoverSqlAndJavaKeepTinyAllocationsNonnegativeAndPreserveNormalRounding() throws Exception {
        jdbc.update("UPDATE inv_balance SET qty=1,amount=CASE WHEN warehouse_id IN (?,?) THEN 0.01 ELSE 0 END,avg_price=0.01 WHERE material_id=? AND warehouse_id BETWEEN ? AND ?",
                WH, OTHER_WH, MAT, WH, ORG+4);
        jdbc.update("INSERT INTO md_material(id,code,name,org_id) VALUES(?,'AUDIT-COST-NORMAL','Normal rounding',?)", MAT+10, ORG);
        jdbc.update("INSERT INTO inv_balance(material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,3,10,3.33),(?,?,3,20.01,6.67)",
                MAT+10, WH, MAT+10, OTHER_WH);
        Path path = Path.of("../scripts/cost-cutover.sql");
        if (!Files.exists(path)) path = Path.of("scripts/cost-cutover.sql");
        String script = Files.readString(path);
        // Execute the actual cutover CTE without its INSERT, procedure, mode change or balance updates.
        String query = script.substring(script.indexOf("WITH bb AS ("), script.indexOf("  SELECT v_cut, org_id"))
                + "SELECT material_id,warehouse_id,allocated FROM final_alloc WHERE org_id=? ORDER BY material_id,warehouse_id";
        var result = jdbc.queryForList(query, ORG);
        var tinyQty = new LinkedHashMap<Long, BigDecimal>();
        for (long wh = WH; wh <= ORG+4; wh++) tinyQty.put(wh, BigDecimal.ONE);
        var normalQty = new LinkedHashMap<Long, BigDecimal>();
        normalQty.put(WH, n("3")); normalQty.put(OTHER_WH, n("3"));
        var tiny = CostMath.allocate(n("0.02"), tinyQty);
        var normal = CostMath.allocate(n("30.01"), normalQty);
        assertEquals(6, result.size());
        for (var row : result) {
            long material = ((Number) row.get("material_id")).longValue();
            long wh = ((Number) row.get("warehouse_id")).longValue();
            BigDecimal amount = (BigDecimal) row.get("allocated");
            assertTrue(amount.signum() >= 0);
            assertEquals(0, (material==MAT ? tiny : normal).get(wh).compareTo(amount));
        }
        eq("0.02", result.stream().filter(row -> ((Number)row.get("material_id")).longValue()==MAT)
                .map(row -> (BigDecimal)row.get("allocated")).reduce(BigDecimal.ZERO,BigDecimal::add));
        eq("15.00", normal.get(WH)); eq("15.01", normal.get(OTHER_WH));
    }

    @Test void sameWarehouseOutboundRejectsBeforeStocktakeConfirmationWithoutReversedLocks() throws Exception {
        jdbc.update("INSERT INTO biz_stocktake(id,doc_no,org_id,warehouse_id,take_date,status) VALUES(?,'AUDIT-COST-TAKE',?,?,'2038-02-01','SUBMITTED')",
                TAKE, ORG, WH);
        jdbc.update("INSERT INTO biz_stocktake_line(id,stocktake_id,line_no,material_id,book_qty,unit_price,actual_qty) VALUES(?,?,1,?,0,0,2)",
                TAKE, TAKE, MAT);
        var outboundOwnsCompany = new CountDownLatch(1);
        var approvalAttemptsCompany = new CountDownLatch(1);
        // Only synchronize the real company-lock calls; all business SQL and transaction proxies remain real.
        var observedLedger = new InvLedgerSupport(context.getBean(InvBalanceMapper.class),
                context.getBean(InvTransactionMapper.class), jdbc, txManager) {
            @Override public void lockCompanyCost(Long orgId) {
                if (orgId.equals(ORG) && Thread.currentThread().getName().equals("cost-take-confirm")) {
                    approvalAttemptsCompany.countDown();
                }
                super.lockCompanyCost(orgId);
                if (orgId.equals(ORG) && Thread.currentThread().getName().equals("cost-frozen-outbound")) {
                    outboundOwnsCompany.countDown();
                    await(approvalAttemptsCompany);
                }
            }
        };
        var observedWa = transactionProxy(new WaInventoryService(context.getBean(InvBalanceMapper.class),
                context.getBean(InvTransactionMapper.class), jdbc, observedLedger, context.getBean(OrgCostEngine.class)));
        var observedTake = transactionProxy(new StocktakeService(context.getBean(BizStocktakeMapper.class),
                context.getBean(BizStocktakeLineMapper.class), jdbc, context.getBean(OrgScopeService.class),
                observedWa, context.getBean(PeriodCloseService.class), context.getBean(DocNoService.class), context.getBean(NotifService.class)));
        var workers = Executors.newFixedThreadPool(2);
        var outbound = workers.submit(() -> {
            Thread.currentThread().setName("cost-frozen-outbound");
            BizException rejected = assertThrows(BizException.class, () -> observedWa.outbound(MAT, WH, n("1"),
                    "OUT", 3L, "AUDIT-COST-BLOCKED", null, ""));
            assertTrue(rejected.getMessage().contains("正在盘点"));
        });
        var confirmation = workers.submit(() -> {
            Thread.currentThread().setName("cost-take-confirm");
            await(outboundOwnsCompany);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), new MockHttpServletResponse()));
            StpUtil.login(ORG+101);
            StpUtil.getSession().set("roles", List.of("admin"));
            StpUtil.getSession().set("orgId", ORG);
            try { observedTake.approve(TAKE); }
            finally { StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
        });
        try {
            outbound.get(20, TimeUnit.SECONDS);
            confirmation.get(20, TimeUnit.SECONDS);
            assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM biz_stocktake WHERE id=?", String.class, TAKE));
            eq("20", jdbc.queryForObject("SELECT gain_amt FROM biz_stocktake WHERE id=?", BigDecimal.class, TAKE));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inv_transaction WHERE biz_doc_no='AUDIT-COST-BLOCKED'", Integer.class));
        } finally {
            outboundOwnsCompany.countDown(); approvalAttemptsCompany.countDown();
            outbound.cancel(true); confirmation.cancel(true); workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionProxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "Concurrent cost test timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual));
    }
}
