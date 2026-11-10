package com.group.scm.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.workflow.entity.WfTemplate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WfTemplateMapper extends BaseMapper<WfTemplate> {
    @Select("SELECT * FROM wf_template WHERE biz_type = #{type} AND status = 1 ORDER BY sort")
    List<WfTemplate> findByBizType(@Param("type") String type);

    @Select("""
            SELECT *
            FROM wf_template
            WHERE biz_type = #{type}
              AND status = 1
              AND approval_mode = #{approvalMode}
            ORDER BY sort
            """)
    List<WfTemplate> findByBizTypeAndMode(@Param("type") String type,
                                          @Param("approvalMode") String approvalMode);
}
