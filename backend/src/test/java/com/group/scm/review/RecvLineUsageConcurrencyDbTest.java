package com.group.scm.review;

import com.group.scm.modules.inventory.service.RecvLineUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原金额用量账在真实 MySQL(可重复读)下的并发与重复行(2026-10-18 P0 复查 P1-1 / P2)。
 * 只连专用 scm_regression 测试库(TestDatabaseGuard),从不碰生产。
 *
 * <p>P1-1 复现的关键:审批事务一开头就有普通查询,快照在那一刻定格;等锁期间另一张单提交了用量。
 * 修复前:锁住入库行读到新的已用数量,已用金额却还是旧快照 → 第二张分走 20(应为 10)。</p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@DisplayName("原金额用量账:并发审批与同单重复行")
class RecvLineUsageConcurrencyDbTest {

    private static final long R = 99_100_001L;            // 入库行
    private static final long INV_A = 99_100_011L, INV_B = 99_100_012L, INV_DUP = 99_100_013L;
    private static final long RET_B = 99_100_021L, RET_DUP = 99_100_022L;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private RecvLineUsage usage;

    @BeforeEach
    void setUp() {
        TestDatabaseGuard.requireEnvironment();
        var ds = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        usage = new RecvLineUsage(jdbc, tx);
        cleanup();
        // 入库 2 件、不含税 20 元
        jdbc.update("INSERT INTO biz_recv_line(id, recv_id, source_po_line_id, material_id, qty, unit_price, amount, invoiced_qty, returned_qty)"
                + " VALUES(?, 99100000, 99100000, 99100000, 2, 10, 20, 0, 0)", R);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM biz_invoice_line WHERE invoice_id BETWEEN 99100000 AND 99100099");
        jdbc.update("DELETE FROM biz_invoice WHERE id BETWEEN 99100000 AND 99100099");
        jdbc.update("DELETE FROM biz_purchase_return_line WHERE return_id BETWEEN 99100000 AND 99100099");
        jdbc.update("DELETE FROM biz_purchase_return WHERE id BETWEEN 99100000 AND 99100099");
        jdbc.update("DELETE FROM biz_recv_line WHERE id = ?", R);
    }

    private void invoice(long id, String status, int lines) {
        jdbc.update("INSERT INTO biz_invoice(id, doc_no, supplier_id, org_id, invoice_date, status, deleted)"
                + " VALUES(?, ?, 99100000, 99100000, CURDATE(), ?, 0)", id, "UT-INV-" + id, status);
        for (int i = 1; i <= lines; i++) {
            jdbc.update("INSERT INTO biz_invoice_line(id, invoice_id, material_id, qty, unit_price, amount, recv_line_id)"
                    + " VALUES(?, ?, 99100000, 1, 10, 10, ?)", id * 10 + i, id, R);
        }
    }

    private void purchaseReturn(long id, String status, int lines) {
        jdbc.update("INSERT INTO biz_purchase_return(id, doc_no, source_recv_id, source_recv_no, recv_type, warehouse_id, org_id,"
                + " return_date, status, deleted) VALUES(?, ?, 99100000, 'UT-RECV', 'INVOICE', 99100000, 99100000, CURDATE(), ?, 0)",
                id, "UT-PRET-" + id, status);
        for (int i = 1; i <= lines; i++) {
            jdbc.update("INSERT INTO biz_purchase_return_line(id, return_id, source_recv_line_id, material_id, qty, recv_unit_price, recv_amt)"
                    + " VALUES(?, ?, ?, 99100000, 1, 10, 10)", id * 10 + i, id, R);
        }
    }

