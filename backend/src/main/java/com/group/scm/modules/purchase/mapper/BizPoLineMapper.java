package com.group.scm.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.purchase.entity.BizPoLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BizPoLineMapper extends BaseMapper<BizPoLine> {

    @Update("""
        UPDATE biz_po_line
        SET received_qty = received_qty + #{qty}
        WHERE id = #{id}
          AND received_qty + #{qty} <= qty
    """)
    int addReceivedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Update("""
        UPDATE biz_po_line
        SET invoiced_qty = invoiced_qty + #{qty}
        WHERE id = #{id}
          AND invoiced_qty + #{qty} <= qty
    """)
    int addInvoicedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Select("""
        SELECT COALESCE(SUM(rl.qty), 0)
        FROM biz_recv_line rl
        JOIN biz_recv r ON r.id = rl.recv_id AND r.deleted = 0
        WHERE rl.source_po_line_id = #{poLineId}
          AND r.status IN ('DRAFT', 'PENDING')
    """)
    java.math.BigDecimal activeReceivingQty(@Param("poLineId") Long poLineId);
}
