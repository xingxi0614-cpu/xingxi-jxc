package com.group.scm.modules.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface BizOutReqLineMapper extends BaseMapper<BizOutReqLine> {
    @Update("UPDATE biz_out_req_line SET out_qty = out_qty + #{qty} WHERE id = #{id} AND out_req_id = #{reqId} AND out_qty + #{qty} <= qty")
    int addOutQty(@Param("id") Long id, @Param("qty") java.math.BigDecimal qty, @Param("reqId") Long reqId);

    /**
     * 退料选了"再领":把退回数量从已出数量里让回,并累计到 reissue_qty。
     * 条件更新(CAS):out_qty 不够扣时影响 0 行,调用方据此报错,两张退料并发审批不会超减。
     */
    @Update("""
        UPDATE biz_out_req_line
        SET out_qty = out_qty - #{qty}, reissue_qty = reissue_qty + #{qty}
        WHERE id = #{id} AND out_qty - #{qty} >= 0
    """)
    int releaseForReissue(@Param("id") Long id, @Param("qty") BigDecimal qty);

    @Select("SELECT COALESCE(MAX(line_no), 0) FROM biz_out_req_line WHERE out_req_id = #{reqId}")
    int maxLineNo(@Param("reqId") Long reqId);

    @Select("SELECT COALESCE(SUM(qty), 0) FROM biz_out_req_line WHERE out_req_id = #{reqId}")
    BigDecimal sumQtyByReqId(@Param("reqId") Long reqId);
}
