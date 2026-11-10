package com.group.scm.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id = #{roleId}")
    List<Long> listMenuIds(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int deleteMenus(@Param("roleId") Long roleId);

    @Insert("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (#{roleId}, #{menuId})")
    int insertMenu(@Param("roleId") Long roleId, @Param("menuId") Long menuId);

    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<Long> listUserIdsByRole(@Param("roleId") Long roleId);
}
