package com.group.scm.common;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

/**
 * 鉴权辅助。
 */
public class SecurityUtil {

    public static Long uid() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> roles() {
        Object o = StpUtil.getSession().get("roles");
        return o == null ? List.of() : (List<String>) o;
    }

    public static boolean hasRole(String roleCode) {
        return roles().contains(roleCode);
    }

    public static boolean isAdmin() {
        return hasRole("admin");
    }

    public static boolean isAccountAdmin() {
        return isAdmin() || hasRole("account_admin");
    }

    public static boolean hasFinanceAccess() {
        return hasRole("material_accountant") || hasRole("finance");
    }

    public static boolean hasFinanceAccessOrAdmin() {
        return isAdmin() || hasFinanceAccess();
    }

    @SuppressWarnings("unchecked")
    public static List<String> dataScopes() {
        Object o = StpUtil.getSession().get("dataScopes");
        return o == null ? List.of() : (List<String>) o;
    }

    public static boolean hasDataScope(String dataScope) {
        return dataScopes().contains(dataScope);
    }

    public static void requireRole(String roleCode) {
        if (!hasRole(roleCode)) {
            throw new BizException(4003, "这一步需要「" + roleName(roleCode) + "」来办，您的账号没有这个角色。如需开通请联系账套管理员");
        }
    }

    /**
     * 角色编码 → 中文名，只用于报错提示（以前直接把 finance 这类英文编码显示给用户）。
     * 与前端 utils/roleLabel.ts 保持同一套叫法；表里没有的编码原样返回。
     */
    private static final java.util.Map<String, String> ROLE_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("purchaser", "采购"),
            java.util.Map.entry("warehouse", "仓管"),
            java.util.Map.entry("finance", "材料会计"),
            java.util.Map.entry("material_accountant", "材料会计"),
            java.util.Map.entry("finance_manager", "财务部门负责人"),
            java.util.Map.entry("account_admin", "账套管理员"),
            java.util.Map.entry("field_manager", "现场管理员"),
            java.util.Map.entry("deputy_general_manager", "分管副总"),
            java.util.Map.entry("general_manager", "总经理"),
            java.util.Map.entry("dept_manager", "技术部经理"),
            java.util.Map.entry("tech_manager", "技术部经理"),
            java.util.Map.entry("operation_repair", "运维人员"),
            java.util.Map.entry("branch_manager", "网点负责人"),
            java.util.Map.entry("area_manager", "片区经理"),
            java.util.Map.entry("business_leader", "分管副总"),
            java.util.Map.entry("operation_leader", "运营副总"),
            java.util.Map.entry("engineering_leader", "工程副总"),
            java.util.Map.entry("market_leader", "市场副总"),
            java.util.Map.entry("accountant", "材料会计"),
            java.util.Map.entry("cashier", "材料会计"),
            java.util.Map.entry("service_staff", "网点客服"),
            java.util.Map.entry("admin", "管理员"));

    static String roleName(String roleCode) {
        return ROLE_NAMES.getOrDefault(roleCode, "指定岗位");
    }

    public static void requireAnyRole(String... roleCodes) {
        for (String roleCode : roleCodes) {
            if (hasRole(roleCode)) return;
        }
        String names = java.util.Arrays.stream(roleCodes).map(SecurityUtil::roleName).distinct()
                .collect(java.util.stream.Collectors.joining("、"));
        throw new BizException(4003, "您的账号无权执行这一步（需要：" + names + "）。如需开通请联系账套管理员");
    }

    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new BizException(4003, "当前操作仅系统管理员可执行");
        }
    }

    public static void requireAccountAdmin() {
        if (!isAccountAdmin()) {
            throw new BizException(4003, "当前操作需要系统管理员或账套管理员权限");
        }
    }

    public static void requireFinanceAccess() {
        if (!hasFinanceAccess()) {
            throw new BizException(4003, "当前操作需要材料会计权限");
        }
    }

    public static void requireFinanceAccessOrAdmin() {
        if (!hasFinanceAccessOrAdmin()) {
            throw new BizException(4003, "当前操作需要材料会计或系统管理员权限");
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> perms() {
        Object o = StpUtil.getSession().get("perms");
        return o == null ? List.of() : (List<String>) o;
    }

    public static boolean hasPerm(String permCode) {
        return perms().contains(permCode) || isAdmin();
    }

    public static void requirePerm(String permCode) {
        if (!hasPerm(permCode)) {
            throw new BizException(4003, "无权限：" + permCode);
        }
    }

    public static void requireAnyPerm(String... permCodes) {
        for (String permCode : permCodes) {
            if (hasPerm(permCode)) return;
        }
        throw new BizException(4003, "当前角色无权执行该操作");
    }

    /** 当前用户所属部门/组织 ID（登录时缓存到 Session）。 */
    public static Long orgId() {
        Object o = StpUtil.getSession().get("orgId");
        return o == null ? null : ((Number) o).longValue();
    }
}
