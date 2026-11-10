package com.group.scm.modules.sales.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.sales.entity.BizSo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BizSoMapper extends DocumentMapper<BizSo> {

    /**
     * 加锁读来源单据的最新状态(计算审查 U05/U07/U08,2026-10-18):下游单据最终过账前调用,
     * 来源若已被取消审批退回草稿/作废,就不许下游过账。FOR UPDATE 是当前读,不受本事务早先快照影响;
     * 锁持有到事务提交,与来源的「取消审批」互斥。必须在 @Transactional 内调用。
     */
    @org.apache.ibatis.annotations.Select("SELECT status FROM biz_so WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    String lockStatus(@Param("id") Long id);

    /**
     * 出库后回写已出库数量 + 自动改状态。
     * ⚠️ status 的 CASE 必须写在 out_qty 自增之前——MySQL 的 SET 从左到右执行,
     * 后面表达式会读到已更新的列值,否则本次出库量会算两遍(踩过的坑)。
     */
    @Update("""
        UPDATE biz_so SET
          status = CASE
            WHEN out_qty + #{qty} >= total_qty THEN 'OUT'
            ELSE 'PARTIAL_OUT'
          END,
          out_qty = out_qty + #{qty},
          updated_at = NOW()
        WHERE id = #{id}
    """)
    int addOutQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    @Update("""
        UPDATE biz_so SET invoice_qty = invoice_qty + #{qty}, updated_at = NOW()
        WHERE id = #{id}
    """)
    int addInvoiceQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);
}
