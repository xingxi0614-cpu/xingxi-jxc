package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.modules.inventory.entity.BizRecv;
import com.group.scm.modules.notif.entity.Notif;
import com.group.scm.modules.inventory.entity.InvBalance;
import com.group.scm.modules.inventory.service.RecvService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.invoice.entity.BizInvoice;
import com.group.scm.modules.invoice.entity.BizInvoiceLine;
import com.group.scm.modules.invoice.service.InvoiceService;
import com.group.scm.modules.outbound.entity.BizLoan;
import com.group.scm.modules.outbound.entity.BizLoanLine;
import com.group.scm.modules.outbound.entity.BizOut;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import com.group.scm.modules.outbound.entity.BizReturn;
import com.group.scm.modules.outbound.entity.BizReturnLine;
import com.group.scm.modules.outbound.service.LoanService;
import com.group.scm.modules.outbound.service.OutReqService;
import com.group.scm.modules.outbound.service.OutService;
import com.group.scm.modules.outbound.service.ReturnService;
import com.group.scm.modules.purchase.entity.BizPo;
import com.group.scm.modules.purchase.entity.BizPoLine;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.system.service.SysUserImportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@EnabledIfEnvironmentVariable(
        named = "RUN_DB_TESTS",
        matches = "true",
        disabledReason = "需要隔离 MySQL 测试库，避免误连本地或正式账套"
)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("核心业务流集成测试")
class BusinessFlowTest {

    /**
     * 「提交时就查库存」(SubmitStockGuard)故意另开一个只读小事务读最新库存;本类在测试事务里造的库存
     * 还没提交,它读不到,会把正常单据误判为库存不足。这里换成放行,专心测本类的流程。
     * 该检查本身由全流程测试 scripts/e2e-local.sh 第 35 章覆盖(不够不进审批、扣待审占用、并发)。
     */
    @MockitoBean private com.group.scm.modules.inventory.service.SubmitStockGuard submitStockGuard;

    private static final Long MAT_ID = 99901L;
    private static final Long WH_ID = 99901L;
    private static final Long PROJECT_ID = 99901L;
    private static final Long SUPPLIER_ID = 1L;
    private static final LocalDate TODAY = LocalDate.now();
    private static final String TEST_PASSWORD_HASH =
            "$2a$10$7JBUKxhcM5wmWk7rPVTbQOQjA1JrJZS3mY5d9vZqRu6wqYIIB7HZS";
    private static final long TEST_PURCHASER_ID = 99001L;
    private static final long TEST_WAREHOUSE_ID = 99002L;
    private static final long TEST_FINANCE_ID = 99003L;
    private static final long TEST_FIELD_MANAGER_ID = 99021L;
    private static final long TEST_SERVICE_STAFF_ID = 99022L;
    private static final Map<String, Long> ROLE_APPROVER_USERS = Map.ofEntries(
            Map.entry("material_accountant", TEST_FINANCE_ID),
            Map.entry("finance_manager", 99005L),
            Map.entry("deputy_general_manager", 99006L),
            Map.entry("general_manager", 99007L),
            Map.entry("branch_manager", 99010L),
            Map.entry("tech_manager", 99013L),
            Map.entry("area_manager", 99014L),
            Map.entry("operation_leader", 99015L),
            Map.entry("engineering_leader", 99016L),
            Map.entry("market_leader", 99017L)
    );

    @Autowired private PoService poService;
    @Autowired private RecvService recvService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private OutReqService outReqService;
    @Autowired private OutService outService;
    @Autowired private ReturnService returnService;
    @Autowired private LoanService loanService;
    @Autowired private WaInventoryService waService;
    @Autowired private com.group.scm.modules.notif.service.NotifService notifService;
    @Autowired private com.group.scm.modules.workflow.service.WorkflowService wfService;
    @Autowired private SysUserImportService userImportService;
    @Autowired private com.group.scm.modules.auth.service.AuthService authService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpTestUsers() {
        seedTestMasterData();
        seedTestUser(TEST_PURCHASER_ID, "junit_purchaser", "测试采购员", "purchaser");
        seedTestUser(TEST_WAREHOUSE_ID, "junit_warehouse", "测试仓管员", "warehouse");
        seedTestUser(TEST_FINANCE_ID, "junit_material_accountant_main", "测试材料会计", "material_accountant");
        seedTestUser(TEST_FIELD_MANAGER_ID, "junit_field_manager", "测试现场管理员", "field_manager");
        seedTestUser(TEST_SERVICE_STAFF_ID, "junit_service_staff", "测试网点客服", "service_staff");
        seedTestUser(99004L, "junit_material_accountant", "测试材料会计", "material_accountant");
        seedTestUser(99005L, "junit_finance_manager", "测试财务部门负责人", "finance_manager");
        seedTestUser(99006L, "junit_deputy_general_manager", "测试分管副总", "deputy_general_manager");
        seedTestUser(99007L, "junit_general_manager", "测试总经理", "general_manager");
        seedTestUser(99010L, "junit_branch_manager", "测试网点负责人", "branch_manager");
        seedTestUser(99013L, "junit_tech_manager", "测试技术部经理", "tech_manager");
        seedTestUser(99014L, "junit_area_manager", "测试片区经理", "area_manager");
        seedTestUser(99015L, "junit_operation_leader", "测试运营副总", "operation_leader");
        seedTestUser(99016L, "junit_engineering_leader", "测试工程副总", "engineering_leader");
        seedTestUser(99017L, "junit_market_leader", "测试市场副总", "market_leader");
        seedTestUser(99018L, "junit_operation_repair", "测试运维人员", "operation_repair");
        loginAsPurchaser();
    }

