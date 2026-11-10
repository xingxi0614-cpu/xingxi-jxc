package com.group.scm.modules.attachment.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;

/** 附件继承母单的业务角色；未经分类的财务影像不能作为数量角色的脱敏旁路。 */
final class AttachmentAccess {
    private AttachmentAccess() { }

    static void require(String type, boolean readContent) {
        switch (type) {
            case "INV" -> SecurityUtil.requireFinanceAccessOrAdmin();
            case "PO" -> SecurityUtil.requireAnyRole("purchaser", "warehouse", "finance", "admin",
                    "material_accountant", "finance_manager", "deputy_general_manager", "general_manager");
            case "RECV" -> SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin",
                    "tech_manager", "purchaser");
            case "PP" -> SecurityUtil.requireAnyRole("purchaser", "account_admin", "admin");
            case "LR" -> SecurityUtil.requireAnyRole("purchaser", "warehouse", "material_accountant", "account_admin", "admin");
            case "OREQ" -> SecurityUtil.requireAnyRole("purchaser", "warehouse", "material_accountant", "finance", "admin",
                    "field_manager", "tech_manager", "service_staff", "operation_repair", "branch_manager",
                    "area_manager", "operation_leader", "engineering_leader", "market_leader", "account_admin");
            case "OUT" -> SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin",
                    "tech_manager", "engineering_leader", "purchaser", "field_manager", "service_staff");
            case "RETURN", "LOAN", "TRANSFER" -> SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance", "admin");
            case "STOCKTAKE" -> SecurityUtil.requireAnyRole("warehouse", "material_accountant", "finance_manager", "account_admin", "admin");
            default -> throw new BizException("不支持的单据类型：" + type);
        }
        if (!readContent || SecurityUtil.hasFinanceAccessOrAdmin()) return;
        boolean costDocument = switch (type) {
            case "PO", "RECV", "OUT", "RETURN", "LOAN", "TRANSFER", "STOCKTAKE" -> true;
            default -> false;
        };
        // 采购可读取其订单/收货的合同；财务和管理员可读所有金额附件。
        boolean purchaseCost = ("PO".equals(type) || "RECV".equals(type)) && SecurityUtil.hasRole("purchaser");
        boolean managerCost = "PO".equals(type) && (SecurityUtil.hasRole("finance_manager")
                || SecurityUtil.hasRole("deputy_general_manager") || SecurityUtil.hasRole("general_manager"));
        boolean stocktakeAdmin = "STOCKTAKE".equals(type)
                && (SecurityUtil.isAccountAdmin() || SecurityUtil.hasRole("finance_manager"));
        // 仓管经手的实物单据：验收照片/送货单是仓管自己收、自己传的，纸面价格本就在他手上，
        // 挡住只会让他看不到自己传的附件(2026-09-30 定)。采购订单的合同附件仍不对仓管开放。
        boolean warehouseDoc = SecurityUtil.hasRole("warehouse") && switch (type) {
            case "RECV", "OUT", "TRANSFER", "STOCKTAKE", "RETURN", "LOAN" -> true;
            default -> false;
        };
        if (costDocument && !purchaseCost && !managerCost && !stocktakeAdmin && !warehouseDoc)
            throw new BizException(4003, "附件可能含金额信息，当前角色仅可查看单据数量");
    }
}
