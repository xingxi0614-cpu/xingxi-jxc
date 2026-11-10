package com.group.scm.modules.inventory;

import com.group.scm.modules.inventory.service.CostMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 按公司核算的金额算法(2026-10-15 定:出库=数量×两位单价,出光取余额;仓库参考额按数量分摊)。 */
@DisplayName("按公司核算金额算法")
class CostMathTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("常规出库:金额 = 数量 × 两位单价,零头留在结存")
    void normalOut() {
        // 公司 3 个 共 10.00 元 → 单价 3.33;出 1 个 = 3.33,留 6.67
        var oc = CostMath.outCost(d("3"), d("10.00"), d("3.33"), d("1"));
        assertEquals(0, oc.amount().compareTo(d("3.33")));
        assertEquals(0, oc.tail().signum());
    }

    @Test
    @DisplayName("出光那一笔取余额,尾差单独给出")
    void clearOut() {
        // 真实场景:PVC 套筒 15000 个 4455.45 元,两位单价 0.30,一次出光 → 金额 4455.45,尾差 −44.55
        var oc = CostMath.outCost(d("15000"), d("4455.45"), d("0.30"), d("15000"));
        assertEquals(0, oc.amount().compareTo(d("4455.45")));
        assertEquals(0, oc.tail().compareTo(d("-44.55")));
        assertEquals(0, oc.price().compareTo(d("0.30")));
    }

    @Test
    @DisplayName("便宜物料几乎出光:进位后超过剩余金额 → 封顶,结存不变负")
    void capOut() {
        // 剩 4455.45 元 15000 个,出 14999 个 × 0.30 = 4499.70 > 4455.45 → 只出 4455.45
        var oc = CostMath.outCost(d("15000"), d("4455.45"), d("0.30"), d("14999"));
        assertEquals(0, oc.amount().compareTo(d("4455.45")));
        assertTrue(oc.hasTail());
    }

    @Test
    @DisplayName("仓库参考额:按数量占比分摊,合计正好等于公司金额,零头给数量最大的仓")
    void allocate() {
        var q = new LinkedHashMap<Long, BigDecimal>();
        q.put(1L, d("1")); q.put(2L, d("1")); q.put(3L, d("1")); q.put(4L, d("0"));
        Map<Long, BigDecimal> a = CostMath.allocate(d("10.00"), q);
        BigDecimal sum = a.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(d("10.00")));
        assertEquals(0, a.get(1L).compareTo(d("3.34")));  // 并列最大取第一个,吃 0.01 零头
        assertEquals(0, a.get(2L).compareTo(d("3.33")));
        assertEquals(0, a.get(4L).signum());
    }

    @Test
    @DisplayName("仓库参考额:真实数量下合计不差一分")
    void allocateRealistic() {
        var q = new LinkedHashMap<Long, BigDecimal>();
        q.put(10L, d("48")); q.put(11L, d("210")); q.put(12L, d("24")); q.put(13L, d("48"));
        Map<Long, BigDecimal> a = CostMath.allocate(d("33050.45"), q);
        BigDecimal sum = a.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(d("33050.45")));
    }

    @Test
    @DisplayName("没有正数量的仓:全部为 0")
    void allocateEmpty() {
        var q = new LinkedHashMap<Long, BigDecimal>();
        q.put(1L, d("0"));
        assertEquals(0, CostMath.allocate(d("0"), q).get(1L).signum());
    }

    @Test
    @DisplayName("低价四仓:负尾差逐仓扣到零,不产生负参考金额")
    void allocateTinyAmountAcrossFourWarehouses() {
        var q = new LinkedHashMap<Long, BigDecimal>();
        for (long id = 1; id <= 4; id++) q.put(id, BigDecimal.ONE);
        var a = CostMath.allocate(d("0.02"), q);
        assertEquals(Map.of(1L, d("0.00"), 2L, d("0.00"), 3L, d("0.01"), 4L, d("0.01")), a);
        assertEquals(d("0.02"), a.values().stream().reduce(d("0.00"), BigDecimal::add));
    }

    @Test
    @DisplayName("正常负尾差仍由最大仓承担,相同数量按仓id确定顺序")
    void allocateKeepsNormalRoundingAndStableTieOrder() {
        var q = new LinkedHashMap<Long, BigDecimal>();
        q.put(2L, d("3")); q.put(1L, d("3"));
        var a = CostMath.allocate(d("30.01"), q);
        assertEquals(d("15.00"), a.get(1L));
        assertEquals(d("15.01"), a.get(2L));
    }

    @Test
    @DisplayName("低价多仓与不同数量:每仓非负、零数量分零、合计精确守恒")
    void allocateConservesCentsAcrossRoundingBoundaries() {
        for (int warehouses = 1; warehouses <= 12; warehouses++) {
            var q = new LinkedHashMap<Long, BigDecimal>();
            for (long id = warehouses; id >= 1; id--) q.put(id, d(id % 3 == 0 ? "0.25" : "1"));
            q.put(100L, BigDecimal.ZERO);
            for (int cents = 0; cents <= 100; cents++) {
                BigDecimal total = BigDecimal.valueOf(cents, 2);
                var a = CostMath.allocate(total, q);
                assertTrue(a.values().stream().allMatch(value -> value.signum() >= 0));
                assertEquals(total, a.values().stream().reduce(d("0.00"), BigDecimal::add));
                assertEquals(d("0.00"), a.get(100L));
            }
        }
    }

    /* ===== 2026-10-16 B 方案:采购成本差额拆分(方案文档例 1~4) ===== */

    private static void split(String total, String stockQty, String stockAmt, String qty, String in, String exp) {
        var s = CostMath.splitDiff(d(total), d(stockQty), d(stockAmt), d(qty));
        assertEquals(0, s.inStock().compareTo(d(in)), "进库存");
        assertEquals(0, s.expense().compareTo(d(exp)), "进损益");
        assertEquals(0, s.inStock().add(s.expense()).compareTo(d(total)), "两部分之和 = 总差额");
    }

    @Test
    @DisplayName("例1 常见:结存 300 ≥ 开票 100 → 差额 50 全部进库存")
    void splitAllInStock() { split("50", "300", "3000", "100", "50", "0"); }

    @Test
    @DisplayName("例2 大部分已领:结存 20、开票 100 → 进库存 10,进损益 40")
    void splitPartly() { split("50", "20", "200", "100", "10", "40"); }

    @Test
    @DisplayName("例3 全部领完:结存 0 → 全部进损益")
    void splitAllIssued() { split("50", "0", "0", "100", "0", "50"); }

    @Test
    @DisplayName("例4 负差额超过结存金额:库存最多冲到 0,其余进损益")
    void splitNegativeCapped() { split("-80", "75", "30", "100", "-30", "-50"); }

    @Test
    @DisplayName("拆分四舍五入:进损益用相减得出,不冒零头")
    void splitRounding() { split("0.10", "1", "5", "3", "0.03", "0.07"); }

    /* ===== 采购退货按原入库额冲库存 ===== */

    @Test
    @DisplayName("退货正常:按原入库额冲,差额 0(方案例:20 个 300 元,退 5 个原价 50 → 剩 250)")
    void returnAtOriginal() {
        var oc = CostMath.outAtAmount(d("20"), d("300"), d("5"), d("50"));
        assertEquals(0, oc.amount().compareTo(d("50")));
        assertEquals(0, oc.tail().signum());
    }

    @Test
    @DisplayName("退货兜底:结存金额不够冲原价 → 冲到剩余金额,差额 = 实际 − 原价(负数 = 收益)")
    void returnCapped() {
        var oc = CostMath.outAtAmount(d("8"), d("40"), d("5"), d("50"));
        assertEquals(0, oc.amount().compareTo(d("40")));
        assertEquals(0, oc.tail().compareTo(d("-10")));
    }

    @Test
    @DisplayName("退货退光:冲光剩余金额(多于原价时差额为正 = 损失)")
    void returnClearOut() {
        var oc = CostMath.outAtAmount(d("5"), d("60"), d("5"), d("50"));
        assertEquals(0, oc.amount().compareTo(d("60")));
        assertEquals(0, oc.tail().compareTo(d("10")));
    }
}
