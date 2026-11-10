package com.group.scm.modules.inventory.service;

import com.group.scm.common.BizException;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.entity.InvTransaction;
import com.group.scm.modules.inventory.mapper.InvBalanceMapper;
import com.group.scm.modules.inventory.mapper.InvTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;


/**
 * <h2>★ 移动加权平均库存核算服务 ★</h2>
 *
 * <p>这是整个系统最核心的服务。所有库存变动必须通过此处。</p>
 *
 * <h3>算法</h3>
 * <pre>
 * 入库（包括期初）：
 *   新结存数量 = 原结存数量 + 入库数量
 *   新结存金额 = 原结存金额 + 入库数量 × 入库单价
 *   新结存单价 = 新结存金额 / 新结存数量
 *
 * 出库：
 *   出库单价 = 当前结存单价（出库不改变结存单价）
 *   新结存数量 = 原结存数量 - 出库数量
 *   新结存金额 = 原结存金额 - 原结存金额 × 出库数量 / 原结存数量（金额到分，最后一笔取余额）
 *
 * 采购成本差异(发票 − 入库,暂估开票与货票同到共用):2026-10-16 起按 B 方案拆分 ——
 *   在库部分调库存金额并重算单价(ADJUST),已领用部分计入当期损益(ADJUST_EXPENSE),规则见 CostMath#splitDiff。
 *   反审时库存吃不回的部分转损益,不拦取消。
 * </pre>
 *
 * <h3>🔴 2026-10-15 起:按公司核算(切换后)</h3>
 * <p>上面是"按仓库"核算的旧算法,只在切换前/回滚后生效。切换后(inv_cost_cutover 最新行 = ORG)
 * 入库/出库/转库/调整/反审全部转给 {@link OrgCostEngine}:每家公司每种物料一本账,
 * 出库金额 = 数量 × 公司两位单价(出光取余额),仓库只管数量。见设计稿 30-决策记录/2026-10-15。</p>
 *
 * <h3>并发安全</h3>
 * <ul>
 *   <li>SELECT...FOR UPDATE 锁住 inv_balance 行 + 乐观锁 version 双保险</li>
 *   <li>所有方法 @Transactional，确保 inv_balance 更新和 inv_transaction 插入要么都成功要么都失败</li>
 * </ul>
 *
 * <h3>典型调用方</h3>
 * <ul>
 *   <li>RecvService.approve() → inbound()</li>
 *   <li>OutService.approve() → outbound()</li>
 *   <li>InvoiceService.approve() → adjustEstimateDiff()（暂估开票、货票同到开票）</li>
 *   <li>PurchaseReturnService.approve() → outboundAtAmount()（按原入库额冲库存）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaInventoryService {

    private final InvBalanceMapper balanceMapper;
    private final InvTransactionMapper transMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final InvLedgerSupport ledger;
    /** 按公司核算(切换后)走这里;按仓核算(切换前/回滚后)走本类原逻辑。见 inv_cost_cutover。 */
    private final OrgCostEngine orgEngine;

    @Value("${scm.inv.avg-scale:2}")
    private int avgScale;

    @Value("${scm.inv.negative-policy:FORBID}")
    private String negativePolicy;

    /** 所有库存移动先锁公司成本锁、再锁仓库，再检查冻结；盘点建快照使用同一顺序。 */
    public void assertWarehouseMovementAllowed(Long whId, String bizType, Long bizId) {
        if (whId == null) throw new BizException("仓库不能为空");
        ledger.lockCompanyCost(ledger.orgOf(whId));
        jdbcTemplate.queryForList("SELECT id FROM md_warehouse WHERE id = ? FOR UPDATE", Long.class, whId);
        var active = jdbcTemplate.queryForList("SELECT id, doc_no FROM biz_stocktake WHERE warehouse_id = ? AND deleted = 0 "
                + "AND status IN ('DRAFT','SUBMITTED','REJECTED') ORDER BY id FOR UPDATE", whId);
        for (var take : active) {
            if ("STOCKTAKE".equals(bizType) && bizId != null && bizId.equals(((Number) take.get("id")).longValue())) continue;
            throw new BizException("该仓库正在盘点(盘点单 " + take.get("doc_no") + "),请先完成或删除该盘点单");
        }
    }

    /** 盘点建单等"先锁仓库行"的地方，须先调它拿公司成本锁，保持全站加锁顺序一致(防死锁)。 */
    public void lockCompanyCostByWarehouse(Long whId) {
        if (whId == null) throw new BizException("仓库不能为空");
        // 用独立小事务查公司:调用方(盘点建单)在等月结锁之前不能做任何普通查询,否则快照定格,看不到刚结的账
        ledger.lockCompanyCost(ledger.orgOfFresh(whId));
    }

    /** 当前是否按公司核算(切换后)。 */
    public boolean orgMode() {
        return ledger.orgMode();
    }

    /** 入口必须早于 StocktakeService.require 的盘点头行锁,与同仓普通过账保持公司→仓库→盘点头顺序。 */
    @Transactional(rollbackFor = Exception.class)
    public void lockStocktakeApproval(Long stocktakeId) {
        Long whId = ledger.lockCompanyCostForStocktake(stocktakeId);
        assertWarehouseMovementAllowed(whId, "STOCKTAKE", stocktakeId);
    }

    /**
     * 按公司盘点的确认价:公司成本锁 → 仓库冻结检查 → 成本池当前读。
     * 不能取本仓参考单价:零结存仓的参考价为 0;也不能普通 SELECT 公司池,审批事务可能已有旧 RR 快照。
     * 这把公司锁一直保留到外层盘点审批提交,取价与随后盘盈过账之间别的仓不能改公司成本。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal companyPriceForStocktake(Long matId, Long whId, Long stocktakeId) {
        assertWarehouseMovementAllowed(whId, "STOCKTAKE", stocktakeId);
        if (!ledger.orgMode()) throw new BizException("当前不是按公司核算");
        return orgEngine.companyPriceForUpdate(matId, whId);
    }

    /**
     * 入库（暂估或发票入库）。按"数量 × 单价"入账。
     */
    @Transactional(rollbackFor = Exception.class)
    public InvBalance inbound(Long matId, Long whId, BigDecimal qty, BigDecimal price,
                              String transType, String bizType, Long bizId, String bizNo,
                              Long bizLineId, String remark) {
        return inbound(matId, whId, qty, price, null, transType, bizType, bizId, bizNo, bizLineId, remark);
    }

    /**
     * 入库（暂估或发票入库）。
     *
     * @param amountOverride 若非空，则按此「精确入库金额」入账（避免"单价四舍五入 × 数量"丢分位，
     *                       让库存成本与采购订单不含税金额分毫对齐）；price 仅作流水单价展示/出库均价用。
     *                       为空则退化为按 qty × price 入账。
     */
    @Transactional(rollbackFor = Exception.class)
    public InvBalance inbound(Long matId, Long whId, BigDecimal qty, BigDecimal price, BigDecimal amountOverride,
                              String transType, String bizType, Long bizId, String bizNo,
                              Long bizLineId, String remark) {
        if (qty == null || qty.signum() <= 0) throw new BizException("入库数量必须 > 0");
        if (price == null || price.signum() < 0) throw new BizException("入库单价不能为负");
        assertWarehouseMovementAllowed(whId, bizType, bizId);

        if (amountOverride != null && amountOverride.signum() < 0) throw new BizException("入库金额不能为负");
        BigDecimal inAmt = (amountOverride != null ? amountOverride : qty.multiply(price))
                .setScale(2, RoundingMode.HALF_UP);
        if (ledger.orgMode()) {
            return orgEngine.inbound(matId, whId, qty, price, inAmt, transType, bizType, bizId, bizNo, bizLineId, remark);
        }

        InvBalance b = lockOrCreate(matId, whId);
        BigDecimal newQty = b.getQty().add(qty);
        BigDecimal newAmt = b.getAmount().add(inAmt);
        BigDecimal newPrice = newQty.signum() > 0
                ? newAmt.divide(newQty, avgScale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        b.setQty(newQty);
        b.setAmount(newAmt.setScale(2, RoundingMode.HALF_UP));
        b.setAvgPrice(newPrice);
        b.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(b);

        writeTransaction(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty, price, inAmt, b, remark);
        return b;
    }

    /**
     * 出库。返回 [实际出库单价, 实际出库金额]。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] outbound(Long matId, Long whId, BigDecimal qty,
                                 String bizType, Long bizId, String bizNo,
                                 Long bizLineId, String remark) {
        return outboundWithType(matId, whId, qty, "OUT", bizType, bizId, bizNo, bizLineId, remark);
    }

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] outboundWithType(Long matId, Long whId, BigDecimal qty, String transType,
                                         String bizType, Long bizId, String bizNo,
                                         Long bizLineId, String remark) {
        if (qty == null || qty.signum() <= 0) throw new BizException("出库数量必须 > 0");
        assertWarehouseMovementAllowed(whId, bizType, bizId);
        if (ledger.orgMode()) {
            return orgEngine.outbound(matId, whId, qty, transType, bizType, bizId, bizNo, bizLineId, remark);
        }
        InvBalance b = lockOrCreate(matId, whId);

        if (b.getQty().compareTo(qty) < 0) {
            if ("FORBID".equalsIgnoreCase(negativePolicy)) {
                throw new BizException("库存不足：" + com.group.scm.common.MaterialLabel.of(jdbcTemplate, matId)
                        + "，当前库存 " + com.group.scm.common.MaterialLabel.qty(b.getQty()) + "，本次需要 " + com.group.scm.common.MaterialLabel.qty(qty)
                        + "。请先办理入库，或减少出库数量");
            } else if ("WARN".equalsIgnoreCase(negativePolicy)) {
                log.warn("库存即将变负：matId={}, whId={}, cur={}, need={}", matId, whId, b.getQty(), qty);
            }
        }

        BigDecimal newQty = b.getQty().subtract(qty);
        BigDecimal useAmt;
        BigDecimal usePrice;
        if (newQty.signum() == 0) {
            useAmt = b.getAmount().setScale(2, RoundingMode.HALF_UP);
            usePrice = qty.signum() > 0 ? useAmt.divide(qty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } else {
            usePrice = b.getAvgPrice();
            // 展示单价不参与精确成本计算；部分出库按账面金额比例分摊。
            useAmt = b.getQty().signum() > 0
                    ? b.getAmount().multiply(qty).divide(b.getQty(), 2, RoundingMode.HALF_UP)
                    : qty.multiply(usePrice).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal newAmt = b.getAmount().subtract(useAmt);
        b.setQty(newQty);
        b.setAmount(newAmt);
        b.setAvgPrice(newQty.signum() > 0 ? newAmt.divide(newQty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        b.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(b);

        writeTransaction(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty.negate(), usePrice, useAmt.negate(), b, remark);
        return new BigDecimal[]{usePrice, useAmt};
    }

    /**
     * 差额调整：暂估转发票时核销价差。
     * <p>正数 deltaAmt = 增加金额（发票价 > 暂估价），负数 = 减少金额。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public InvBalance adjust(Long matId, Long whId, BigDecimal deltaAmt,
                             String bizType, Long bizId, String bizNo, String remark) {
        if (deltaAmt == null || deltaAmt.signum() == 0) {
            return balanceMapper.find(matId, whId);  // 无差额，直接返回
        }
        assertWarehouseMovementAllowed(whId, bizType, bizId);
        if (ledger.orgMode()) {
            return orgEngine.adjust(matId, whId, deltaAmt, bizType, bizId, bizNo, remark);
        }
        InvBalance b = lockOrCreate(matId, whId);
        BigDecimal newAmt = b.getAmount().add(deltaAmt).setScale(2, RoundingMode.HALF_UP);
        if (newAmt.signum() < 0 || (b.getQty().signum() == 0 && newAmt.signum() != 0))
            throw new BizException("库存金额调整后不合法，请核对数量与金额");
        BigDecimal newPrice = b.getQty().signum() == 0 ? BigDecimal.ZERO
                : newAmt.divide(b.getQty(), avgScale, RoundingMode.HALF_UP);
        b.setAmount(newAmt);
        b.setAvgPrice(newPrice);
        b.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(b);

        writeTransaction(matId, whId, "ADJUST", bizType, bizId, bizNo, null,
                BigDecimal.ZERO, newPrice, deltaAmt, b, remark);
        return b;
    }

    /** 兼容单价差调用；发票使用精确金额重载，差异全额计损益。 */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] adjustEstimateDiff(Long matId, Long whId, BigDecimal priceDiff, BigDecimal qty,
                                           String bizType, Long bizId, String bizNo, String remark) {
        return adjustEstimateDiff(matId, whId, priceDiff, null, qty, bizType, bizId, bizNo, remark);
    }

    /**
     * 采购成本差额过账(暂估开票、货票同到开票共用)。
     *
     * @param amountDiff 本次的【实际金额差】(发票不含税金额 − 入库金额按本次开票数量折算)。
     *                   传 null 则退回用 priceDiff × qty 计算。
     * @return [总差额, 进库存部分, 进损益部分]
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] adjustEstimateDiff(Long matId, Long whId, BigDecimal priceDiff, BigDecimal amountDiff,
                                           BigDecimal qty,
                                           String bizType, Long bizId, String bizNo, String remark) {
        return adjustEstimateDiff(matId, whId, priceDiff, amountDiff, qty, null, bizType, bizId, bizNo, remark);
    }

    /**
     * @param baseAmt 本次开票数量对应的入库金额,用作"差异超 1%"提醒的分母;为空时退回 结存单价 × 数量。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] adjustEstimateDiff(Long matId, Long whId, BigDecimal priceDiff, BigDecimal amountDiff,
                                           BigDecimal qty, BigDecimal baseAmt,
                                           String bizType, Long bizId, String bizNo, String remark) {
        return adjustEstimateDiff(matId, whId, priceDiff, amountDiff, qty, baseAmt, BigDecimal.ZERO,
                bizType, bizId, bizNo, remark);
    }

    /** 成本池标识:按仓核算 = 物料+仓库;按公司核算 = 物料+公司。同一张单据内"已用在库数量"按它累计(见下)。 */
    public String poolKey(Long matId, Long whId) {
        return ledger.orgMode() ? matId + ":O" + ledger.orgOf(whId) : matId + ":W" + whId;
    }

    /**
     * @param usedStockQty 本张单据前面几行已经"认领"过的在库数量(同一成本池)。
     *        🔴 2026-10-18(计算审查 U02,定修法 A):一张发票勾同物料同仓的两条入库,以前每行都按
     *        "当前在库数量"拆进库存/进损益——第一行调金额不改数量,第二行又把同一份在库量用一遍,
     *        在库 5 件、两行各 5 件时价差全进了库存(应一半进库存、一半进损益)。
     *        调用方按 {@link #poolKey} 累计返回值 [3](本行认领的在库数量)传进来,后面的行只能用剩下的。
     * @return [总差额, 进库存部分, 进损益部分, 本行认领的在库数量]
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] adjustEstimateDiff(Long matId, Long whId, BigDecimal priceDiff, BigDecimal amountDiff,
                                           BigDecimal qty, BigDecimal baseAmt, BigDecimal usedStockQty,
                                           String bizType, Long bizId, String bizNo, String remark) {
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal used = usedStockQty == null ? zero : usedStockQty;
        if (priceDiff == null) priceDiff = zero;
        // ⚠️ 空判必须同时看 amountDiff:单价差为 0 但金额差不为 0 是【正常情况】——
        //    发票金额手工填过之后,单价(2位显示)可能与暂估价相同,而实际金额差了几分钱。
        //    只判 priceDiff 会把这种差额静默丢掉。
        boolean noDiff = (amountDiff == null) ? (priceDiff.signum() == 0) : (amountDiff.signum() == 0);
        if (qty == null || qty.signum() <= 0 || noDiff) {
            return new BigDecimal[]{zero, zero, zero, zero};
        }
        // 差额以精确金额为准，不按已四舍五入的参考单价重算。
        BigDecimal totalDiff = (amountDiff != null)
                ? amountDiff.setScale(2, RoundingMode.HALF_UP)
                : priceDiff.multiply(qty).setScale(2, RoundingMode.HALF_UP);

        /*
         * 🔴 会计政策沿革:
         *   2026-08-05 ~ 08-21:按在库/已发拆分(在库调单价、已发进损益)
         *   2026-08-21 ~ 10-16:全额进当期损益(用户当时口径:财务软件 侧全额进损益)
         *   2026-10-16 起(对照财务软件操作手册后定 B 方案):恢复拆分 ——
         *     手册原文是"先红字冲原暂估、再按实际发票蓝字入库"(岗位标准 p68、财务指导手册 p54),
         *     差额进存货;货已领走、消化不掉的部分月末做异常结存调整转成本(岗位标准 p64、p69)。
         *     拆分规则与"为什么不全额进结存"见 CostMath#splitDiff。
         * 大额差异保护:单笔差异率超过 1%(可能是调价/漏运费/录错单价)时备注打标记,报表标红,提示财务复核。
         */
        Long orgId = ledger.orgOf(whId);
        ledger.lockCompanyCost(orgId);
        // 1% 提醒的分母:优先用调用方给的"本次入库金额";没给时退回 结存单价 × 数量(不加锁读,只作提醒用)
        BigDecimal base = baseAmt != null ? baseAmt.abs() : zero;
        if (baseAmt == null && amountDiff != null) {
            BigDecimal p = ledger.orgMode()
                    ? java.util.Optional.ofNullable(orgEngine.peekPool(orgId, matId)).map(x -> x.getAvgPrice()).orElse(zero)
                    : currentBalance(matId, whId).getAvgPrice();
            base = p.multiply(qty).abs();
        }
        String flag = base.signum() > 0
                && totalDiff.abs().divide(base, 6, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.01")) > 0
                ? "【差异超1%,请复核】" : "";
        String rmk = safeRemark(remark) + flag;

        // 加锁顺序:公司成本锁(上面)→ 成本池 → 余额行。按公司核算时由引擎先锁池再锁行,这里不能先锁余额行
        CostMath.DiffSplit split;
        InvBalance b;
        if (ledger.orgMode()) {
            split = orgEngine.invoiceDiff(matId, whId, totalDiff, qty, used, bizType, bizId, bizNo, rmk);
            b = ledger.lockOrCreate(matId, whId);
        } else {
            b = lockOrCreate(matId, whId);
            split = CostMath.splitDiff(totalDiff, b.getQty().subtract(used), b.getAmount(), qty);
            if (split.inStock().signum() != 0) {
                BigDecimal newAmt = b.getAmount().add(split.inStock());
                b.setAmount(newAmt);
                b.setAvgPrice(b.getQty().signum() > 0 ? newAmt.divide(b.getQty(), avgScale, RoundingMode.HALF_UP) : zero);
                b.setUpdatedAt(LocalDateTime.now());
                balanceMapper.updateById(b);
                writeTransaction(matId, whId, "ADJUST", bizType, bizId, bizNo, null,
                        zero, b.getAvgPrice(), split.inStock(), b,
                        rmk + "(在库 " + split.inStockQty().stripTrailingZeros().toPlainString()
                                + " 进库存单价,差额 " + split.inStock() + ")");
            }
        }

        if (split.expense().signum() != 0) {
            var pool = ledger.orgMode() ? orgEngine.peekPool(orgId, matId) : null;
            BigDecimal issuedQty = qty.subtract(split.inStockQty());
            ledger.write(matId, whId, "ADJUST_EXPENSE", bizType, bizId, bizNo, null,
                    zero, priceDiff, split.expense(), b, pool, null,
                    rmk + "(已领用 " + issuedQty.stripTrailingZeros().toPlainString()
                            + " 计入当期损益,差额 " + split.expense() + ")");
        }
        return new BigDecimal[]{totalDiff, split.inStock(), split.expense(), split.inStockQty()};
    }

    /**
     * 按指定金额出库(采购退货按原入库额冲库存)。返回 [流水单价, 实际冲减金额, 差额(实际 − 指定)]。
     * 规则见 CostMath#outAtAmount;差额(兜底情况)由调用方记损益。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] outboundAtAmount(Long matId, Long whId, BigDecimal qty, BigDecimal want, String transType,
                                         String bizType, Long bizId, String bizNo, Long bizLineId, String remark) {
        if (qty == null || qty.signum() <= 0) throw new BizException("出库数量必须 > 0");
        if (want == null || want.signum() < 0) throw new BizException("出库金额不能为负");
        assertWarehouseMovementAllowed(whId, bizType, bizId);
        if (ledger.orgMode()) {
            return orgEngine.outboundAtAmount(matId, whId, qty, want, transType, bizType, bizId, bizNo, bizLineId, remark);
        }
        InvBalance b = lockOrCreate(matId, whId);
        if (b.getQty().compareTo(qty) < 0) {
            throw new BizException("库存不足：" + com.group.scm.common.MaterialLabel.of(jdbcTemplate, matId)
                    + "，当前库存 " + com.group.scm.common.MaterialLabel.qty(b.getQty()) + "，本次退货 " + com.group.scm.common.MaterialLabel.qty(qty));
        }
        CostMath.OutCost oc = CostMath.outAtAmount(b.getQty(), b.getAmount(), qty, want);
        BigDecimal newQty = b.getQty().subtract(qty);
        BigDecimal newAmt = b.getAmount().subtract(oc.amount());
        b.setQty(newQty);
        b.setAmount(newAmt);
        b.setAvgPrice(newQty.signum() > 0 ? newAmt.divide(newQty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        b.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(b);
        writeTransaction(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty.negate(), oc.price(), oc.amount().negate(), b, remark);
        return new BigDecimal[]{oc.price(), oc.amount(), oc.tail()};
    }

    /** 记一笔只进损益、不动库存的流水(采购退货兜底差额等)。 */
    @Transactional(rollbackFor = Exception.class)
    public void postExpense(Long matId, Long whId, BigDecimal amt, String bizType, Long bizId, String bizNo,
                            Long bizLineId, String remark) {
        if (amt == null || amt.signum() == 0) return;
        Long orgId = ledger.orgOf(whId);
        ledger.lockCompanyCost(orgId);
        InvBalance b = lockOrCreate(matId, whId);
        var pool = ledger.orgMode() ? orgEngine.peekPool(orgId, matId) : null;
        ledger.write(matId, whId, "ADJUST_EXPENSE", bizType, bizId, bizNo, bizLineId,
                BigDecimal.ZERO, BigDecimal.ZERO, amt.setScale(2, RoundingMode.HALF_UP), b, pool, null, remark);
    }

    /**
     * 仓库间转库。总数量和总金额不变，只改变库存所在仓库。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal[] transfer(Long matId, Long fromWhId, Long toWhId, BigDecimal qty,
                                 String bizType, Long bizId, String bizNo, Long bizLineId,
                                 String remark) {
        if (matId == null) throw new BizException("物料不能为空");
        if (fromWhId == null || toWhId == null) throw new BizException("转出仓库和转入仓库不能为空");
        if (fromWhId.equals(toWhId)) throw new BizException("转出仓库和转入仓库不能相同");
        if (qty == null || qty.signum() <= 0) throw new BizException("转库数量必须 > 0");
        // 2026-10-15 定:跨公司挪货是法人之间的买卖,应走销售/采购,不能走转库
        if (!ledger.orgOf(fromWhId).equals(ledger.orgOf(toWhId))) {
            throw new BizException("转出仓库和转入仓库不属于同一公司，不能转库；公司之间调货请走销售/采购");
        }
        assertWarehouseMovementAllowed(Math.min(fromWhId, toWhId), bizType, bizId);
        assertWarehouseMovementAllowed(Math.max(fromWhId, toWhId), bizType, bizId);
        if (ledger.orgMode()) {
            return orgEngine.transfer(matId, fromWhId, toWhId, qty, bizType, bizId, bizNo, bizLineId, remark);
        }

        InvBalance first;
        InvBalance second;
        if (fromWhId < toWhId) {
            first = lockOrCreate(matId, fromWhId);
            second = lockOrCreate(matId, toWhId);
        } else {
            first = lockOrCreate(matId, toWhId);
            second = lockOrCreate(matId, fromWhId);
        }
        InvBalance from = fromWhId < toWhId ? first : second;
        InvBalance to = fromWhId < toWhId ? second : first;

        if (from.getQty().compareTo(qty) < 0) {
            throw new BizException("转出仓库库存不足：" + com.group.scm.common.MaterialLabel.of(jdbcTemplate, matId)
                    + "，当前库存 " + com.group.scm.common.MaterialLabel.qty(from.getQty()) + "，本次转库 " + com.group.scm.common.MaterialLabel.qty(qty));
        }

        BigDecimal moveAmt;
        BigDecimal movePrice;
        BigDecimal fromNewQty = from.getQty().subtract(qty);
        if (fromNewQty.signum() == 0) {
            moveAmt = from.getAmount().setScale(2, RoundingMode.HALF_UP);
            movePrice = qty.signum() > 0 ? moveAmt.divide(qty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } else {
            movePrice = from.getAvgPrice();
            moveAmt = from.getAmount().multiply(qty).divide(from.getQty(), 2, RoundingMode.HALF_UP);
            if (moveAmt.compareTo(from.getAmount()) > 0) moveAmt = from.getAmount();
        }

        BigDecimal fromNewAmt = from.getAmount().subtract(moveAmt);
        if (fromNewAmt.signum() < 0) fromNewAmt = BigDecimal.ZERO;
        from.setQty(fromNewQty);
        from.setAmount(fromNewAmt);
        from.setAvgPrice(fromNewQty.signum() > 0 ? fromNewAmt.divide(fromNewQty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        from.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(from);

        BigDecimal toNewQty = to.getQty().add(qty);
        BigDecimal toNewAmt = to.getAmount().add(moveAmt).setScale(2, RoundingMode.HALF_UP);
        BigDecimal toNewPrice = toNewQty.signum() > 0
                ? toNewAmt.divide(toNewQty, avgScale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        to.setQty(toNewQty);
        to.setAmount(toNewAmt);
        to.setAvgPrice(toNewPrice);
        to.setUpdatedAt(LocalDateTime.now());
        balanceMapper.updateById(to);

        writeTransaction(matId, fromWhId, "TRANSFER_OUT", bizType, bizId, bizNo, bizLineId,
                qty.negate(), movePrice, moveAmt.negate(), from, "转库出库：" + safeRemark(remark));
        writeTransaction(matId, toWhId, "TRANSFER_IN", bizType, bizId, bizNo, bizLineId,
                qty, movePrice, moveAmt, to, "转库入库：" + safeRemark(remark));
        return new BigDecimal[]{movePrice, moveAmt};
    }

    /**
     * 反审：把某笔流水反向冲销。
     * <p>用法：业务模块在反审入库/出库单时调用，恢复库存到该流水之前的状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void reverse(Long bizDocId, String bizDocType, String reason) {
        // 新流水按 reversal_of_id 精确配对；旧版 REVERSE 之前的历史不再重复冲销。
        var qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<InvTransaction>();
        qw.eq("biz_doc_id", bizDocId).eq("biz_doc_type", bizDocType)
                .isNull("reversal_of_id").ne("trans_type", "REVERSE")
                .apply("NOT EXISTS (SELECT 1 FROM inv_transaction r WHERE r.reversal_of_id = inv_transaction.id)")
                .apply("id > COALESCE((SELECT MAX(old.id) FROM inv_transaction old WHERE old.biz_doc_id = {0} "
                        + "AND old.biz_doc_type = {1} AND old.trans_type = 'REVERSE' AND old.reversal_of_id IS NULL), 0)", bizDocId, bizDocType)
                .orderByDesc("id").last("FOR UPDATE");
        var list = transMapper.selectList(qw);
        for (var t : list) assertWarehouseMovementAllowed(t.getWarehouseId(), bizDocType, bizDocId);
        if (ledger.orgMode()) {
            orgEngine.reverse(list, bizDocType, bizDocId, reason);
            return;
        }
        for (var t : list) {
            boolean expense = "ADJUST_EXPENSE".equals(t.getTransType());
            InvBalance b = lockOrCreate(t.getMaterialId(), t.getWarehouseId());
            if (OrgCostEngine.amountOnly(t)) {
                // 发票差额进库存的那笔:货可能已领光,库存吃不下的部分转损益(规则同按公司核算)
                BigDecimal back = OrgCostEngine.absorbable(b.getQty(), b.getAmount(), t.getAmount());
                BigDecimal newAmt = b.getAmount().subtract(back);
                b.setAmount(newAmt);
                b.setAvgPrice(b.getQty().signum() > 0 ? newAmt.divide(b.getQty(), avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                b.setUpdatedAt(LocalDateTime.now());
                balanceMapper.updateById(b);
                InvTransaction reversal = writeTransaction(t.getMaterialId(), t.getWarehouseId(), "REVERSE",
                        bizDocType, bizDocId, t.getBizDocNo(), t.getBizLineId(),
                        BigDecimal.ZERO, t.getUnitPrice(), back.negate(), b, "反审：" + reason);
                reversal.setReversalOfId(t.getId());
                transMapper.updateById(reversal);
                orgEngine.writeReverseOverflow(t, reversal, back, b, null, bizDocType, bizDocId, reason);
                continue;
            }
            if (!expense) {
                BigDecimal newQty = b.getQty().subtract(t.getQty());
                BigDecimal newAmt = b.getAmount().subtract(t.getAmount());
                if (newQty.signum() < 0) throw new BizException("反审会导致负库存：" + com.group.scm.common.MaterialLabel.of(jdbcTemplate, t.getMaterialId())
                        + "（这批货入库后已被领用或转出，需先处理后续单据）");
                if (newAmt.signum() < 0 || (newQty.signum() == 0 && newAmt.signum() != 0))
                    throw new BizException("反审会导致结存金额异常，请先处理后续成本变动：" + com.group.scm.common.MaterialLabel.of(jdbcTemplate, t.getMaterialId()));
                b.setQty(newQty);
                b.setAmount(newAmt);
                b.setAvgPrice(newQty.signum() > 0 ? newAmt.divide(newQty, avgScale, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                b.setUpdatedAt(LocalDateTime.now());
                balanceMapper.updateById(b);
            }
            // 损益的逆向记录仍用 ADJUST_EXPENSE，现有明细/汇总/结账自然按正负净额计算。
            InvTransaction reversal = writeTransaction(t.getMaterialId(), t.getWarehouseId(), expense ? "ADJUST_EXPENSE" : "REVERSE",
                    bizDocType, bizDocId, t.getBizDocNo(), t.getBizLineId(),
                    t.getQty().negate(), t.getUnitPrice(), t.getAmount().negate(), b, "反审：" + reason);
            reversal.setReversalOfId(t.getId());
            transMapper.updateById(reversal);
        }
    }

    /* ============ 内部工具 ============ */

    private InvBalance lockOrCreate(Long matId, Long whId) {
        return ledger.lockOrCreate(matId, whId);
    }

    /** 按仓核算写流水(切换前/回滚后)。 */
    private InvTransaction writeTransaction(Long matId, Long whId, String transType,
                                  String bizType, Long bizId, String bizNo, Long bizLineId,
                                  BigDecimal qty, BigDecimal price, BigDecimal amt,
                                  InvBalance afterBal, String remark) {
        return ledger.write(matId, whId, transType, bizType, bizId, bizNo, bizLineId,
                qty, price, amt, afterBal, null, null, remark);
    }

    private String safeRemark(String remark) {
        return remark == null ? "" : remark;
    }

    /**
     * 取当前结存（不加锁）—— 用于业务校验如"出库前预检查库存"。
     */
    public InvBalance currentBalance(Long matId, Long whId) {
        InvBalance b = balanceMapper.find(matId, whId);
        if (b == null) {
            b = new InvBalance();
            b.setMaterialId(matId); b.setWarehouseId(whId);
            b.setQty(BigDecimal.ZERO); b.setAmount(BigDecimal.ZERO); b.setAvgPrice(BigDecimal.ZERO);
        }
        return b;
    }
}
