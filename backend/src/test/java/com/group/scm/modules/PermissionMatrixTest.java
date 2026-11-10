package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.inventory.controller.InventoryController;
import com.group.scm.modules.inventory.controller.RecvController;
import com.group.scm.modules.inventory.entity.BizTransfer;
import com.group.scm.modules.inventory.entity.BizTransferLine;
import com.group.scm.modules.inventory.service.TransferService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.invoice.controller.InvoiceController;
import com.group.scm.modules.invoice.entity.BizInvoice;
import com.group.scm.modules.outbound.controller.OutboundController;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.purchase.controller.PoController;
import com.group.scm.modules.purchase.entity.BizPo;
import com.group.scm.modules.workflow.service.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色权限矩阵测试：逐条断言"哪个角色不能做哪个操作"。
 *
 * <p>各单据的写操作在 Controller 第一行用 {@link com.group.scm.common.SecurityUtil#requireAnyRole}
 * 等做角色闸门，越权会抛 {@code BizException(4003)} 且早于任何业务逻辑——因此这里直接以越权角色
 * 调用控制器方法，断言被 4003 拦截。这类负向断言是安全保证(防越权)，确定性强、不依赖业务数据。</p>
 *
 * <p>正向(指定审批人能审批、申请人不能审批自己的单)等需要真实审批流的场景，在
 * {@code BusinessFlowTest} 里基于已提交单据断言。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true",
        disabledReason = "需要隔离 MySQL 测试库")
@DisplayName("角色权限矩阵")
class PermissionMatrixTest {

    /**
     * 「提交时就查库存」(SubmitStockGuard)故意另开一个只读小事务读最新库存;本类在测试事务里造的库存
     * 还没提交,它读不到,会把正常单据误判为库存不足。这里换成放行,专心测本类的流程。
     * 该检查本身由全流程测试 scripts/e2e-local.sh 第 35 章覆盖(不够不进审批、扣待审占用、并发)。
     */
    @MockitoBean private com.group.scm.modules.inventory.service.SubmitStockGuard submitStockGuard;

    @Autowired private PoController poController;
    @Autowired private RecvController recvController;
    @Autowired private OutboundController outboundController;
    @Autowired private InvoiceController invoiceController;
    @Autowired private InventoryController inventoryController;
    @Autowired private TransferService transferService;
    @Autowired private WorkflowService workflowService;
    @Autowired private WaInventoryService waInventoryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearLogin() {
        try { if (StpUtil.isLogin()) StpUtil.logout(); } catch (Exception ignored) {}
    }

    private void loginAs(String... roles) {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(99100L);
        StpUtil.getSession().set("roles", List.of(roles));
        StpUtil.getSession().set("perms", List.of());
        StpUtil.getSession().set("dataScopes", List.of("ACCOUNT_SET"));
        StpUtil.getSession().set("orgId", 1L);
    }

    /** 断言:这些角色调用该操作都会被权限闸门(4003)拦截。 */
    private void forbid(String op, Executable call, String... forbiddenRoles) {
        for (String role : forbiddenRoles) {
            loginAs(role);
            BizException e = assertThrows(BizException.class, call,
                    op + " 不应允许角色【" + role + "】");
            assertEquals(4003, e.getCode(),
                    op + " 对角色【" + role + "】应返回 4003 无权，实际：" + e.getMessage());
        }
    }

    // ============ 采购订单（仅采购员可建/提交/撤回/删除）============
    @Test
    @DisplayName("PERM-PO: 非采购员不能建单/提交/撤回/删除采购订单")
    void poGates() {
        forbid("创建采购订单", () -> poController.create(new BizPo()), "warehouse", "material_accountant", "field_manager", "service_staff");
        forbid("提交采购订单", () -> poController.submit(1L), "warehouse", "material_accountant", "field_manager");
        forbid("取消提交采购订单", () -> poController.withdraw(1L), "material_accountant", "field_manager", "service_staff");
        forbid("删除采购订单", () -> poController.delete(1L), "material_accountant", "field_manager", "service_staff");
        // 工程现场角色连采购订单列表都不该看到
        forbid("查看采购订单列表", () -> poController.page(1, 20, null, null, null, null, null, null, null, null, null), "field_manager", "service_staff");
    }

