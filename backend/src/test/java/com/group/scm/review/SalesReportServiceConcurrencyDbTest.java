package com.group.scm.review;

import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.sales.service.SalesIncomeCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Report service through its Spring transaction proxy, with a second real MySQL connection approving mid-read. */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class SalesReportServiceConcurrencyDbTest {
    private static final long ORG = 99891, MATERIAL = 99891, OUT = 99891, INVOICE = 99891;
    private static final LocalDate DATE = LocalDate.of(2038, 2, 1);

    @Test void approvalBetweenServiceQueriesCannotEraseUninvoicedSale() throws Exception {
        TestDatabaseGuard.requireEnvironment();
        var ds = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        var fixture = new JdbcTemplate(ds);
        var beforeUninvoicedRead = new CountDownLatch(1);
        var approved = new CountDownLatch(1);
        var writerError = new AtomicReference<Throwable>();
        var readerConnection = new AtomicLong();
        var writerConnection = new AtomicLong();
        var reportTransactionActive = new AtomicBoolean();
        var hooked = new JdbcTemplate(ds) {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("FROM biz_sale_out o") && sql.contains("NOT EXISTS")) {
                    readerConnection.set(super.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    reportTransactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                    beforeUninvoicedRead.countDown();
                    try { assertTrue(approved.await(15, TimeUnit.SECONDS), "writer did not commit"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
                return super.queryForList(sql, args);
            }
        };
        try {
            fixture.update("INSERT INTO sys_org(id,parent_id,code,name,type) VALUES(?,0,?,'Service concurrency','COMPANY')", ORG, "AUDIT-SERVICE-ORG");
            fixture.update("INSERT INTO md_material(id,code,name) VALUES(?,?,'Service material')", MATERIAL, "AUDIT-SERVICE-MAT");
            fixture.update("INSERT INTO biz_sale_out(id,doc_no,org_id,warehouse_id,out_date,total_qty,total_amt,status) VALUES(?,?,?,1,?,1,60,'APPROVED')",
                    OUT, "AUDIT-SERVICE-OUT", ORG, DATE);
            fixture.update("INSERT INTO biz_sale_out_line(id,out_id,line_no,material_id,qty,amount) VALUES(?,?,?,?,1,60)", OUT, OUT, 1, MATERIAL);
            fixture.update("INSERT INTO biz_sale_invoice(id,doc_no,org_id,source_out_id,source_out_no,invoice_date,status) VALUES(?,?,?,?,?,?,'PENDING')",
                    INVOICE, "AUDIT-SERVICE-INV", ORG, OUT, "AUDIT-SERVICE-OUT", DATE);
            fixture.update("INSERT INTO biz_sale_invoice_line(id,invoice_id,line_no,material_id,qty,tax_rate,amount_excl_tax,tax_amount,amount) VALUES(?,?,1,?,1,13,100,13,113)",
                    INVOICE, INVOICE, MATERIAL);

            var scope = new OrgScopeService(hooked) {
                @Override public List<Long> visibleOrgIds() { return List.of(ORG); }
            };
            var target = new SalesIncomeCostService(hooked, scope);
            ReflectionTestUtils.setField(target, "salesTaxRate", new BigDecimal("13"));
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
            var reportService = (SalesIncomeCostService) proxy.getProxy();

            var writer = new Thread(() -> {
                try {
                    assertTrue(beforeUninvoicedRead.await(15, TimeUnit.SECONDS), "report did not reach second query");
                    // DriverManagerDataSource obtains a separate physical MySQL connection in this thread.
                    new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status -> {
                        writerConnection.set(fixture.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        assertEquals(1, fixture.update("UPDATE biz_sale_invoice SET status='APPROVED' WHERE id=?", INVOICE));
                        return null;
                    });
                } catch (Throwable e) { writerError.set(e); }
                finally { approved.countDown(); }
            }, "invoice-approver");
            writer.start();
            Map<String, Object> result = reportService.report(ORG, DATE, DATE);
            writer.join(15000);
            assertFalse(writer.isAlive());
            assertNull(writerError.get());
            assertNotEquals(readerConnection.get(), writerConnection.get(), "interleaving must use distinct MySQL connections");
            assertEquals(1, ((List<?>) result.get("uninvoiced")).size(),
                    "one report must not mix pre- and post-approval database snapshots");
            assertTrue(reportTransactionActive.get(), "report must run through its Spring transaction proxy");
            assertEquals(List.of(), result.get("detail"));
            assertEquals("APPROVED", fixture.queryForObject("SELECT status FROM biz_sale_invoice WHERE id=?", String.class, INVOICE));
        } finally {
            fixture.update("DELETE FROM biz_sale_invoice_line WHERE invoice_id=?", INVOICE);
            fixture.update("DELETE FROM biz_sale_invoice WHERE id=?", INVOICE);
            fixture.update("DELETE FROM biz_sale_out_line WHERE out_id=?", OUT);
            fixture.update("DELETE FROM biz_sale_out WHERE id=?", OUT);
            fixture.update("DELETE FROM md_material WHERE id=?", MATERIAL);
            fixture.update("DELETE FROM sys_org WHERE id=?", ORG);
        }
    }
}
