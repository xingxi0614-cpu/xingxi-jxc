package com.group.scm.modules.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按公司核算的金额算法(纯函数,不碰数据库,单测见 CostMathTest)。
 *
 * <h3>出库金额口径(2026-10-15 定,与财务软件一致)</h3>
 * <pre>
 * 出库单价 = 当时公司结存单价(两位小数)
 * 出库金额 = 数量 × 单价,四舍五入到分 —— 零头留在结存里,下次重算单价时消化
 * 例外①:这一笔出完公司数量归零 → 金额 = 公司剩余金额(出光取余额,方案 A),不留尾巴
 * 例外②:数量 × 单价 超过公司剩余金额(便宜物料两位单价进位,几乎出光时)→ 只出剩余金额,绝不让结存变负
 * 两种例外的"金额 − 数量×单价"记为尾差 tail,流水上单独一列,方便财务解释为什么这一行不等。
 * </pre>
 * 9-28 ~ 本次之前用的是"按结存金额比例"出库,数量×单价≠金额,与财务软件不一致,已废止(仅按仓旧模式保留)。
 */
public final class CostMath {

    private CostMath() {}

    /** 一笔出库的成本:单价、金额、其中尾差(金额 − 数量×单价)。 */
    public record OutCost(BigDecimal price, BigDecimal amount, BigDecimal tail) {
        public boolean hasTail() { return tail.signum() != 0; }
    }

