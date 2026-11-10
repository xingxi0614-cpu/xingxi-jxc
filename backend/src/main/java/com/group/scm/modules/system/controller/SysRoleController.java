package com.group.scm.modules.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.group.scm.modules.system.entity.SysRole;
import com.group.scm.modules.system.mapper.SysRoleMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.group.scm.common.BizException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色与权限。
 *
 * ⚠️ 【角色是全系统共用的,不分公司】sys_role 表没有 org_id ——
 * 改了「采购员」的权限,所有公司的采购员都跟着变。所以只有系统管理员能动角色,
 * 账套管理员只能看。以后若要做成"每家公司自己一套角色",需要给 sys_role 加租户维度,
 * 那是架构级改动,别在这里悄悄放宽权限。
 *
 * 另:改权限/改数据范围后会把该角色下的人踢下线,强制重新登录 —— 因为权限是登录时
 * 算好存在会话里的,不踢下线改了也不生效。
 */
@Tag(name = "角色与权限")
@RestController
@RequestMapping("/sys/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final com.group.scm.modules.system.service.RoleAssignmentService roleAssignment;
    private final SysRoleMapper roleMapper;
    private final com.group.scm.modules.system.service.OnlineSessionService onlineSessionService;

    @GetMapping
    public Result<List<SysRole>> list() {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(roleAssignment.assignableRoles());
    }

    /**
     * 数据范围可选值 —— 决定这个角色能看到哪些公司/部门的数据,给错等于数据泄露。
     * 与 OrgScopeService.visibleOrgIds() 里认的值严格一致,改这里必须同步改那边。
     */
    private static final Set<String> DATA_SCOPES = Set.of("ALL", "GROUP", "ACCOUNT_SET", "COMPANY", "DEPT", "SELF");
    /** 角色编码规则:小写字母/数字/下划线。编码是权限判断的依据,建好之后不允许再改。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");

    @OpLog(module = "角色权限", action = "新建角色")
    @PostMapping
    public Result<SysRole> create(@RequestBody SysRole dto) {
        SecurityUtil.requireAdmin();
        String code = dto.getCode() == null ? "" : dto.getCode().trim().toLowerCase();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(4000, "角色编码只能用小写字母、数字、下划线,以字母开头,2~40 位");
        }
        if (roleMapper.selectCount(new QueryWrapper<SysRole>().eq("code", code).eq("deleted", 0)) > 0) {
            throw new BizException(4000, "角色编码「" + code + "」已存在");
        }
        validateName(dto.getName());
        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(dto.getName().trim());
        role.setDataScope(normalizeScope(dto.getDataScope()));
        role.setRemark(dto.getRemark());
        role.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        roleMapper.insert(role);
        return Result.ok(role);
    }

    /** 改名/改数据范围/停用。**编码不给改**——它是权限判断的依据,改了会让已有授权全部失效。 */
    @OpLog(module = "角色权限", action = "修改角色")
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody SysRole dto) {
        SecurityUtil.requireAdmin();
        SysRole role = requireRole(id);
        if ("admin".equals(role.getCode())) {
            throw new BizException(4030, "系统管理员角色不允许修改(改了会把自己锁在门外)");
        }
        validateName(dto.getName());
        role.setName(dto.getName().trim());
        role.setDataScope(normalizeScope(dto.getDataScope()));
        role.setRemark(dto.getRemark());
        if (dto.getStatus() != null) role.setStatus(dto.getStatus());
        roleMapper.updateById(role);
        // 数据范围变了要立刻生效,否则这个人还揣着旧范围在看数据
        roleMapper.listUserIdsByRole(id).forEach(this::logoutUserQuietly);
        return Result.ok();
    }

    /** 删除角色。已经分配给人的不许删——先把人挪走,免得有人一觉醒来什么都点不了。 */
    @OpLog(module = "角色权限", action = "删除角色")
    @DeleteMapping("/{id}")
    public Result<?> remove(@PathVariable Long id) {
        SecurityUtil.requireAdmin();
        SysRole role = requireRole(id);
        if ("admin".equals(role.getCode())) throw new BizException(4030, "系统管理员角色不允许删除");
        int used = roleMapper.listUserIdsByRole(id).size();
        if (used > 0) throw new BizException(4000, "还有 " + used + " 个账号在用这个角色,请先在「用户管理」里改掉他们的角色");
        roleMapper.deleteMenus(id);
        // deleted 写本行 id 不写 1，理由同删除用户（uk_code(code, deleted) 会撞键），见迁移 20261001_001
        roleMapper.update(null, new UpdateWrapper<SysRole>().eq("id", id).setSql("deleted = id"));
        return Result.ok();
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null || (role.getDeleted() != null && role.getDeleted() == 1)) {
            throw new BizException(4040, "角色不存在");
        }
        return role;
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty()) throw new BizException(4000, "角色名称不能为空");
        if (name.trim().length() > 40) throw new BizException(4000, "角色名称不能超过 40 字");
    }

    private String normalizeScope(String scope) {
        String s = scope == null ? "" : scope.trim().toUpperCase();
        if (!DATA_SCOPES.contains(s)) return "ACCOUNT_SET";   // 默认只看本公司,宁可少给
        if ("ALL".equals(s) && !SecurityUtil.isAdmin()) return "ACCOUNT_SET";
        return s;
    }

    @GetMapping("/{id}/menus")
    public Result<List<Long>> menus(@PathVariable Long id) {
        SecurityUtil.requireAdmin();
        return Result.ok(roleMapper.listMenuIds(id));
    }

    @OpLog(module = "角色权限", action = "分配菜单权限")
    @PutMapping("/{id}/menus")
    public Result<?> saveMenus(@PathVariable Long id, @RequestBody RoleMenusDTO dto) {
        SecurityUtil.requireAdmin();
        SysRole role = roleMapper.selectById(id);
        if (role == null || (role.getDeleted() != null && role.getDeleted() == 1)) {
            return Result.fail(404, "角色不存在");
        }
        roleMapper.deleteMenus(id);
        if (dto != null && dto.menuIds != null) {
            for (Long menuId : new LinkedHashSet<>(dto.menuIds)) {
                if (menuId == null) continue;
                roleMapper.insertMenu(id, menuId);
            }
        }
        roleMapper.listUserIdsByRole(id).forEach(this::logoutUserQuietly);
        return Result.ok();
    }

    private void logoutUserQuietly(Long userId) {
        try {
            if (userId != null) StpUtil.logout(userId);
        } catch (Exception ignored) {
        }
        // 改了角色权限:在线登记一并清掉,日报免密票也随之作废(安全审查 S04,与用户管理同一写法)
        onlineSessionService.removeUser(userId);
    }

    public static class RoleMenusDTO {
        public List<Long> menuIds;
    }
}
