package com.group.scm.modules.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.outbound.entity.BizOutLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

public interface BizOutLineMapper extends BaseMapper<BizOutLine> {
    /**
     * 申请行的"已占用"数量 = 有效出库单(草稿/待审/已审)行数量之和 − 退回待再领数量(reissue_qty)。
     * 2026-10-15 退料可选再领:选了"再领"的退料审批后,把这部分让回给申请,可以按原申请再出。
     * 出库建单、申请详情的可出余量、修订下限都用它,改这里三处一起生效。
     */
    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
             - COALESCE((SELECT rl.reissue_qty FROM biz_out_req_line rl WHERE rl.id = #{reqLineId}), 0)
        FROM biz_out_line l
        JOIN biz_out o ON o.id = l.out_id AND o.deleted = 0
        WHERE l.source_req_line_id = #{reqLineId}
          AND o.status IN ('DRAFT', 'PENDING', 'APPROVED')
    """)
    BigDecimal sumActiveOutQtyByReqLine(@Param("reqLineId") Long reqLineId);

    @Select("""
        SELECT COALESCE(SUM(l.qty), 0)
        FROM biz_out_line l
        JOIN biz_out o ON o.id = l.out_id AND o.deleted = 0
        WHERE l.source_req_line_id = #{reqLineId}
          AND o.status = 'APPROVED'
    """)
    BigDecimal sumApprovedOutQtyByReqLine(@Param("reqLineId") Long reqLineId);

    @Select("""
        SELECT l.*
        FROM biz_out_line l
        JOIN biz_out o ON o.id = l.out_id AND o.deleted = 0
        WHERE l.source_req_line_id = #{reqLineId}
          AND o.status = 'APPROVED'
        ORDER BY o.out_date ASC, o.id ASC, l.line_no ASC, l.id ASC
    """)
    List<BizOutLine> findApprovedLinesByReqLine(@Param("reqLineId") Long reqLineId);

    @Select("""
        <script>
        SELECT o.source_req_id reqId, SUM(l.qty) qty FROM biz_out o
        JOIN biz_out_line l ON l.out_id=o.id
        WHERE o.deleted=0 AND o.status='APPROVED' AND o.source_req_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY o.source_req_id
        </script>
    """)
    List<java.util.Map<String,Object>> approvedOutQtyByReqIds(@Param("ids") List<Long> ids);
}
