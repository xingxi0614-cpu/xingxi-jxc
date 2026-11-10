package com.group.scm.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.workflow.entity.WfRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WfRecordMapper extends BaseMapper<WfRecord> {
    @Select("SELECT * FROM wf_record WHERE biz_no = #{bizNo} ORDER BY id")
    List<WfRecord> findByBizNo(@Param("bizNo") String bizNo);
}
