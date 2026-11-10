package com.group.scm.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.system.entity.SysMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysMenuMapper extends BaseMapper<SysMenu> {

    @Select("SELECT DISTINCT m.* FROM sys_menu m " +
            "JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "JOIN sys_user_role ur ON ur.role_id = rm.role_id " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1 " +
            "AND m.deleted = 0 AND m.status = 1 AND m.type IN ('DIR','MENU') " +
            "ORDER BY m.parent_id, m.sort")
    List<SysMenu> listByUser(@Param("userId") Long userId);
}
