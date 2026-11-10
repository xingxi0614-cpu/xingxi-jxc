package com.group.scm.modules.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.outbound.entity.BizReturnLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

public interface BizReturnLineMapper extends BaseMapper<BizReturnLine> {
    @Select("SELECT COALESCE(SUM(l.qty),0) FROM biz_return_line l JOIN biz_return r ON r.id=l.return_id WHERE l.source_req_line_id=#{id} AND r.deleted=0 AND r.status='APPROVED' AND r.reissue=1")
    BigDecimal sumApprovedReissueQtyByReqLine(@Param("id") Long id);
    /** 当前读真实已过账净额，包含反审，避免使用展示单价或草稿金额。 */
    @Select("""
        SELECT COALESCE(SUM(t.qty),0) qty, COALESCE(SUM(t.amount),0) amount
        FROM inv_transaction t JOIN biz_return_line l ON l.id=t.biz_line_id
        WHERE t.biz_doc_type='RETURN' AND l.source_out_line_id=#{outLineId}
        FOR UPDATE
    """)
    java.util.Map<String,Object> postedReturnCost(@Param("outLineId") Long outLineId);

    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
        FROM biz_return_line l
        JOIN biz_return r ON r.id = l.return_id AND r.deleted = 0
        WHERE l.source_out_line_id = #{outLineId}
          AND r.status IN ('DRAFT', 'PENDING', 'APPROVED')
    """)
    BigDecimal sumActiveReturnQtyByOutLine(@Param("outLineId") Long outLineId);

    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
        FROM biz_return_line l
        JOIN biz_return r ON r.id = l.return_id AND r.deleted = 0
        WHERE l.source_req_line_id = #{reqLineId}
          AND r.status = 'APPROVED'
    """)
    BigDecimal sumApprovedReturnQtyByReqLine(@Param("reqLineId") Long reqLineId);

    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
        FROM biz_return_line l
        JOIN biz_return r ON r.id = l.return_id AND r.deleted = 0
        WHERE l.source_req_line_id = #{reqLineId}
          AND r.status IN ('DRAFT', 'PENDING', 'APPROVED')
    """)
    BigDecimal sumActiveReturnQtyByReqLine(@Param("reqLineId") Long reqLineId);

    /** 按出库申请批量汇总已审批退料数量(列表页一次查完,避免逐张查库)。 */
    @Select("""
        <script>
        SELECT rl.out_req_id AS reqId, COALESCE(SUM(l.qty), 0) AS qty
        FROM biz_return_line l
        JOIN biz_return r ON r.id = l.return_id AND r.deleted = 0 AND r.status = 'APPROVED'
        JOIN biz_out_req_line rl ON rl.id = l.source_req_line_id
        WHERE rl.out_req_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY rl.out_req_id
        </script>
    """)
    java.util.List<java.util.Map<String, Object>> approvedReturnQtyByReqIds(@Param("ids") java.util.List<Long> ids);
}