    @AfterEach
    void clearLogin() {
        try {
            if (StpUtil.isLogin()) {
                StpUtil.logout();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("SESSION-01: 电脑与手机可各保留一个会话")
    void pcAndAppSessionsCoexist() {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(99004L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("PC"));
        String pcToken = StpUtil.getTokenValue();
        StpUtil.login(99004L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("APP"));
        String appToken = StpUtil.getTokenValue();
        assertNotEquals(pcToken, appToken);
        assertEquals("99004", String.valueOf(StpUtil.getLoginIdByToken(pcToken)));
        assertEquals("99004", String.valueOf(StpUtil.getLoginIdByToken(appToken)));
        StpUtil.logout(99004L);
    }

    @Test
    @DisplayName("SESSION-02: 账号已在线时二次登录需确认(4090)，force=true 可强制登录")
    void secondLoginRequiresConfirmWhenOnline() {
        if (StpUtil.isLogin()) StpUtil.logout();
        long uid = 99099L;
        String pwd = "Test@1234";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(pwd);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, name, org_id, status, deleted)
                VALUES (?, 'junit_session_user', ?, '会话测试用户', 1, 1, 0)
                ON DUPLICATE KEY UPDATE password = VALUES(password), status = 1, deleted = 0
                """, uid, hash);
        try {
            // 首次登录成功，账号在线
            assertNotNull(authService.login(buildLogin(pwd, false), "127.0.0.1"));
            assertTrue(StpUtil.isLogin(uid));
            String firstToken = StpUtil.getTokenValue();

            // 账号已在线、未确认强制登录 → 抛 4090（前端据此弹确认框）
            com.group.scm.common.BizException ex = assertThrows(
                    com.group.scm.common.BizException.class,
                    () -> authService.login(buildLogin(pwd, false), "127.0.0.1"));
            assertEquals(4090, ex.getCode());

            // 确认强制登录 → 成功
            assertNotNull(authService.login(buildLogin(pwd, true), "127.0.0.1"));
            assertNull(StpUtil.getLoginIdByToken(firstToken), "强制登录应使同终端的旧会话失效");
        } finally {
            try { StpUtil.logout(uid); } catch (Exception ignored) {}
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", uid);
        }
    }

    private com.group.scm.modules.auth.dto.LoginDTO buildLogin(String pwd, boolean force) {
        com.group.scm.modules.auth.dto.LoginDTO dto = new com.group.scm.modules.auth.dto.LoginDTO();
        dto.setUsername("junit_session_user");
        dto.setPassword(pwd);
        dto.setForce(force);
        return dto;
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("PERM-APPROVE: 申请人/非审批人不能审批，指定审批人可以")
    void poApprovePermissionBoundary() {
        loginAsPurchaser();
        BizPo po = poService.create(newPo(10, "13.50"));
        poService.submit(po.getId());

        // 申请人(采购员)不能审批自己提交的单
        loginAsPurchaser();
        assertThrows(com.group.scm.common.BizException.class,
                () -> poService.approve(po.getId(), "尝试自审"));

        // 非当前节点审批人(仓管)不能审批
        loginAsWarehouse();
        assertThrows(com.group.scm.common.BizException.class,
                () -> poService.approve(po.getId(), "尝试越权审批"));

        // 当前节点审批人可以审批（不抛异常），并落下一条审批记录
        loginAsNextApprover("PO", po.getId());
        poService.approve(po.getId(), "同意");
        Integer approveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_record WHERE biz_no = ? AND action = 'APPROVE'",
                Integer.class, po.getDocNo());
        assertTrue(approveCount != null && approveCount >= 1, "指定审批人审批后应有审批记录");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("PERM-WITHDRAW: 非提交人不能取消提交采购订单，提交人可以")
    void poWithdrawPermissionBoundary() {
        loginAsPurchaser();
        BizPo po = poService.create(newPo(5, "10.00"));
        poService.submit(po.getId());
        assertEquals("PENDING", poService.detail(po.getId()).getStatus());

        // 非提交人(材料会计)不能撤回别人提交的单
        loginAsFinance();
        assertThrows(com.group.scm.common.BizException.class,
                () -> poService.withdraw(po.getId()));

        // 提交人本人可以撤回为草稿
        loginAsPurchaser();
        poService.withdraw(po.getId());
        assertEquals("DRAFT", poService.detail(po.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("PO-01: 创建采购订单 -> 提交 -> 完整审批通过")
    void poFullFlow() {
        loginAsPurchaser();
        BizPo po = newPo(10, "13.50");
        BizPo created = poService.create(po);
        assertNotNull(created.getId());
        assertDocNo(created.getDocNo(), "CD");
        assertEquals("DRAFT", created.getStatus());

        poService.submit(created.getId());
        assertEquals("PENDING", poService.detail(created.getId()).getStatus());

        approveDocument("PO", created.getId(), poService::approve);
        assertEquals("APPROVED", poService.detail(created.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("NOTIF-01: 按角色广播的待办按账套隔离，不串其它账套")
    void notifTodoOrgIsolation() {
        long orgA = 90001L, orgB = 90002L;
        // 同一「材料会计」角色，在两个账套各推一条待办
        notifService.pushToRole("material_accountant", orgA, "A账套单据待审", "PO", "PO-ORG-A", 911001L);
        notifService.pushToRole("material_accountant", orgB, "B账套单据待审", "PO", "PO-ORG-B", 911002L);

        // 仅可见 A 账套的材料会计：应看到 A，绝不应看到 B
        List<Notif> scopedA = notifService.myUnread(TEST_FINANCE_ID, List.of(orgA), false, 50);
        assertTrue(scopedA.stream().anyMatch(n -> "PO-ORG-A".equals(n.getBizNo())), "应看到本账套待办");
        assertFalse(scopedA.stream().anyMatch(n -> "PO-ORG-B".equals(n.getBizNo())), "不应看到其它账套的待办");

        // 拥有全局数据权限（管理员）时忽略账套过滤，应看到全部
        List<Notif> all = notifService.myUnread(TEST_FINANCE_ID, List.of(), true, 50);
        assertTrue(all.stream().anyMatch(n -> "PO-ORG-A".equals(n.getBizNo())));
        assertTrue(all.stream().anyMatch(n -> "PO-ORG-B".equals(n.getBizNo())));

        // 未读计数同样按账套隔离
        long countA = notifService.unreadCount(TEST_FINANCE_ID, List.of(orgA), false);
        long countAll = notifService.unreadCount(TEST_FINANCE_ID, List.of(), true);
        assertTrue(countAll > countA, "全局未读计数应大于单账套计数");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("PO-02: 采购订单审批中可被当前审批人驳回")
    void poReject() {
        loginAsPurchaser();
        BizPo created = poService.create(newPo(5, "10.00"));
        poService.submit(created.getId());

        loginAsNextApprover("PO", created.getId());
        poService.reject(created.getId(), "价格过高");
        assertEquals("REJECTED", poService.detail(created.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("RECV-01: 从 PO 下推入库 -> 审批 -> 库存更新")
    void recvFromPo() {
        BizPo po = createAndApprovePo(10, "15.00");
        loginAsWarehouse();
        BizRecv recv = recvService.fromPo(po.getId(), "ESTIMATE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.valueOf(5))), "test");
        assertNotNull(recv.getId());
        assertDocNo(recv.getDocNo(), "CR");

        recvService.submit(recv.getId());
        assertNextApproverRole("RECV", recv.getId(), "tech_manager");
        loginAsNextApprover("RECV", recv.getId());
        recvService.approve(recv.getId(), "同意入库");
        assertNextApproverRole("RECV", recv.getId(), "material_accountant");
        approveDocument("RECV", recv.getId(), recvService::approve);

        assertEquals("APPROVED", recvService.detail(recv.getId()).getStatus());
        InvBalance bal = waService.currentBalance(MAT_ID, WH_ID);
        assertTrue(bal.getQty().compareTo(BigDecimal.ZERO) > 0, "入库后库存应 > 0");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("RECV-02: 入库数量不能超过 PO 未入库数量")
    void recvExceedsPo() {
        BizPo po = createAndApprovePo(3, "10.00");
        loginAsWarehouse();
        assertThrows(RuntimeException.class, () ->
                recvService.fromPo(po.getId(), "ESTIMATE",
                        List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.valueOf(100))),
                        "超量")
        );
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("RECV-03: 分批入库后 PO 状态正确")
    void recvPartial() {
        BizPo po = createAndApprovePo(10, "10.00");

        loginAsWarehouse();
        approveRecv(recvService.fromPo(po.getId(), "ESTIMATE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.valueOf(4))), "第一批"));
        assertEquals("PARTIAL_RECEIVED", poService.detail(po.getId()).getStatus());

        loginAsWarehouse();
        approveRecv(recvService.fromPo(po.getId(), "ESTIMATE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.valueOf(6))), "第二批"));
        assertEquals("RECEIVED", poService.detail(po.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("INV-01: 暂估入库后发票核销 -> 差额调整结存单价")
    void invoiceAdjust() {
        BizPo po = createAndApprovePo(10, "10.00");
        loginAsWarehouse();
        BizRecv recv = approveRecv(recvService.fromPo(po.getId(), "ESTIMATE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.TEN)), "暂估"));

        InvBalance beforeInv = waService.currentBalance(MAT_ID, WH_ID);

        BizInvoice inv = new BizInvoice();
        inv.setInvoiceNo("INV-TEST-" + System.currentTimeMillis());
        inv.setSupplierId(SUPPLIER_ID);
        inv.setInvoiceDate(TODAY);
        BizInvoiceLine line = new BizInvoiceLine();
        line.setRecvLineId(recv.getLines().get(0).getId());
        line.setMaterialId(MAT_ID);
        line.setQty(BigDecimal.TEN);
        line.setUnitPrice(new BigDecimal("12.00"));
        line.setTaxRate(new BigDecimal("13"));
        line.setAmount(new BigDecimal("120.00"));
        line.setTaxAmount(new BigDecimal("15.60"));
        inv.setLines(List.of(line));

        loginAsFinance();
        BizInvoice created = invoiceService.create(inv);
        assertDocNo(created.getDocNo(), "CF");
        invoiceService.submit(created.getId());
        approveDocument("INV", created.getId(), invoiceService::approve);

        // B 方案(2026-10-16):结存 ≥ 开票数量 → 差额 20 全部进库存、单价上调,不进损益
        InvBalance afterInv = waService.currentBalance(MAT_ID, WH_ID);
        assertEquals(0, afterInv.getAmount().compareTo(beforeInv.getAmount().add(new BigDecimal("20.00"))), "差额进库存金额");
        BigDecimal expense = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount),0) FROM inv_transaction WHERE biz_doc_type='INV' AND biz_doc_id=? AND trans_type='ADJUST_EXPENSE'", BigDecimal.class, created.getId());
        assertEquals(0, expense.signum(), "在库足够时不进损益");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("INV-02: 货票同到开票,发票金额与入库金额不同 -> 同样按差额调整(以前只核销不记)")
    void invoiceTypeRecvDiff() {
        BizPo po = createAndApprovePo(10, "10.00");
        loginAsWarehouse();
        BizRecv recv = approveRecv(recvService.fromPo(po.getId(), "INVOICE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.TEN)), "货票同到"));
        InvBalance before = waService.currentBalance(MAT_ID, WH_ID);
        BigDecimal recvAmt = jdbcTemplate.queryForObject("SELECT amount FROM biz_recv_line WHERE id=?", BigDecimal.class, recv.getLines().get(0).getId());

        BizInvoice inv = new BizInvoice();
        inv.setInvoiceNo("INV-TEST-I-" + System.currentTimeMillis());
        inv.setSupplierId(SUPPLIER_ID);
        inv.setInvoiceDate(TODAY);
        BizInvoiceLine line = new BizInvoiceLine();
        line.setRecvLineId(recv.getLines().get(0).getId());
        line.setMaterialId(MAT_ID);
        line.setQty(BigDecimal.TEN);
        line.setUnitPrice(new BigDecimal("10.01"));
        line.setTaxRate(new BigDecimal("13"));
        line.setAmount(recvAmt.add(new BigDecimal("0.05")));
        line.setTaxAmount(new BigDecimal("13.01"));
        inv.setLines(List.of(line));
        loginAsFinance();
        BizInvoice created = invoiceService.create(inv);
        invoiceService.submit(created.getId());
        approveDocument("INV", created.getId(), invoiceService::approve);

        BigDecimal adjusted = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount),0) FROM inv_transaction WHERE biz_doc_type='INV' AND biz_doc_id=? AND trans_type IN ('ADJUST','ADJUST_EXPENSE')", BigDecimal.class, created.getId());
        BigDecimal actualNet = jdbcTemplate.queryForObject("SELECT amount FROM biz_invoice_line WHERE invoice_id=? LIMIT 1", BigDecimal.class, created.getId());
        assertEquals(0, adjusted.compareTo(actualNet.subtract(recvAmt)), "货票同到的差额 = 发票不含税 − 入库金额");
        InvBalance after = waService.currentBalance(MAT_ID, WH_ID);
        assertEquals(0, after.getAmount().compareTo(before.getAmount().add(actualNet.subtract(recvAmt))), "在库足够,差额进库存");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OUT-01: 出库申请 -> 审批 -> 下推出库 -> 审批 -> 库存扣减")
    void outFullFlow() {
        ensureStock(20, 20);
        BigDecimal beforeQty = waService.currentBalance(MAT_ID, WH_ID).getQty();

        loginAsFieldManager();
        BizOutReq oreq = new BizOutReq();
        oreq.setWarehouseId(WH_ID);
        oreq.setPurpose("PROJECT_USE");
        fillOutReqHeader(oreq);
        oreq.setApplyDate(TODAY);
        BizOutReqLine reqLine = new BizOutReqLine();
        reqLine.setMaterialId(MAT_ID);
        reqLine.setQty(BigDecimal.valueOf(5));
        oreq.setLines(List.of(reqLine));

        BizOutReq created = outReqService.create(oreq);
        assertDocNo(created.getDocNo(), "SA");
        outReqService.submit(created.getId());
        approveDocument("OREQ", created.getId(), outReqService::approve);

        loginAsWarehouse();
        BizOutReq approved = outReqService.detail(created.getId());
        BizOut out = outService.fromOreq(approved.getId(),
                List.of(outIn(approved.getLines().get(0).getId(), BigDecimal.valueOf(5))), "生活用料");
        assertDocNo(out.getDocNo(), "CC");
        outService.submit(out.getId());
        assertNextApproverRole("OUT", out.getId(), "tech_manager");
        approveDocument("OUT", out.getId(), outService::approve);

        BigDecimal afterQty = waService.currentBalance(MAT_ID, WH_ID).getQty();
        assertEquals(0, afterQty.compareTo(beforeQty.subtract(BigDecimal.valueOf(5))),
                "出库后库存应减少 5");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-01: 工程类出库申请按技术部经理 -> 工程副总 -> 材料会计")
    void oreqProjectApprovalRoute() {
        BizOutReq created = createPendingOreq("工程类", "工程领料", 2);
        assertNextApproverRole("OREQ", created.getId(), "tech_manager");

        approveDocument("OREQ", created.getId(), outReqService::approve);
        assertEquals("APPROVED", outReqService.detail(created.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-02: 运营类出库申请按网点负责人 -> 片区经理 -> 运营副总 -> 材料会计")
    void oreqOperationApprovalRoute() {
        BizOutReq created = createPendingOreq("运营类", "生活用料", 2);
        assertNextApproverRole("OREQ", created.getId(), "branch_manager");

        approveDocument("OREQ", created.getId(), outReqService::approve);
        assertEquals("APPROVED", outReqService.detail(created.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-03: 增值销售类出库申请按网点负责人 -> 片区经理 -> 市场副总 -> 材料会计")
    void oreqValueAddedApprovalRoute() {
        BizOutReq created = createPendingOreq("增值销售类", "销售材料", 2);
        assertNextApproverRole("OREQ", created.getId(), "branch_manager");

        approveDocument("OREQ", created.getId(), outReqService::approve);
        assertEquals("APPROVED", outReqService.detail(created.getId()).getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-04: 出库申请发起人按业务分类限定岗位")
    void oreqApplicantRoleRestricted() {
        ensureStock(20, 20);
        loginAsWarehouse();
        BizOutReq projectReq = newOutReq("工程类", "工程领料", 1, "junit-warehouse");
        assertThrows(RuntimeException.class, () -> outReqService.create(projectReq));

        loginAsFieldManager();
        BizOutReq operationReq = newOutReq("运营类", "生活用料", 1, "junit-field-manager");
        assertThrows(RuntimeException.class, () -> outReqService.create(operationReq));

        loginAsServiceStaff();
        BizOutReq created = outReqService.create(newOutReq("运营类", "生活用料", 1, "junit-service-staff"));
        assertDocNo(created.getDocNo(), "SA");
        assertEquals("DRAFT", created.getStatus());
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("SYS-01: 用户账号 Excel 导入可新增并更新岗位角色")
    void userImportCreatesAndUpdatesRoles() {
        loginAs(TEST_FINANCE_ID, List.of("account_admin"));

        SysUserImportService.ImportResult inserted = userImportService.importExcel(
                userImportFile("service_staff", "启用", "junit2026"));
        assertEquals(1, inserted.getInserted());
        assertEquals(0, inserted.getSkipped());

        Long userId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM sys_user
                WHERE username = 'junit_import_user' AND deleted = 0
                """, Long.class);
        assertNotNull(userId);
        assertEquals(20L, jdbcTemplate.queryForObject("""
                SELECT org_id FROM sys_user WHERE id = ?
                """, Long.class, userId));
        assertTrue(jdbcTemplate.queryForList("""
                SELECT r.code
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, userId).contains("service_staff"));

        SysUserImportService.ImportResult updated = userImportService.importExcel(
                userImportFile("warehouse", "停用", ""));
        assertEquals(1, updated.getUpdated());
        assertEquals(0, updated.getSkipped());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId));
        List<String> roles = jdbcTemplate.queryForList("""
                SELECT r.code
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                """, String.class, userId);
        assertTrue(roles.contains("warehouse"));
        assertTrue(!roles.contains("service_staff"));
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OUT-02: 出库数量不能超过申请剩余数量")
    void outExceedsOreq() {
        ensureStock(20, 20);
        BizOutReq oreq = createAndApproveOreq(3);
        loginAsWarehouse();
        assertThrows(RuntimeException.class, () ->
                outService.fromOreq(oreq.getId(),
                        List.of(outIn(oreq.getLines().get(0).getId(), BigDecimal.valueOf(100))), "生活用料")
        );
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("LOAN-01: 借料登记(草稿) -> 提交材料会计审核 -> 扣库存 -> 归还 -> 库存恢复")
    void loanFlow() {
        BigDecimal beforeQty = ensureStock(20, 20);

        loginAsWarehouse();
        BizLoan loan = new BizLoan();
        loan.setWarehouseId(WH_ID);
        loan.setBorrower("测试借料人");
        loan.setBorrowDate(TODAY);
        loan.setPurpose("测试借料用途");
        BizLoanLine loanLine = new BizLoanLine();
        loanLine.setMaterialId(MAT_ID);
        loanLine.setQty(BigDecimal.valueOf(3));
        loan.setLines(List.of(loanLine));
        BizLoan created = loanService.create(loan);
        assertEquals("DRAFT", created.getStatus(), "借料登记创建为草稿，未扣库存");
        assertEquals(0, waService.currentBalance(MAT_ID, WH_ID).getQty().compareTo(beforeQty),
                "借料草稿阶段库存不应变化");

        // 提交 -> 材料会计审核通过：此刻才按加权平均扣减库存，状态置「已登记」
        loanService.submit(created.getId());
        approveDocument("LOAN", created.getId(), loanService::approve);
        String loanStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM biz_loan WHERE id = ?", String.class, created.getId());
        assertEquals("REGISTERED", loanStatus, "借料审核通过后状态应为已登记");

        BigDecimal afterLoan = waService.currentBalance(MAT_ID, WH_ID).getQty();
        assertEquals(0, afterLoan.compareTo(beforeQty.subtract(BigDecimal.valueOf(3))),
                "借料审核通过后库存应减少 3");

        LoanService.LoanReturnDTO ret = new LoanService.LoanReturnDTO();
        LoanService.LoanReturnLineDTO retLine = new LoanService.LoanReturnLineDTO();
        retLine.setLoanLineId(created.getLines().get(0).getId());
        retLine.setQty(BigDecimal.valueOf(3));
        ret.setLines(List.of(retLine));
        ret.setRemark("归还测试");
        loginAsWarehouse();
        loanService.registerReturn(created.getId(), ret);

        BigDecimal afterReturn = waService.currentBalance(MAT_ID, WH_ID).getQty();
        assertEquals(0, afterReturn.compareTo(beforeQty), "归还后库存应恢复");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("RETURN-01: 退料后库存回增")
    void returnFlow() {
        ensureStock(20, 20);
        BizOutReq oreq = createAndApproveOreq(5);
        loginAsWarehouse();
        BizOut out = approveOut(outService.fromOreq(oreq.getId(),
                List.of(outIn(oreq.getLines().get(0).getId(), BigDecimal.valueOf(5))), "生活用料"));

        BigDecimal beforeReturn = waService.currentBalance(MAT_ID, WH_ID).getQty();

        BizReturn ret = new BizReturn();
        ret.setSourceOutId(out.getId());
        ret.setReturnDate(TODAY);
        ret.setWarehouseId(WH_ID);
        ret.setDeptId(1L);
        ret.setOrgId(1L);
        BizReturnLine retLine = new BizReturnLine();
        retLine.setSourceOutLineId(out.getLines().get(0).getId());
        retLine.setMaterialId(MAT_ID);
        retLine.setQty(BigDecimal.valueOf(2));
        ret.setLines(List.of(retLine));

        loginAsWarehouse();
        BizReturn created = returnService.fromOut(ret);
        returnService.submit(created.getId());
        approveDocument("RETURN", created.getId(), returnService::approve);

        BigDecimal afterReturn = waService.currentBalance(MAT_ID, WH_ID).getQty();
        assertEquals(0, afterReturn.compareTo(beforeReturn.add(BigDecimal.valueOf(2))),
                "退料后库存应增加 2");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("RETURN-02: 三次各退1件，最后接回3件10元的尾差")
    void partialReturnsRestoreExactOriginalCost() {
        waService.inbound(MAT_ID, WH_ID, new BigDecimal("3"), new BigDecimal("3.33"), new BigDecimal("10.00"),
                "INIT", "INIT", 999L, "FIX-RETURN-INIT", null, "");
        BizOutReq req=createAndApproveOreq(3);
        loginAsWarehouse();
        BizOut out=approveOut(outService.fromOreq(req.getId(), List.of(outIn(req.getLines().get(0).getId(), new BigDecimal("3"))), "生活用料"));
        for (int i=0; i<3; i++) {
            loginAsWarehouse();
            BizReturn ret=new BizReturn();ret.setSourceOutId(out.getId());ret.setReturnDate(TODAY);
            BizReturnLine ln=new BizReturnLine();ln.setSourceOutLineId(out.getLines().get(0).getId());ln.setQty(BigDecimal.ONE);
            ret.setLines(List.of(ln));
            BizReturn created=returnService.fromOut(ret);returnService.submit(created.getId());
            approveDocument("RETURN", created.getId(), returnService::approve);
        }
        assertEquals(0, new BigDecimal("10.00").compareTo(waService.currentBalance(MAT_ID,WH_ID).getAmount()));
        assertEquals(0, new BigDecimal("3").compareTo(waService.currentBalance(MAT_ID,WH_ID).getQty()));
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-LR-01: 领料生成的出库申请(无审批实例)取消审批 -> 申请作废+领料退回待财务(2026-10-18 U10 起不再退回草稿)")
    void cancelLrGeneratedOreq() {
        // 领料链财务确认后直接生成 APPROVED 出库申请、不建 wf_instance —— 裸插模拟该形态。
        // 回归:ABC-SA-20260701-0001 取消审批曾报"取消审批申请与原审批单据不匹配"。
        jdbcTemplate.update("""
                INSERT INTO biz_out_req (doc_no, org_id, dept_id, warehouse_id, applicant_id,
                                         status, apply_date, purpose, remark, deleted)
                VALUES ('JUNIT-SA-LR-0001', 1, 1, ?, ?, 'APPROVED', ?, 'PROJECT_USE', '由领料申请 JUNIT-LR-0001 生成', 0)
                """, WH_ID, TEST_PURCHASER_ID, TODAY);
        Long oreqId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_out_req WHERE doc_no = 'JUNIT-SA-LR-0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO lr_request (doc_no, org_id, warehouse_id, status, finance_by, finance_at,
                                        out_req_id, out_req_no, deleted)
                VALUES ('JUNIT-LR-0001', 1, ?, 'APPROVED', '测试材料会计', NOW(), ?, 'JUNIT-SA-LR-0001', 0)
                """, WH_ID, oreqId);

        loginAsFinance();
        var cr = wfService.requestCancel("OREQ", oreqId, "JUNIT-SA-LR-0001", "填错了，取消重报");
        assertEquals("APPROVED", cr.getStatus(), "领料生成单的取消应当场生效");

        // 计算审查 U10:以前退回草稿 → 旧单可重提审批、领料单又能再生成一张,同一需求两份额度。现在直接作废,只留重新生成一条路
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM biz_out_req WHERE id = ?", String.class, oreqId), "出库申请应作废");
        Map<String, Object> lr = jdbcTemplate.queryForMap(
                "SELECT status, out_req_id, finance_by FROM lr_request WHERE doc_no = 'JUNIT-LR-0001'");
        assertEquals("WAREHOUSE_OK", lr.get("status"), "领料申请应退回待财务确认");
        assertNull(lr.get("out_req_id"), "生成关联应解除");
        assertNull(lr.get("finance_by"), "财务确认记名应清除");
        // 作废后给发起人(采购)发普通通知,不发待办(作废单不需要任何人处理)
        Integer todo = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notif WHERE user_id = ? AND type = 'TODO'
                  AND biz_type = 'OREQ' AND biz_id = ? AND is_read = 0
                """, Integer.class, TEST_PURCHASER_ID, oreqId);
        assertEquals(0, todo, "作废单不应给发起人发待办");
        Integer info = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notif WHERE user_id = ? AND biz_type = 'OREQ' AND biz_id = ? AND is_read = 0
                """, Integer.class, TEST_PURCHASER_ID, oreqId);
        assertEquals(1, info, "应告知发起人这张申请已作废");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("OREQ-LR-02: 领料生成草稿单由采购(发起人)删；财务(审批人)不可；普通单非申请人不可")
    void deleteLrGeneratedOreqDraft() {
        // 新单形态：领料生成的出库申请 applicant = 采购(收件箱受理人)
        jdbcTemplate.update("""
                INSERT INTO biz_out_req (doc_no, org_id, dept_id, warehouse_id, applicant_id,
                                         status, apply_date, purpose, remark, deleted)
                VALUES ('JUNIT-SA-LR-0002', 1, 1, ?, ?, 'DRAFT', ?, 'PROJECT_USE', '由领料申请 JUNIT-LR-0002 生成', 0)
                """, WH_ID, TEST_PURCHASER_ID, TODAY);
        Long lrOreqId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_out_req WHERE doc_no = 'JUNIT-SA-LR-0002'", Long.class);
        // 财务(材料会计，审批人)不应能删发起人的单
        loginAs(99004L, List.of("material_accountant"));
        assertThrows(RuntimeException.class, () -> outReqService.delete(lrOreqId),
                "财务是审批人，不应能删领料生成的出库申请");
        // 采购(发起人本人)删 —— 成功
        loginAsPurchaser();
        outReqService.delete(lrOreqId);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_out_req WHERE id = ? AND deleted = 0", Integer.class, lrOreqId),
                "采购(发起人)应能删领料生成的草稿出库申请");

        // 历史单形态：applicant 是财务确认者，采购角色兜底(remark 识别)仍可删
        jdbcTemplate.update("""
                INSERT INTO biz_out_req (doc_no, org_id, dept_id, warehouse_id, applicant_id,
                                         status, apply_date, purpose, remark, deleted)
                VALUES ('JUNIT-SA-LR-0003', 1, 1, ?, ?, 'DRAFT', ?, 'PROJECT_USE', '由领料申请 JUNIT-LR-0003 生成', 0)
                """, WH_ID, TEST_FINANCE_ID, TODAY);
        Long histId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_out_req WHERE doc_no = 'JUNIT-SA-LR-0003'", Long.class);
        loginAsPurchaser();
        outReqService.delete(histId);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_out_req WHERE id = ? AND deleted = 0", Integer.class, histId),
                "采购应能兜底删历史领料生成单(applicant=财务)");

        // 反向：普通(非领料生成)草稿单，非申请人的采购不应能删
        jdbcTemplate.update("""
                INSERT INTO biz_out_req (doc_no, org_id, dept_id, warehouse_id, applicant_id,
                                         status, apply_date, purpose, remark, deleted)
                VALUES ('JUNIT-SA-NORMAL-1', 1, 1, ?, ?, 'DRAFT', ?, 'PROJECT_USE', '现场手工建单', 0)
                """, WH_ID, TEST_FIELD_MANAGER_ID, TODAY);
        Long normalId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_out_req WHERE doc_no = 'JUNIT-SA-NORMAL-1'", Long.class);
        loginAsPurchaser();
        assertThrows(RuntimeException.class, () -> outReqService.delete(normalId),
                "非领料生成的普通草稿单，非申请人采购不应能删");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("MYDOC-01: my-documents 只返回我发起的草稿/退回单，不泄露他人的")
    void myDocumentsScoped() {
        // 我(采购)的草稿 + 他人(现场)的草稿，均为出库申请(applicant_id 发起人)
        jdbcTemplate.update("""
                INSERT INTO biz_out_req (doc_no, org_id, dept_id, warehouse_id, applicant_id,
                                         status, apply_date, purpose, deleted)
                VALUES ('JUNIT-MYDOC-MINE', 1, 1, ?, ?, 'DRAFT', ?, 'PROJECT_USE', 0),
                       ('JUNIT-MYDOC-OTHER', 1, 1, ?, ?, 'DRAFT', ?, 'PROJECT_USE', 0)
                """, WH_ID, TEST_PURCHASER_ID, TODAY, WH_ID, TEST_FIELD_MANAGER_ID, TODAY);

        loginAsPurchaser();
        java.util.Set<String> nos = wfService.myDocuments("DRAFT,REJECTED").stream()
                .map(d -> String.valueOf(d.get("bizNo"))).collect(java.util.stream.Collectors.toSet());
        assertTrue(nos.contains("JUNIT-MYDOC-MINE"), "应返回我发起的草稿");
        assertFalse(nos.contains("JUNIT-MYDOC-OTHER"), "不应泄露他人发起的草稿");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("INV-02: 出库超库存应被拒绝")
    void negativeBalanceRejected() {
        loginAsWarehouse();
        waService.inbound(MAT_ID, WH_ID, BigDecimal.valueOf(3), BigDecimal.TEN,
                "INIT", "INIT", 0L, "INIT", null, "期初");
        assertThrows(RuntimeException.class,
                () -> waService.outbound(MAT_ID, WH_ID, BigDecimal.valueOf(100),
                        "OUT", 99L, "OUT-X", null, ""),
                "库存不足应抛异常");
    }

    private MockMultipartFile userImportFile(String roleText, String statusText, String password) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("填写模板");
            Row header = sheet.createRow(0);
            String[] headers = {"登录账号", "姓名", "人员归属", "岗位角色", "初始密码", "手机", "邮箱", "状态"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

            Row row = sheet.createRow(1);
            String[] values = {
                    "junit_import_user",
                    "导入测试人员",
                    "OPERATION",
                    roleText,
                    password,
                    "13900000000",
                    "junit-import@example.com",
                    statusText
            };
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            workbook.write(out);
            return new MockMultipartFile(
                    "file",
                    "users.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static RecvService.RecvLineInput recvIn(Long poLineId, BigDecimal qty) {
        RecvService.RecvLineInput in = new RecvService.RecvLineInput();
        in.poLineId = poLineId;
        in.qty = qty;
        return in;
    }

    private static OutService.OutLineInput outIn(Long reqLineId, BigDecimal qty) {
        OutService.OutLineInput in = new OutService.OutLineInput();
        in.reqLineId = reqLineId;
        in.qty = qty;
        return in;
    }

    private BizPo newPo(int qty, String price) {
        BizPo po = new BizPo();
        po.setSupplierId(SUPPLIER_ID);
        po.setWarehouseId(WH_ID);
        po.setOrderDate(TODAY);
        po.setDeliveryDate(TODAY);
        po.setRemark("JUNIT-" + System.currentTimeMillis());
        BizPoLine line = new BizPoLine();
        line.setMaterialId(MAT_ID);
        line.setQty(BigDecimal.valueOf(qty));
        line.setUnitPrice(new BigDecimal(price));
        line.setAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        po.setLines(List.of(line));
        return po;
    }

    private BizPo createAndApprovePo(int qty, String price) {
        loginAsPurchaser();
        BizPo po = poService.create(newPo(qty, price));
        poService.submit(po.getId());
        approveDocument("PO", po.getId(), poService::approve);
        return poService.detail(po.getId());
    }

    private BigDecimal ensureStock(int poQty, int recvQty) {
        BizPo po = createAndApprovePo(poQty, "10.00");
        loginAsWarehouse();
        BizRecv recv = recvService.fromPo(po.getId(), "INVOICE",
                List.of(recvIn(po.getLines().get(0).getId(), BigDecimal.valueOf(recvQty))),
                "ensure-stock");
        approveRecv(recv);
        return waService.currentBalance(MAT_ID, WH_ID).getQty();
    }

    private BizRecv approveRecv(BizRecv recv) {
        loginAsWarehouse();
        recvService.submit(recv.getId());
        approveDocument("RECV", recv.getId(), recvService::approve);
        return recvService.detail(recv.getId());
    }

    private BizOutReq createAndApproveOreq(int qty) {
        loginAsFieldManager();
        BizOutReq oreq = new BizOutReq();
        oreq.setWarehouseId(WH_ID);
        oreq.setPurpose("PROJECT_USE");
        fillOutReqHeader(oreq);
        oreq.setApplyDate(TODAY);
        BizOutReqLine line = new BizOutReqLine();
        line.setMaterialId(MAT_ID);
        line.setQty(BigDecimal.valueOf(qty));
        oreq.setLines(List.of(line));
        BizOutReq created = outReqService.create(oreq);
        outReqService.submit(created.getId());
        approveDocument("OREQ", created.getId(), outReqService::approve);
        return outReqService.detail(created.getId());
    }

    private BizOutReq createPendingOreq(String businessCategory, String outType, int qty) {
        ensureStock(qty, qty);
        loginForOutReqCategory(businessCategory, outType);
        String applicant = isProjectOutReq(businessCategory, outType) ? "junit-field-manager" : "junit-service-staff";
        BizOutReq oreq = newOutReq(businessCategory, outType, qty, applicant);
        BizOutReq created = outReqService.create(oreq);
        outReqService.submit(created.getId());
        return created;
    }

    private void assertNextApproverRole(String bizType, Long bizId, String expectedRole) {
        String nextRole = jdbcTemplate.queryForObject("""
                SELECT next_approver_role
                FROM wf_instance
                WHERE biz_type = ? AND biz_id = ? AND status = 'PROCESSING'
                ORDER BY id DESC
                LIMIT 1
                """, String.class, bizType, bizId);
        assertEquals(expectedRole, nextRole);
    }

    private void fillOutReqHeader(BizOutReq oreq) {
        oreq.setProjectId(PROJECT_ID);
        oreq.setOutType("PROJECT_MATERIAL");
        oreq.setBusinessCategory("PROJECT");
        oreq.setMaterialApplicant("junit-field-manager");
    }

    private BizOutReq newOutReq(String businessCategory, String outType, int qty, String applicant) {
        BizOutReq oreq = new BizOutReq();
        oreq.setWarehouseId(WH_ID);
        oreq.setPurpose("JUNIT_ROUTE");
        oreq.setProjectId(PROJECT_ID);
        oreq.setOutType(outType);
        oreq.setBusinessCategory(businessCategory);
        oreq.setMaterialApplicant(applicant);
        oreq.setApplyDate(TODAY);
        BizOutReqLine line = new BizOutReqLine();
        line.setMaterialId(MAT_ID);
        line.setQty(BigDecimal.valueOf(qty));
        oreq.setLines(List.of(line));
        return oreq;
    }

    private BizOut approveOut(BizOut out) {
        loginAsWarehouse();
        outService.submit(out.getId());
        approveDocument("OUT", out.getId(), outService::approve);
        return outService.detail(out.getId());
    }

    private void seedTestUser(long userId, String username, String name, String roleCode) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, name, org_id, status, deleted)
                VALUES (?, ?, ?, ?, 1, 1, 0)
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username),
                    password = VALUES(password),
                    name = VALUES(name),
                    org_id = VALUES(org_id),
                    status = 1,
                    deleted = 0
                """, userId, username, TEST_PASSWORD_HASH, name);
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id)
                SELECT ?, id FROM sys_role WHERE code = ? AND deleted = 0 AND status = 1
                """, userId, roleCode);
    }

    private void seedTestMasterData() {
        jdbcTemplate.update("""
                INSERT INTO md_material (id, code, name, spec, unit, org_id, is_shared, status, deleted)
                VALUES (?, 'JUNIT-MAT-99901', 'JUnit测试物料', 'JUNIT', '个', 1, 1, 1, 0)
                ON DUPLICATE KEY UPDATE
                    code = VALUES(code),
                    name = VALUES(name),
                    spec = VALUES(spec),
                    unit = VALUES(unit),
                    org_id = VALUES(org_id),
                    is_shared = VALUES(is_shared),
                    status = 1,
                    deleted = 0
                """, MAT_ID);
        jdbcTemplate.update("""
                INSERT INTO md_warehouse (id, code, name, warehouse_type, org_id, manager_id, status, deleted)
                VALUES (?, 'JUNIT-WH-99901', 'JUnit测试仓库', 'MAIN', 1, ?, 1, 0)
                ON DUPLICATE KEY UPDATE
                    code = VALUES(code),
                    name = VALUES(name),
                    warehouse_type = VALUES(warehouse_type),
                    org_id = VALUES(org_id),
                    manager_id = VALUES(manager_id),
                    status = 1,
                    deleted = 0
                """, WH_ID, TEST_WAREHOUSE_ID);
        jdbcTemplate.update("""
                INSERT INTO md_project (id, parent_id, org_id, code, name, category, status, deleted)
                VALUES (?, 0, 1, 'JUNIT-PRJ-99901', 'JUnit Test Project', 'PROJECT', 1, 0)
                ON DUPLICATE KEY UPDATE
                    parent_id = VALUES(parent_id),
                    org_id = VALUES(org_id),
                    code = VALUES(code),
                    name = VALUES(name),
                    category = VALUES(category),
                    status = 1,
                    deleted = 0
                """, PROJECT_ID);
    }

    private void loginAsPurchaser() {
        loginAs(TEST_PURCHASER_ID, List.of("purchaser"));
    }

    private void loginAsWarehouse() {
        loginAs(TEST_WAREHOUSE_ID, List.of("warehouse"));
    }

    private void loginAsFinance() {
        loginAs(TEST_FINANCE_ID, List.of("material_accountant"));
    }

    private void loginAsFieldManager() {
        loginAs(TEST_FIELD_MANAGER_ID, List.of("field_manager"));
    }

    private void loginAsServiceStaff() {
        loginAs(TEST_SERVICE_STAFF_ID, List.of("service_staff"));
    }

    private void loginForOutReqCategory(String businessCategory, String outType) {
        if (isProjectOutReq(businessCategory, outType)) {
            loginAsFieldManager();
        } else {
            loginAsServiceStaff();
        }
    }

    private boolean isProjectOutReq(String businessCategory, String outType) {
        return "工程类".equals(businessCategory) || "工程业务".equals(businessCategory) || "PROJECT".equals(businessCategory)
                || "工程领料".equals(outType) || "PROJECT_MATERIAL".equals(outType);
    }

    private void assertDocNo(String docNo, String prefix) {
        assertNotNull(docNo);
        assertTrue(docNo.matches("^[A-Z0-9]+-" + prefix + "-\\d{8}-\\d{4}$"),
                "单据号应为 公司简称-" + prefix + "-YYYYMMDD-流水号，实际：" + docNo);
    }

    private void loginAs(long userId, List<String> roles) {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        StpUtil.login(userId);
        StpUtil.getSession().set("roles", roles);
        StpUtil.getSession().set("perms", List.of());
        StpUtil.getSession().set("dataScopes", List.of("ACCOUNT_SET"));
        StpUtil.getSession().set("orgId", 1L);
    }

    private void loginAsNextApprover(String bizType, Long bizId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT next_approver_id, next_approver_role
                FROM wf_instance
                WHERE biz_type = ? AND biz_id = ? AND status = 'PROCESSING'
                ORDER BY id DESC
                LIMIT 1
                """, bizType, bizId);
        Number approverId = (Number) row.get("next_approver_id");
        String roleCode = (String) row.get("next_approver_role");
        long userId = approverId == null
                ? ROLE_APPROVER_USERS.getOrDefault(roleCode, TEST_FINANCE_ID)
                : approverId.longValue();

        List<String> roles = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT r.code
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.deleted = 0 AND r.status = 1
                """, String.class, userId));
        if (roleCode != null && !roles.contains(roleCode)) {
            roles.add(roleCode);
        }
        loginAs(userId, roles);
    }

    private void approveDocument(String bizType, Long bizId, ApprovalCall approvalCall) {
        for (int i = 0; i < 10; i++) {
            Integer active = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM wf_instance
                    WHERE biz_type = ? AND biz_id = ? AND status = 'PROCESSING'
                    """, Integer.class, bizType, bizId);
            if (active == null || active == 0) {
                return;
            }
            loginAsNextApprover(bizType, bizId);
            approvalCall.approve(bizId, "同意");
        }
        fail("审批流程超过 10 个节点仍未结束：" + bizType + "/" + bizId);
    }

    @FunctionalInterface
    private interface ApprovalCall {
        void approve(Long id, String comment);
    }
}
