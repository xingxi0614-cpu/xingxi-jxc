package com.group.scm.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    SysUser findByUsername(@Param("username") String username);

    @Select("SELECT r.code FROM sys_role r " +
            "JOIN sys_user_role ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1")
    List<String> listRoleCodes(@Param("userId") Long userId);

    @Select("SELECT DISTINCT r.data_scope FROM sys_role r " +
            "JOIN sys_user_role ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1 AND r.data_scope IS NOT NULL")
    List<String> listDataScopes(@Param("userId") Long userId);

    @Select("SELECT ur.role_id FROM sys_user_role ur " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1")
    List<Long> listRoleIds(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteRoles(@Param("userId") Long userId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Select("SELECT DISTINCT m.perm_code FROM sys_menu m " +
            "JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "JOIN sys_user_role ur ON ur.role_id = rm.role_id " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1 " +
            "AND m.deleted = 0 AND m.status = 1 AND m.perm_code IS NOT NULL AND m.perm_code <> ''")
    List<String> listPermCodes(@Param("userId") Long userId);
}
