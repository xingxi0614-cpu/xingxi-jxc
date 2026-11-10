package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.inventory.service.*;
import com.group.scm.modules.inventory.controller.StocktakeController;
import com.group.scm.modules.inventory.entity.*;
import com.group.scm.modules.outbound.service.LoanService;
import com.group.scm.modules.system.service.RoleAssignmentService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.purchase.mapper.BizPoMapper;
import com.group.scm.modules.purchase.entity.BizPo;
import com.group.scm.modules.toolbox.service.ToolboxService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 缺陷回归：在真实 MySQL 上验证财务守恒、状态机和服务端边界，所有业务行由事务回滚。 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS", matches="true")
class RemediationRegressionTest {
    static final long ID=99881L;
    @Autowired JdbcTemplate jdbc;
    @Autowired WaInventoryService wa;
    @Autowired StocktakeService takes;
    @Autowired StocktakeController controller;
    @Autowired LoanService loans;
    @Autowired RoleAssignmentService roles;
    @Autowired PeriodCloseService periods;
    @Autowired PoService po;
    @Autowired BizPoMapper poMapper;
    @Autowired ToolboxService toolbox;
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static void eq(String expected, BigDecimal actual) { assertEquals(0,n(expected).compareTo(actual)); }
    void login(String role) {
        if(StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(ID);
        StpUtil.getSession().set("roles",List.of(role));
        StpUtil.getSession().set("orgId",1L);
        StpUtil.getSession().set("dataScopes",List.of("ACCOUNT_SET"));
    }
    @BeforeEach void setup() {
        login("admin");
        jdbc.update("INSERT INTO md_material(id,code,name,unit,org_id,status,deleted) VALUES(?,'FIX-MAT','回归物料','个',1,1,0)",ID);
        jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id,status,deleted) VALUES(?,'FIX-WH','回归仓库','MAIN',1,1,0)",ID);
    }
    @AfterEach void logout() { if(StpUtil.isLogin()) StpUtil.logout(); }
    void stock(String qty,String amount) {
        wa.inbound(ID,ID,n(qty),n(amount).divide(n(qty),2,java.math.RoundingMode.HALF_UP),n(amount),"INIT","INIT",ID,"FIX-INIT",null,"");
    }
    @Test void tinyUnitCostNeverPostsMoreThanAvailable() {
        stock("100","0.60");
        eq("0.59",wa.outbound(ID,ID,n("99"),"OUT",ID,"FIX-OUT",null,"")[1]);
        eq("0.01",wa.currentBalance(ID,ID).getAmount());
        eq("0.01",wa.outbound(ID,ID,n("1"),"OUT",ID+1,"FIX-OUT2",null,"")[1]);
        eq("0",wa.currentBalance(ID,ID).getAmount());
        eq("0",jdbc.queryForObject("SELECT SUM(amount) FROM inv_transaction WHERE material_id=? AND warehouse_id=?",BigDecimal.class,ID,ID));
    }
    @Test void expenseReverseAndReapproveIsNotDuplicated() {
        // B 方案(2026-10-16):结存 5 件、开票 10 件 → 差额 10 一半进库存、一半进损益
        stock("10","100");
        wa.outbound(ID,ID,n("5"),"OUT",ID,"FIX-OUT",null,"");
        wa.adjustEstimateDiff(ID,ID,n("1"),n("10"),n("10"),"INV",ID,"FIX-INV","");
        eq("5",expense()); eq("55",wa.currentBalance(ID,ID).getAmount());
        wa.reverse(ID,"INV","");
        wa.reverse(ID,"INV","");
        eq("0",expense()); eq("50",wa.currentBalance(ID,ID).getAmount());
        wa.adjustEstimateDiff(ID,ID,n("1"),n("10"),n("10"),"INV",ID,"FIX-INV","");
        eq("5",expense());
        wa.reverse(ID,"INV","");
        eq("0",expense()); eq("50",wa.currentBalance(ID,ID).getAmount());
    }
    @Test void invoiceDiffInStockThenIssuedReverseGoesToExpense() {
        // 差额全部进库存后货被领光,再取消发票:库存吃不回 → 转损益,取消不被拦
        stock("10","100");
        wa.adjustEstimateDiff(ID,ID,n("1"),n("10"),n("10"),"INV",ID,"FIX-INV","");
        eq("0",expense()); eq("110",wa.currentBalance(ID,ID).getAmount());
        eq("110",wa.outbound(ID,ID,n("10"),"OUT",ID,"FIX-OUT",null,"")[1]);
        wa.reverse(ID,"INV","");
        eq("-10",expense()); eq("0",wa.currentBalance(ID,ID).getAmount()); eq("0",wa.currentBalance(ID,ID).getQty());
        // 再次反审不会重复冲(冲回流水与转损益流水都已配对)
        wa.reverse(ID,"INV","");
        eq("-10",expense());
    }
    @Test void purchaseReturnAtOriginalAmountAndFallback() {
        wa.inbound(ID,ID,n("10"),n("10"),n("100"),"IN","RECV",ID,"FIX-R1",null,"");
        wa.inbound(ID,ID,n("10"),n("20"),n("200"),"IN","RECV",ID+1,"FIX-R2",null,"");
        BigDecimal[] r = wa.outboundAtAmount(ID,ID,n("5"),n("50"),"OUT_PRET","PRET",ID,"FIX-PRET",null,"");
        eq("50",r[1]); eq("0",r[2]);
        eq("15",wa.currentBalance(ID,ID).getQty()); eq("250",wa.currentBalance(ID,ID).getAmount());
        // 退光:冲光剩余 250,原入库额只有 100 → 差额 150(损失)
        r = wa.outboundAtAmount(ID,ID,n("15"),n("100"),"OUT_PRET","PRET",ID+1,"FIX-PRET2",null,"");
        eq("250",r[1]); eq("150",r[2]);
        eq("0",wa.currentBalance(ID,ID).getAmount());
        // 取消第一张退货:库存精确恢复
        wa.reverse(ID,"PRET","");
        eq("5",wa.currentBalance(ID,ID).getQty()); eq("50",wa.currentBalance(ID,ID).getAmount());
    }
    BigDecimal expense() { return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM inv_transaction WHERE biz_doc_type='INV' AND biz_doc_id=? AND trans_type='ADJUST_EXPENSE'",BigDecimal.class,ID); }
    @Test void reverseReapproveReverseOnlyUndoesLatestPosting() {
        stock("10","100");
        wa.outbound(ID,ID,n("10"),"OUT",ID,"FIX-OUT",null,"");
        wa.reverse(ID,"OUT","");
        wa.outbound(ID,ID,n("10"),"OUT",ID,"FIX-OUT",null,"");
        wa.reverse(ID,"OUT","");
        eq("100",wa.currentBalance(ID,ID).getAmount()); eq("10",wa.currentBalance(ID,ID).getQty());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM inv_transaction WHERE biz_doc_type='OUT' AND biz_doc_id=? AND reversal_of_id IS NOT NULL",Integer.class,ID));
    }
    @Test void stocktakeRejectKeepsFreezeAndNullActuallyClears() {
        stock("100","100");
        var take=takes.create(ID,LocalDate.now(),false,"");
        assertThrows(BizException.class,()->takes.create(ID,LocalDate.now(),false,""));
        var line=take.getLines().get(0); line.setActualQty(n("80"));
        takes.saveActual(take.getId(),List.of(line));
        line.setActualQty(null); takes.saveActual(take.getId(),List.of(line));
        assertNull(takes.detail(take.getId()).getLines().get(0).getActualQty());
        assertThrows(BizException.class,()->takes.submit(take.getId()));
        line.setActualQty(n("80")); takes.saveActual(take.getId(),List.of(line));
        takes.submit(take.getId()); takes.reject(take.getId(),"复核");
        assertThrows(BizException.class,()->wa.outbound(ID,ID,n("20"),"OUT",ID,"FIX-O",null,""));
        takes.delete(take.getId());
        wa.outbound(ID,ID,n("20"),"OUT",ID,"FIX-O",null,""); eq("80",wa.currentBalance(ID,ID).getQty());
    }
    @Test void legacyStaleStocktakeCannotSubmit() {
        stock("100","100"); var take=takes.create(ID,LocalDate.now(),false,"");
        var line=take.getLines().get(0); line.setActualQty(n("80")); takes.saveActual(take.getId(),List.of(line));
        jdbc.update("UPDATE inv_balance SET qty=80,amount=80 WHERE material_id=? AND warehouse_id=?",ID,ID);
        assertThrows(BizException.class,()->takes.submit(take.getId()));
    }
    @Test void warehouseStocktakeApiHidesCost() {
        stock("10","100"); var take=takes.create(ID,LocalDate.now(),false,"");
        login("warehouse"); var doc=controller.detail(take.getId()).getData();
        assertNull(doc.getGainAmt()); assertNull(doc.getLossAmt()); assertNull(doc.getLines().get(0).getUnitPrice());
    }
    @Test void documentDeletionRequiresOwnerEvenInsideSameOrg() {
        BizPo doc=new BizPo(); doc.setDocNo("FIX-PO"); doc.setStatus("DRAFT"); doc.setOrgId(1L);
        doc.setWarehouseId(ID);doc.setSupplierId(1L);doc.setOrderDate(LocalDate.now());doc.setCreatedBy(ID+1);poMapper.insert(doc);
        login("purchaser"); assertThrows(BizException.class,()->po.delete(doc.getId()));
        assertNotNull(poMapper.selectById(doc.getId()));
    }
    /** 2026-09-28 定：账套管理员可清理本账套他人的草稿（财务常需要清理网点负责人留下的草稿）。 */
    @Test void accountAdminMayDeleteOthersDraftInsideOrg() {
        BizPo doc=new BizPo(); doc.setDocNo("FIX-PO-AA"); doc.setStatus("DRAFT"); doc.setOrgId(1L);
        doc.setWarehouseId(ID);doc.setSupplierId(1L);doc.setOrderDate(LocalDate.now());doc.setCreatedBy(ID+1);poMapper.insert(doc);
        login("account_admin"); po.delete(doc.getId());
        assertNull(poMapper.selectById(doc.getId()));
    }
    @Test void roleAssignmentRejectsCustomGlobalRoleAndUnknownIds() {
        jdbc.update("INSERT INTO sys_role(id,code,name,data_scope,status,deleted) VALUES(?,'fix_global','自定义全局','ALL',1,0)",ID);
        login("account_admin"); assertThrows(BizException.class,()->roles.validate(List.of(ID)));
        assertThrows(BizException.class,()->roles.validate(List.of(-100L)));
        assertFalse(roles.assignableRoles().stream().anyMatch(r->r.getId().equals(ID)));
        Long local=jdbc.queryForObject("SELECT id FROM sys_role WHERE code='warehouse' AND deleted=0",Long.class);
        assertDoesNotThrow(()->roles.validate(List.of(local)));
    }
    @Test void loanPartialReturnsAllocateFinalCentExactly() {
        stock("3","10");
        wa.outboundWithType(ID,ID,n("3"),"LOAN","LOAN",ID,"FIX-LOAN",ID,"");
        jdbc.update("INSERT INTO biz_loan(id,doc_no,borrower,purpose,dept_id,org_id,warehouse_id,borrow_date,status,total_qty,returned_qty,total_amt,deleted) VALUES(?,'FIX-LOAN','回归','测试',1,1,?,CURDATE(),'REGISTERED',3,0,10,0)",ID,ID);
        jdbc.update("INSERT INTO biz_loan_line(id,loan_id,line_no,material_id,qty,returned_qty,unit_price,amount) VALUES(?,?,1,?,3,0,3.33,10)",ID,ID,ID);
        for(int i=0;i<3;i++) {
            var line=new LoanService.LoanReturnLineDTO();line.setLoanLineId(ID);line.setQty(n("1"));
            var dto=new LoanService.LoanReturnDTO();dto.setReturnDate(LocalDate.now());dto.setLines(List.of(line));loans.registerReturn(ID,dto);
        }
        eq("10",wa.currentBalance(ID,ID).getAmount());eq("3",wa.currentBalance(ID,ID).getQty());
    }
    @Test void monthCloseIncludesStocktakeLoanPurchaseReturn() {
        stock("1","1"); var take=takes.create(ID,LocalDate.now(),false,"");
        var line=take.getLines().get(0);line.setActualQty(n("1"));takes.saveActual(take.getId(),List.of(line));takes.submit(take.getId());
        jdbc.update("INSERT INTO biz_loan(doc_no,borrower,purpose,dept_id,org_id,warehouse_id,borrow_date,status,deleted) VALUES('FIX-L','回归','测试',1,1,?,CURDATE(),'PENDING',0)",ID);
        jdbc.update("INSERT INTO biz_purchase_return(doc_no,source_recv_id,source_recv_no,recv_type,org_id,warehouse_id,return_date,status,deleted) VALUES('FIX-P',1,'FIX-R','ESTIMATE',1,?,CURDATE(),'PENDING',0)",ID);
        var result=periods.checkPeriod(java.time.YearMonth.now().toString());assertEquals(false,result.get("canClose"));
        String items=result.get("items").toString(); assertTrue(items.contains("STOCKTAKE"));assertTrue(items.contains("LOAN"));assertTrue(items.contains("PRET"));
    }
    @Test void restartedToolboxTasksFailAndExpiredFilesAreRemoved() throws Exception {
        Path root=Files.createTempDirectory("yt-fix-toolbox-");
        Object old=ReflectionTestUtils.getField(toolbox,"basePath");ReflectionTestUtils.setField(toolbox,"basePath",root.toString());
        try {
            jdbc.update("INSERT INTO tool_task(id,org_id,tool_code,title,status,input_path,created_at) VALUES(?,1,'test','重启测试','RUNNING','input.xlsx',NOW())",ID);
            Path dir=root.resolve("1/"+ID);Files.createDirectories(dir);Files.writeString(dir.resolve("input.xlsx"),"fixture");
            toolbox.recoverInterrupted();
            assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM tool_task WHERE id=?",String.class,ID));
            assertFalse(Files.exists(dir.resolve("input.xlsx")));
            jdbc.update("UPDATE tool_task SET created_at=DATE_SUB(NOW(),INTERVAL 91 DAY) WHERE id=?",ID);
            Files.writeString(dir.resolve("output.txt"),"fixture"); toolbox.cleanExpired();
            assertFalse(Files.exists(dir));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tool_task WHERE id=? AND deleted=0",Integer.class,ID));
        } finally { ReflectionTestUtils.setField(toolbox,"basePath",old);Files.deleteIfExists(root.resolve("1"));Files.deleteIfExists(root); }
    }
}