    // ============ 采购入库（仅仓管可下推/提交/撤回；删除仓管或管理员）============
    @Test
    @DisplayName("PERM-RECV: 非仓管不能提交/撤回入库单")
    void recvGates() {
        forbid("提交入库单", () -> recvController.submit(1L), "purchaser", "material_accountant", "field_manager");
        forbid("取消提交入库单", () -> recvController.withdraw(1L), "purchaser", "field_manager", "service_staff");
        forbid("删除入库单", () -> recvController.delete(1L), "purchaser", "material_accountant", "field_manager");
    }

    // ============ 采购发票（仅财务/材料会计可录入/提交；撤回删除财务或管理员）============
    @Test
    @DisplayName("PERM-INV: 非财务不能录入/提交/撤回发票")
    void invoiceGates() {
        forbid("录入发票", () -> invoiceController.create(new BizInvoice()), "purchaser", "warehouse", "field_manager", "service_staff");
        forbid("提交发票", () -> invoiceController.submit(1L), "purchaser", "warehouse", "field_manager");
        forbid("取消提交发票", () -> invoiceController.withdraw(1L), "purchaser", "warehouse", "field_manager");
        forbid("删除发票", () -> invoiceController.delete(1L), "purchaser", "warehouse", "field_manager");
    }

    // ============ 出库申请（现场/客服/采购可建/提交/撤回；仓管/材料会计不可）============
    // 采购(purchaser)自 v1.3 起是合法申请人：领料收件箱由采购受理并生成出库申请。
    @Test
    @DisplayName("PERM-OREQ: 仓管/材料会计不能建/提交/撤回出库申请")
    void outReqGates() {
        forbid("创建出库申请", () -> outboundController.oreqCreate(new BizOutReq()), "warehouse", "material_accountant");
        forbid("提交出库申请", () -> outboundController.oreqSubmit(1L), "warehouse", "material_accountant");
        forbid("取消提交出库申请", () -> outboundController.oreqWithdraw(1L), "warehouse", "material_accountant");
    }

    // ============ 材料出库 / 退料（仅仓管可提交/撤回）============
    @Test
    @DisplayName("PERM-OUT: 非仓管不能提交/撤回出库单与退料单")
    void outAndReturnGates() {
        forbid("提交出库单", () -> outboundController.outSubmit(1L), "purchaser", "material_accountant", "field_manager");
        forbid("取消提交出库单", () -> outboundController.outWithdraw(1L), "purchaser", "material_accountant", "field_manager");
        forbid("提交退料单", () -> outboundController.returnSubmit(1L), "purchaser", "material_accountant", "field_manager");
        forbid("取消提交退料单", () -> outboundController.returnWithdraw(1L), "purchaser", "material_accountant", "field_manager");
    }

    // ============ 转库（仅仓管可提交/撤回）============
    @Test
    @DisplayName("PERM-TRANSFER: 非仓管不能提交/撤回转库单")
    void transferGates() {
        forbid("提交转库单", () -> inventoryController.transferSubmit(1L), "purchaser", "material_accountant", "field_manager");
        forbid("取消提交转库单", () -> inventoryController.transferWithdraw(1L), "purchaser", "material_accountant", "field_manager");
    }

    // ============ 转库取消审批（通用取消审批覆盖 TRANSFER：反审冲销库存并退回草稿）============
    @Test
    @DisplayName("TRANSFER-CANCEL: 转库单取消审批回退库存并退回草稿；关联领料生成的不走此路径")
    @Transactional
    void transferCancelApprove() {
        long uid = 98601L, finId = 98602L, wh1 = 98603L, wh2 = 98604L, matId = 98605L, pickupId = 98606L;
        jdbcTemplate.update("INSERT INTO sys_user(id,username,password,name,org_id,status,deleted) VALUES(?,'tc-warehouse','disabled','反审仓管',1,1,0),(?,'tc-finance','disabled','反审会计',1,1,0)", uid, finId);
        jdbcTemplate.update("INSERT INTO md_material(id,code,name,unit,org_id,status,deleted) VALUES(?,'TC-MAT','反审物料','个',1,1,0)", matId);
        jdbcTemplate.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id,status,deleted) VALUES(?,'TC-WH1','反审原仓','MAIN',1,1,0),(?,'TC-WH2','反审目标仓','SECONDARY',1,1,0)", wh1, wh2);
        waInventoryService.inbound(matId, wh1, new BigDecimal("100"), new BigDecimal("1.23"), new BigDecimal("123"),
                "INIT", "INIT", matId, "TC-INIT", null, "");
        // 单节点角色审批模板（事务内生效，回滚后自动还原）
        jdbcTemplate.update("UPDATE wf_template SET nodes_json='[{\"node\":\"REVIEW\",\"approverRole\":\"material_accountant\",\"name\":\"材料会计\"}]' WHERE biz_type='TRANSFER'");

