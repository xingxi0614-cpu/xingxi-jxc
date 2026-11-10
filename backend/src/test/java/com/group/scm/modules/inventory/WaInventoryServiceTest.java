package com.group.scm.modules.inventory;

import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.service.WaInventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加权平均算法单元测试 - 严格按照规划书 §9.3 算例验证。
 *
 * <p>这是整个系统最核心的逻辑测试，财务签字算例：</p>
 *
 * <pre>
 * # | 操作          | 数量 | 单价   | 结存数 | 结存额    | 结存单价
 * 1 | 期初          | 100  | 10.00  | 100   | 1000.00  | 10.0000
 * 2 | 入库（发票）  | 50   | 12.00  | 150   | 1600.00  | 10.6667
 * 3 | 出库          | 60   | 10.6667| 90    | 960.00   | 10.6667
 * 4 | 入库（暂估）  | 100  | 11.00  | 190   | 2060.00  | 10.8421
 * 5 | 出库          | 40   | 10.8421| 150   | 1626.32  | 10.8421
 * 6 | 发票到 #4 实际11.5 → 差额+50 | 150 | 1676.32 | 11.1855
 * 7 | 出库          | 50   | 11.1855| 100   | 1117.55  | 11.1855
 * </pre>
 *
 * <p>使用：本测试需要 @SpringBootTest 启动整个 Spring 上下文，会读取 application.yml
 * 连接测试数据库。建议在独立的 test 数据库上跑。</p>
 *
 * <p>简化方式：可改用 H2 内存库（在 src/test/resources 下加 application-test.yml）
 * 然后加 @ActiveProfiles("test")。</p>
 */
@SpringBootTest
@Transactional
@Rollback
@EnabledIfEnvironmentVariable(
        named = "RUN_DB_TESTS",
        matches = "true",
        disabledReason = "需要独立 MySQL 测试库，避免默认测试误连本地或正式账套"
)
@DisplayName("加权平均算法（规划书 §9.3 算例）")
class WaInventoryServiceTest {

    @Autowired
    private WaInventoryService waService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 引擎现在按"仓库所属公司"记账(2026-10-15 按公司核算),仓库必须真实存在。
     * 在本测试事务里建一个虚拟仓库(挂集团 org 1),随事务回滚。
     */
    @org.junit.jupiter.api.BeforeEach
    void createVirtualWarehouse() {
        jdbc.update("INSERT IGNORE INTO md_warehouse (id, code, name, warehouse_type, org_id, status, deleted) "
                + "VALUES (?, 'TEST-WH-99001', '单测虚拟仓', 'MAIN', 1, 1, 0)", WH_ID);
    }

    /** 用临时虚拟物料 ID 99001 + 仓库 99001 做测试，避免污染真实数据 */
    private static final Long MAT_ID = 99001L;
    private static final Long WH_ID  = 99001L;

