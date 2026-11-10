package com.group.scm.common;

import java.math.BigDecimal;

/**
 * 金额/数量的小数位校验:超出数据库能存的位数就明确拒绝,不让数据库静默四舍五入。
 *
 * 🔴 2026-10-18(计算审查 U27/U29/U37):以前只校验"大于 0",
 *    收款输入 0.001 → 接口回"成功、0.001",库里实际存成 0.00;
 *    期初数量 0.00001 → 库里数量 0、金额 1.00;借料归还 0.99995 → 数量记成 1(单据关闭)、成本只回 999.95。
 *    根子都是"先用高精度算、再被数据库按列精度截断",两边基数不一致。统一在入口拦住。
 * 金额列 DECIMAL(18,2) → 最多 2 位小数;数量列 DECIMAL(18,4) → 最多 4 位小数。
 */
public final class DecimalGuard {
    private DecimalGuard() {}

    public static void money(BigDecimal v, String label) {
        scale(v, 2, label);
    }

    public static void qty(BigDecimal v, String label) {
        scale(v, 4, label);
    }

    private static void scale(BigDecimal v, int max, String label) {
        if (v == null) return;
        if (v.stripTrailingZeros().scale() > max) {
            throw new BizException(4000, label + "最多保留 " + max + " 位小数（当前：" + v.toPlainString() + "）");
        }
    }
}