        // 建单 → 提交 → 审批过账：库存从原仓转到目标仓
        loginUser(uid, "warehouse");
        BizTransfer transfer = new BizTransfer();
        transfer.setFromWarehouseId(wh1);
        transfer.setToWarehouseId(wh2);
        transfer.setTransferDate(LocalDate.now());
        BizTransferLine line = new BizTransferLine();
        line.setMaterialId(matId);
        line.setQty(new BigDecimal("10"));
        transfer.setLines(List.of(line));
        transfer = transferService.create(transfer);
        transferService.submit(transfer.getId());
        loginUser(finId, "material_accountant");
        transferService.approve(transfer.getId(), "同意");
        assertEquals("APPROVED", transferService.detail(transfer.getId()).getStatus());
        assertEquals(0, new BigDecimal("90").compareTo(waInventoryService.currentBalance(matId, wh1).getQty()));
        assertEquals(0, new BigDecimal("10").compareTo(waInventoryService.currentBalance(matId, wh2).getQty()));

        // 申请人发起取消审批（原审批人是会计 → 进待办），原审批人同意后反审生效
        loginUser(uid, "warehouse");
        var cr = workflowService.requestCancel("TRANSFER", transfer.getId(), transfer.getDocNo(), "取消审批");
        assertEquals("PENDING", cr.getStatus());
        loginUser(finId, "material_accountant");
        workflowService.processCancel(cr.getId(), true, "同意取消", null);

        assertEquals("DRAFT", jdbcTemplate.queryForObject(
                "SELECT status FROM biz_transfer WHERE id = ?", String.class, transfer.getId()));
        // 库存按原流水冲销回位（转出仓补回 10，转入仓减掉 10），并留下成对 REVERSE 流水
        assertEquals(0, new BigDecimal("100").compareTo(waInventoryService.currentBalance(matId, wh1).getQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(waInventoryService.currentBalance(matId, wh2).getQty()));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inv_transaction WHERE biz_doc_type='TRANSFER' AND biz_doc_id=? AND trans_type='REVERSE'",
                Integer.class, transfer.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_transfer_line WHERE transfer_id=? AND unit_price = 0 AND amount = 0",
                Integer.class, transfer.getId()));

        // 关联领料生成的转库单（source_req_id 非空）不走通用取消审批，给明确报错
        jdbcTemplate.update("""
                INSERT INTO biz_transfer(id,doc_no,from_warehouse_id,to_warehouse_id,org_id,transfer_date,status,source_req_id,created_by,deleted)
                VALUES(?,'TC-PICKUP',?,?,1,CURDATE(),'READY',?,?,0)
                """, pickupId, wh1, wh2, 999L, uid);
        jdbcTemplate.update("""
                INSERT INTO wf_instance(id,biz_type,biz_id,biz_no,status,current_node,next_approver_id,applicant_id,total_amt,started_at)
                VALUES(?,'TRANSFER',?,'TC-PICKUP','APPROVED','REVIEW',?,?,0,NOW())
                """, 98608L, pickupId, finId, uid);
        jdbcTemplate.update("""
                INSERT INTO wf_cancel_request(id,biz_type,biz_id,biz_no,applicant_id,origin_approver_id,reason,status,created_at)
                VALUES(?,'TRANSFER',?,'TC-PICKUP',?,?, '取消审批','PENDING',NOW())
                """, 98607L, pickupId, uid, finId);
        loginUser(finId, "material_accountant");
        BizException e = assertThrows(BizException.class,
                () -> workflowService.processCancel(98607L, true, "同意取消", null));
        assertTrue(e.getMessage().contains("取消本次领料"),
                "关联领料的转库单应提示走「取消本次领料」，实际：" + e.getMessage());
    }

    private void loginUser(long id, String... roles) {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(id);
        StpUtil.getSession().set("roles", List.of(roles));
        StpUtil.getSession().set("perms", List.of());
        StpUtil.getSession().set("dataScopes", List.of("ACCOUNT_SET"));
        StpUtil.getSession().set("orgId", 1L);
    }
}