    /** 另一张发票 A 审批:锁入库行 → 记用量 10 → 已开票 +1 → 提交 */
    private Thread approveInvoiceAInBackground(CountDownLatch snapshotTaken, CountDownLatch aCommitted,
                                               AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS));
                new TransactionTemplate(tx).execute(s -> {
                    usage.lock(R);
                    jdbc.update("UPDATE biz_invoice SET status = 'MATCHED' WHERE id = ?", INV_A);
                    jdbc.update("UPDATE biz_invoice_line SET recv_amt_used = 10 WHERE invoice_id = ?", INV_A);
                    jdbc.update("UPDATE biz_recv_line SET invoiced_qty = invoiced_qty + 1 WHERE id = ?", R);
                    return null;
                });
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                aCommitted.countDown();
            }
        });
        t.start();
        return t;
    }

    /**
     * 在"开头已有普通查询"的事务里等 A 提交,再锁入库行分本次 1 件。
     * excludeInv/excludeRet = 正在审批的本单。
     */
    private BigDecimal allocateAfterStaleSnapshot(Long excludeInv, Long excludeRet) throws Exception {
        CountDownLatch snapshotTaken = new CountDownLatch(1), aCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = approveInvoiceAInBackground(snapshotTaken, aCommitted, failure);
        BigDecimal amt = new TransactionTemplate(tx).execute(s -> {
            // 真实审批开头就是普通查询(读单据头)——快照在这里定格
            jdbc.queryForObject("SELECT COUNT(*) FROM biz_invoice_line WHERE recv_line_id = ?", Integer.class, R);
            snapshotTaken.countDown();
            try {
                assertTrue(aCommitted.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            RecvLineUsage.Line line = usage.lock(R);
            assertEquals(0, line.usedQty().compareTo(BigDecimal.ONE), "锁读应看到 A 提交后的已用数量 1");
            return usage.allocateForDoc(new RecvLineUsage.DocAllocation(), line, excludeInv, excludeRet, BigDecimal.ONE);
        });
        a.join(5000);
        assertNull(failure.get());
        return amt;
    }

    @Test
    @DisplayName("P1-1 两张发票并发审批:后到的那张分 10(修复前读旧快照分 20,凭空调差 −10)")
    void concurrentInvoices_secondSeesCommittedAmount() throws Exception {
        invoice(INV_A, "PENDING", 1);
        invoice(INV_B, "APPROVED", 1);
        assertEquals(0, new BigDecimal("10.00").compareTo(allocateAfterStaleSnapshot(INV_B, null)));
    }

    @Test
    @DisplayName("P1-1 发票与退货交错:退货在旧快照里等到发票提交,原入库额分 10")
    void invoiceThenReturn_returnSeesCommittedAmount() throws Exception {
        invoice(INV_A, "PENDING", 1);
        purchaseReturn(RET_B, "APPROVED", 1);
        assertEquals(0, new BigDecimal("10.00").compareTo(allocateAfterStaleSnapshot(null, RET_B)));
    }

    @Test
    @DisplayName("P2 同一入库行在一张发票里出现两行:合计分 20(修复前 10+20=30)")
    void duplicateLinesInOneInvoice_totalEqualsSourceAmount() {
        invoice(INV_DUP, "APPROVED", 2);
        BigDecimal total = new TransactionTemplate(tx).execute(s -> {
            var doc = new RecvLineUsage.DocAllocation();
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 1; i <= 2; i++) {
                RecvLineUsage.Line line = usage.lock(R);
                BigDecimal amt = usage.allocateForDoc(doc, line, INV_DUP, null, BigDecimal.ONE);
                jdbc.update("UPDATE biz_invoice_line SET recv_amt_used = ? WHERE id = ?", amt, INV_DUP * 10 + i);
                jdbc.update("UPDATE biz_recv_line SET invoiced_qty = invoiced_qty + 1 WHERE id = ?", R);  // 同 addInvoicedQty
                sum = sum.add(amt);
            }
            return sum;
        });
        assertEquals(0, new BigDecimal("20.00").compareTo(total), "实际合计 " + total);
    }

    @Test
    @DisplayName("P2 同一入库行在一张退货里出现两行:原入库额合计 20(修复前 30)")
    void duplicateLinesInOneReturn_totalEqualsSourceAmount() {
        purchaseReturn(RET_DUP, "APPROVED", 2);
        BigDecimal total = new TransactionTemplate(tx).execute(s -> {
            var doc = new RecvLineUsage.DocAllocation();
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 1; i <= 2; i++) {
                RecvLineUsage.Line line = usage.lock(R);
                BigDecimal amt = usage.allocateForDoc(doc, line, null, RET_DUP, BigDecimal.ONE);
                jdbc.update("UPDATE biz_purchase_return_line SET recv_amt = ? WHERE id = ?", amt, RET_DUP * 10 + i);
                jdbc.update("UPDATE biz_recv_line SET returned_qty = returned_qty + 1 WHERE id = ?", R);  // 同 addReturnedQty
                sum = sum.add(amt);
            }
            return sum;
        });
        assertEquals(0, new BigDecimal("20.00").compareTo(total), "实际合计 " + total);
    }
}
