package com.group.scm.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.system.entity.SysDocRule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SysDocRuleMapper extends BaseMapper<SysDocRule> {

    /** 原子加 1 并返回当前值（在 service 中先 update 再 select）*/
    @Update("UPDATE sys_doc_rule SET current_no = current_no + 1 WHERE doc_type = #{docType}")
    int incrementNo(@Param("docType") String docType);
}