    public static BigDecimal price(BigDecimal amount, BigDecimal qty, int scale) {
        return qty.signum() > 0 ? amount.divide(qty, scale, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * @param poolQty   出库前公司结存数量
     * @param poolAmt   出库前公司结存金额
     * @param poolPrice 出库前公司结存单价(已是两位)
     * @param qty       本次出库数量(> 0)
     */
    public static OutCost outCost(BigDecimal poolQty, BigDecimal poolAmt, BigDecimal poolPrice, BigDecimal qty) {
        BigDecimal nominal = qty.multiply(poolPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal left = poolQty.subtract(qty);
        BigDecimal amt;
        if (left.signum() == 0) {
            amt = poolAmt.setScale(2, RoundingMode.HALF_UP);          // 出光取余额
        } else if (left.signum() > 0 && nominal.compareTo(poolAmt) > 0) {
            amt = poolAmt.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP); // 进位封顶
        } else {
            amt = nominal;                                             // 常规:数量 × 两位单价
        }
        return new OutCost(poolPrice, amt, amt.subtract(nominal));
    }

    /**
     * 按指定金额出库(采购退货按原入库价冲库存,2026-10-16 定,对齐主流财务软件 红字采购入库)。
     * <pre>
     * 正常:冲减金额 = 指定金额(原入库额),供应商冲多少库存就减多少,差额 0
     * 兜底①:这一笔退完公司数量归零 → 冲减金额 = 剩余金额(出光取余额,不留"无数量有金额")
     * 兜底②:指定金额 > 剩余金额(那批货早被高价领走)→ 最多冲到剩余金额,结存绝不为负
     * 差额 diff = 实际冲减 − 指定金额:正数 = 库存多减了(损失,进损益),负数 = 少减了(收益,冲减损益)
     * </pre>
     */
    public static OutCost outAtAmount(BigDecimal poolQty, BigDecimal poolAmt, BigDecimal qty, BigDecimal want) {
        want = want.setScale(2, RoundingMode.HALF_UP);
        BigDecimal left = poolQty.subtract(qty);
        BigDecimal avail = poolAmt.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amt = left.signum() == 0 ? avail : want.min(avail);
        // tail 这里表示"实际冲减 − 原入库额",调用方据此记损益
        return new OutCost(price(amt, qty, 2), amt, amt.subtract(want));
    }

    /** 一笔采购成本差额(发票 − 入库)的去向:进库存多少、进当期损益多少,两者之和恒等于总差额。 */
    public record DiffSplit(BigDecimal inStock, BigDecimal expense, BigDecimal inStockQty) {}

    /**
     * 采购成本差额拆分(2026-10-16 定 B 方案,对齐主流财务软件"红字冲暂估 + 蓝字按发票入库")。
     * <pre>
     * 在库数量 = min(开票时结存数量, 本次开票数量);结存 ≤ 0 视为 0
     * 进库存   = 总差额 × 在库数量 ÷ 本次开票数量,四舍五入到分
     * 进损益   = 总差额 − 进库存            ← 相减得出,两半精确相加等于总差额
     * 负差额超过结存金额 → 库存最多冲到 0,超出的部分并入损益(结存金额绝不为负)
     * </pre>
     * 为什么不像财务软件那样全额进结存:货大部分已领走时,财务软件 会把差额塞进剩下的几件(单价异常跳动),
     * 月末再做"异常结存调整"转成本;这里在开票时就把已领走那部分进损益,等于把月末调整提前做了。
     * 结存数量 ≥ 开票数量(常见情况)时两者完全一致:全额进库存。
     *
     * @param total    总差额(已到分)
     * @param stockQty 开票时结存数量(按公司核算 = 公司账;按仓核算 = 入库仓)
     * @param stockAmt 开票时结存金额
     * @param qty      本次开票数量(> 0)
     */
    public static DiffSplit splitDiff(BigDecimal total, BigDecimal stockQty, BigDecimal stockAmt, BigDecimal qty) {
        total = total.setScale(2, RoundingMode.HALF_UP);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        BigDecimal inQty = stockQty == null || stockQty.signum() <= 0 ? BigDecimal.ZERO : stockQty.min(qty);
        BigDecimal in = inQty.signum() == 0 ? zero : total.multiply(inQty).divide(qty, 2, RoundingMode.HALF_UP);
        BigDecimal amt = stockAmt == null ? BigDecimal.ZERO : stockAmt;
        if (amt.add(in).signum() < 0) in = amt.max(BigDecimal.ZERO).negate().setScale(2, RoundingMode.HALF_UP);
        return new DiffSplit(in, total.subtract(in), inQty);
    }

    /**
     * 把公司结存金额按各仓数量占比分摊成"仓库参考金额"。
     * <p>只给数量 > 0 的仓分摊;每仓四舍五入到分,正尾差补给数量最大的仓(并列取仓库 id 最小的)。
     * 负尾差按数量降序、仓库 id 升序扣减,每仓最多扣到 0,余下的继续扣下一仓。
     * 保留通常的分摊结果,同时避免低金额、多仓舍入时把最大仓扣成负数;合计始终等于公司金额。
     * scripts/cost-cutover.sql 使用相同顺序与封底算法。</p>
     *
     * @param total    公司结存金额
     * @param qtyByKey 仓库 → 数量(按仓库 id 升序)
     * @return 仓库 → 参考金额(数量 ≤ 0 的仓为 0)
     */
    public static Map<Long, BigDecimal> allocate(BigDecimal total, LinkedHashMap<Long, BigDecimal> qtyByKey) {
        total = total.setScale(2, RoundingMode.HALF_UP);
        if (total.signum() < 0) throw new IllegalArgumentException("公司结存金额不能为负");
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal base = BigDecimal.ZERO;
        for (var e : qtyByKey.entrySet()) {
            out.put(e.getKey(), BigDecimal.ZERO.setScale(2));
            if (e.getValue().signum() > 0) {
                base = base.add(e.getValue());
            }
        }
        if (base.signum() == 0) return out;   // 没有正数量:金额应为 0,由调用方校验
        var priority = qtyByKey.keySet().stream().filter(key -> qtyByKey.get(key).signum() > 0)
                .sorted((left, right) -> {
                    int byQty = qtyByKey.get(right).compareTo(qtyByKey.get(left));
                    return byQty != 0 ? byQty : Long.compare(left, right);
                }).toList();
        BigDecimal sum = BigDecimal.ZERO;
        for (var e : qtyByKey.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            BigDecimal a = total.multiply(e.getValue()).divide(base, 2, RoundingMode.HALF_UP);
            out.put(e.getKey(), a);
            sum = sum.add(a);
        }
        BigDecimal delta = total.subtract(sum);
        if (delta.signum() >= 0) {
            Long largest = priority.get(0);
            out.put(largest, out.get(largest).add(delta));
        } else {
            BigDecimal remaining = delta.negate();
            for (Long key : priority) {
                BigDecimal deduction = out.get(key).min(remaining);
                out.put(key, out.get(key).subtract(deduction));
                remaining = remaining.subtract(deduction);
                if (remaining.signum() == 0) break;
            }
        }
        return out;
    }
}
