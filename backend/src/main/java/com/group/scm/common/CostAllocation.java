package com.group.scm.common;
import java.math.BigDecimal;
import java.math.RoundingMode;
/** 按剩余数量/金额分摊，最后一笔完整接回尾差。单价仅展示。 */
public final class CostAllocation {
    private CostAllocation() { }
    public static BigDecimal remaining(BigDecimal totalQty, BigDecimal totalAmount,
                                       BigDecimal usedQty, BigDecimal usedAmount, BigDecimal qty) {
        BigDecimal leftQty = totalQty.subtract(usedQty);
        BigDecimal leftAmount = totalAmount.subtract(usedAmount);
        if (qty.signum() <= 0 || leftQty.compareTo(qty) < 0 || leftAmount.signum() < 0)
            throw new BizException("归还数量或已分摊成本异常，请核对原出库及归还记录");
        return qty.compareTo(leftQty) == 0 ? leftAmount.setScale(2, RoundingMode.HALF_UP)
                : leftAmount.multiply(qty).divide(leftQty, 2, RoundingMode.HALF_UP);
    }
}
