package com.group.scm.modules.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.outbound.entity.BizLoanLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BizLoanLineMapper extends BaseMapper<BizLoanLine> {

    @org.apache.ibatis.annotations.Select("""
        SELECT COALESCE(SUM(amount),0) FROM inv_transaction
        WHERE biz_doc_type='LOAN_RETURN' AND biz_line_id=#{id} FOR UPDATE
    """)
    java.math.BigDecimal returnedAmount(@Param("id") Long id);

    /** 原子累加已归还数量，超过借出数量时不生效（返回 0）——防并发归还超还/重复入库。 */
    @Update("""
        UPDATE biz_loan_line
        SET returned_qty = COALESCE(returned_qty, 0) + #{qty}
        WHERE id = #{id}
          AND COALESCE(returned_qty, 0) + #{qty} <= qty
    """)
    int addReturnedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);
}
