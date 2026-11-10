package com.group.scm.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.workflow.entity.WfCancelRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface WfCancelRequestMapper extends BaseMapper<WfCancelRequest> {

    /**
     * 裁决取消审批申请（APPROVED/REJECTED）——条件更新：只有仍是 PENDING 时才生效。
     * 返回 0 表示已被并发处理，调用方必须中止，防止「取消审批→反冲库存」执行两次。
     */
    @Update("""
        UPDATE wf_cancel_request
        SET status = #{to}, approver_comment = #{comment}, approved_at = NOW()
        WHERE id = #{id} AND status = 'PENDING'
    """)
    int decideIfPending(@Param("id") Long id, @Param("to") String to, @Param("comment") String comment);
}
