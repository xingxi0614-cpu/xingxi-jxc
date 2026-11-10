package com.group.scm.modules.inventory.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.entity.InvCostPool;
import com.group.scm.modules.inventory.entity.InvTransaction;
import com.group.scm.modules.inventory.mapper.InvBalanceMapper;
import com.group.scm.modules.inventory.mapper.InvTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 库存账的公共件:核算模式、公司成本锁、余额行加锁/创建、写流水。
 * 按仓(WaInventoryService 原逻辑)与按公司(OrgCostEngine)两套核算共用,保证写法一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvLedgerSupport {

    public static final String SCOPE_WH = "WH";
    public static final String SCOPE_ORG = "ORG";

    private final InvBalanceMapper balanceMapper;
    private final InvTransactionMapper transMapper;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * 当前核算模式:inv_cost_cutover 最新一行的 scope_after;没有记录 = 按仓(切换前)。
     * 只能由 scripts/cost-cutover.sql / cost-rollback.sql 改(停后端执行),不放在可随意编辑的 sys_param 里。
     */
    public String scope() {
        return jdbc.query("SELECT scope_after FROM inv_cost_cutover ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : SCOPE_WH);
    }

    public boolean orgMode() {
        return SCOPE_ORG.equals(scope());
    }

    /** 仓库所属公司。所有仓库都直挂公司,成本池按它分。 */
    public Long orgOf(Long whId) {
        return jdbc.queryForList("SELECT org_id FROM md_warehouse WHERE id = ?", Long.class, whId)
                .stream().findFirst().orElseThrow(() -> new BizException("仓库不存在"));
    }

    /**
     * 在【独立的只读小事务】里查仓库所属公司,不在调用方事务里做普通查询。
     * <p>为什么(2026-10-15 CI 抓到):MySQL 可重复读下,事务里第一次普通 SELECT 就定格了"快照"。
     * 盘点建单要先等月结的组织锁、再判断"本月结没结账";若在等锁之前先普通查了一次仓库,
     * 后面就一直看旧快照,看不到刚提交的结账,盘点单会建在已结账月份里。
     * 只在"先拿锁、后读数"敏感的入口(盘点建单)用它;要求仓库已提交(生产必然如此)。</p>
     */
    public Long orgOfFresh(Long whId) {
        var tt = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setReadOnly(true);
        Long orgId = tt.execute(st -> jdbc.queryForList("SELECT org_id FROM md_warehouse WHERE id = ?", Long.class, whId)
                .stream().findFirst().orElse(null));
        // 独立事务看不到时,只可能是仓库就在本事务里刚建、还没提交(单据流程里不会出现,集成测试会):
        // 退回本事务内查询。已提交的仓库(月结并发场景)始终走上面的独立查询,防线不受影响。
        return orgId != null ? orgId : orgOf(whId);
    }

    /**
     * 盘点确认先拿公司锁,再允许调用方锁仓库和盘点头。
     * DocumentMapper.selectById 会锁盘点头;若先锁头再等公司锁,会与普通出库的"公司→盘点头"互等。
     * 归属在独立只读事务取,不让等待锁之前的普通查询定格外层 RR 快照。
     */
    public Long lockCompanyCostForStocktake(Long stocktakeId) {
        var tt = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setReadOnly(true);
        java.util.function.Supplier<long[]> location = () -> jdbc.query("""
                SELECT w.org_id, s.warehouse_id FROM biz_stocktake s
                JOIN md_warehouse w ON w.id=s.warehouse_id
                WHERE s.id=? AND s.deleted=0
                """, rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null, stocktakeId);
        long[] found = tt.execute(st -> location.get());
        // 同事务刚建的盘点尚未提交,独立事务不可见;建单已持有公司锁,在外层查不影响锁序。
        if (found == null) found = location.get();
        if (found == null) throw new BizException("盘点单不存在");
        lockCompanyCost(found[0]);
        return found[1];
    }

    /**
     * 🔒 公司成本锁 —— 所有库存动作的【第一把锁】。
     * <p>为什么:按公司核算后,同一公司不同仓库的单子会争同一个成本池行;若各自先锁仓库再按不同物料顺序锁池,
     * 就可能互等死锁。规定全站固定顺序:公司成本锁 → 仓库行 → 成本池行 → 余额行(仓库 id 升序)。
     * 同一事务里重复调用是可重入的(InnoDB 已持有的锁)。盘点建单也先拿这把锁(StocktakeService#create)。</p>
     * <p>代价:同一公司的库存过账排队执行;全集团 5 月至今约 1 万笔流水,可忽略。</p>
     */
    public void lockCompanyCost(Long orgId) {
        if (orgId == null) throw new BizException("仓库未归属公司");
        var hit = jdbc.queryForList("SELECT org_id FROM inv_cost_lock WHERE org_id = ? FOR UPDATE", Long.class, orgId);
        if (!hit.isEmpty()) return;
        // 新建公司后首次过账:补一行锁(迁移已为当时所有组织预建)
        try {
            jdbc.update("INSERT INTO inv_cost_lock (org_id) VALUES (?)", orgId);
        } catch (DuplicateKeyException e) {
            jdbc.queryForList("SELECT org_id FROM inv_cost_lock WHERE org_id = ? FOR UPDATE", Long.class, orgId);
        }
    }

    public InvBalance lockOrCreate(Long matId, Long whId) {
        InvBalance b = balanceMapper.findForUpdate(matId, whId);
        if (b == null) {
            b = new InvBalance();
            b.setMaterialId(matId);
            b.setWarehouseId(whId);
            b.setQty(BigDecimal.ZERO);
            b.setAmount(BigDecimal.ZERO);
            b.setAvgPrice(BigDecimal.ZERO);
            b.setUpdatedAt(LocalDateTime.now());
            try {
                balanceMapper.insert(b);
            } catch (DuplicateKeyException e) {
                // 并发首次入库：另一个事务已抢先创建了该 (matId, whId) 行，重新加锁读取
                log.debug("并发首次入库，重试锁定 matId={} whId={}", matId, whId);
                b = balanceMapper.findForUpdate(matId, whId);
                if (b == null) throw new BizException("库存余额创建冲突，请重试");
            }
        }
        return b;
    }

    /** 写流水。pool 非空 = 按公司核算(记公司结存),为空 = 按仓核算。 */
    public InvTransaction write(Long matId, Long whId, String transType,
                                String bizType, Long bizId, String bizNo, Long bizLineId,
                                BigDecimal qty, BigDecimal price, BigDecimal amt,
                                InvBalance afterBal, InvCostPool pool, BigDecimal tail, String remark) {
        InvTransaction t = new InvTransaction();
        t.setTransNo("TR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        t.setMaterialId(matId); t.setWarehouseId(whId);
        t.setTransType(transType);
        t.setBizDocType(bizType); t.setBizDocId(bizId); t.setBizDocNo(bizNo); t.setBizLineId(bizLineId);
        t.setQty(qty); t.setUnitPrice(price); t.setAmount(amt);
        t.setBalQty(afterBal.getQty());
        t.setBalAmount(afterBal.getAmount());
        t.setBalAvgPrice(afterBal.getAvgPrice());
        if (pool != null) {
            t.setCostScope(SCOPE_ORG);
            t.setOrgBalQty(pool.getQty());
            t.setOrgBalAmount(pool.getAmount());
        } else {
            t.setCostScope(SCOPE_WH);
        }
        if (tail != null && tail.signum() != 0) t.setTailAmt(tail);
        t.setRemark(remark != null && remark.length() > 255 ? remark.substring(0, 255) : remark);
        t.setCreatedAt(LocalDateTime.now());
        t.setCreatedBy(SecurityUtil.uid());
        transMapper.insert(t);
        return t;
    }
}
