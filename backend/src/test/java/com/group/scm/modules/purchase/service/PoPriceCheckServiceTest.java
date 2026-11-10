package com.group.scm.modules.purchase.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 采购单价防错：参考价算法与偏离判断（纯计算，不连库）。 */
class PoPriceCheckServiceTest {

    private static List<BigDecimal> p(String... v) { return java.util.Arrays.stream(v).map(BigDecimal::new).toList(); }

    @Test
    void medianOfRecentFive() {
        var ref = PoPriceCheckService.reference(p("10", "12", "11", "9", "30", "1000"));
        // 最高 1000 → 剔除 <200 的行，只剩 1000 → 中位数 1000（高价作参考，最坏只是多问一句）
        assertEquals(0, ref.refPrice().compareTo(new BigDecimal("1000")));
        var normal = PoPriceCheckService.reference(p("10", "12", "11", "9", "30", "8"));
        assertEquals(0, normal.refPrice().compareTo(new BigDecimal("11")));   // 最近5行 10,12,11,9,30 → 11
        assertEquals(5, normal.samples());
    }

    @Test
    void dirtyMajorityIsFilteredOut() {
        // 真实形态：某物料 7 行里 5 行把单价录成金额(0.02)，正常价 17~18.8
        var ref = PoPriceCheckService.reference(p("0.0376", "0.0226", "0.0513", "0.0226", "0.0256", "0.0256", "18.80", "19.25"));
        assertNotNull(ref);
        assertTrue(ref.refPrice().compareTo(new BigDecimal("18")) > 0, "错价不能被当成参考价: " + ref.refPrice());
    }

    @Test
    void evenCountAveragesMiddle() {
        var ref = PoPriceCheckService.reference(p("10", "20"));
        assertEquals(0, ref.refPrice().compareTo(new BigDecimal("15")));
    }

    @Test
    void deviationThresholds() {
        BigDecimal ref = new BigDecimal("220");
        assertTrue(PoPriceCheckService.deviates(new BigDecimal("1.10"), ref));     // 单价录成金额
        assertTrue(PoPriceCheckService.deviates(new BigDecimal("43.99"), ref));    // 低于 20%
        assertFalse(PoPriceCheckService.deviates(new BigDecimal("44"), ref));      // 恰好 20% 不提醒
        assertFalse(PoPriceCheckService.deviates(new BigDecimal("1100"), ref));    // 恰好 5 倍不提醒
        assertTrue(PoPriceCheckService.deviates(new BigDecimal("1100.01"), ref));
        assertFalse(PoPriceCheckService.deviates(new BigDecimal("1"), null));      // 无历史价不提醒
    }

    @Test
    void emptyHistoryHasNoReference() {
        assertNull(PoPriceCheckService.reference(List.of()));
        assertNull(PoPriceCheckService.reference(p("0")));
    }
}
