package com.group.scm.common.service;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import java.util.Set;
/** 后端单据归属边界；账套可见和操作角色仍由各入口校验。 */
public final class DocumentAccess {
    private DocumentAccess() { }
    /**
     * 账套管理员可清理本账套他人的草稿/已驳回/已作废单据（这些状态未动库存、未过账）。
     * 2026-09-28 定：账套管理员常需要清理网点负责人建的销售出库草稿，
     * 只许制单人删会打断这个做法；普通同事之间仍不能互删。
     * 已登记借料等已动库存的状态不在此列，仍须制单人或系统管理员。
     * 调用方必须已校验单据属于当前可见账套（requireExisting / assertOrgVisible）。
     */
    private static final Set<String> ACCOUNT_ADMIN_DELETABLE = Set.of("DRAFT", "REJECTED", "CANCELLED");

    public static void requireDeleteOwner(Long createdBy, String status) {
        if (SecurityUtil.isAdmin()) return;
        if (ACCOUNT_ADMIN_DELETABLE.contains(status) && SecurityUtil.isAccountAdmin()) return;
        if (createdBy == null || !createdBy.equals(SecurityUtil.uid()))
            throw new BizException(4003, "只有制单人、账套管理员或系统管理员可以删除该单据");
    }
}
