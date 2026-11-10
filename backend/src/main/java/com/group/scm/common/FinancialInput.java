package com.group.scm.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 校验用于落库的分位金额，避免正数在 DECIMAL(18,2) 中静默变成零。 */
public final class FinancialInput {
    private FinancialInput() { }

    public static BigDecimal positiveMoney(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new BizException(label + "必须大于 0");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException(label + "最多保留两位小数，请按实际金额填写");
        }
    }

    public static void taxRate(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(new BigDecimal("100")) >= 0))
            throw new BizException("税率必须在 0（含）到 100（不含）之间");
    }
}