    private void assertBalance(InvBalance b, double qty, double amt, double price) {
        assertEquals(0, b.getQty().compareTo(BigDecimal.valueOf(qty)),
                "数量错误，期望 " + qty + " 实际 " + b.getQty());
        assertEquals(0, b.getAmount().compareTo(BigDecimal.valueOf(amt).setScale(2, RoundingMode.HALF_UP)),
                "金额错误，期望 " + amt + " 实际 " + b.getAmount());
        assertEquals(0, b.getAvgPrice().compareTo(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP)),
                "结存单价错误，期望 " + price + " 实际 " + b.getAvgPrice());
    }

    @Test
    @DisplayName("Step 1-2：期初 100@10 + 入库 50@12 → 单价 10.6667")
    void step1And2() {
        // 期初
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10),
                "INIT", "INIT", 0L, "INIT", null, "期初");
        InvBalance b1 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b1, 100, 1000.00, 10.0000);

        // 入库 50 @ 12
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12),
                "IN_INVOICE", "RECV", 1L, "RECV-T-001", null, "测试入库");
        InvBalance b2 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b2, 150, 1600.00, 10.67);
    }

    @Test
    @DisplayName("Step 3：出库 60 → 单价不变 10.6667，金额减 640.00")
    void step3() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10), "INIT", "INIT", 0L, "INIT", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12), "IN_INVOICE", "RECV", 1L, "RECV-T-001", null, "");

        BigDecimal[] r = waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(60), "OUT", 1L, "OUT-T-001", null, "");
        assertEquals(0, r[0].compareTo(new BigDecimal("10.67")), "出库单价应为当前结存单价");
        assertEquals(0, r[1].compareTo(new BigDecimal("640.00")), "出库金额错误");

        InvBalance b3 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b3, 90, 960.00, 10.67);
    }

    @Test
    @DisplayName("Step 4-5：再入库暂估 100@11 → 10.8421；再出 40 → 不变")
    void step4And5() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10), "INIT", "INIT", 0L, "INIT", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12), "IN_INVOICE", "RECV", 1L, "RECV-T-001", null, "");
        waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(60), "OUT", 1L, "OUT-T-001", null, "");

        // Step 4：暂估入库 100 @ 11
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(11), "IN_ESTIMATE", "RECV", 2L, "RECV-T-002", null, "暂估");
        InvBalance b4 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b4, 190, 2060.00, 10.84);

        // Step 5：出库 40
        BigDecimal[] r5 = waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(40), "OUT", 2L, "OUT-T-002", null, "");
        assertEquals(0, r5[0].compareTo(new BigDecimal("10.84")));
        InvBalance b5 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b5, 150, 1626.32, 10.84);
    }

    @Test
    @DisplayName("Step 6：显式库存金额调整 +50；发票差异另走损益接口")
    void step6_ExplicitInventoryAdjustment() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10), "INIT", "INIT", 0L, "INIT", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12), "IN_INVOICE", "RECV", 1L, "RECV-T-001", null, "");
        waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(60), "OUT", 1L, "OUT-T-001", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(11), "IN_ESTIMATE", "RECV", 2L, "RECV-T-002", null, "暂估");
        waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(40), "OUT", 2L, "OUT-T-002", null, "");

        // Step 6：暂估转发票，发票单价 11.5，差额 = (11.5 - 11) * 100 = 50
        BigDecimal delta = new BigDecimal("0.5").multiply(BigDecimal.valueOf(100));
        waService.adjust(MAT_ID, WH_ID, delta, "INV", 1L, "INV-T-001", "暂估冲销");
        InvBalance b6 = waService.currentBalance(MAT_ID, WH_ID);

        assertBalance(b6, 150, 1676.32, 11.18);
    }

    @Test
    @DisplayName("Step 7：调差后再出库 50 → 用新单价 11.1855")
    void step7_OutboundAfterAdjust() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10), "INIT", "INIT", 0L, "INIT", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12), "IN_INVOICE", "RECV", 1L, "RECV-T-001", null, "");
        waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(60), "OUT", 1L, "OUT-T-001", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(11), "IN_ESTIMATE", "RECV", 2L, "RECV-T-002", null, "暂估");
        waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(40), "OUT", 2L, "OUT-T-002", null, "");
        waService.adjust(MAT_ID, WH_ID, new BigDecimal("50"), "INV", 1L, "INV-T-001", "");

        BigDecimal[] r7 = waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), "OUT", 3L, "OUT-T-003", null, "");
        assertEquals(0, r7[0].compareTo(new BigDecimal("11.18")), "出库单价应使用调差后的新单价");
        assertEquals(0, r7[1].compareTo(new BigDecimal("558.77")), "出库金额错误");

        InvBalance b7 = waService.currentBalance(MAT_ID, WH_ID);
        assertBalance(b7, 100, 1117.55, 11.18);
    }

    @Test
    @DisplayName("负库存控制：出库超量应抛异常（默认 FORBID）")
    void negativeBalance() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(10), BigDecimal.valueOf(5), "INIT", "INIT", 0L, "INIT", null, "");
        assertThrows(RuntimeException.class,
                () -> waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(20), "OUT", 1L, "OUT-X", null, ""),
                "出库超量应抛异常");
    }

    @Test
    @DisplayName("反审：把某笔流水反向冲销，库存恢复")
    void reverse() {
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(100), BigDecimal.valueOf(10), "INIT", "INIT", 0L, "INIT", null, "");
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(50), BigDecimal.valueOf(12), "IN_INVOICE", "RECV", 99L, "RECV-T-099", null, "");

        InvBalance before = waService.currentBalance(MAT_ID, WH_ID);
        assertEquals(0, before.getQty().compareTo(new BigDecimal("150")));

        waService.reverse(99L, "RECV", "测试反审");
        InvBalance after = waService.currentBalance(MAT_ID, WH_ID);
        assertEquals(0, after.getQty().compareTo(new BigDecimal("100")), "反审后应恢复到 100");
    }
}
