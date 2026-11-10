package com.group.scm.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.system.entity.SysRole;
import com.group.scm.modules.system.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

/** 账套管理员可委派本账套业务岗位，集团/全局岗位只能由系统管理员委派。 */
@Service
@RequiredArgsConstructor
public class RoleAssignmentService {
    private final SysRoleMapper mapper;
    private static final Set<String> LOCAL_SCOPES = Set.of("ACCOUNT_SET", "COMPANY", "DEPT", "SELF");

    public List<SysRole> assignableRoles() {
        return mapper.selectList(new QueryWrapper<SysRole>().eq("status", 1).orderByAsc("id"))
                .stream().filter(this::canAssign).toList();
    }

    private boolean canAssign(SysRole role) {
        if (SecurityUtil.isAdmin()) return true;
        if ("admin".equals(role.getCode()) || !LOCAL_SCOPES.contains(role.getDataScope() == null ? "" : role.getDataScope())) return false;
        int ceiling = SecurityUtil.dataScopes().stream().mapToInt(RoleAssignmentService::scopeRank).max().orElse(0);
        return scopeRank(role.getDataScope()) <= Math.min(ceiling, 2);
    }

    private static int scopeRank(String scope) {
        return switch (scope) {
            case "ALL", "GROUP" -> 3;
            case "ACCOUNT_SET", "COMPANY" -> 2;
            case "DEPT" -> 1;
            default -> 0;
        };
    }

    public void validate(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        if (roleIds.stream().anyMatch(java.util.Objects::isNull)) throw new BizException("角色不能为空");
        var ids = Set.copyOf(roleIds);
        var roles = mapper.selectList(new QueryWrapper<SysRole>().in("id", ids).eq("status", 1));
        if (roles.size() != ids.size()) throw new BizException("岗位角色不存在或已停用，请刷新后重试");
        if (roles.stream().anyMatch(r -> !canAssign(r)))
            throw new BizException(4003, "不能分配超出当前管理范围的角色；集团或全局角色需由系统管理员分配");
    }
}
