package com.group.scm.modules.outbound.mapper;

import com.group.scm.common.mapper.DocumentMapper;
import com.group.scm.modules.outbound.entity.BizOutReq;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BizOutReqMapper extends DocumentMapper<BizOutReq> {
    @Select("SELECT * FROM biz_out_req WHERE id=#{id} AND deleted=0 FOR UPDATE")
    BizOutReq findForUpdate(@Param("id") Long id);
    /**
     * 出库后回写已出数量 + 自动改状态。
     * ⚠️ status 的 CASE 必须写在 out_qty 自增之前——MySQL 的 SET 从左到右执行,
     * 若写在自增之后,out_qty 已是新值,CASE 里再 +qty 等于算两遍,会把"部分出库"误判成"已出库"。
     */
    @Update("""
        UPDATE biz_out_req SET
          status = CASE WHEN out_qty + #{qty} >= total_qty THEN 'OUT' ELSE 'PARTIAL_OUT' END,
          out_qty = out_qty + #{qty},
          updated_at = NOW()
        WHERE id = #{id} AND deleted = 0 AND status IN ('APPROVED','PARTIAL_OUT')
          AND out_qty + #{qty} <= total_qty
    """)
    int addOutQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty);

    /** 按明细重算表头已出数量与状态(与取消出库审批时的重算同口径):0 → 已审批,够数 → 已出库,其余 → 部分出库。 */
    @Update("""
        UPDATE biz_out_req r
        SET out_qty = (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id),
            status = CASE
              WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id) <= 0 THEN 'APPROVED'
              WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id) >= r.total_qty THEN 'OUT'
              ELSE 'PARTIAL_OUT'
            END,
            updated_at = NOW()
        WHERE r.id = #{id} AND r.deleted = 0 AND r.status IN ('APPROVED', 'PARTIAL_OUT', 'OUT')
    """)
    int recomputeProgress(@Param("id") Long id);
}
