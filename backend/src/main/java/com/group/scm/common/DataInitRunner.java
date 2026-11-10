package com.group.scm.common;

import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysUserMapper;
import com.group.scm.modules.master.service.MaterialMasterImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时数据初始化：
 *  1. 把 02-seed.sql 里的占位 BCrypt 哈希替换成真正可用的密码哈希。
 *     —— BCrypt 哈希包含随机盐，无法在 SQL 里硬编码，必须在 Java 里生成。
 *  2. 此 Runner 只在用户的 password 字段仍是种子脚本占位哈希时生效。
 *     生产用户修改密码后，服务重启不能把密码重置回演示默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements CommandLineRunner {

    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MaterialMasterImportService materialImportService;
    private final BCryptPasswordEncoder pwdEncoder = new BCryptPasswordEncoder();

    /** 种子里的 6 个样例业务账号(采购/仓管/材料会计各两个)。演示账号 demo 单独处理,admin 见 initAdminPassword()。 */
    private static final List<String> SAMPLE_USERS = List.of("zhangsan", "liujie", "lisi", "sunliu", "wangfang", "zhouwei");

    /** 占位哈希前缀（与 02-seed.sql 中一致） */
    private static final String PLACEHOLDER_PREFIX = "$2a$10$7JBUKxhcM5wmWk7rPVTbQO";
    private static final String PLACEHOLDER_PREFIX_ADMIN = "$2a$10$N9qo8uLOickgx2ZMRZoMye";

    @Override
    public void run(String... args) {
        ensureNotifReadTable();
        ensureReleaseNoteTable();
        ensurePeriodCloseTable();
        ensureDocSequenceTable();
        syncCommercialRoleModel();
        initSampleAccounts(System::getenv);
        initAdminPassword();
    }

    /**
     * 样例/演示账号:仅当密码还是种子里的占位哈希(全新安装)时初始化;已改过密码的既有环境跳过(无影响)。
     *
     * 【为什么不写死密码】开源后代码人人可见,写死的密码等于公开密码。所以:
     *  ① 6 个样例业务账号默认【停用】,密码取 SCM_SAMPLE_PASSWORD,没配就随机(不打印——停用的号没人要用)。
     *     只有设 SCM_SAMPLE_ACCOUNTS_ENABLED=true 才启用(本地测试 / E2E 用)。
     *  ② 演示账号 demo 只能看演示账套,保持启用;密码取 SCM_DEMO_PASSWORD,没配就随机并在启动日志打印一次。
     * 环境变量经 env 传入,包级可见便于单测。
     */
    void initSampleAccounts(java.util.function.Function<String, String> env) {
        boolean enableSamples = "true".equalsIgnoreCase(env.apply("SCM_SAMPLE_ACCOUNTS_ENABLED"));
        String samplePwd = env.apply("SCM_SAMPLE_PASSWORD");
        int fixed = 0;
        for (String name : SAMPLE_USERS) {
            SysUser u = userMapper.findByUsername(name);
            if (u == null || !isPlaceholder(u)) continue;
            u.setPassword(pwdEncoder.encode(samplePwd == null || samplePwd.isBlank() ? randomPassword(16) : samplePwd.trim()));
            u.setFailCount(0);
            u.setLockedUntil(null);
            u.setStatus(enableSamples ? 1 : 0);
            userMapper.updateById(u);
            fixed++;
        }
        if (fixed > 0) {
            log.info("✅ DataInitRunner: 已初始化 {} 个样例业务账号({})", fixed,
                    enableSamples ? "已启用" : "默认停用,需要时由管理员改密后再启用");
        }

        SysUser demo = userMapper.findByUsername("demo");
        if (demo != null && isPlaceholder(demo)) {
            String envPwd = env.apply("SCM_DEMO_PASSWORD");
            boolean random = envPwd == null || envPwd.isBlank();
            String pwd = random ? randomPassword(12) : envPwd.trim();
            demo.setPassword(pwdEncoder.encode(pwd));
            demo.setFailCount(0);
            demo.setLockedUntil(null);
            userMapper.updateById(demo);
            if (random) {
                log.warn("\n  🔐 全新安装：演示账号 demo(只能看演示账套)的初始密码：{}\n"
                       + "     此密码仅在本次启动日志出现一次;如需自定义,部署前设置环境变量 SCM_DEMO_PASSWORD。", pwd);
            }
        }
    }

    private boolean isPlaceholder(SysUser u) {
        String stored = u.getPassword() == null ? "" : u.getPassword();
        return stored.startsWith(PLACEHOLDER_PREFIX) || stored.startsWith(PLACEHOLDER_PREFIX_ADMIN);
    }

    /**
     * 初始化系统管理员(admin)密码——仅当当前是占位哈希(全新安装)时执行,已改过密码的既有环境跳过(无影响)。
     * 密码来源优先级：环境变量 SCM_ADMIN_PASSWORD > 随机生成(16位)。随机密码只在启动日志打印一次,
     * 首次登录后应立即改密。杜绝所有新装环境共用同一个"人人皆知的默认管理员密码"(外部审查 P0-01)。
     */
    private void initAdminPassword() {
        SysUser admin = userMapper.findByUsername("admin");
        if (admin == null) return;
        if (!isPlaceholder(admin)) return;  // 已是真实密码(既有环境),不动

        String envPwd = System.getenv("SCM_ADMIN_PASSWORD");
        boolean random = envPwd == null || envPwd.isBlank();
        String pwd = random ? randomPassword(16) : envPwd.trim();
        admin.setPassword(pwdEncoder.encode(pwd));
        admin.setFailCount(0);
        admin.setLockedUntil(null);
        userMapper.updateById(admin);

        if (random) {
            log.warn("\n============================================================\n"
                   + "  🔐 全新安装：已为系统管理员 admin 生成随机初始密码\n"
                   + "     用户名：admin\n"
                   + "     初始密码：{}\n"
                   + "  ⚠️ 请立即登录并修改此密码；此密码仅在本次启动日志出现一次。\n"
                   + "  （如需自定义，部署前设置环境变量 SCM_ADMIN_PASSWORD）\n"
                   + "============================================================", pwd);
        } else {
            log.info("🔐 全新安装：已按环境变量 SCM_ADMIN_PASSWORD 设置 admin 初始密码。");
        }
    }

    /** 生成含大小写字母+数字的随机密码,避免易混字符(0/O、1/l/I)。 */
    private String randomPassword(int len) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    private void ensureNotifReadTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS notif_read (
                  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
                  notif_id    BIGINT       NOT NULL,
                  user_id     BIGINT       NOT NULL,
                  read_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_notif_user (notif_id, user_id),
                  KEY idx_user_read (user_id, read_at)
                ) COMMENT='role todo personal read state'
                """);
    }

    private void ensureReleaseNoteTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sys_release_note (
                  id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
                  version_no    VARCHAR(40)  NOT NULL,
                  title         VARCHAR(120) NOT NULL,
                  summary       VARCHAR(500) NULL,
                  content       TEXT         NOT NULL,
                  published_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  popup         TINYINT      NOT NULL DEFAULT 1,
                  status        TINYINT      NOT NULL DEFAULT 1,
                  sort          INT          NOT NULL DEFAULT 0,
                  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  deleted       TINYINT      NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_release_version (version_no, deleted),
                  KEY idx_release_visible (status, deleted, published_at)
                ) COMMENT='系统更新说明'
                """);
    }

    private void ensurePeriodCloseTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS fin_period_close (
                  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
                  org_id          BIGINT       NOT NULL,
                  period_month    VARCHAR(7)   NOT NULL,
                  status          VARCHAR(20)  NOT NULL DEFAULT 'CLOSED',
                  issue_count     INT          NOT NULL DEFAULT 0,
                  warning_count   INT          NOT NULL DEFAULT 0,
                  check_result    LONGTEXT     NULL,
                  remark          VARCHAR(255) NULL,
                  checked_at      DATETIME     NULL,
                  closed_at       DATETIME     NULL,
                  closed_by       BIGINT       NULL,
                  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  deleted         TINYINT      NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_period_org (org_id, period_month, deleted),
                  KEY idx_period_status (period_month, status, deleted),
                  KEY idx_org_status (org_id, status, deleted)
                ) COMMENT='库存期间锁定记录'
                """);
    }

    private void ensureDocSequenceTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sys_doc_sequence (
                  doc_type    VARCHAR(20) NOT NULL COMMENT '单据类型',
                  scope_key   VARCHAR(40) NOT NULL COMMENT '编号范围：公司/账套编码',
                  serial_date DATE        NOT NULL COMMENT '流水日期',
                  current_no  BIGINT      NOT NULL DEFAULT 0 COMMENT '当前流水',
                  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (doc_type, scope_key, serial_date),
                  KEY idx_scope_date (scope_key, serial_date)
                ) COMMENT='单据编码流水'
                """);
    }

    /**
     * Keep old local databases aligned with the commercial single-machine role model.
     * This is intentionally idempotent: a fresh database already matches 02-seed.sql,
     * while an older 9-role demo database is folded back to purchaser/warehouse/finance/admin.
     */
    private void syncCommercialRoleModel() {
        try {
            boolean formalInitialized = isFormalInitialized();
            ensureApprovalModeColumns();
            ensureDocCreatorColumns();
            upsertRole("purchaser", "采购员", "ACCOUNT_SET", "采购订单录入与跟踪");
            upsertRole("warehouse", "仓管员", "ACCOUNT_SET", "入库、出库数量操作，不维护成本金额");
            upsertRole("account_admin", "账套管理员", "ACCOUNT_SET", "本账套内账号、组织和基础资料维护，可叠加给财务等人员");
            upsertRole("admin", "系统管理员", "ALL", "账套、账号、角色、权限、基础资料维护");
            upsertApprovalRoles();
            mergeRetiredApprovalRoles();

            jdbcTemplate.update("""
                    UPDATE sys_role
                    SET status = 0, deleted = id
                    WHERE code IN ('deptMgr','purchaseMgr','whMgr','financeMgr','superAdmin')
                      AND deleted = 0
                    """);

            Long purchaser = roleId("purchaser");
            Long warehouse = roleId("warehouse");
            Long materialAccountant = roleId("material_accountant");
            Long accountAdmin = roleId("account_admin");
            Long admin = roleId("admin");

            ensureProjectMenu();
            ensureOutTypeSubjectMenu();
            ensureTickerMenu();
            ensureOutboundReturnMenu();
            ensurePurchaseReturnMenu();
            ensureOutboundLoanMenu();
            ensureInitializationMenu();
            ensureOnlineUserMenu();
            ensureBackupMenu();
            ensureReportMenus();
            ensureCostMenus();
            ensureInventoryTransferMenu();
            ensureStocktakeMenu();
            ensureOperationManualMenus();
            ensureMaterialReqMenus();
            ensureSalesMenus();
            ensureDemoApprovalRoles();
            closeOrphanTodos();
            ensureMenuOrder();
            ensureLightInventoryDocRules();
            removeLegacyMasterMenus();
            disablePurchaseRequestMenu();
            ensureAdminOnlyMenu(1106, admin);

            if (!formalInitialized) {
                ensureDemoRole("zhangsan", purchaser);
                ensureDemoRole("liujie", purchaser);
                ensureDemoRole("lisi", warehouse);
                ensureDemoRole("sunliu", warehouse);
                ensureDemoRole("wangfang", materialAccountant);
                ensureDemoRole("zhouwei", materialAccountant);
            }
            ensureDemoRole("admin", admin);
            // deleted 写本行 id 不写 1(唯一键含 deleted，写 1 会和回收站里的同名撞键)，见迁移 20261001_001
            jdbcTemplate.update("UPDATE sys_user SET status = 0, deleted = id WHERE username = 'zhaowu' AND deleted = 0");
            // 采购：入库管理(300)+采购入库单(301) 只读查看/打印（写操作由各 Controller 角色门控拦住）
            ensureRoleMenusInclude(purchaser, 300, 301, 1200, 1201, 1202);
            ensureRoleMenusInclude(warehouse, 200, 202, 503, 1200, 1201, 1202);
            ensureRoleMenusInclude(materialAccountant, 503, 700, 703, 1200, 1201, 1202);
            ensureRoleMenusInclude(accountAdmin, 400,401, 100,101,102, 1000,1001,1003,1009, 1100,1101,1102,1103,1106, 1200,1201,1202);
            ensureRoleMenusInclude(admin, 1200, 1201, 1202);
            ensureApprovalRoleMenus();

            if (!formalInitialized) {
                ensureRoleMenusInclude(purchaser, 100,101,102, 200,202,203, 400,401);
                ensureRoleMenusInclude(warehouse, 100,101,102, 200,202, 300,301,302, 400,401,402,403,404, 500,501,502,503,
                        900);
                ensureRoleMenusInclude(materialAccountant, 100,101,102, 200,202,203, 300,301,302, 400,401,402,
                        403, 500,501,502,503, 600,601,602,603, 700,703, 900,901,902,904,905,906);
                removeRoleMenus(purchaser, 800,801);
                removeRoleMenus(warehouse, 800,801);
                removeRoleMenus(materialAccountant, 800,801);
            }
            // 系统管理员（admin）拥有整个系统的控制权：分配全部菜单（含日后新增的菜单自动包含），
            // 不再剥离业务模块——之前的职责分离限制按用户要求取消。
            // 存货明细账(916)权限同收发存汇总(2026-10-16 定):凡有收发存菜单(904)的角色都补上 916。
            // 放在所有角色授权之后,确保本次启动刚补的 904 也被跟随。只补不删:单独收回某角色的 916 会在重启后被补回,
            // 要收回请连 904 一起收回(两者本就是同一权限 rpt.inout,接口也按 rpt.inout 校验)。
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT role_id, 916 FROM sys_role_menu WHERE menu_id = 904");
            // 工具箱菜单(917)跟随工具箱权限按钮(1620,portal.tool.use):有权限的岗位(默认财务岗)才看得到入口。只补不删,收回请连 1620 一起收
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT role_id, 917 FROM sys_role_menu WHERE menu_id = 1620");
            ensureAdminHasAllMenus(admin);

            ensureWorkflowTemplates();
            ensureCurrentReleaseNote();
            materialImportService.ensureMaterialCategorySchema();
            log.info("✅ DataInitRunner: 商用单机角色模型已同步为采购/仓管/财务/管理员");
        } catch (Exception e) {
            log.warn("DataInitRunner: 商用角色模型同步跳过：{}", e.getMessage());
        }
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

    private void ensureApprovalModeColumns() {
        addColumnIfMissing("sys_org", "approval_mode",
                "ALTER TABLE sys_org ADD COLUMN approval_mode VARCHAR(20) NULL COMMENT 'approval mode: STANDARD/SIMPLE' AFTER type");
        addColumnIfMissing("wf_template", "approval_mode",
                "ALTER TABLE wf_template ADD COLUMN approval_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD' COMMENT 'approval mode: STANDARD/SIMPLE' AFTER name");
        addColumnIfMissing("wf_instance", "template_id",
                "ALTER TABLE wf_instance ADD COLUMN template_id BIGINT NULL COMMENT 'workflow template snapshot id' AFTER next_approver_role");
        addColumnIfMissing("wf_instance", "nodes_json",
                "ALTER TABLE wf_instance ADD COLUMN nodes_json TEXT NULL COMMENT 'workflow nodes snapshot' AFTER template_id");
        jdbcTemplate.update("""
                UPDATE sys_org
                SET approval_mode = 'STANDARD'
                WHERE deleted = 0
                  AND type = 'COMPANY'
                  AND (approval_mode IS NULL OR approval_mode = '')
                """);
    }

    /** 部分单据表缺少创建人列（biz_out / biz_return / biz_loan），补上以支持“谁提交谁删除”。
     *  历史行 created_by 为 NULL，老单据只能管理员删除；新单据由 MetaObjectHandler 自动填当前用户。 */
    private void ensureDocCreatorColumns() {
        addColumnIfMissing("biz_out", "created_by",
                "ALTER TABLE biz_out ADD COLUMN created_by BIGINT NULL COMMENT '创建人(原始提交人)' AFTER updated_at");
        addColumnIfMissing("biz_return", "created_by",
                "ALTER TABLE biz_return ADD COLUMN created_by BIGINT NULL COMMENT '创建人(原始提交人)' AFTER updated_at");
        addColumnIfMissing("biz_loan", "created_by",
                "ALTER TABLE biz_loan ADD COLUMN created_by BIGINT NULL COMMENT '创建人(原始提交人)' AFTER updated_at");
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private void upsertRole(String code, String name, String dataScope, String remark) {
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
    }

    private Long roleId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = ? AND deleted = 0 AND status = 1 LIMIT 1",
                Long.class, code);
    }

    private Long roleIdIncludingInactive(String code) {
        return jdbcTemplate.query(
                "SELECT id FROM sys_role WHERE code = ? AND deleted = 0 LIMIT 1",
                rs -> rs.next() ? rs.getLong("id") : null,
                code);
    }

    private void upsertApprovalRoles() {
        upsertRole("material_accountant", "材料会计", "ACCOUNT_SET", "采购订单材料会计审批");
        upsertRole("finance_manager", "财务部门负责人", "ACCOUNT_SET", "采购订单财务部门负责人审批");
        upsertRole("deputy_general_manager", "分管副总", "ACCOUNT_SET", "采购订单分管副总审批");
        upsertRole("general_manager", "总经理", "ACCOUNT_SET", "采购订单总经理审批");
        upsertRole("field_manager", "现场管理员", "ACCOUNT_SET", "工程类出库申请经办/审批岗位");
        upsertRole("tech_manager", "技术部经理", "ACCOUNT_SET", "采购、工程、仓储相关部门负责人审批");
        upsertRole("service_staff", "网点客服", "ACCOUNT_SET", "运营/增值销售类出库申请经办岗位");
        upsertRole("operation_repair", "运维人员", "ACCOUNT_SET", "运营维修类材料申请经办岗位");
        upsertRole("branch_manager", "网点负责人", "ACCOUNT_SET", "运营/增值销售类出库申请网点负责人审批");
        upsertRole("area_manager", "片区经理", "ACCOUNT_SET", "多个网点合并后的片区审批岗位");
        upsertRole("operation_leader", "运营副总", "ACCOUNT_SET", "运营类业务分管领导审批");
        upsertRole("engineering_leader", "工程副总", "ACCOUNT_SET", "工程类业务分管领导审批");
        upsertRole("market_leader", "市场副总", "ACCOUNT_SET", "市场/增值销售类业务分管领导审批");
    }

    private void mergeRetiredApprovalRoles() {
        mergeRoleInto("finance", "material_accountant");
        mergeRoleInto("dept_manager", "tech_manager");
        mergeRoleInto("business_leader", "deputy_general_manager");
        // 🔴 2026-07-31 用户要求「岗位角色下拉里要有出纳」后查实并修复的一个隐蔽陷阱：
        //    原来这里还有 mergeRoleInto("accountant"/"cashier", "material_accountant")
        //    并把这两个角色一起写进下面的“强制停用”名单。后果有两个，都是每次重启复发：
        //      ① 管理员在界面上把“出纳”启用了，下次部署重启又被改回停用 —— 界面上的操作留不住；
        //      ② mergeRoleInto 会把 会计/出纳 的**全部菜单权限复制给材料会计**，
        //         这正是“材料会计莫名其妙能看收费日报”的根因（portal.app.* 六个外部应用都是这么来的）。
        //    会计/出纳是明确要启用的在编岗位，不是退役角色，故从这两处一并摘出。
        //    仍然保留 finance / dept_manager / business_leader：它们确实已被新角色取代，
        //    且身上挂的 5 个人都另有可用角色（已于 2026-07-31 逐人核实）。
        jdbcTemplate.update("""
                UPDATE sys_role
                SET status = 0,
                    remark = '已按最新岗位口径合并停用'
                WHERE code IN ('finance', 'dept_manager', 'business_leader')
                  AND deleted = 0
                """);
    }

    private void mergeRoleInto(String sourceCode, String targetCode) {
        Long sourceId = roleIdIncludingInactive(sourceCode);
        Long targetId = roleId(targetCode);
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) return;
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id)
                SELECT user_id, ?
                FROM sys_user_role
                WHERE role_id = ?
                """, targetId, sourceId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT ?, menu_id
                FROM sys_role_menu
                WHERE role_id = ?
                """, targetId, sourceId);
        jdbcTemplate.update("UPDATE notif SET role_code = ? WHERE role_code = ?", targetCode, sourceCode);
        jdbcTemplate.update("""
                UPDATE wf_instance
                SET next_approver_role = ?
                WHERE next_approver_role = ?
                  AND status = 'PROCESSING'
                """, targetCode, sourceCode);
    }

    private void ensureApprovalRoleMenus() {
        int[] approvalMenus = {
                100, 101, 102,
                200, 202, 203,
                300, 301, 302,
                400, 401, 402,
                500, 501, 502, 503,
                900, // 出入库流水(502)已挪到报表中心(900)分组下，补上父分组以保证正常嵌套显示
                1200, 1201, 1202
        };
        for (String code : approvalRoleCodes()) {
            Long roleId = roleId(code);
            ensureRoleMenusInclude(roleId, approvalMenus);
        }
    }

    private List<String> approvalRoleCodes() {
        return List.of(
                "material_accountant",
                "finance_manager",
                "deputy_general_manager",
                "general_manager",
                "field_manager",
                "tech_manager",
                "service_staff",
                "operation_repair",
                "branch_manager",
                "area_manager",
                "operation_leader",
                "engineering_leader",
                "market_leader"
        );
    }

    private void ensureDemoRole(String username, Long roleId) {
        Long userId = jdbcTemplate.query(
                "SELECT id FROM sys_user WHERE username = ? AND deleted = 0 LIMIT 1",
                rs -> rs.next() ? rs.getLong("id") : null,
                username);
        if (userId == null || roleId == null) return;
        Integer activeRoleCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.deleted = 0 AND r.status = 1
                """, Integer.class, userId);
        if (activeRoleCount != null && activeRoleCount > 0) return;
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    private void ensureRoleMenusInclude(Long roleId, int... menuIds) {
        if (roleId == null) return;
        for (int menuId : menuIds) {
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
    }

    /** 系统管理员拥有全部菜单（含日后新增）。每次启动补齐，保证“整个系统的控制权”。 */
    private void ensureAdminHasAllMenus(Long adminRoleId) {
        if (adminRoleId == null) return;
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT ?, m.id FROM sys_menu m WHERE m.deleted = 0
                """, adminRoleId);
    }

    private void removeRoleMenus(Long roleId, int... menuIds) {
        if (roleId == null || menuIds.length == 0) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(menuIds.length, "?"));
        Object[] args = new Object[menuIds.length + 1];
        args[0] = roleId;
        for (int i = 0; i < menuIds.length; i++) args[i + 1] = menuIds[i];
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ? AND menu_id IN (" + placeholders + ")", args);
    }

    private void ensureProjectMenu() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (1106, 1100, '工程项目档案', '/master/project', 'master/project/list', 'folder-opened', 'md.project', 'MENU', 6, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  perm_code = VALUES(perm_code),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
    }

    /** 基础资料·出库类型科目映射(账套管理员维护,admin 走 ensureAdminHasAllMenus)。 */
    private void ensureOutTypeSubjectMenu() {
        upsertMenu(1107, 1100, "出库类型科目映射", "/master/out-type-subject", "master/out-type-subject/list", "coin", "md.outTypeSubject", 7);
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT r.id, 1107 FROM sys_role r
                WHERE r.deleted = 0 AND r.status = 1 AND r.code = 'account_admin'
                """);
    }

    /** 系统管理·滚动通知(2026-10-21):只有系统管理员能发,只授给 admin(ensureAdminHasAllMenus 自动带上),不授账套管理员。
     *  编号用 1011(1010 已有他用,别撞号)。 */
    private void ensureTickerMenu() {
        upsertMenu(1011, 1000, "滚动通知", "/system/ticker", "system/ticker/index", "bell", "sys.ticker", 11);
    }

    private void ensureOutboundReturnMenu() {
        upsertMenu(403, 400, "退料单", "/outbound/return", "outbound/return/list", "refresh-left", "return.view", 3);
    }

    /**
     * 采购退货单菜单(2026-08-25)。
     *
     * <p>授权口径:谁能看采购入库单(recv.view),谁就能看采购退货单 —— 退货是入库的逆操作,
     * 按现有权限派生而不是硬编码角色名,免得角色一改这里就漏(角色合并陷阱踩过)。
     * 父菜单「入库管理」(300)已授权给同一批角色,不必另行处理。</p>
     */
    private void ensurePurchaseReturnMenu() {
        upsertMenu(303, 300, "采购退货单", "/inbound/purchase-return", "inbound/purchase-return/list", "refresh-left", "pret.view", 3);
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT rm.role_id, 303
                FROM sys_role_menu rm
                JOIN sys_menu m ON m.id = rm.menu_id AND m.perm_code = 'recv.view' AND m.deleted = 0
                """);
    }

    private void ensureOutboundLoanMenu() {
        upsertMenu(404, 400, "借料登记", "/outbound/loan", "outbound/loan/list", "document-copy", "loan.view", 4);
        // 结账会列出待处理借料，查看入口须与已允许的后端财务查询角色一致。
        for (String code : List.of("material_accountant", "finance")) {
            Long role = roleIdIncludingInactive(code);
            if (role != null) ensureRoleMenusInclude(role, 400, 404);
        }
    }

    private void ensureInitializationMenu() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (1007, 1000, '正式启用初始化', '/system/initialize', 'system/initialize/index', 'tools', 'sys.init', 'MENU', 7, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  perm_code = VALUES(perm_code),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
    }

    private void ensureOnlineUserMenu() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (1008, 1000, '在线用户', '/system/online-users', 'system/online-users/index', 'monitor', 'sys.online', 'MENU', 8, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  perm_code = VALUES(perm_code),
                  type = VALUES(type),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
    }

    private void ensureBackupMenu() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (1009, 1000, '数据备份', '/system/backup', 'system/backup/index', 'download', 'sys.backup', 'MENU', 9, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  perm_code = VALUES(perm_code),
                  type = VALUES(type),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
    }

    /** 盘点单菜单：仓管建单录实盘、财务确认盘盈盘亏。用 roleIdIncludingInactive 防角色缺失整链崩(踩过的坑)。 */
    private void ensureStocktakeMenu() {
        upsertMenu(505, 400, "盘点单", "/inventory/stocktake", "inventory/stocktake", "document-checked", "inv.stocktake", 6);
        Long warehouse = roleIdIncludingInactive("warehouse");
        Long materialAccountant = roleIdIncludingInactive("material_accountant");
        Long financeManager = roleIdIncludingInactive("finance_manager");
        Long accountAdmin = roleIdIncludingInactive("account_admin");
        if (warehouse != null) ensureRoleMenusInclude(warehouse, 505);
        if (materialAccountant != null) ensureRoleMenusInclude(materialAccountant, 505);
        if (financeManager != null) ensureRoleMenusInclude(financeManager, 505);
        if (accountAdmin != null) ensureRoleMenusInclude(accountAdmin, 505);
    }

    /** 领料申请模块菜单：分组(1300) + 收件箱(1301) + 领料人档案(1302) + 提交链接管理(1303)。 */
    private void ensureMaterialReqMenus() {
        upsertMenu(1300, 0, "领料·采购", "/material-req", "Layout", "tickets", "", 10);
        upsertMenu(1301, 1300, "领料申请", "/material-req/inbox", "materialreq/inbox", "tickets", "lr.inbox", 1);
        upsertMenu(1304, 1300, "采购计划", "/material-req/pp-inbox", "materialreq/pp-inbox", "shopping-cart", "lr.pp", 2);
        upsertMenu(1302, 1300, "领料人档案", "/material-req/picker", "materialreq/picker", "user", "lr.picker", 3);
        upsertMenu(1303, 1300, "提交链接", "/material-req/link", "materialreq/link", "link", "lr.link", 4);
        // 授权：后台两项(领料人/链接)归账套管理员；领料收件箱给 采购/仓管/材料会计+账套管理员；采购计划收件箱给 采购+账套管理员；admin 自动全含
        Long accountAdmin = roleId("account_admin");
        Long purchaser = roleId("purchaser");
        Long warehouse = roleId("warehouse");
        Long materialAccountant = roleId("material_accountant");
        if (accountAdmin != null) ensureRoleMenusInclude(accountAdmin, 1300, 1301, 1302, 1303, 1304);
        if (purchaser != null) ensureRoleMenusInclude(purchaser, 1300, 1301, 1304);
        if (warehouse != null) ensureRoleMenusInclude(warehouse, 1300, 1301);
        if (materialAccountant != null) ensureRoleMenusInclude(materialAccountant, 1300, 1301);
    }

    /** 销售管理模块菜单：分组(1400) + 销售订单(1401) + 销售出库(1402) + 销售发票(1403) + 收款登记(1404)。 */
    private void ensureSalesMenus() {
        upsertMenu(1400, 0, "销售管理", "/sale", "Layout", "shopping-cart", "", 8);
        upsertMenu(1401, 1400, "销售订单", "/sale/order", "sales/order/list", "document", "sale.order", 1);
        upsertMenu(1402, 1400, "销售出库", "/sale/out", "sales/out/list", "sold-out", "sale.out", 2);
        upsertMenu(1403, 1400, "销售发票", "/sale/invoice", "sales/invoice/list", "tickets", "sale.invoice", 3);
        upsertMenu(1407, 1400, "销售退货", "/sale/return", "sales/return/list", "refresh-left", "sale.return", 4);
        upsertMenu(1404, 1400, "收款登记", "/sale/receipt", "sales/receipt/list", "wallet", "sale.receipt", 5);
        upsertMenu(1405, 1400, "销售订单执行查询", "/sale/order-exec", "sales/exec/order-exec", "data-line", "sale.exec", 6);
        upsertMenu(1406, 1400, "销售出库执行查询", "/sale/out-exec", "sales/exec/out-exec", "data-line", "sale.exec", 7);
        // 网点扫码录销售单的链接管理(生成二维码)
        // ⚠️ 必须在这里登记 —— DataInitRunner 每次启动重刷菜单,只写迁移会被刷掉
        // 🔴 授权只给账套管理员/系统管理员(见迁移 20260821_003)。
        // ⚠️ 2026-08-21 纠正:sys_role 没有 org_id,角色是全局的(全库只有 1 个 account_admin),
        //    所以【菜单授权做不到"按公司开功能"】——一授权就是所有公司的账套管理员都看得见。
        //    真隔离在 LrLinkService(applyOrgScope + assertOrgVisible):每家只能给自己公司建链接。
        upsertMenu(1409, 1400, "销售录单链接", "/sale/entry-link", "sales/EntryLink", "link", "sale.entryLink", 8);
        Long accountAdmin = roleId("account_admin");
        Long purchaser = roleId("purchaser");
        Long warehouse = roleId("warehouse");
        Long materialAccountant = roleId("material_accountant");
        if (accountAdmin != null) ensureRoleMenusInclude(accountAdmin, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407);
        if (purchaser != null) ensureRoleMenusInclude(purchaser, 1400, 1401, 1405);
        if (warehouse != null) ensureRoleMenusInclude(warehouse, 1400, 1401, 1402, 1406, 1407); // 1401:仓库需查看订单安排发货(只读,写接口按角色拦)
        if (materialAccountant != null) ensureRoleMenusInclude(materialAccountant, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407);
    }

    /**
     * 关闭"孤儿待办":单据已经没了,待办还挂着 —— 点开搜不到单据显示空白,用户以为系统坏了。
     *
     * 这是【安全网】,不是主防线。主防线是各 Service 的 delete() 里同步调
     * closeBusinessTodos(2026-07-31 已给 11 类单据补齐;在那之前只有 OREQ / OUT 两类有,
     * 其余 11 类全靠这里兜底,而这里只在【后端启动时】跑一次 —— 两次部署之间可能隔几周)。
     *
     * 2026-07-31 修了两处:
     *  ① 名单漏了 LR / PP / STOCKTAKE / CANCEL_REQ 四类,它们的孤儿待办永远清不掉。
     *  ② 匹配条件原来是 `biz_id IN (SELECT id FROM 表 WHERE deleted = 1)`,只认【逻辑删】。
     *     演示账套每日复位是【物理删】(DELETE FROM ...),那些行查都查不到,自然也救不回来。
     *     改成 NOT EXISTS(...id=biz_id AND deleted=0),逻辑删和物理删一并覆盖。
     */
    private void closeOrphanTodos() {
        String[][] map = {
                {"OREQ", "biz_out_req"}, {"OREQ_REV", "biz_out_req_revision"}, {"OUT", "biz_out"},
                {"RETURN", "biz_return"}, {"LOAN", "biz_loan"}, {"TRANSFER", "biz_transfer"},
                {"PO", "biz_po"}, {"PR", "biz_pr"}, {"RECV", "biz_recv"}, {"INV", "biz_invoice"},
                {"SO", "biz_so"}, {"SALE_OUT", "biz_sale_out"}, {"SALE_INV", "biz_sale_invoice"},
                {"SALE_RETURN", "biz_sale_return"},
                // 下面三类是 2026-07-31 补的(都有 deleted 列)
                {"LR", "lr_request"}, {"PP", "pp_request"}, {"STOCKTAKE", "biz_stocktake"}
        };
        for (String[] m : map) {
            try {
                jdbcTemplate.update(
                        "UPDATE notif SET is_read = 1, read_at = NOW() " +
                        "WHERE type = 'TODO' AND is_read = 0 AND biz_type = ? AND biz_id IS NOT NULL " +
                        "AND NOT EXISTS (SELECT 1 FROM " + m[1] + " t WHERE t.id = notif.biz_id AND t.deleted = 0)", m[0]);
            } catch (Exception ignore) { /* 表不存在等情况跳过,不阻断启动 */ }
        }
        // CANCEL_REQ 单独处理:wf_cancel_request 【没有 deleted 列】,
        // 套用上面带 t.deleted = 0 的语句会直接报 SQL 错、被 catch 静默吞掉,等于白写。
        try {
            jdbcTemplate.update(
                    "UPDATE notif SET is_read = 1, read_at = NOW() " +
                    "WHERE type = 'TODO' AND is_read = 0 AND biz_type = 'CANCEL_REQ' AND biz_id IS NOT NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM wf_cancel_request t WHERE t.id = notif.biz_id)");
        } catch (Exception ignore) { /* 表不存在时跳过 */ }
    }

    /** 演示账号补齐审批中间节点角色(operation_leader/engineering_leader/market_leader)——
     *  20260701_003 迁移的角色白名单漏了这三个,出库申请三条审批链会在中间节点卡死(E2E 抓到)。幂等。 */
    private void ensureDemoApprovalRoles() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id)
                SELECT u.id, r.id FROM sys_user u
                CROSS JOIN sys_role r
                WHERE u.username = 'demo' AND u.deleted = 0
                  AND r.deleted = 0 AND r.status = 1
                  AND r.code IN ('operation_leader', 'engineering_leader', 'market_leader')
                """);
    }

    /** 侧边栏顶层菜单排序：业务类(用得多)靠上，结账/报表/基础资料/系统管理/帮助(频次低)靠下。 */
    private void ensureMenuOrder() {
        int[][] order = {
                {100, 1},   // 工作台
                {200, 2},   // 采购管理
                {300, 3},   // 入库管理
                {400, 4},   // 出库管理
                {1300, 5},  // 领料·采购
                {1400, 8},  // 销售管理
                {500, 6},   // 库存
                {600, 7},   // 发票管理
                {700, 20},  // 结账
                {900, 21},  // 报表中心
                {1100, 22}, // 基础资料
                {1000, 23}, // 系统管理
                {1200, 24}  // 帮助中心
        };
        for (int[] o : order) {
            jdbcTemplate.update("UPDATE sys_menu SET sort = ? WHERE id = ? AND deleted = 0", o[1], o[0]);
        }
    }

    private void ensureReportMenus() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (900, 0, '报表中心', '/report', 'Layout', 'data-analysis', '', 'DIR', 9, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
        upsertMenu(901, 900, "采购订单跟踪", "/report/po-tracking", "report/po-tracking", "document", "rpt.po", 1);
        upsertMenu(902, 900, "暂估清单", "/report/estimate", "report/estimate", "warning", "rpt.est", 2);
        upsertMenu(904, 900, "收发存汇总表", "/report/in-out-summary", "report/in-out-summary", "list", "rpt.inout", 4);
        // 2026-10-16:存货明细账(按公司,对应财务软件存货明细账)。权限同收发存汇总(rpt.inout),授权跟随 904,见 syncCommercialRoleModel 末尾
        upsertMenu(916, 900, "存货明细账", "/report/inventory-ledger", "report/inventory-ledger", "reading", "rpt.inout", 4);
        // 2026-10-20:工具箱放在报表中心下;
        // 权限仍是工具箱原有的 portal.tool.use(按钮 1620),授权跟随 1620,见 syncCommercialRoleModel 末尾
        upsertMenu(917, 900, "工具箱", "/report/toolbox", "report/toolbox", "tools", "portal.tool.use", 30);
        upsertMenu(905, 900, "出库汇总表", "/report/out-summary", "report/out-summary", "truck", "rpt.outSummary", 5);
        upsertMenu(906, 900, "工程项目材料计划执行表", "/report/project-material-plan", "report/project-material-plan", "collection", "rpt.projectPlan", 6);
        upsertMenu(907, 900, "财务核对台账", "/report/finance-reconcile", "report/finance-reconcile", "wallet", "rpt.reconcile", 8);
        upsertMenu(908, 900, "出库成本科目汇总", "/report/out-cost-by-subject", "report/out-cost-by-subject", "coin", "rpt.outSummary", 9);
        // 2026-08-21 新增:暂估差异调整表(只给财务,见迁移 20260821_001)
        // ⚠️ 必须在这里登记 —— DataInitRunner 每次启动会重刷菜单,只写迁移会被刷掉
        upsertMenu(909, 900, "采购成本差异调整表", "/report/estimate-diff", "report/estimate-diff", "coin", "rpt.estDiff", 10);
        upsertMenu(910, 900, "销售订单查询", "/report/sales-order-query", "report/sales-order-query", "document", "rpt.sales", 10);
        upsertMenu(911, 900, "销售出库查询", "/report/sales-out-query", "report/sales-out-query", "sold-out", "rpt.sales", 11);
        upsertMenu(912, 900, "销售发票查询", "/report/sales-invoice-query", "report/sales-invoice-query", "tickets", "rpt.sales", 12);
        upsertMenu(913, 900, "销售退货查询", "/report/sales-return-query", "report/sales-return-query", "refresh-left", "rpt.sales", 13);
        // 2026-10-01:销售收入成本明细表(发票收入×出库成本，按月汇总+打印签字)，授权同销售明细查询
        upsertMenu(914, 900, "销售收入成本明细表", "/report/sales-income-cost", "report/sales-income-cost", "coin", "rpt.sales", 14);
        // 2026-10-10:集团汇总分析(各公司并排对比)。故意不授权给任何业务角色——只有 admin(ensureAdminHasAllMenus)看得到；
        // 接口另在 GroupOverviewController 里 requireRole("admin")，别只靠菜单挡。
        upsertMenu(915, 900, "集团汇总分析", "/report/group-overview", "report/group-overview", "data-analysis", "rpt.groupOverview", 0);
        // 财务核对台账/出库成本科目汇总/暂估差异调整表(909,2026-08-21新增) 授权给 材料会计/财务/财务负责人/账套管理员
        // ⚠️ 909 只给财务 —— 差异金额涉及采购价格,采购与仓管岗按内控不给（IN 只命中存在的角色；admin 走 ensureAdminHasAllMenus）
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT r.id, x.menu_id
                FROM sys_role r
                CROSS JOIN (SELECT 900 menu_id UNION ALL SELECT 907 UNION ALL SELECT 908 UNION ALL SELECT 909) x
                WHERE r.deleted = 0 AND r.status = 1
                  AND r.code IN ('material_accountant','finance','finance_manager','account_admin')
                """);
        // 销售明细查询报表 授权给 材料会计/财务/财务负责人/账套管理员/采购（带成本列，不给纯仓管；admin 自动全含）
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
                SELECT r.id, x.menu_id
                FROM sys_role r
                CROSS JOIN (SELECT 900 menu_id UNION ALL SELECT 910 UNION ALL SELECT 911 UNION ALL SELECT 912 UNION ALL SELECT 913 UNION ALL SELECT 914) x
                WHERE r.deleted = 0 AND r.status = 1
                  AND r.code IN ('material_accountant','finance','finance_manager','account_admin','purchaser')
                """);

        // 库存/报表归位（用户要求）：① 库存余额(501)→「现存量」(实时库存/盘点底表)；
        // ② 出入库流水(502)从「库存」(500)挪到「报表中心」(900)；
        // ③ 删除重复的「现存量表」(903)——与「现存量(库存余额)」是同一张实时现存量表（903 只是不含金额）。
        jdbcTemplate.update("UPDATE sys_menu SET name = '现存量' WHERE id = 501 AND deleted = 0");
        jdbcTemplate.update("UPDATE sys_menu SET parent_id = 900, sort = 7 WHERE id = 502 AND deleted = 0");
        jdbcTemplate.update("UPDATE sys_menu SET status = 0, deleted = 1 WHERE id = 903");
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE menu_id = 903");

        // 追溯模块(800 目录 / 801 单据链查询)取消侧边栏独立菜单，融入单据（点单据号即跳单据链页，路由由前端 hiddenBusinessRoutes 保留）。
        jdbcTemplate.update("UPDATE sys_menu SET status = 0, deleted = 1 WHERE id IN (800, 801)");
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE menu_id IN (800, 801)");
    }

    private void ensureCostMenus() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (700, 0, '结账', '/cost', 'Layout', 'coin', '', 'DIR', 7, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
        upsertMenu(703, 700, "月末结账", "/cost/period-close", "cost/period-close", "calendar", "cost.close", 3);
        // 下线两个重复页：库存成本参考(701)/库存流水参考(702)——与「出入库流水」同一份 transactions 数据，
        // 金额已按角色脱敏，无需另开两套；「重算结存单价」功能已并入「库存余额」页。
        jdbcTemplate.update("UPDATE sys_menu SET status = 0, deleted = 1 WHERE id IN (701, 702)");
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE menu_id IN (701, 702)");
    }

    private void ensureInventoryTransferMenu() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (500, 0, '库存', '/inventory', 'Layout', 'database', '', 'DIR', 5, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
        upsertMenu(503, 400, "库存转库", "/inventory/transfer", "inventory/transfer", "switch", "inv.transfer", 5);
    }

    private void ensureLightInventoryDocRules() {
        upsertDocRule("PO", "CD", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("RECV", "CR", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("INV", "CF", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("OREQ", "SA", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("OREQ_REV", "SAX", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("OUT", "CC", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("RETURN", "TL", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("LOAN", "JL", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("TRANSFER", "ZK", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("SO", "XD", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("SALE_OUT", "XC", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("SALE_INV", "XF", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
        upsertDocRule("SALE_RETURN", "XT", "{company}-{prefix}-{YYYYMMDD}-{0000}", "DAILY");
    }

    private void upsertDocRule(String docType, String prefix, String pattern, String resetFreq) {
        jdbcTemplate.update("""
                INSERT INTO sys_doc_rule (doc_type, prefix, pattern, current_no, reset_freq)
                VALUES (?, ?, ?, 0, ?)
                ON DUPLICATE KEY UPDATE
                  prefix = VALUES(prefix),
                  pattern = VALUES(pattern),
                  reset_freq = VALUES(reset_freq)
                """, docType, prefix, pattern, resetFreq);
    }

    private void ensureOperationManualMenus() {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (1200, 0, '帮助中心', '/help', 'Layout', 'reading', '', 'DIR', 21, 1, 0)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """);
        upsertMenu(1201, 1200, "操作手册", "/help/role-guide", "help/role-guide", "reading", "help.guide", 1);
        upsertMenu(1202, 1200, "系统更新", "/help/release-notes", "help/release-notes", "document-copy", "help.release", 2);
        upsertMenu(1203, 1200, "操作流程指引", "/help/flow-guide", "help/flow-guide", "guide", "help.flow", 3);
        // 有“操作手册(1201)”的角色一并给“操作流程指引(1203)”（admin 走 ensureAdminHasAllMenus）
        jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu(role_id, menu_id) SELECT DISTINCT role_id, 1203 FROM sys_role_menu WHERE menu_id = 1201");
    }

    private void ensureCurrentReleaseNote() {
        jdbcTemplate.update("""
                INSERT INTO sys_release_note (version_no, title, summary, content, published_at, popup, status, sort, deleted)
                VALUES (?, ?, ?, ?, NOW(), 0, 1, 100, 0)
                ON DUPLICATE KEY UPDATE title = VALUES(title), summary = VALUES(summary), content = VALUES(content)
                """,
                "1.0.0",
                "星嘻进销存 开源首版",
                "采购、入库、出库、库存、发票、销售、月末结账与报表的完整进销存流程。",
                """
                采购:采购订单、采购入库(暂估/发票入库)、采购退货、采购发票与付款登记。
                出库:出库申请、材料出库、退料、借料、转库,提交时即检查可用库存。
                库存:按仓库或按公司的移动加权平均成本,盘点、库存上下限预警、期初导入。
                销售:销售订单、销售出库、销售发票、销售退货与收款。
                结账与报表:月末结账前对账检查,存货明细账、收发存汇总、暂估待冲回等报表。
                管理:多公司账套隔离、岗位与审批流程配置、单据编号规则、操作日志。
                """);
    }

    private void upsertMenu(int id, int parentId, String name, String path, String component,
                            String icon, String permCode, int sort) {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, path, component, icon, perm_code, type, sort, status, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'MENU', ?, 1, 0)
                ON DUPLICATE KEY UPDATE
                  parent_id = VALUES(parent_id),
                  name = VALUES(name),
                  path = VALUES(path),
                  component = VALUES(component),
                  icon = VALUES(icon),
                  perm_code = VALUES(perm_code),
                  sort = VALUES(sort),
                  status = 1,
                  deleted = 0
                """, id, parentId, name, path, component, icon, permCode, sort);
    }

    private void removeLegacyMasterMenus() {
        jdbcTemplate.update("""
                UPDATE sys_menu
                SET status = 0, deleted = 1
                WHERE parent_id = 1100
                  AND id NOT IN (1101, 1102, 1103, 1104, 1105, 1106, 1107)
                """);
        jdbcTemplate.update("""
                UPDATE sys_menu
                SET status = 0, deleted = 1
                WHERE name REGEXP '人员|员工|职员'
                  AND (path LIKE '/master/%' OR component LIKE 'master/%')
                """);
        jdbcTemplate.update("""
                DELETE rm
                FROM sys_role_menu rm
                JOIN sys_menu m ON m.id = rm.menu_id
                WHERE m.deleted = 1
                  AND (m.parent_id = 1100 OR m.name REGEXP '人员|员工|职员')
                """);
    }

    private void disablePurchaseRequestMenu() {
        jdbcTemplate.update("""
                UPDATE sys_menu
                SET status = 0, deleted = 1
                WHERE id = 201
                   OR path = '/purchase/pr'
                   OR component = 'purchase/pr/list'
                   OR perm_code = 'pr.view'
                """);
        jdbcTemplate.update("""
                DELETE rm
                FROM sys_role_menu rm
                JOIN sys_menu m ON m.id = rm.menu_id
                WHERE m.deleted = 1
                  AND (m.id = 201 OR m.path = '/purchase/pr' OR m.perm_code = 'pr.view')
                """);
    }

    private void ensureAdminOnlyMenu(int menuId, Long adminRoleId) {
        jdbcTemplate.update("""
                DELETE rm
                FROM sys_role_menu rm
                JOIN sys_role r ON r.id = rm.role_id
                WHERE rm.menu_id = ?
                  AND (r.code <> 'admin' OR r.deleted <> 0 OR r.status <> 1)
                """, menuId);
        if (adminRoleId != null) {
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", adminRoleId, menuId);
        }
    }

    private void ensureWorkflowTemplates() {
        disableWorkflow("PR");
        replaceWorkflow("PO", "采购订单审批", null, """
                [{"node":"MATERIAL_ACCOUNTANT","approverRole":"material_accountant","name":"材料会计"},
                 {"node":"FINANCE_MANAGER","approverRole":"finance_manager","name":"财务部门负责人"},
                 {"node":"DEPUTY_GENERAL_MANAGER","approverRole":"deputy_general_manager","name":"分管副总"},
                 {"node":"GENERAL_MANAGER","approverRole":"general_manager","name":"总经理"}]
                """, 1);
        replaceWorkflow("RECV", "采购入库审批", null, """
                [{"node":"TECH_MANAGER","approverRole":"tech_manager","name":"技术部经理"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 2);
        replaceWorkflow("PRET", "采购退货审批", null, """
                [{"node":"TECH_MANAGER","approverRole":"tech_manager","name":"技术部经理"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 2);
        ensureMaterialAccountantWorkflow("INV", "采购发票审批", 3);
        disableWorkflow("OREQ");
        insertWorkflow("OREQ", "出库申请审批-工程类",
                "#businessCategory == '工程业务' || #businessCategory == '工程类' || #businessCategory == 'PROJECT' || #outType == '工程领料' || #outType == 'PROJECT_MATERIAL'",
                """
                [{"node":"TECH_MANAGER","approverRole":"tech_manager","name":"技术部经理"},
                 {"node":"ENGINEERING_LEADER","approverRole":"engineering_leader","name":"工程副总"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 4);
        insertWorkflow("OREQ", "出库申请审批-增值销售类",
                "#businessCategory == '增值销售类' || #businessCategory == '增值销售' || #businessCategory == 'VALUE_ADDED' || #outType == '销售材料'",
                """
                [{"node":"BRANCH_MANAGER","approverRole":"branch_manager","name":"网点负责人"},
                 {"node":"AREA_MANAGER","approverRole":"area_manager","name":"片区经理"},
                 {"node":"MARKET_LEADER","approverRole":"market_leader","name":"市场副总"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 5);
        insertWorkflow("OREQ", "出库申请审批-运营类", null, """
                [{"node":"BRANCH_MANAGER","approverRole":"branch_manager","name":"网点负责人"},
                 {"node":"AREA_MANAGER","approverRole":"area_manager","name":"片区经理"},
                 {"node":"OPERATION_LEADER","approverRole":"operation_leader","name":"运营副总"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 6);
        replaceWorkflow("OUT", "材料出库审批", null, """
                [{"node":"TECH_MANAGER","approverRole":"tech_manager","name":"技术部经理"},
                 {"node":"ENGINEERING_LEADER","approverRole":"engineering_leader","name":"工程副总"},
                 {"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]
                """, 7);
        ensureMaterialAccountantWorkflow("OREQ_REV", "出库申请修订审批", 8);
        ensureMaterialAccountantWorkflow("RETURN", "退料审批", 9);
        ensureMaterialAccountantWorkflow("TRANSFER", "库存转库审批", 10);
        ensureMaterialAccountantWorkflow("LOAN", "借料审批", 11);
        ensureMaterialAccountantWorkflow("SO", "销售订单审批", 12);
        ensureMaterialAccountantWorkflow("SALE_OUT", "销售出库审批", 13);
        ensureMaterialAccountantWorkflow("SALE_INV", "销售发票审批", 14);
        ensureMaterialAccountantWorkflow("SALE_RETURN", "销售退货审批", 15);
        ensureSimpleWorkflowTemplates();
    }

    private void disableWorkflow(String bizType) {
        jdbcTemplate.update("DELETE FROM wf_template WHERE biz_type = ?", bizType);
    }

    private void replaceWorkflow(String bizType, String name, String conditionExpr, String nodesJson, int sort) {
        jdbcTemplate.update("DELETE FROM wf_template WHERE biz_type = ?", bizType);
        insertWorkflow(bizType, name, conditionExpr, nodesJson, sort);
    }

    private void insertWorkflow(String bizType, String name, String conditionExpr, String nodesJson, int sort) {
        jdbcTemplate.update("""
                INSERT INTO wf_template (biz_type, name, condition_expr, nodes_json, sort, status)
                VALUES (?, ?, ?, ?, ?, 1)
                """, bizType, name, conditionExpr, nodesJson.replaceAll("\\s+", " ").trim(), sort);
    }

    private void ensureMaterialAccountantWorkflow(String bizType, String name, int sort) {
        String materialAccountantOnlyNodes = "[{\"node\":\"MATERIAL_ACCOUNTANT_REVIEW\",\"approverRole\":\"material_accountant\",\"name\":\"材料会计\"}]";
        Integer existingTemplate = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wf_template
                WHERE biz_type = ? AND status = 1 AND TRIM(nodes_json) = ?
                """, Integer.class, bizType, materialAccountantOnlyNodes);
        Integer activeTemplateCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wf_template
                WHERE biz_type = ? AND status = 1
                """, Integer.class, bizType);
        if (Integer.valueOf(1).equals(existingTemplate) && Integer.valueOf(1).equals(activeTemplateCount)) return;
        jdbcTemplate.update("DELETE FROM wf_template WHERE biz_type = ?", bizType);
        insertMaterialAccountantWorkflow(bizType, name, sort);
    }

    private void insertMaterialAccountantWorkflow(String bizType, String name, int sort) {
        jdbcTemplate.update("""
                INSERT INTO wf_template (biz_type, name, condition_expr, nodes_json, sort, status)
                VALUES (?, ?, NULL, '[{"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]', ?, 1)
                """, bizType, name, sort);
    }

    private void ensureSimpleWorkflowTemplates() {
        insertSimpleWorkflow("PO", "采购订单审批-简化", 101);
        insertSimpleWorkflow("RECV", "采购入库审批-简化", 102);
        insertSimpleWorkflow("INV", "采购发票审批-简化", 103);
        insertSimpleWorkflow("OREQ", "出库申请审批-简化", 104);
        insertSimpleWorkflow("OUT", "材料出库审批-简化", 105);
        insertSimpleWorkflow("OREQ_REV", "出库申请修订审批-简化", 106);
        insertSimpleWorkflow("RETURN", "退料审批-简化", 107);
        insertSimpleWorkflow("TRANSFER", "库存转库审批-简化", 108);
        insertSimpleWorkflow("LOAN", "借料审批-简化", 109);
        insertSimpleWorkflow("SO", "销售订单审批-简化", 110);
        insertSimpleWorkflow("SALE_OUT", "销售出库审批-简化", 111);
        insertSimpleWorkflow("SALE_INV", "销售发票审批-简化", 112);
        insertSimpleWorkflow("SALE_RETURN", "销售退货审批-简化", 113);
        insertSimpleWorkflow("PRET", "采购退货审批-简化", 114);
    }

    private void insertSimpleWorkflow(String bizType, String name, int sort) {
        jdbcTemplate.update("""
                INSERT INTO wf_template (biz_type, name, approval_mode, condition_expr, nodes_json, sort, status)
                VALUES (?, ?, 'SIMPLE', NULL, '[{"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}]', ?, 1)
                """, bizType, name, sort);
    }
}
