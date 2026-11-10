package com.group.scm.modules.system.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SystemInitializationService {

    public static final String CONFIRM_TEXT = "\u786e\u8ba4\u6b63\u5f0f\u542f\u7528";

    private final JdbcTemplate jdbcTemplate;

    @Value("${scm.initialization.enabled:false}")
    private boolean initializationEnabled;

    public Map<String, Object> preview() {
        SecurityUtil.requireAdmin();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("confirmText", CONFIRM_TEXT);
        summary.put("enabled", initializationEnabled);
        summary.put("formalInitialized", isFormalInitialized());
        summary.put("willKeep", List.of(
                "admin 管理员账号、当前密码和管理员角色",
                "系统菜单、系统参数、单据编码规则、审批流程模板和系统更新说明",
                "采购、仓管、材料会计、账套管理员等启用岗位角色；具体权限后续由管理员重新分配"
        ));
        summary.put("willClear", List.of(
                "除 admin 外的所有用户，以及用户与角色的分配关系",
                "组织/账套下级数据；仅保留一个可编辑的默认账套节点",
                "供应商、物料、仓库/库位、工程项目等基础资料",
                "采购、入库、发票、出库申请、材料出库等全部业务单据",
                "库存余额、出入库流水、单据链路、审批流程、待办事项和操作日志"
        ));
        summary.put("counts", collectCounts());
        return summary;
    }

    @Transactional
    public Map<String, Object> formalReset(String confirmText) {
        SecurityUtil.requireAdmin();
        if (!initializationEnabled) {
            throw new BizException(4003, "正式启用初始化当前未开启。请仅在计划清空账套时设置 SCM_INITIALIZATION_ENABLED=true。");
        }
        if (!CONFIRM_TEXT.equals(confirmText == null ? "" : confirmText.trim())) {
            throw new BizException(4000, "请输入确认语句：" + CONFIRM_TEXT);
        }

        Long adminUserId = jdbcTemplate.query("""
                SELECT id
                FROM sys_user
                WHERE username = 'admin' AND deleted = 0
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong("id") : null);
        if (adminUserId == null) {
            throw new BizException(4000, "未找到 admin 管理员账号，无法继续初始化");
        }

        Long adminRoleId = ensureRole("admin", "\u7cfb\u7edf\u7ba1\u7406\u5458", "ALL", "账套、用户、角色、权限和基础资料维护");
        Long purchaserRoleId = ensureRole("purchaser", "\u91c7\u8d2d", "ACCOUNT_SET", "权限由管理员重新分配");
        Long warehouseRoleId = ensureRole("warehouse", "\u4ed3\u7ba1", "ACCOUNT_SET", "权限由管理员重新分配");
        Long materialAccountantRoleId = ensureRole("material_accountant", "材料会计", "ACCOUNT_SET", "承接原财务审核、成本参考和库存期间权限");
        Long accountAdminRoleId = ensureRole("account_admin", "账套管理员", "ACCOUNT_SET", "本账套内账号、组织和基础资料维护，可叠加给材料会计等人员");
        List<Long> approvalRoleIds = ensureApprovalRoles();
        disableRetiredRoles();

        Map<String, Object> before = collectCounts();

        clearRows("notif_read");
        clearRows("notif");
        clearRows("wf_cancel_request");
        clearRows("wf_record");
        clearRows("wf_instance");
        clearRows("doc_relation");
        clearRows("inv_transaction");
        clearRows("inv_balance");
        clearRows("inv_cost_pool");
        clearRows("biz_transfer_line");
        clearRows("biz_transfer");
        clearRows("biz_loan_line");
        clearRows("biz_loan");
        clearRows("biz_return_line");
        clearRows("biz_return");
        clearRows("biz_out_line");
        clearRows("biz_out");
        clearRows("biz_req_fulfillment_event");
        clearRows("biz_out_req_line");
        clearRows("biz_out_req");
        clearRows("biz_invoice_line");
        clearRows("biz_invoice");
        clearRows("biz_recv_line");
        clearRows("biz_recv");
        clearRows("biz_po_line");
        clearRows("biz_po");
        clearRows("biz_pr_line");
        clearRows("biz_pr");
        clearRows("md_warehouse_loc");
        clearRows("md_warehouse");
        clearRows("md_material");
        clearRows("md_material_cat");
        clearRows("md_supplier");
        clearRows("md_project");
        clearRows("sys_log");

        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id <> ?", adminUserId);
        jdbcTemplate.update("DELETE FROM sys_org WHERE id <> 1");
        jdbcTemplate.update("""
                INSERT INTO sys_org (id, parent_id, code, name, type, sort, status, deleted)
                VALUES (1, 0, 'DEFAULT', '\u9ed8\u8ba4\u8d26\u5957\uff08\u5f85\u914d\u7f6e\uff09', 'COMPANY', 1, 1, 0)
                ON DUPLICATE KEY UPDATE
                  parent_id = 0,
                  code = VALUES(code),
                  name = VALUES(name),
                  type = VALUES(type),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
        jdbcTemplate.update("""
                UPDATE sys_user
                SET org_id = 1,
                    status = 1,
                    fail_count = 0,
                    locked_until = NULL,
                    deleted = 0
                WHERE id = ?
                """, adminUserId);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", adminUserId, adminRoleId);

        jdbcTemplate.update("DELETE FROM sys_role_menu");
        for (long menuId : adminMenuIds()) {
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", adminRoleId, menuId);
        }
        assignRoleMenus(purchaserRoleId,
                100, 101, 102,
                200, 202, 203,
                400, 401,
                800, 801,
                900, 901,
                1200, 1201);
        assignRoleMenus(warehouseRoleId,
                100, 101, 102,
                200, 202,
                300, 301, 302,
                400, 401, 402, 403, 404,
                500, 501, 502, 503,
                900, 903,
                1200, 1201);
        assignRoleMenus(materialAccountantRoleId,
                100, 101, 102,
                200, 202, 203,
                300, 301, 302,
                400, 401, 402, 403, 404,
                500, 501, 502, 503,
                600, 601, 602, 603,
                700, 701, 702, 703,
                800, 801,
                900, 901, 902, 903, 904, 905, 906,
                1200, 1201);
        assignRoleMenus(accountAdminRoleId,
                400, 401,
                100, 101, 102,
                1000, 1001, 1003,
                1100, 1101, 1102, 1103, 1106,
                1200, 1201);
        for (Long approvalRoleId : approvalRoleIds) {
            assignRoleMenus(approvalRoleId, approvalMenuIds());
        }

        jdbcTemplate.update("UPDATE sys_doc_rule SET current_no = 0");
        jdbcTemplate.update("DELETE FROM sys_doc_sequence");
        jdbcTemplate.update("""
                INSERT INTO sys_param (param_key, param_value, remark)
                VALUES ('system.formal_initialized', 'true', '正式启用初始化已完成，系统启动时不再恢复演示数据')
                ON DUPLICATE KEY UPDATE
                  param_value = VALUES(param_value),
                  remark = VALUES(remark)
                """);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("before", before);
        result.put("after", collectCounts());
        result.put("keptAdmin", "admin");
        result.put("message", "正式启用初始化已完成");
        return result;
    }

    private Long ensureRole(String code, String name, String dataScope, String remark) {
        jdbcTemplate.update("""
                INSERT INTO sys_role (code, name, data_scope, remark, status, deleted)
                VALUES (?, ?, ?, ?, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  data_scope = VALUES(data_scope),
                  remark = VALUES(remark),
                  status = 1,
                  deleted = 0
                """, code, name, dataScope, remark);
        return jdbcTemplate.queryForObject("""
                SELECT id
                FROM sys_role
                WHERE code = ? AND deleted = 0
                LIMIT 1
                """, Long.class, code);
    }

    private List<Long> ensureApprovalRoles() {
        return List.of(
                ensureRole("material_accountant", "材料会计", "ACCOUNT_SET", "采购订单材料会计审批"),
                ensureRole("finance_manager", "财务部门负责人", "ACCOUNT_SET", "采购订单财务部门负责人审批"),
                ensureRole("deputy_general_manager", "分管副总", "ACCOUNT_SET", "采购订单分管副总审批"),
                ensureRole("general_manager", "总经理", "ACCOUNT_SET", "采购订单总经理审批"),
                ensureRole("field_manager", "现场管理员", "ACCOUNT_SET", "工程类出库申请经办/审批岗位"),
                ensureRole("tech_manager", "技术部经理", "ACCOUNT_SET", "采购、工程、仓储相关部门负责人审批"),
                ensureRole("service_staff", "网点客服", "ACCOUNT_SET", "运营/增值销售类出库申请经办岗位"),
                ensureRole("operation_repair", "运维人员", "ACCOUNT_SET", "运营维修类材料申请经办岗位"),
                ensureRole("branch_manager", "网点负责人", "ACCOUNT_SET", "运营/增值销售类出库申请网点负责人审批"),
                ensureRole("area_manager", "片区经理", "ACCOUNT_SET", "多个网点合并后的片区审批岗位"),
                ensureRole("operation_leader", "运营副总", "ACCOUNT_SET", "运营类业务分管领导审批"),
                ensureRole("engineering_leader", "工程副总", "ACCOUNT_SET", "工程类业务分管领导审批"),
                ensureRole("market_leader", "市场副总", "ACCOUNT_SET", "市场/增值销售类业务分管领导审批")
        );
    }

    private void disableRetiredRoles() {
        jdbcTemplate.update("""
                UPDATE sys_role
                SET status = 0,
                    remark = CONCAT(COALESCE(remark, ''), '；已按最新岗位口径合并停用')
                WHERE code IN ('finance', 'dept_manager', 'business_leader')
                  AND deleted = 0
                """);
    }

    private long[] approvalMenuIds() {
        return new long[] {
                100, 101, 102,
                200, 202, 203,
                300, 301, 302,
                400, 401, 402,
                500, 501, 502, 503,
                1200, 1201, 1202
        };
    }

    private List<Long> adminMenuIds() {
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM sys_menu
                WHERE deleted = 0 AND status = 1
                """, Long.class);
    }

    private void assignRoleMenus(Long roleId, long... menuIds) {
        if (roleId == null) return;
        for (long menuId : menuIds) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                    SELECT ?, id
                    FROM sys_menu
                    WHERE id = ? AND deleted = 0 AND status = 1
                    """, roleId, menuId);
        }
    }

    private void clearRows(String table) {
        if (!tableExists(table)) return;
        jdbcTemplate.update("DELETE FROM " + table);
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private boolean isFormalInitialized() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_param
                WHERE param_key = 'system.formal_initialized'
                  AND LOWER(param_value) = 'true'
                """, Integer.class);
        return count != null && count > 0;
    }

    private Map<String, Object> collectCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("users", count("sys_user", "deleted = 0"));
        counts.put("orgs", count("sys_org", "deleted = 0"));
        counts.put("suppliers", count("md_supplier", "deleted = 0"));
        counts.put("materials", count("md_material", "deleted = 0"));
        counts.put("materialCategories", count("md_material_cat", "deleted = 0"));
        counts.put("warehouses", count("md_warehouse", "deleted = 0"));
        counts.put("projects", count("md_project", "deleted = 0"));
        counts.put("businessDocs", countBusinessDocs());
        counts.put("inventoryBalances", count("inv_balance", "1 = 1"));
        counts.put("inventoryTransactions", count("inv_transaction", "1 = 1"));
        counts.put("workflowItems", count("wf_instance", "1 = 1"));
        counts.put("todos", count("notif", "1 = 1"));
        return counts;
    }

    private Integer count(String table, String condition) {
        if (!tableExists(table)) return 0;
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
    }

    private Integer countBusinessDocs() {
        int total = 0;
        for (String table : List.of("biz_pr", "biz_po", "biz_recv", "biz_invoice", "biz_out_req", "biz_out", "biz_return", "biz_loan", "biz_transfer")) {
            Integer count = count(table, "deleted = 0");
            total += count == null ? 0 : count;
        }
        return total;
    }
}
