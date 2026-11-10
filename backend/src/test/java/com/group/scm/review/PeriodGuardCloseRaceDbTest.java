package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.mapper.FinPeriodCloseMapper;
import com.group.scm.modules.close.service.PeriodCloseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U06(扩展,2026-10-18):全站单据的期间检查与月末结账互斥。
 * 修前:检查只是普通读,结账在检查之后、单据提交之前完成,单据照样写进已结账月份。
 * 修后:检查拿组织行共享锁,结账拿排他锁——结账进行中,单据等它提交后读到 CLOSED 被拦;单据之间互不阻塞。
 */
@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class PeriodGuardCloseRaceDbTest {
    private static final long ID = 99893;
    private static final String MONTH = "2038-03";
    private static final LocalDate DATE = LocalDate.of(2038, 3, 5);
    @Autowired JdbcTemplate jdbc;
    @Autowired PeriodCloseService periods;
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

    private void seed() {
        jdbc.update("INSERT INTO sys_org(id,parent_id,code,name,type) VALUES(?,0,?,'Guard race','COMPANY')", ID, "AUDIT-GUARD-ORG");
    }

    private void cleanup() {
        jdbc.update("DELETE FROM fin_period_close WHERE org_id=?", ID);
        jdbc.update("DELETE FROM sys_org WHERE id=?", ID);
    }

    /** 结账已做完检查、尚未提交时,任何单据的期间检查都要等它;它提交后单据被拦。 */
    @Test void businessGuardWaitsForInFlightCloseAndThenRejects() throws Exception {
        seed();
        var checked = new CountDownLatch(1);
        var allowClose = new CountDownLatch(1);
        var guardFinished = new CountDownLatch(1);
        var closeError = new AtomicReference<Throwable>();
        var guardError = new AtomicReference<Throwable>();
        try {
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
                try { login(ID + 1); closing.closePeriod(MONTH, "guard race"); }
                catch (Throwable e) { closeError.set(e); }
                finally { StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "period-close");
            Thread writer = new Thread(() -> {
                try {
                    assertTrue(checked.await(15, TimeUnit.SECONDS), "close did not finish check");
                    // 模拟任意一张单据的写入事务:先过期间检查,再写数据,最后提交
                    new TransactionTemplate(txManager).executeWithoutResult(st ->
                            periods.assertPeriodOpen(DATE, ID, "测试单据", "GUARD-1"));
                } catch (Throwable e) { guardError.set(e); }
                finally { guardFinished.countDown(); }
            }, "business-guard");
            closer.start(); writer.start();
            boolean passedWhileClosing;
            try {
                assertTrue(checked.await(15, TimeUnit.SECONDS));
                passedWhileClosing = guardFinished.await(2, TimeUnit.SECONDS);
            } finally { allowClose.countDown(); }
            closer.join(15000); writer.join(15000);
            assertFalse(closer.isAlive()); assertFalse(writer.isAlive());
            assertFalse(passedWhileClosing, "期间检查必须等在途的结账提交");
            assertNull(closeError.get(), "结账应成功提交");
            assertInstanceOf(BizException.class, guardError.get(), "结账提交后,单据必须被期间检查拦下");
            assertTrue(guardError.get().getMessage().contains("已结账"));
        } finally { cleanup(); }
    }

    /** 两张单据同时在事务里过期间检查:共享锁互不阻塞(不能因为这把锁让日常业务排队)。 */
    @Test void twoBusinessGuardsDoNotBlockEachOther() throws Exception {
        seed();
        var firstHolding = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstError = new AtomicReference<Throwable>();
        try {
            Thread first = new Thread(() -> {
                try {
                    new TransactionTemplate(txManager).executeWithoutResult(st -> {
                        periods.assertPeriodOpen(DATE, ID, "单据A", null);
                        firstHolding.countDown();
                        try { releaseFirst.await(15, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                } catch (Throwable e) { firstError.set(e); }
            }, "guard-a");
            first.start();
            try {
                assertTrue(firstHolding.await(15, TimeUnit.SECONDS));
                long t0 = System.nanoTime();
                new TransactionTemplate(txManager).executeWithoutResult(st ->
                        periods.assertPeriodOpen(DATE, ID, "单据B", null));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 2000, "第二张单据不应等第一张提交,实际等了 " + ms + "ms");
            } finally { releaseFirst.countDown(); }
            first.join(15000);
            assertNull(firstError.get());
        } finally { cleanup(); }
    }
}
