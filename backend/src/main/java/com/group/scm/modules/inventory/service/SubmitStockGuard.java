package com.group.scm.modules.inventory.service;

import com.group.scm.common.BizException;
import com.group.scm.common.MaterialLabel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 提交审批前的库存检查（2026-10-20 定）。
 *
 * <p><b>为什么要有它：</b>以前库存只在审批扣库存那一下才查，制单、提交都不查。明知过不了的单照样进审批队列，
 * 审批人点了才报"库存不足"，待审批里也一直挂着库存不够的单。
 * 口径："没库存就不该能出库"：提交审批时库存不足就拦；草稿照样能存；审批时的库存硬闸保留（双保险）。</p>
 *
 * <p><b>可用量 = 账面库存 − 占用。</b>账面库存取 inv_balance.qty（按仓库，两种核算模式都一样）；
 * 占用 = 同仓库同物料其他"已提交、还没真正扣库存"的单据数量。不扣占用的话，仓里 10 个、两张单各要 8 个，两张都能提交，
 * 第二张审批时照样过不去（用户选了扣占用这一档）。各单据"已提交未扣库存"的状态：</p>
 * <ul>
 *   <li>材料出库、销售出库、借料、采购退货：PENDING（审批通过即扣库存）；</li>
 *   <li>库存转库（看转出仓）：PENDING，以及关联领料"已批准待实际交接"的 READY——交接确认时才扣库存；</li>
 * </ul>
 * <p>草稿、已驳回不算占用：没提交的单不该挡别人。出库申请（计划）不在此列，它不扣库存。</p>
 *
 * <p><b>并发：</b>两张单同时提交、库存只够一张时只能成一张。做法是按库存过账的同一顺序加锁——
 * 先公司成本锁、再仓库行（{@link WaInventoryService#assertWarehouseMovementAllowed} 的顺序），
 * 同一家公司的提交和审批过账排队，不会互相等成死锁。锁到手以后，库存和占用用<b>独立的只读小事务</b>（读已提交）去读：
 * 本事务加锁前若已做过普通查询，MySQL 会一直给它看那一刻的旧快照，读不到刚排在前面那张单的提交结果
 * （同类坑见 RecvLineUsage，2026-10-18 用量账修复）。</p>
 *
 * <p>盘点冻结不在这里拦：盘点期间允许提交（只提醒），真正过账时库存引擎会拦。</p>
 */
@Service
@RequiredArgsConstructor
public class SubmitStockGuard {

    private final JdbcTemplate jdbc;
    private final InvLedgerSupport ledger;
    private final PlatformTransactionManager txManager;

    /** 一行需求：哪个物料、要多少 */
    public record Need(Long materialId, BigDecimal qty) {}

    /** 单据类型（与审批流 bizType 一致），用来把"本单自己"从占用里排除 */
    public static final String OUT = "OUT", SALE_OUT = "SALE_OUT", TRANSFER = "TRANSFER",
            LOAN = "LOAN", PRET = "PRET";

    /**
     * 库存不够就抛出，提示里写清：哪个物料、账面多少、被哪几张单占了多少、还能用多少、本单要多少。
     *
     * @param hint 补在提示最后的"怎么办"，传 null 用默认说法
     */
    public void assertAvailable(String bizType, Long bizId, Long warehouseId, Collection<Need> needs, String hint) {
        if (warehouseId == null) throw new BizException("仓库不能为空");
        // 同一物料多行合并后再比（一张单里同一物料分两行各 6 个，仓里 10 个，合起来就不够）
        Map<Long, BigDecimal> need = new TreeMap<>();
        for (Need n : needs) {
            if (n == null || n.materialId() == null || n.qty() == null || n.qty().signum() <= 0) continue;
            need.merge(n.materialId(), n.qty(), BigDecimal::add);
        }
        if (need.isEmpty()) return;

        ledger.lockCompanyCost(ledger.orgOf(warehouseId));
        jdbc.queryForList("SELECT id FROM md_warehouse WHERE id = ? FOR UPDATE", Long.class, warehouseId);

        Fresh fresh = readFresh(bizType, bizId, warehouseId, need.keySet());
        List<String> lack = new ArrayList<>();
        for (var e : need.entrySet()) {
            BigDecimal have = fresh.book.getOrDefault(e.getKey(), BigDecimal.ZERO);
            Map<String, BigDecimal> held = fresh.held.getOrDefault(e.getKey(), Map.of());
            BigDecimal heldSum = held.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal usable = have.subtract(heldSum);
            if (usable.compareTo(e.getValue()) >= 0) continue;
            StringBuilder s = new StringBuilder(MaterialLabel.of(jdbc, e.getKey()))
                    .append(" 账面库存 ").append(MaterialLabel.qty(have));
            if (heldSum.signum() > 0) {
                List<String> docs = new ArrayList<>(held.keySet());
                s.append("，其他待审批单已占用 ").append(MaterialLabel.qty(heldSum)).append("（")
                        .append(String.join("、", docs.subList(0, Math.min(3, docs.size()))))
                        .append(docs.size() > 3 ? " 等 " + docs.size() + " 张" : "").append("）")
                        .append("，可用 ").append(MaterialLabel.qty(usable.max(BigDecimal.ZERO)));
            }
            s.append("，本单需要 ").append(MaterialLabel.qty(e.getValue()));
            lack.add(s.toString());
        }
        if (lack.isEmpty()) return;
        throw new BizException("库存不足，不能提交审批：" + String.join("；", lack) + "。"
                + (hint != null ? hint : "请先办理入库（入库单审批通过才算库存），或减少数量；占用的单据审批或撤回后会释放。")
                + "本单没有提交，单据原样保留。");
    }

    private record Fresh(Map<Long, BigDecimal> book, Map<Long, Map<String, BigDecimal>> held) {}

    private Fresh readFresh(String bizType, Long bizId, Long warehouseId, Collection<Long> materialIds) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tt.setReadOnly(true);
        String in = String.join(",", materialIds.stream().map(x -> "?").toList());
        return tt.execute(status -> {
            Map<Long, BigDecimal> book = new LinkedHashMap<>();
            List<Object> args = new ArrayList<>();
            args.add(warehouseId);
            args.addAll(materialIds);
            jdbc.query("SELECT material_id, qty FROM inv_balance WHERE warehouse_id = ? AND material_id IN (" + in + ")",
                    rs -> { book.merge(rs.getLong(1), rs.getBigDecimal(2), BigDecimal::add); }, args.toArray());

            // 五类单据"已提交未扣库存"的数量。每段都用 (类型, id) 排除本单；material_id 过滤放在最外层。
            String sql = """
                    SELECT x.material_id, x.doc_no, SUM(x.qty) FROM (
                      SELECT 'OUT' t, o.id, o.doc_no, l.material_id, l.qty FROM biz_out o JOIN biz_out_line l ON l.out_id = o.id
                        WHERE o.deleted = 0 AND o.status = 'PENDING' AND o.warehouse_id = ?
                      UNION ALL
                      SELECT 'SALE_OUT', o.id, o.doc_no, l.material_id, l.qty FROM biz_sale_out o JOIN biz_sale_out_line l ON l.out_id = o.id
                        WHERE o.deleted = 0 AND o.status = 'PENDING' AND o.warehouse_id = ?
                      UNION ALL
                      SELECT 'TRANSFER', o.id, o.doc_no, l.material_id, l.qty FROM biz_transfer o JOIN biz_transfer_line l ON l.transfer_id = o.id
                        WHERE o.deleted = 0 AND o.status IN ('PENDING', 'READY') AND o.from_warehouse_id = ?
                      UNION ALL
                      SELECT 'LOAN', o.id, o.doc_no, l.material_id, l.qty FROM biz_loan o JOIN biz_loan_line l ON l.loan_id = o.id
                        WHERE o.deleted = 0 AND o.status = 'PENDING' AND o.warehouse_id = ?
                      UNION ALL
                      SELECT 'PRET', o.id, o.doc_no, l.material_id, l.qty FROM biz_purchase_return o JOIN biz_purchase_return_line l ON l.return_id = o.id
                        WHERE o.deleted = 0 AND o.status = 'PENDING' AND o.warehouse_id = ?
                    ) x
                    WHERE NOT (x.t = ? AND x.id = ?) AND x.material_id IN (""" + in + """
                    )
                    GROUP BY x.material_id, x.doc_no
                    ORDER BY x.material_id, x.doc_no""";
            List<Object> a = new ArrayList<>(List.of(warehouseId, warehouseId, warehouseId, warehouseId, warehouseId,
                    bizType, bizId == null ? -1L : bizId));
            a.addAll(materialIds);
            Map<Long, Map<String, BigDecimal>> held = new LinkedHashMap<>();
            jdbc.query(sql, rs -> {
                held.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).merge(rs.getString(2), rs.getBigDecimal(3), BigDecimal::add);
            }, a.toArray());
            return new Fresh(book, held);
        });
    }
}
