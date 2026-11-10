package com.group.scm.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.inventory.entity.InvCostPool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InvCostPoolMapper extends BaseMapper<InvCostPool> {

    @Select("SELECT * FROM inv_cost_pool WHERE org_id = #{orgId} AND material_id = #{matId} FOR UPDATE")
    InvCostPool findForUpdate(@Param("orgId") Long orgId, @Param("matId") Long matId);
}
