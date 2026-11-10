package com.group.scm.modules.sales.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.sales.entity.BizSaleOut;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BizSaleOutMapper extends DocumentMapper<BizSaleOut> {

    /**
     * 加锁读来源单据的最新状态(计算审查 U05/U07/U08,2026-10-18):下游单据最终过账前调用,
     * 来源若已被取消审批退回草稿/作废,就不许下游过账。FOR UPDATE 是当前读,不受本事务早先快照影响;
     * 锁持有到事务提交,与来源的「取消审批」互斥。必须在 @Transactional 内调用。
     */
    @org.apache.ibatis.annotations.Select("SELECT status FROM biz_sale_out WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    String lockStatus(@Param("id") Long id);

    /**
     * 悲观锁定源销售出库单行,用于开票/退货下推前串行化——
     * 两个并发下推会争同一行锁,后者阻塞到前者提交后再做查重,防止同一出库单产生两张发票/两张退货。
     * 必须在 @Transactional 内调用,锁保持到事务提交。
     */
    @Select("SELECT id FROM biz_sale_out WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
