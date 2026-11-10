package com.group.scm.modules.purchase.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.purchase.entity.BizPo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BizPoMapper extends DocumentMapper<BizPo> {

    /**
     * 加锁读来源单据的最新状态(计算审查 U05/U07/U08,2026-10-18):下游单据最终过账前调用,
     * 来源若已被取消审批退回草稿/作废,就不许下游过账。FOR UPDATE 是当前读,不受本事务早先快照影响;
     * 锁持有到事务提交,与来源的「取消审批」互斥。必须在 @Transactional 内调用。
     */
    @org.apache.ibatis.annotations.Select("SELECT status FROM biz_po WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    String lockStatus(@Param("id") Long id);

    /**
     * 入库后回写已入库数量 + 自动改状态。
     * ⚠️ status 的 CASE 必须写在 received_qty 自增之前——MySQL 的 SET 从左到右执行,
     * 后面的表达式会读到已被更新的列值;若 status 写在自增之后,received_qty 已是新值,
     * CASE 里再 +qty 等于把本次入库量算两遍,会把"部分入库"误判成"已入库"(踩过的坑)。
     */
    @Update("""
        UPDATE biz_po SET
          status = CASE
            WHEN received_qty + #{qty} >= total_qty THEN 'RECEIVED'
            ELSE 'PARTIAL_RECEIVED'
          END,
          received_qty = received_qty + #{qty},
          updated_at = NOW()
        WHERE id = #{id}
    """)
    int addReceivedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Update("""
        UPDATE biz_po SET invoiced_qty = invoiced_qty + #{qty}, updated_at = NOW()
        WHERE id = #{id}
    """)
    int addInvoicedQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);
}
