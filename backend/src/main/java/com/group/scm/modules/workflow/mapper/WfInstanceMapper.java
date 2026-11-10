package com.group.scm.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.workflow.entity.WfInstance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WfInstanceMapper extends BaseMapper<WfInstance> {

    /**
     * 终结实例（APPROVED/REJECTED/WITHDRAWN）——条件更新：只有仍在 PROCESSING 时才生效。
     * 返回 0 表示已被并发处理（双击/两人同审），调用方必须中止并抛错，防止审批回调（库存过账等）执行两次。
     */
    @Update("""
        UPDATE wf_instance
        SET status = #{to}, next_approver_id = NULL, next_approver_role = NULL, finished_at = NOW(6)
        WHERE id = #{id} AND status = 'PROCESSING'
    """)
    int finishIfProcessing(@Param("id") Long id, @Param("to") String to);

    /** 推进到下一节点——条件更新：仍在 PROCESSING 且当前节点未被并发推进时才生效。 */
    @Update("""
        UPDATE wf_instance
        SET current_node = #{node}, next_approver_id = #{approverId}, next_approver_role = #{role}
        WHERE id = #{id} AND status = 'PROCESSING' AND current_node <=> #{fromNode}
    """)
    int advanceIfAt(@Param("id") Long id, @Param("fromNode") String fromNode,
                    @Param("node") String node, @Param("approverId") Long approverId,
                    @Param("role") String role);
    @Select("SELECT * FROM wf_instance WHERE biz_type = #{type} AND biz_id = #{id} AND status = 'PROCESSING' ORDER BY id DESC LIMIT 1")
    WfInstance findActive(@Param("type") String type, @Param("id") Long id);

    @Select("SELECT * FROM wf_instance WHERE biz_type = #{type} AND biz_id = #{id} ORDER BY id DESC LIMIT 1")
    WfInstance findLatest(@Param("type") String type, @Param("id") Long id);

    @Select("SELECT * FROM wf_instance WHERE biz_no = #{bizNo} ORDER BY id DESC LIMIT 1")
    WfInstance findLatestByBizNo(@Param("bizNo") String bizNo);
}
