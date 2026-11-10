package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.mapper.FinPeriodCloseMapper;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.service.StocktakeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** The real Spring-proxied create and close service methods interleave on two MySQL connections. */
@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class PeriodCloseStocktakeServiceRaceDbTest {
    private static final long ID = 99892;
    private static final String MONTH = "2038-02";
    private static final LocalDate DATE = LocalDate.of(2038, 2, 1);
    @Autowired JdbcTemplate jdbc;
    @Autowired StocktakeService stocktake;
    @Autowired FinPeriodCloseMapper closeMapper;
    @Autowired OrgScopeService orgScope;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach void requireDedicatedDatabase() { TestDatabaseGuard.requireEnvironment(); }

    private void login(long uid) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), new MockHttpServletResponse()));
        StpUtil.login(uid);
        StpUtil.getSession().set("roles", List.of("admin"));
        StpUtil.getSession().set("orgId", ID);
        StpUtil.getSession().set("dataScopes", List.of("SELF"));
    }

    @Test void closeAfterCleanCheckRejectsOverlappingNewStocktake() throws Exception {
        jdbc.update("INSERT INTO sys_org(id,parent_id,code,name,type) VALUES(?,0,?,'Close race','COMPANY')", ID, "AUDIT-CLOSE-ORG");
        jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,?,'Close race','MAIN',?)", ID, "AUDIT-CLOSE-WH", ID);
        jdbc.update("INSERT INTO md_material(id,code,name,org_id) VALUES(?,?,'Close race',?)", ID, "AUDIT-CLOSE-MAT", ID);
        jdbc.update("INSERT INTO inv_balance(id,material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,?,1,10,10)", ID, ID, ID);
        var checked = new CountDownLatch(1);
        var allowClose = new CountDownLatch(1);
        var stocktakeStarted = new CountDownLatch(1);
        var stocktakeFinished = new CountDownLatch(1);
        var closeError = new AtomicReference<Throwable>();
        var createError = new AtomicReference<Throwable>();
        try {
            // Inherited production closePeriod body runs through the same annotation-based Spring transaction proxy.
            // Only the post-check wait is added; super.checkPeriod executes every real closing query.
            var target = new PeriodCloseService(jdbc, closeMapper, orgScope) {
                @Override public Map<String, Object> checkPeriod(String month) {
                    Map<String, Object> result = super.checkPeriod(month);
                    checked.countDown();
                    try { assertTrue(allowClose.await(15, TimeUnit.SECONDS), "close not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return result;
                }
            };
            var factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
            PeriodCloseService closing = (PeriodCloseService) factory.getProxy();
            Thread closer = new Thread(() -> {
                try { login(ID + 1); closing.closePeriod(MONTH, "concurrency regression"); }
                catch (Throwable e) { closeError.set(e); }
                finally { StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "real-period-close");
            Thread creator = new Thread(() -> {
                try {
                    login(ID + 2);
                    assertTrue(checked.await(15, TimeUnit.SECONDS), "close did not finish check");
                    stocktakeStarted.countDown();
                    stocktake.create(ID, DATE, false, "concurrency regression");
                } catch (Throwable e) { createError.set(e); }
                finally { stocktakeFinished.countDown(); StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "real-stocktake-create");
            closer.start(); creator.start();
            boolean createdBeforeClose;
            try {
                assertTrue(checked.await(15, TimeUnit.SECONDS), "full check did not finish");
                assertTrue(stocktakeStarted.await(15, TimeUnit.SECONDS));
                // Old create can finish while close is held after its clean check. New create waits on org row.
                createdBeforeClose = stocktakeFinished.await(2, TimeUnit.SECONDS);
            } finally { allowClose.countDown(); }
            closer.join(15000); creator.join(15000);
            assertFalse(closer.isAlive()); assertFalse(creator.isAlive());
            assertFalse(createdBeforeClose, "new stocktake must wait for the still-open closing transaction");
            assertNull(closeError.get(), "close must commit");
            assertInstanceOf(BizException.class, createError.get(), "create after close must fail at period guard");
            assertTrue(createError.get().getMessage().contains("已结账"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM biz_stocktake WHERE org_id=? AND deleted=0", Integer.class, ID));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM fin_period_close WHERE org_id=? AND period_month=? AND status='CLOSED'", Integer.class, ID, MONTH));
        } finally {
            jdbc.update("DELETE FROM biz_stocktake_line WHERE stocktake_id IN (SELECT id FROM biz_stocktake WHERE org_id=?)", ID);
            jdbc.update("DELETE FROM biz_stocktake WHERE org_id=?", ID);
            jdbc.update("DELETE FROM fin_period_close WHERE org_id=?", ID);
            jdbc.update("DELETE FROM inv_balance WHERE warehouse_id=?", ID);
            jdbc.update("DELETE FROM md_material WHERE id=?", ID);
            jdbc.update("DELETE FROM md_warehouse WHERE id=?", ID);
            jdbc.update("DELETE FROM sys_org WHERE id=?", ID);
        }
    }

    @Test void closeSeesStocktakeCommittedWhileWaitingForOrganizationLock() throws Exception {
        jdbc.update("INSERT INTO sys_org(id,parent_id,code,name,type) VALUES(?,0,?,'Close race','COMPANY')", ID, "AUDIT-CLOSE-ORG");
        jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,?,'Close race','MAIN',?)", ID, "AUDIT-CLOSE-WH", ID);
        jdbc.update("INSERT INTO md_material(id,code,name,org_id) VALUES(?,?,'Close race',?)", ID, "AUDIT-CLOSE-MAT", ID);
        jdbc.update("INSERT INTO inv_balance(id,material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,?,1,10,10)", ID, ID, ID);
        var stocktakeInserted = new CountDownLatch(1);
        var visibleRead = new CountDownLatch(1);
        var allowStocktakeCommit = new CountDownLatch(1);
        var creatorError = new AtomicReference<Throwable>();
        var closerError = new AtomicReference<Throwable>();
        var creatorConnection = new AtomicLong();
        var closerConnection = new AtomicLong();
        var closingTransactionActive = new AtomicBoolean();
        var closingIsolation = new AtomicReference<String>();
        try {
            // Real OrgScopeService query establishes RR snapshot before close attempts the organization lock.
            var observedScope = new OrgScopeService(jdbc) {
                @Override public List<Long> visibleOrgIds() {
                    List<Long> result = super.visibleOrgIds();
                    if (visibleRead.getCount() != 0) {
                        closerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        closingTransactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                        closingIsolation.set(jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                        visibleRead.countDown();
                    }
                    return result;
                }
            };
            var factory = new ProxyFactory(new PeriodCloseService(jdbc, closeMapper, observedScope));
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
            PeriodCloseService closing = (PeriodCloseService) factory.getProxy();
            Thread creator = new Thread(() -> {
                try {
                    login(ID + 3);
                    new TransactionTemplate(txManager).execute(status -> {
                        stocktake.create(ID, DATE, false, "first transaction");
                        creatorConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        stocktakeInserted.countDown();
                        try { assertTrue(allowStocktakeCommit.await(15, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        return null;
                    });
                } catch (Throwable e) { creatorError.set(e); }
                finally { StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "stocktake-holds-org-lock");
            Thread closer = new Thread(() -> {
                try {
                    login(ID + 4);
                    assertTrue(stocktakeInserted.await(15, TimeUnit.SECONDS));
                    closing.closePeriod(MONTH, "must see preceding stocktake");
                } catch (Throwable e) { closerError.set(e); }
                finally { StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "close-waits-for-stocktake");
            creator.start(); closer.start();
            try {
                assertTrue(stocktakeInserted.await(15, TimeUnit.SECONDS));
                assertTrue(visibleRead.await(15, TimeUnit.SECONDS), "real org-scope read must precede stocktake commit");
                assertNotEquals(creatorConnection.get(), closerConnection.get(), "transactions require separate MySQL connections");
            } finally { allowStocktakeCommit.countDown(); }
            creator.join(15000); closer.join(15000);
            assertFalse(creator.isAlive()); assertFalse(closer.isAlive());
            assertNull(creatorError.get());
            assertInstanceOf(BizException.class, closerError.get(), "close must detect the just-committed unfinished stocktake");
            assertTrue(closerError.get().getMessage().contains("异常"));
            assertTrue(closingTransactionActive.get(), "close must run through its Spring transaction proxy");
            assertEquals("READ-COMMITTED", closingIsolation.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM biz_stocktake WHERE org_id=? AND deleted=0", Integer.class, ID));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fin_period_close WHERE org_id=? AND period_month=? AND status='CLOSED'", Integer.class, ID, MONTH));
        } finally {
            jdbc.update("DELETE FROM biz_stocktake_line WHERE stocktake_id IN (SELECT id FROM biz_stocktake WHERE org_id=?)", ID);
            jdbc.update("DELETE FROM biz_stocktake WHERE org_id=?", ID);
            jdbc.update("DELETE FROM fin_period_close WHERE org_id=?", ID);
            jdbc.update("DELETE FROM inv_balance WHERE warehouse_id=?", ID);
            jdbc.update("DELETE FROM md_material WHERE id=?", ID);
            jdbc.update("DELETE FROM md_warehouse WHERE id=?", ID);
            jdbc.update("DELETE FROM sys_org WHERE id=?", ID);
        }
    }
}
