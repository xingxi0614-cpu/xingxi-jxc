package com.group.scm.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.inventory.entity.BizPurchaseReturnLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BizPurchaseReturnLineMapper extends BaseMapper<BizPurchaseReturnLine> {

    /**
     * 某入库行被【草稿/待审】退货单占用的数量。
     * <p>与发票的 activeInvoiceQty 同款口径：未过账的单据也要占额度，
     * 否则同一批货可以被两张草稿退货单各退一遍，审批时才发现库存不够。</p>
     */
    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
        FROM biz_purchase_return_line l
        JOIN biz_purchase_return r ON r.id = l.return_id AND r.deleted = 0
        WHERE l.source_recv_line_id = #{recvLineId}
          AND r.status IN ('DRAFT', 'PENDING')
    """)
    java.math.BigDecimal activeReturningQty(@Param("recvLineId") Long recvLineId);
}
