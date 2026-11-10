package com.group.scm.modules.auth.dto;

import lombok.Data;

import java.util.List;

/**
 * 当前用户信息：登录后前端首屏调用 GET /auth/me 拉取
 * <ul>
 *   <li>roles: 角色编码列表（用于 hasRole）</li>
 *   <li>perms: 权限码列表（用于 v-permission）</li>
 *   <li>menus: 树形菜单（用于动态生成菜单和路由）</li>
 * </ul>
 */
@Data
public class UserInfoVO {
    private Long id;
    private String username;
    private String name;
    private String avatar;
    private String phone;
    private String email;
    private Long orgId;
    private String orgName;
    /** 向上追溯到公司层级的名称，用于页面右上角展示 */
    private String companyName;
    private List<String> roles;
    private List<String> perms;
    private List<MenuTreeVO> menus;
}
