package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.modules.sales.service.SaleInvoiceService;
import com.group.scm.modules.sales.service.SaleReturnService;
import com.group.scm.modules.sales.service.SalesIncomeCostService;
import com.group.scm.common.service.OrgScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real submit/approve lifecycle, including MyBatis update fill and workflow completion timestamps. */
@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class SalesApprovalLifecycleDbTest {
    private static final long ID = 99893;
    private static final long SUBMITTER = 998931, APPROVER = 998932;
    private static final String OUT = "AUDIT-LIFECYCLE-OUT", INV = "AUDIT-LIFECYCLE-INV", RET = "AUDIT-LIFECYCLE-RET";
    @Autowired JdbcTemplate db;
    @Autowired SaleInvoiceService invoices;
    @Autowired SaleReturnService returns;
    @Autowired SalesIncomeCostService report;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach void requireDedicatedDatabase() { TestDatabaseGuard.requireEnvironment(); }

    private void login(long id, String... roles) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), new MockHttpServletResponse()));
        StpUtil.login(id);
        StpUtil.getSession().set("roles", List.of(roles));
        StpUtil.getSession().set("orgId", ID);
        StpUtil.getSession().set("dataScopes", List.of("SELF"));
    }

    @SuppressWarnings("unchecked")
    @Test void realReturnApprovalCannotChangeOneReportSnapshotMidQuery() throws Exception {
        try {
            seedDraftDocuments();
            db.update("UPDATE biz_sale_return SET return_date='2026-10-02' WHERE id=?", ID);
            login(SUBMITTER, "admin");
            invoices.submit(ID);
            login(APPROVER, "admin", "material_accountant");
            invoices.approve(ID, "invoice first");
            login(SUBMITTER, "admin");
            returns.submit(ID);

            var readingReturn = new CountDownLatch(1);
            var approvalDone = new CountDownLatch(1);
            var approverError = new AtomicReference<Throwable>();
            var readerConnection = new AtomicLong();
            var writerConnection = new AtomicLong();
            var reportTransaction = new AtomicReference<Boolean>(false);
            var hooked = new JdbcTemplate(dataSource) {
                @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                    if (sql.contains("return_approved_at") && sql.contains("FROM biz_sale_return r")) {
                        readerConnection.set(super.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        reportTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                        readingReturn.countDown();
                        try { assertTrue(approvalDone.await(15, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    }
                    return super.queryForList(sql, args);
                }
            };
            var target = new SalesIncomeCostService(hooked, new OrgScopeService(hooked));
            ReflectionTestUtils.setField(target, "salesTaxRate", new BigDecimal("13"));
            var factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
            SalesIncomeCostService reading = (SalesIncomeCostService) factory.getProxy();
            Thread approver = new Thread(() -> {
                try {
                    login(APPROVER, "admin", "material_accountant");
                    assertTrue(readingReturn.await(15, TimeUnit.SECONDS));
                    new TransactionTemplate(txManager).execute(status -> {
                        writerConnection.set(db.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        returns.approve(ID, "real concurrent approval");
                        return null;
                    });
                } catch (Throwable error) { approverError.set(error); }
                finally { approvalDone.countDown(); StpUtil.logout(); RequestContextHolder.resetRequestAttributes(); }
            }, "real-sale-return-approver");
            approver.start();
            var before = reading.report(ID, LocalDate.of(2026,10,1), LocalDate.of(2026,10,31));
            approver.join(15000);
            assertFalse(approver.isAlive());
            assertNull(approverError.get());
            assertNotEquals(readerConnection.get(), writerConnection.get(), "real DB interleaving needs two connections");
            assertTrue(reportTransaction.get(), "report must use its Spring transaction proxy");
            assertAmount((Map<String,Object>)before.get("total"), "revenue", "100");
            var after = report.report(ID, LocalDate.of(2026,10,1), LocalDate.of(2026,10,31));
            assertAmount((Map<String,Object>)after.get("total"), "revenue", "0");
        } finally { cleanupDocuments(); }
    }

    @SuppressWarnings("unchecked")
    @Test void workflowFinishedAtOrdersPreinvoiceReturnDespiteStaleDocumentTimestamps() throws Exception {
        try {
            seedDraftDocuments();

            login(SUBMITTER, "admin");
            returns.submit(ID);
            invoices.submit(ID);
            db.update("UPDATE biz_sale_invoice SET updated_at='2026-10-01 09:00:00' WHERE id=?", ID);
            login(APPROVER, "admin", "material_accountant");
            returns.approve(ID, "test return first");
            Thread.sleep(5); // NOW(6) must distinguish the two actual approval transactions.
            invoices.approve(ID, "test invoice second");

            var stamps = db.queryForList("SELECT biz_type,finished_at FROM wf_instance WHERE biz_id=? AND status='APPROVED' ORDER BY finished_at", ID);
            assertEquals(List.of("SALE_RETURN", "SALE_INV"), stamps.stream().map(x -> x.get("biz_type")).toList());
            assertNotNull(stamps.get(0).get("finished_at"));
            var documentInvoiceTime = db.queryForObject("SELECT updated_at FROM biz_sale_invoice WHERE id=?", java.sql.Timestamp.class, ID);
            var documentReturnTime = db.queryForObject("SELECT updated_at FROM biz_sale_return WHERE id=?", java.sql.Timestamp.class, ID);
            assertTrue(documentInvoiceTime.before(documentReturnTime), "document updated_at intentionally gives the wrong order");
            Map<String,Object> september = report.report(ID, LocalDate.of(2026,9,1), LocalDate.of(2026,9,30));
            Map<String,Object> october = report.report(ID, LocalDate.of(2026,10,1), LocalDate.of(2026,10,31));
            Map<String,Object> both = report.report(ID, LocalDate.of(2026,9,1), LocalDate.of(2026,10,31));
            assertAmount((Map<String,Object>)september.get("total"), "revenue", "0");
            assertAmount((Map<String,Object>)october.get("total"), "revenue", "0");
            assertAmount((Map<String,Object>)both.get("total"), "revenue", "0");
        } finally {
            cleanupDocuments();
        }
    }

    @Test void actualCrossMonthReverseApprovalBlocksBothMonthsAndCombinedReport() throws Exception {
        try {
            seedDraftDocuments();
            login(SUBMITTER, "admin");
            returns.submit(ID);
            invoices.submit(ID);
            login(APPROVER, "admin", "material_accountant");
            invoices.approve(ID, "invoice first");
            Thread.sleep(5);
            returns.approve(ID, "prior-month return second");

            var september = assertThrows(com.group.scm.common.BizException.class,
                    () -> report.report(ID, LocalDate.of(2026,9,1), LocalDate.of(2026,9,30)));
            var october = assertThrows(com.group.scm.common.BizException.class,
                    () -> report.report(ID, LocalDate.of(2026,10,1), LocalDate.of(2026,10,31)));
            var combined = assertThrows(com.group.scm.common.BizException.class,
                    () -> report.report(ID, LocalDate.of(2026,9,1), LocalDate.of(2026,10,31)));
            assertEquals(september.getMessage(), october.getMessage());
            assertEquals(september.getMessage(), combined.getMessage());
            assertTrue(september.getMessage().contains(RET));
            assertTrue(september.getMessage().contains(INV));
            assertTrue(september.getMessage().contains("暂停签字"));
        } finally { cleanupDocuments(); }
    }

    private void seedDraftDocuments() {
        db.update("INSERT INTO sys_org(id,parent_id,code,name,type,approval_mode) VALUES(?,0,?,'Approval lifecycle','COMPANY','SIMPLE')", ID, "AUDIT-LIFECYCLE-ORG");
        db.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,?,'Approval lifecycle','MAIN',?)", ID, "AUDIT-LIFECYCLE-WH", ID);
        db.update("INSERT INTO md_material(id,code,name,org_id) VALUES(?,?,'Approval lifecycle',?)", ID, "AUDIT-LIFECYCLE-MAT", ID);
        db.update("INSERT INTO sys_user(id,username,password,name,org_id) VALUES(?,?,'test-only','Lifecycle submitter',?),(?,?,'test-only','Lifecycle approver',?)",
                SUBMITTER, "audit-lifecycle-submitter", ID, APPROVER, "audit-lifecycle-approver", ID);
        db.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='material_accountant'", APPROVER);
        db.update("INSERT INTO biz_sale_out(id,doc_no,org_id,warehouse_id,out_date,total_qty,total_amt,status) VALUES(?,?,?,?,?,1,60,'APPROVED')",
                ID, OUT, ID, ID, LocalDate.of(2026, 9, 1));
        db.update("INSERT INTO biz_sale_out_line(id,out_id,line_no,material_id,qty,unit_price,amount) VALUES(?,?,1,?,1,60,60)", ID, ID, ID);
        db.update("INSERT INTO biz_sale_return(id,doc_no,org_id,source_out_id,source_out_no,warehouse_id,return_date,total_qty,total_amt,refund_amt,status) VALUES(?,?,?,?,?,?,?,1,60,113,'DRAFT')",
                ID, RET, ID, ID, OUT, ID, LocalDate.of(2026, 9, 30));
        db.update("INSERT INTO biz_sale_return_line(id,return_id,line_no,source_out_line_id,material_id,qty,unit_price,amount,sale_tax_price,sale_amount) VALUES(?,?,1,?,?,1,60,60,113,113)", ID, ID, ID, ID);
        db.update("INSERT INTO biz_sale_invoice(id,doc_no,org_id,source_out_id,source_out_no,invoice_date,total_qty,amount,tax_amt,total_amt,status) VALUES(?,?,?,?,?,?,1,100,13,113,'DRAFT')",
                ID, INV, ID, ID, OUT, LocalDate.of(2026, 10, 1));
        db.update("INSERT INTO biz_sale_invoice_line(id,invoice_id,line_no,material_id,qty,tax_rate,amount_excl_tax,tax_amount,amount) VALUES(?,?,1,?,1,13,100,13,113)", ID, ID, ID);
    }

    private void cleanupDocuments() {
        StpUtil.logout(); RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM notif WHERE biz_type IN ('SALE_INV','SALE_RETURN') AND biz_id=?", ID);
        db.update("DELETE FROM wf_record WHERE biz_no IN (?,?)", INV, RET);
        db.update("DELETE FROM wf_instance WHERE biz_id=? AND biz_type IN ('SALE_INV','SALE_RETURN')", ID);
        db.update("DELETE FROM inv_transaction WHERE biz_doc_type='SALE_RETURN' AND biz_doc_id=?", ID);
        db.update("DELETE FROM inv_balance WHERE warehouse_id=?", ID);
        db.update("DELETE FROM biz_sale_invoice_line WHERE invoice_id=?", ID);
        db.update("DELETE FROM biz_sale_invoice WHERE id=?", ID);
        db.update("DELETE FROM biz_sale_return_line WHERE return_id=?", ID);
        db.update("DELETE FROM biz_sale_return WHERE id=?", ID);
        db.update("DELETE FROM biz_sale_out_line WHERE out_id=?", ID);
        db.update("DELETE FROM biz_sale_out WHERE id=?", ID);
        db.update("DELETE FROM sys_user_role WHERE user_id IN (?,?)", SUBMITTER, APPROVER);
        db.update("DELETE FROM sys_user WHERE id IN (?,?)", SUBMITTER, APPROVER);
        db.update("DELETE FROM md_material WHERE id=?", ID);
        db.update("DELETE FROM md_warehouse WHERE id=?", ID);
        db.update("DELETE FROM sys_org WHERE id=?", ID);
    }

    private void assertAmount(Map<String,Object> row, String field, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) row.get(field)), field);
    }
}
