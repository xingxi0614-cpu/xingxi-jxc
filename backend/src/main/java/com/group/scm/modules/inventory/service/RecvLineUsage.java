package com.group.scm.modules.inventory.service;

import com.group.scm.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 入库行的「原金额用量账」(2026-10-18,计算审查 U13/U14/U22,定总思路)。
 *
 * <p><b>为什么:</b>一条入库行(比如 10 件、1000 元)会被采购发票、采购退货陆续"用掉"。以前不记每张单据实际用了多少,
 * 每次按"累计数量首尾相减"从头反推:顺序一路开下去没问题,一旦中间撤销一张、两张同时审批、或分好几次,
 * 反推出来的"已用"和实际对不上,冒出一两分的尾差或假价差(报告里 3 件 0.10 元的例子)。</p>
 *
 * <p><b>规则(所有用量同一条):</b>
 * 剩余金额 = 入库额 − 已审批发票记下的用量 − 已审批退货的原入库额;剩余数量 = 入库数 − 已开票数 − 已退货数。
 * 本次数量 = 剩余数量 → 拿走全部剩余金额;否则 剩余金额 × 本次数量 ÷ 剩余数量(四舍五入到分)。
 * 按"剩余"的比例分,而不是按原始总额反推:不管中间撤销了哪张、顺序怎么乱,用完时加起来一定等于入库额。
 * 撤销(取消审批)后单据不再是已审批,它的用量自动不算,金额回到剩余里,不需要任何反向计算。</p>
 *
 * <p><b>并发:</b>调用方先拿公司成本锁,再用 {@link #lock} 锁住入库行(当前读),然后才读用量 → 两张同时审批时,
 * 后到的读到前一张提交后的剩余(U22)。全站统一顺序:公司成本锁 → 入库行 → 库存余额行。</p>
 *
 * <p>🔴 <b>2026-10-18 P0 复查 P1:已用金额必须用独立小事务读</b>(见 {@link #usedAmount(Long, Long, Long)})。
 * 审批事务一开头就有普通查询(读单据头),MySQL 可重复读的快照在那一刻就定格了;后面等锁等到前一张发票提交,
 * {@link #lock} 的当前读拿到了新的已开票数量,而普通查询读的已用金额还是旧快照——数量是新的、金额是旧的,
 * 两张各 1 件 10 元同时审批,第二张会分走 20,凭空调差 −10。不用 FOR SHARE 加锁读:它要连带锁别的发票头,
 * 而那张发票的审批事务正拿着自己的单据头等这条入库行的锁,两边互等就是死锁。</p>
 *
 * <p><b>同一入库行在一张单里出现多行</b>(接口允许,桌面选择器会去重):用 {@link DocAllocation} 记住本单前面几行
 * 已分走的金额,后面的行从剩余里再扣(P0 复查 P2)。以前只排除"整张本单",前行已经加进已用数量、金额却没扣,
 * 第二行会拿走全部剩余金额。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecvLineUsage {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    /**
     * 一张单据审批过程中已分走的入库额(按入库行累计)。每张单据审批开头 new 一个,逐行传给 {@link #allocateForDoc}。
     * 前提:调用方在处理下一行之前,已把本行数量记到入库行上(addInvoicedQty / addReturnedQty),
     * 这样下一行 {@link #lock} 读到的已用数量里包含本行——数量和金额才对得上。
     */
    public static final class DocAllocation {
        private final Map<Long, BigDecimal> allocated = new HashMap<>();

        BigDecimal of(Long recvLineId) { return allocated.getOrDefault(recvLineId, BigDecimal.ZERO); }

        void add(Long recvLineId, BigDecimal amt) { allocated.merge(recvLineId, amt, BigDecimal::add); }
    }

    /** 入库行当前值(加锁读到的,不受本事务早先快照影响) */
    public record Line(Long id, BigDecimal qty, BigDecimal amount, BigDecimal invoicedQty, BigDecimal returnedQty) {
        public BigDecimal usedQty() { return invoicedQty.add(returnedQty); }
        public BigDecimal remainingQty() { return qty.subtract(usedQty()); }
    }

    /** 锁住入库行并读当前值。必须在事务内、且在公司成本锁之后调用。 */
    public Line lock(Long recvLineId) {
        Map<String, Object> m = jdbc.queryForList("""
                SELECT id, qty, amount, COALESCE(invoiced_qty, 0) invoiced_qty, COALESCE(returned_qty, 0) returned_qty
                FROM biz_recv_line WHERE id = ? FOR UPDATE
                """, recvLineId).stream().findFirst().orElseThrow(() -> new BizException("入库行不存在：" + recvLineId));
        return toLine(m);
    }

    /** 不加锁读(建草稿时算参考值用;最终以审批时加锁重算为准) */
    public Line peek(Long recvLineId) {
        Map<String, Object> m = jdbc.queryForList("""
                SELECT id, qty, amount, COALESCE(invoiced_qty, 0) invoiced_qty, COALESCE(returned_qty, 0) returned_qty
                FROM biz_recv_line WHERE id = ?
                """, recvLineId).stream().findFirst().orElseThrow(() -> new BizException("入库行不存在：" + recvLineId));
        return toLine(m);
    }

    /**
     * 已被用掉的入库额 = 已审批采购发票行的用量 + 已审批采购退货的原入库额。
     * 历史发票行由迁移 20261018_001 补了值;万一有漏补的(迁移与新代码切换之间审批的),
     * 按旧算法近似(入库额 × 本行数量 ÷ 入库数)并告警,不让业务卡住。
     */
    public BigDecimal usedAmount(Long recvLineId) {
        return usedAmount(recvLineId, null, null);
    }

    /**
     * 审批时分配本行入库额:已用 = 别的已审批单据(独立小事务读到的最新提交值)+ 本单前面几行已分走的。
     * 必须在 {@link #lock} 之后调用。
     */
    public BigDecimal allocateForDoc(DocAllocation doc, Line line, Long excludeInvoiceId, Long excludeReturnId, BigDecimal qty) {
        BigDecimal used = usedAmount(line.id(), excludeInvoiceId, excludeReturnId).add(doc.of(line.id()));
        BigDecimal amt = allocate(line, used, qty);
        doc.add(line.id(), amt);
        return amt;
    }

    /**
     * 用独立只读小事务读(新快照 = 最新已提交值),不受调用方事务早先快照影响。
     * 为什么这样是对的:调用方已经锁住这条入库行;别的单据要给这条入库行记用量,也得先拿这把锁,
     * 所以此刻凡是记过用量的都已经提交了,新快照一定看得到;还没拿到锁的,用量还没写,本来就不该算。
     * 代价:看不到调用方本事务里还没提交的写入——所以同一事务里不能先审批另一张用同一入库行的单据
     * (生产没有这种批量审批;同一张单里重复的行由 {@link DocAllocation} 处理)。
     *
     * @param excludeInvoiceId 正在审批的那张发票(审批时它已先被改成已审批,不能把自己算进"已用")
     * @param excludeReturnId  正在审批的那张退货单(同理)
     */
    public BigDecimal usedAmount(Long recvLineId, Long excludeInvoiceId, Long excludeReturnId) {
        TransactionTemplate fresh = new TransactionTemplate(txManager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setReadOnly(true);
        return fresh.execute(st -> usedAmountInCurrentTx(recvLineId, excludeInvoiceId, excludeReturnId));
    }

    private BigDecimal usedAmountInCurrentTx(Long recvLineId, Long excludeInvoiceId, Long excludeReturnId) {
        long exInv = excludeInvoiceId == null ? -1L : excludeInvoiceId;
        long exRet = excludeReturnId == null ? -1L : excludeReturnId;
        BigDecimal inv = jdbc.queryForObject("""
                SELECT COALESCE(SUM(il.recv_amt_used), 0) FROM biz_invoice_line il
                JOIN biz_invoice v ON v.id = il.invoice_id AND v.deleted = 0 AND v.status IN ('APPROVED', 'MATCHED')
                WHERE il.recv_line_id = ? AND il.recv_amt_used IS NOT NULL AND il.invoice_id <> ?
                """, BigDecimal.class, recvLineId, exInv);
        BigDecimal legacy = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ROUND(rl.amount * il.qty / rl.qty, 2)), 0) FROM biz_invoice_line il
                JOIN biz_invoice v ON v.id = il.invoice_id AND v.deleted = 0 AND v.status IN ('APPROVED', 'MATCHED')
                JOIN biz_recv_line rl ON rl.id = il.recv_line_id AND rl.qty > 0
                WHERE il.recv_line_id = ? AND il.recv_amt_used IS NULL AND il.invoice_id <> ?
                """, BigDecimal.class, recvLineId, exInv);
        if (legacy != null && legacy.signum() != 0) {
            log.warn("入库行 {} 有已审批发票行未记用量(迁移后漏补),按旧算法近似 {}", recvLineId, legacy);
        }
        BigDecimal ret = jdbc.queryForObject("""
                SELECT COALESCE(SUM(prl.recv_amt), 0) FROM biz_purchase_return_line prl
                JOIN biz_purchase_return pr ON pr.id = prl.return_id AND pr.deleted = 0 AND pr.status = 'APPROVED'
                WHERE prl.source_recv_line_id = ? AND prl.return_id <> ?
                """, BigDecimal.class, recvLineId, exRet);
        return nz(inv).add(nz(legacy)).add(nz(ret));
    }

    /** 本次 qty 应分到的入库额(规则见类注释) */
    public BigDecimal allocate(Line line, BigDecimal usedAmount, BigDecimal qty) {
        return allocate(nz(line.amount()), line.qty(), line.usedQty(), usedAmount, qty);
    }

    /** 纯计算,便于单测:总额 total、总数 totalQty,已用 usedQty/usedAmt,本次 qty */
    public static BigDecimal allocate(BigDecimal total, BigDecimal totalQty, BigDecimal usedQty, BigDecimal usedAmt, BigDecimal qty) {
        if (totalQty == null || totalQty.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal remainingQty = totalQty.subtract(usedQty);
        BigDecimal remainingAmt = total.subtract(usedAmt).setScale(2, RoundingMode.HALF_UP);
        int cmp = qty.compareTo(remainingQty);
        if (cmp > 0) throw new BizException("本次数量超过入库行剩余可用数量，剩余：" + remainingQty.stripTrailingZeros().toPlainString());
        if (remainingAmt.signum() < 0) throw new BizException("入库行已用金额超过入库额，请先核对该入库行的发票与退货记录");
        if (cmp == 0) return remainingAmt;   // 最后一笔:拿走全部剩余,一分不差
        return remainingAmt.multiply(qty).divide(remainingQty, 2, RoundingMode.HALF_UP).min(remainingAmt);
    }

    private static Line toLine(Map<String, Object> m) {
        return new Line(((Number) m.get("id")).longValue(), nz((BigDecimal) m.get("qty")), nz((BigDecimal) m.get("amount")),
                nz((BigDecimal) m.get("invoiced_qty")), nz((BigDecimal) m.get("returned_qty")));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
