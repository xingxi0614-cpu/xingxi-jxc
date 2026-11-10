package com.group.scm.review;

import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.sales.service.SalesIncomeCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Two real JDBC connections in the dedicated scm_regression DB; never targets production. */
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class AccountingConcurrencyDbTest {
    private record Context(JdbcTemplate jdbc, DataSourceTransactionManager tx) {}
    private Context context() {
        TestDatabaseGuard.requireEnvironment();
        var ds=new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        return new Context(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    }
    private TransactionTemplate transaction(Context c,int isolation) {
        var t=new TransactionTemplate(c.tx);t.setIsolationLevel(isolation);return t;
    }
    @Test void repeatableReadKeepsReportSnapshotAcrossConcurrentApproval() throws Exception {
        var annotation=SalesIncomeCostService.class.getMethod("report",Long.class,java.time.LocalDate.class,java.time.LocalDate.class)
                .getAnnotation(Transactional.class);
        assertEquals(Isolation.REPEATABLE_READ,annotation.isolation());
        var c=context();
        c.jdbc.execute("CREATE TABLE IF NOT EXISTS audit_concurrency_probe(id INT PRIMARY KEY,status VARCHAR(20))");
        try {
            c.jdbc.update("DELETE FROM audit_concurrency_probe");
            c.jdbc.update("INSERT INTO audit_concurrency_probe VALUES(1,'PENDING')");
            CountDownLatch firstRead=new CountDownLatch(1), writerDone=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread writer=new Thread(()->{
                try {
                    assertTrue(firstRead.await(5,TimeUnit.SECONDS));
                    transaction(c,TransactionDefinition.ISOLATION_DEFAULT).execute(s->{
                        c.jdbc.update("UPDATE audit_concurrency_probe SET status='APPROVED' WHERE id=1");return null;});
                } catch(Throwable e){failure.set(e);} finally {writerDone.countDown();}
            });writer.start();
            transaction(c,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(s->{
                assertEquals("PENDING",c.jdbc.queryForObject("SELECT status FROM audit_concurrency_probe WHERE id=1",String.class));
                firstRead.countDown();
                try {assertTrue(writerDone.await(5,TimeUnit.SECONDS));} catch(InterruptedException e){throw new AssertionError(e);}
                assertEquals("PENDING",c.jdbc.queryForObject("SELECT status FROM audit_concurrency_probe WHERE id=1",String.class));
                return null;
            });
            writer.join(5000);assertNull(failure.get());
            assertEquals("APPROVED",c.jdbc.queryForObject("SELECT status FROM audit_concurrency_probe WHERE id=1",String.class));
        } finally {c.jdbc.execute("DROP TABLE IF EXISTS audit_concurrency_probe");}
    }
    @Test void closeAndStocktakeProtocolShareOrganizationRowLock() throws Exception {
        var c=context();
        c.jdbc.execute("CREATE TABLE IF NOT EXISTS audit_concurrency_probe(id INT PRIMARY KEY,status VARCHAR(20))");
        c.jdbc.execute("CREATE TABLE IF NOT EXISTS audit_concurrency_warehouse(id INT PRIMARY KEY,org_id BIGINT)");
        try {
            c.jdbc.update("DELETE FROM audit_concurrency_probe");
            c.jdbc.update("INSERT INTO audit_concurrency_probe VALUES(1,'OPEN')");
            c.jdbc.update("DELETE FROM audit_concurrency_warehouse");
            c.jdbc.update("INSERT INTO audit_concurrency_warehouse VALUES(1,1)");
            var service=new PeriodCloseService(c.jdbc,null,null);
            CountDownLatch closeLocked=new CountDownLatch(1), createAttempt=new CountDownLatch(1), closeMayCommit=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread closing=new Thread(()->{
                try {transaction(c,TransactionDefinition.ISOLATION_DEFAULT).execute(s->{
                    service.lockPeriodOrganizations(List.of(1L));closeLocked.countDown();
                    try {assertTrue(createAttempt.await(5,TimeUnit.SECONDS));assertTrue(closeMayCommit.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}
                    c.jdbc.update("UPDATE audit_concurrency_probe SET status='CLOSED' WHERE id=1");return null;
                });}catch(Throwable e){failure.set(e);}
            });
            Thread creating=new Thread(()->{
                try {assertTrue(closeLocked.await(5,TimeUnit.SECONDS));
                    transaction(c,TransactionDefinition.ISOLATION_DEFAULT).execute(s->{
                        // 与 StocktakeService.create 同序：仓库当前读、组织锁、再读期间状态。
                        Long orgId=c.jdbc.queryForObject("SELECT org_id FROM audit_concurrency_warehouse WHERE id=1 FOR UPDATE",Long.class);
                        createAttempt.countDown();service.lockPeriodOrganizations(List.of(orgId));
                        String status=c.jdbc.queryForObject("SELECT status FROM audit_concurrency_probe WHERE id=1",String.class);
                        if("OPEN".equals(status))c.jdbc.update("UPDATE audit_concurrency_probe SET status='ACTIVE_STOCKTAKE' WHERE id=1");
                        return null;
                    });}catch(Throwable e){failure.set(e);}
            });
            closing.start();creating.start();
            assertTrue(createAttempt.await(5,TimeUnit.SECONDS));
            closeMayCommit.countDown();closing.join(5000);creating.join(5000);
            assertNull(failure.get());
            assertEquals("CLOSED",c.jdbc.queryForObject("SELECT status FROM audit_concurrency_probe WHERE id=1",String.class));
        } finally {c.jdbc.execute("DROP TABLE IF EXISTS audit_concurrency_warehouse");c.jdbc.execute("DROP TABLE IF EXISTS audit_concurrency_probe");}
    }
}
