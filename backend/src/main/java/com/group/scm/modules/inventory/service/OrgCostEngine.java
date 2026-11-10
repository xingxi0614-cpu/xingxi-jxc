package com.group.scm.modules.inventory.service;

import com.group.scm.common.BizException;
import com.group.scm.common.MaterialLabel;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.entity.InvCostPool;
import com.group.scm.modules.inventory.entity.InvTransaction;
import com.group.scm.modules.inventory.mapper.InvBalanceMapper;
import com.group.scm.modules.inventory.mapper.InvCostPoolMapper;
import com.group.scm.modules.inventory.mapper.InvTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>★ 按公司核算引擎(2026-10-15 定,对齐主流财务软件 成本域)★</h2>
 *
 * <pre>
 * 账怎么记:
 *   公司成本池 inv_cost_pool(公司 × 物料):数量、金额、单价 —— 金额的唯一权威,移动加权在这里算。
 *   仓库余额 inv_balance:数量是真的(管"这个仓够不够出");金额/单价 = 公司账按数量占比分摊的【参考值】,
 *     每次过账后重算同一物料的各仓(尾差按 CostMath.allocate 非负分摊,各仓合计 = 公司金额,分毫不差)。
 *     这样现有 20 多处读 inv_balance.amount/avg_price 的报表、盘点、结账检查不用改,口径自动变成公司口径。
 *
 * 各动作:
 *   入库   :仓库 +数量;公司 +数量 +金额,重算单价
 *   出库   :仓库 −数量(按仓校验够不够);公司 −数量 −金额;金额 = 数量 × 公司两位单价,出光取余额(CostMath)
 *   转库   :只挪数量,公司账不动;流水转出/转入各记 数量×公司单价,金额相等相反(财务软件 同款,公司收发存两边都计)
 *   调整   :只动公司金额
 *   反审   :按原流水的数量、金额精确冲回;整张单冲完再统一校验(转库冲回途中会出现暂时不平的中间态)
 * </pre>
 *
 * 加锁顺序见 InvLedgerSupport#lockCompanyCost:调用方(WaInventoryService)已先拿公司锁与仓库锁,
 * 这里再锁 成本池 → 余额行(仓库 id 升序)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgCostEngine {

    private final InvLedgerSupport ledger;
    private final InvBalanceMapper balanceMapper;
    private final InvCostPoolMapper poolMapper;
    private final InvTransactionMapper transMapper;
    private final JdbcTemplate jdbc;

    @Value("${scm.inv.avg-scale:2}")
    private int avgScale;

    @Value("${scm.inv.negative-policy:FORBID}")
    private String negativePolicy;

    /* ===================== 各动作 ===================== */

    /** 调用方已按顺序锁公司、仓库;FOR UPDATE 当前读不受审批前普通查询建立的 RR 快照影响。 */
    public BigDecimal companyPriceForUpdate(Long matId, Long whId) {
        return lockPool(ledger.orgOf(whId), matId).getAvgPrice();
    }

    public InvBalance inbound(Long matId, Long whId, BigDecimal qty, BigDecimal price, BigDecimal inAmt,
                              String transType, String bizType, Long bizId, String bizNo,
                              Long bizLineId, String remark) {
        Book bk = open(ledger.orgOf(whId), matId, whId);
        InvBalance b = bk.row(whId);
        b.setQty(b.getQty().add(qty));
        bk.pool.setQty(bk.pool.getQty().add(qty));
        bk.pool.setAmount(bk.pool.getAmount().add(inAmt));
        validate(bk, matId);
        close(bk);
        ledger.write(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty, price, inAmt, b, bk.pool, null, remark);
        return b;
    }

    public BigDecimal[] outbound(Long matId, Long whId, BigDecimal qty, String transType,
                                 String bizType, Long bizId, String bizNo, Long bizLineId, String remark) {
        Book bk = open(ledger.orgOf(whId), matId, whId);
        InvBalance b = bk.row(whId);
        if (b.getQty().compareTo(qty) < 0) {
            if ("FORBID".equalsIgnoreCase(negativePolicy)) {
                throw new BizException("库存不足：" + MaterialLabel.of(jdbc, matId)
                        + "，当前库存 " + MaterialLabel.qty(b.getQty()) + "，本次需要 " + MaterialLabel.qty(qty)
                        + "。请先办理入库，或减少出库数量");
            }
            log.warn("库存即将变负：matId={}, whId={}, cur={}, need={}", matId, whId, b.getQty(), qty);
        }
        InvCostPool pool = bk.pool;
        CostMath.OutCost oc = CostMath.outCost(pool.getQty(), pool.getAmount(), pool.getAvgPrice(), qty);
        b.setQty(b.getQty().subtract(qty));
        pool.setQty(pool.getQty().subtract(qty));
        pool.setAmount(pool.getAmount().subtract(oc.amount()));
        validate(bk, matId);
        close(bk);
        ledger.write(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty.negate(), oc.price(), oc.amount().negate(), b, pool, oc.tail().negate(),
                withTailNote(remark, oc, pool.getQty()));
        return new BigDecimal[]{oc.price(), oc.amount()};
    }

    /**
     * 按指定金额出库(采购退货按原入库额冲公司账,规则见 CostMath#outAtAmount)。
     * 返回 [流水单价, 实际冲减金额, 差额(实际 − 指定)];差额由调用方记损益。
     */
    public BigDecimal[] outboundAtAmount(Long matId, Long whId, BigDecimal qty, BigDecimal want, String transType,
                                         String bizType, Long bizId, String bizNo, Long bizLineId, String remark) {
        Book bk = open(ledger.orgOf(whId), matId, whId);
        InvBalance b = bk.row(whId);
        if (b.getQty().compareTo(qty) < 0) {
            throw new BizException("库存不足：" + MaterialLabel.of(jdbc, matId)
                    + "，当前库存 " + MaterialLabel.qty(b.getQty()) + "，本次退货 " + MaterialLabel.qty(qty));
        }
        InvCostPool pool = bk.pool;
        CostMath.OutCost oc = CostMath.outAtAmount(pool.getQty(), pool.getAmount(), qty, want);
        b.setQty(b.getQty().subtract(qty));
        pool.setQty(pool.getQty().subtract(qty));
        pool.setAmount(pool.getAmount().subtract(oc.amount()));
        validate(bk, matId);
        close(bk);
        ledger.write(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty.negate(), oc.price(), oc.amount().negate(), b, pool, null, remark);
        return new BigDecimal[]{oc.price(), oc.amount(), oc.tail()};
    }

    /**
     * 采购成本差额进库存的那一半(B 方案,规则见 CostMath#splitDiff):按【公司结存】拆分,
     * 进库存部分只动公司金额、重算单价,写一笔 ADJUST(数量 0);进损益部分由调用方写 ADJUST_EXPENSE。
     */
    /** @param usedStockQty 同一张单据前面几行已认领的公司池在库数量(计算审查 U02,见 WaInventoryService) */
    public CostMath.DiffSplit invoiceDiff(Long matId, Long whId, BigDecimal total, BigDecimal qty, BigDecimal usedStockQty,
                                          String bizType, Long bizId, String bizNo, String remark) {
        Book bk = open(ledger.orgOf(whId), matId, whId);
        BigDecimal stock = bk.pool.getQty().subtract(usedStockQty == null ? BigDecimal.ZERO : usedStockQty);
        CostMath.DiffSplit s = CostMath.splitDiff(total, stock, bk.pool.getAmount(), qty);
        if (s.inStock().signum() != 0) {
            bk.pool.setAmount(bk.pool.getAmount().add(s.inStock()));
            validate(bk, matId);
            close(bk);
            ledger.write(matId, whId, "ADJUST", bizType, bizId, bizNo, null,
                    BigDecimal.ZERO, bk.pool.getAvgPrice(), s.inStock(), bk.row(whId), bk.pool, null,
                    (remark == null ? "" : remark) + "(在库 " + s.inStockQty().stripTrailingZeros().toPlainString()
                            + " 进库存单价,差额 " + s.inStock() + ")");
        }
        return s;
    }

    public BigDecimal[] transfer(Long matId, Long fromWhId, Long toWhId, BigDecimal qty,
                                 String bizType, Long bizId, String bizNo, Long bizLineId, String remark) {
        Book bk = open(ledger.orgOf(fromWhId), matId, Math.min(fromWhId, toWhId), Math.max(fromWhId, toWhId));
        InvBalance from = bk.row(fromWhId);
        InvBalance to = bk.row(toWhId);
        if (from.getQty().compareTo(qty) < 0) {
            throw new BizException("转出仓库库存不足：" + MaterialLabel.of(jdbc, matId)
                    + "，当前库存 " + MaterialLabel.qty(from.getQty()) + "，本次转库 " + MaterialLabel.qty(qty));
        }
        // 公司账不动;流水金额按公司单价,极端情况(便宜物料进位)不超过公司金额
        BigDecimal price = bk.pool.getAvgPrice();
        BigDecimal amt = qty.multiply(price).setScale(2, RoundingMode.HALF_UP)
                .min(bk.pool.getAmount().max(BigDecimal.ZERO));
        from.setQty(from.getQty().subtract(qty));
        to.setQty(to.getQty().add(qty));
        validate(bk, matId);
        close(bk);
        String r = remark == null ? "" : remark;
        ledger.write(matId, fromWhId, "TRANSFER_OUT", bizType, bizId, bizNo, bizLineId,
                qty.negate(), price, amt.negate(), from, bk.pool, null, "转库出库：" + r);
        ledger.write(matId, toWhId, "TRANSFER_IN", bizType, bizId, bizNo, bizLineId,
                qty, price, amt, to, bk.pool, null, "转库入库：" + r);
        return new BigDecimal[]{price, amt};
    }

    public InvBalance adjust(Long matId, Long whId, BigDecimal deltaAmt,
                             String bizType, Long bizId, String bizNo, String remark) {
        Book bk = open(ledger.orgOf(whId), matId, whId);
        BigDecimal newAmt = bk.pool.getAmount().add(deltaAmt).setScale(2, RoundingMode.HALF_UP);
        if (newAmt.signum() < 0 || (bk.pool.getQty().signum() == 0 && newAmt.signum() != 0))
            throw new BizException("库存金额调整后不合法，请核对数量与金额");
        bk.pool.setAmount(newAmt);
        close(bk);
        InvBalance b = bk.row(whId);
        ledger.write(matId, whId, "ADJUST", bizType, bizId, bizNo, null,
                BigDecimal.ZERO, bk.pool.getAvgPrice(), deltaAmt, b, bk.pool, null, remark);
        return b;
    }

    /**
     * 反审:把一张单的流水按原数量、原金额精确冲回(含切换前按仓记的旧流水,公司账 = 各仓之和所以同样成立)。
     * 先把整张单的增减都记上,最后统一校验 —— 转库单冲回时,单看"转入"那一行会出现暂时不平的中间态。
     */
    public void reverse(List<InvTransaction> flows, String bizDocType, Long bizDocId, String reason) {
        Map<String, Book> books = new LinkedHashMap<>();
        // 只调金额的流水(发票差额进库存的 ADJUST)实际能冲回多少:货可能已经领光,库存吃不下的部分转损益
        Map<Long, BigDecimal> absorbed = new HashMap<>();
        for (InvTransaction t : flows) {
            if ("ADJUST_EXPENSE".equals(t.getTransType())) continue;
            Long orgId = ledger.orgOf(t.getWarehouseId());
            Book bk = books.computeIfAbsent(orgId + ":" + t.getMaterialId(), k -> open(orgId, t.getMaterialId()));
            InvBalance b = bk.ensureRow(t.getWarehouseId());
            if (amountOnly(t)) {
                BigDecimal take = absorbable(bk.pool.getQty(), bk.pool.getAmount(), t.getAmount());
                absorbed.put(t.getId(), take);
                bk.pool.setAmount(bk.pool.getAmount().subtract(take));
                continue;
            }
            b.setQty(b.getQty().subtract(t.getQty()));
            bk.pool.setQty(bk.pool.getQty().subtract(t.getQty()));
            bk.pool.setAmount(bk.pool.getAmount().subtract(t.getAmount()));
        }
        for (Book bk : books.values()) {
            for (InvBalance b : bk.rows.values()) {
                if (b.getQty().signum() < 0) throw new BizException("反审会导致负库存：" + MaterialLabel.of(jdbc, bk.pool.getMaterialId())
                        + "（这批货入库后已被领用或转出，需先处理后续单据）");
            }
            if (bk.pool.getQty().signum() < 0) throw new BizException("反审会导致负库存：" + MaterialLabel.of(jdbc, bk.pool.getMaterialId())
                    + "（这批货入库后已被领用或转出，需先处理后续单据）");
            if (bk.pool.getAmount().signum() < 0 || (bk.pool.getQty().signum() == 0 && bk.pool.getAmount().signum() != 0))
                throw new BizException("反审会导致结存金额异常，请先处理后续成本变动：" + MaterialLabel.of(jdbc, bk.pool.getMaterialId()));
            close(bk);
        }
        for (InvTransaction t : flows) {
            boolean expense = "ADJUST_EXPENSE".equals(t.getTransType());
            Book bk = expense ? null : books.get(ledger.orgOf(t.getWarehouseId()) + ":" + t.getMaterialId());
            InvBalance after = bk != null ? bk.row(t.getWarehouseId()) : ledger.lockOrCreate(t.getMaterialId(), t.getWarehouseId());
            InvCostPool pool = bk != null ? bk.pool : peekPool(ledger.orgOf(t.getWarehouseId()), t.getMaterialId());
            BigDecimal back = absorbed.getOrDefault(t.getId(), t.getAmount());
            InvTransaction reversal = ledger.write(t.getMaterialId(), t.getWarehouseId(), expense ? "ADJUST_EXPENSE" : "REVERSE",
                    bizDocType, bizDocId, t.getBizDocNo(), t.getBizLineId(),
                    t.getQty().negate(), t.getUnitPrice(), back.negate(), after, pool,
                    t.getTailAmt() == null ? null : t.getTailAmt().negate(), "反审：" + reason);
            reversal.setReversalOfId(t.getId());
            transMapper.updateById(reversal);
            writeReverseOverflow(t, reversal, back, after, pool, bizDocType, bizDocId, reason);
        }
    }

    /** 只动金额、不动数量的流水(ADJUST)。 */
    static boolean amountOnly(InvTransaction t) {
        return "ADJUST".equals(t.getTransType()) && t.getQty() != null && t.getQty().signum() == 0;
    }

    /**
     * 冲回一笔只调金额的流水时,库存最多能吃回多少:结存为 0 → 一分也吃不回;
     * 冲回后金额会变负 → 只冲到 0。其余部分由 {@link #writeReverseOverflow} 转损益,取消审批不再被拦。
     */
    static BigDecimal absorbable(BigDecimal stockQty, BigDecimal stockAmt, BigDecimal original) {
        if (stockQty.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        if (stockAmt.subtract(original).signum() < 0) return stockAmt.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return original;
    }

    /**
     * 库存吃不回的部分记一笔 ADJUST_EXPENSE(金额 = −冲不回的部分)。
     * reversal_of_id 指向刚写的冲回流水(唯一索引:一笔原流水只能被冲一次),
     * 这样它既不会被下次反审再次捡起,也能顺着链查到它冲的是哪一笔。
     */
    void writeReverseOverflow(InvTransaction t, InvTransaction reversal, BigDecimal back, InvBalance after,
                              InvCostPool pool, String bizDocType, Long bizDocId, String reason) {
        BigDecimal overflow = t.getAmount().subtract(back);
        if (!amountOnly(t) || overflow.signum() == 0) return;
        InvTransaction x = ledger.write(t.getMaterialId(), t.getWarehouseId(), "ADJUST_EXPENSE",
                bizDocType, bizDocId, t.getBizDocNo(), t.getBizLineId(),
                BigDecimal.ZERO, t.getUnitPrice(), overflow.negate(), after, pool, null,
                "反审：" + reason + "(该物料已领用,库存冲不回的 " + overflow + " 转当期损益)");
        x.setReversalOfId(reversal.getId());
        transMapper.updateById(x);
    }

    /** 不加锁读取公司账(用于只记损益、不动库存的流水留痕)。 */
    public InvCostPool peekPool(Long orgId, Long matId) {
        return poolMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InvCostPool>()
                .eq("org_id", orgId).eq("material_id", matId));
    }

    /* ===================== 账本:成本池 + 各仓余额 ===================== */

    /** 一次过账里同一(公司, 物料)的账:成本池 + 该公司该物料的全部仓库余额行(记下原值,只回写有变化的行)。 */
    private final class Book {
        final InvCostPool pool;
        final Long orgId;
        final Map<Long, InvBalance> rows = new LinkedHashMap<>();
        final Map<Long, BigDecimal[]> orig = new HashMap<>();

        Book(InvCostPool pool, Long orgId) {
            this.pool = pool;
            this.orgId = orgId;
        }

        InvBalance row(Long whId) {
            InvBalance b = rows.get(whId);
            if (b == null) throw new IllegalStateException("余额行未加锁 wh=" + whId);
            return b;
        }

        /** 反审时遇到账本里没有的仓库行(极少:切换前删过余额行),补建并纳入账本。 */
        InvBalance ensureRow(Long whId) {
            if (!rows.containsKey(whId)) {
                InvBalance b = ledger.lockOrCreate(pool.getMaterialId(), whId);
                rows.put(whId, b);
                orig.put(whId, new BigDecimal[]{b.getQty(), b.getAmount(), b.getAvgPrice()});
            }
            return rows.get(whId);
        }
    }

    /** 锁 成本池 → 确保涉及的仓库行存在 → 按仓库 id 升序锁住该公司该物料全部余额行。 */
    private Book open(Long orgId, Long matId, Long... whIds) {
        InvCostPool pool = lockPool(orgId, matId);
        for (Long whId : whIds) ledger.lockOrCreate(matId, whId);
        Book bk = new Book(pool, orgId);
        BigDecimal sum = BigDecimal.ZERO;
        for (InvBalance b : balanceMapper.findOrgMaterialForUpdate(orgId, matId)) {
            bk.rows.put(b.getWarehouseId(), b);
            bk.orig.put(b.getWarehouseId(), new BigDecimal[]{b.getQty(), b.getAmount(), b.getAvgPrice()});
            sum = sum.add(b.getQty());
        }
        // 不变式:公司账数量 = 各仓数量之和。对不上说明账被绕过引擎改过,宁可停下也不在错账上继续记
        if (sum.compareTo(pool.getQty()) != 0) {
            log.error("公司成本账数量与各仓合计不一致 org={} mat={} pool={} sumWh={}", orgId, matId, pool.getQty(), sum);
            throw new BizException("公司成本账与各仓库存数量不一致：" + MaterialLabel.of(jdbc, matId) + "，请联系管理员核对后再办理");
        }
        return bk;
    }

    private InvCostPool lockPool(Long orgId, Long matId) {
        InvCostPool p = poolMapper.findForUpdate(orgId, matId);
        if (p != null) return p;
        p = new InvCostPool();
        p.setOrgId(orgId);
        p.setMaterialId(matId);
        p.setQty(BigDecimal.ZERO);
        p.setAmount(BigDecimal.ZERO);
        p.setAvgPrice(BigDecimal.ZERO);
        p.setUpdatedAt(LocalDateTime.now());
        try {
            poolMapper.insert(p);
        } catch (DuplicateKeyException e) {
            log.debug("并发首次建公司成本账，重试锁定 org={} mat={}", orgId, matId);
        }
        p = poolMapper.findForUpdate(orgId, matId);
        if (p == null) throw new BizException("公司成本账创建冲突，请重试");
        return p;
    }

    /** 过账后校验:数量不为负(FORBID 时)、金额不为负、数量为 0 时金额必须为 0。 */
    private void validate(Book bk, Long matId) {
        InvCostPool p = bk.pool;
        if (p.getAmount().signum() < 0 || (p.getQty().signum() == 0 && p.getAmount().signum() != 0)) {
            log.error("公司成本账金额异常 org={} mat={} qty={} amt={}", bk.orgId, matId, p.getQty(), p.getAmount());
            throw new BizException("本次过账会导致公司结存金额异常：" + MaterialLabel.of(jdbc, matId) + "，请联系管理员");
        }
    }

    /** 重算公司单价 → 按数量占比把公司金额分摊到各仓(参考值)→ 只回写有变化的行 → 写回公司账。 */
    private void close(Book bk) {
        InvCostPool p = bk.pool;
        p.setAmount(p.getAmount().setScale(2, RoundingMode.HALF_UP));
        p.setAvgPrice(CostMath.price(p.getAmount(), p.getQty(), avgScale));
        LinkedHashMap<Long, BigDecimal> qtyByWh = new LinkedHashMap<>();
        bk.rows.keySet().stream().sorted().forEach(wh -> qtyByWh.put(wh, bk.rows.get(wh).getQty()));
        Map<Long, BigDecimal> alloc = CostMath.allocate(p.getAmount(), qtyByWh);
        LocalDateTime now = LocalDateTime.now();
        for (var e : bk.rows.entrySet()) {
            InvBalance b = e.getValue();
            b.setAmount(alloc.get(e.getKey()));
            b.setAvgPrice(b.getQty().signum() > 0 ? p.getAvgPrice() : BigDecimal.ZERO);
            BigDecimal[] o = bk.orig.get(e.getKey());
            if (b.getQty().compareTo(o[0]) != 0 || b.getAmount().compareTo(o[1]) != 0 || b.getAvgPrice().compareTo(o[2]) != 0) {
                b.setUpdatedAt(now);
                if (balanceMapper.updateById(b) != 1) throw new BizException("库存余额已被并发修改，请重试");
                bk.orig.put(e.getKey(), new BigDecimal[]{b.getQty(), b.getAmount(), b.getAvgPrice()});
            }
        }
        p.setUpdatedAt(now);
        if (poolMapper.updateById(p) != 1) throw new BizException("公司成本账已被并发修改，请重试");
    }

    private static String withTailNote(String remark, CostMath.OutCost oc, BigDecimal leftQty) {
        String r = remark == null ? "" : remark;
        if (!oc.hasTail()) return r;
        return r + (leftQty.signum() == 0 ? "（出光取余额，尾差 " : "（单价进位封顶，尾差 ") + oc.tail() + "）";
    }
}
