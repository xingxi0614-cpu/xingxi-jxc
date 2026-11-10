package com.group.scm.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.inventory.entity.BizRecvLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BizRecvLineMapper extends BaseMapper<BizRecvLine> {

    /**
     * 核销开票数量。
     * <p>🔴 2026-08-25：上限从 qty 改为 qty - returned_qty —— 退给供应商的那部分不会再有发票，
     * 若仍按 qty 判断，退货后同一行还能被开票核销，暂估会被重复冲掉。</p>
     */
    @Update("""
        UPDATE biz_recv_line
        SET invoiced_qty = invoiced_qty + #{qty}
        WHERE id = #{id}
          AND invoiced_qty + #{qty} <= qty - returned_qty
    """)
    int addInvoicedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    /**
     * 登记退货数量（采购退货单过账时调用）。
     * <p>并发防线：条件更新，退货总量不得超过「入库量 - 已开票量」；返回 0 表示额度不足，
     * 调用方必须中止事务，防止两张退货单把同一批货各退一遍。</p>
     */
    @Update("""
        UPDATE biz_recv_line
        SET returned_qty = returned_qty + #{qty}
        WHERE id = #{id}
          AND returned_qty + #{qty} <= qty - invoiced_qty
    """)
    int addReturnedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    /** 反审采购退货单时对称回退。 */
    @Update("""
        UPDATE biz_recv_line
        SET returned_qty = GREATEST(returned_qty - #{qty}, 0)
        WHERE id = #{id}
    """)
    int subtractReturnedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Select("""
        SELECT COALESCE(SUM(il.qty), 0)
        FROM biz_invoice_line il
        JOIN biz_invoice i ON i.id = il.invoice_id AND i.deleted = 0
        WHERE il.recv_line_id = #{recvLineId}
          AND i.status IN ('DRAFT', 'PENDING')
    """)
    java.math.BigDecimal activeInvoiceQty(@Param("recvLineId") Long recvLineId);
}
