package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.sales.service.*;
import com.group.scm.modules.sales.controller.SalesController;
import com.group.scm.modules.sales.entity.BizSaleOut;
import com.group.scm.modules.workflow.service.WorkflowService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.request.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class SalesOutDocumentsDbTest {
    static final long ID=99894, MAKER=998941, APPROVER=998942;
    static final String ORDER="AUDIT-SALE-DOCS-SO";
    @Autowired JdbcTemplate db;
    @Autowired SaleOutService outs;
    @Autowired SoService orders;
    @Autowired WorkflowService workflow;
    @Autowired SalesController controller;

    void login(long user, String... roles) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(),new MockHttpServletResponse()));
        StpUtil.login(user); StpUtil.getSession().set("roles",List.of(roles));
        StpUtil.getSession().set("orgId",ID); StpUtil.getSession().set("dataScopes",List.of("SELF"));
    }
    @BeforeEach void seed() {
        TestDatabaseGuard.requireEnvironment();
        db.update("INSERT INTO sys_org(id,parent_id,code,name,type,approval_mode) VALUES(?,0,'AUDIT-SALE-DOCS','Sales docs','COMPANY','SIMPLE')",ID);
        db.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,'AUDIT-SALE-DOCS','Test warehouse','MAIN',?)",ID,ID);
        db.update("INSERT INTO md_material(id,code,name,org_id,unit) VALUES(?,'00099894','=1+1',?,'件')",ID,ID);
        db.update("INSERT INTO sys_user(id,username,password,name,org_id) VALUES(?,'sale-doc-maker','test-only','Maker',?),(?,'sale-doc-approver','test-only','Approver',?)",MAKER,ID,APPROVER,ID);
        db.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='material_accountant'",APPROVER);
        db.update("INSERT INTO biz_so(id,doc_no,org_id,order_date,total_qty,out_qty,status,created_by) VALUES(?,?,?,CURRENT_DATE,10,0,'APPROVED',?)",ID,ORDER,ID,MAKER);
        db.update("INSERT INTO biz_so_line(id,so_id,line_no,material_id,qty,out_qty) VALUES(?,?,1,?,10,0)",ID,ID,ID);
        db.update("INSERT INTO inv_balance(material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,100,1000,10)",ID,ID);
        login(MAKER,"admin");
    }
    @AfterEach void cleanup() {
        StpUtil.logout(); RequestContextHolder.resetRequestAttributes();
        db.update("DELETE FROM wf_cancel_request WHERE biz_type='SALE_OUT' AND biz_id IN (SELECT id FROM biz_sale_out WHERE source_so_id=?)",ID);
        db.update("DELETE FROM notif WHERE biz_type='SALE_OUT' AND biz_id IN (SELECT id FROM biz_sale_out WHERE source_so_id=?)",ID);
        for (String no : db.queryForList("SELECT doc_no FROM biz_sale_out WHERE source_so_id=?",String.class,ID)) db.update("DELETE FROM wf_record WHERE biz_no=?",no);
        db.update("DELETE FROM wf_instance WHERE biz_type='SALE_OUT' AND biz_id IN (SELECT id FROM biz_sale_out WHERE source_so_id=?)",ID);
        db.update("DELETE FROM inv_transaction WHERE material_id=?",ID);
        db.update("DELETE FROM inv_balance WHERE material_id=?",ID);
        db.update("DELETE FROM biz_sale_out_line WHERE out_id IN (SELECT id FROM biz_sale_out WHERE source_so_id=?)",ID);
        db.update("DELETE FROM biz_sale_out WHERE source_so_id=?",ID);
        db.update("DELETE FROM biz_so_line WHERE so_id=?",ID); db.update("DELETE FROM biz_so WHERE id=?",ID);
        db.update("DELETE FROM sys_user_role WHERE user_id IN (?,?)",MAKER,APPROVER);
        db.update("DELETE FROM sys_user WHERE id IN (?,?)",MAKER,APPROVER);
        db.update("DELETE FROM md_material WHERE id=?",ID); db.update("DELETE FROM md_warehouse WHERE id=?",ID);
        db.update("DELETE FROM sys_org WHERE id=?",ID);
    }
    SaleOutService.OutLineInput line(String qty) { return new SaleOutService.OutLineInput(ID,new BigDecimal(qty),"test"); }
    BizSaleOut create(String qty) { return outs.fromSo(ID,ID,List.of(line(qty))); }
    void number(String expected, BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual)); }
    void approve(BizSaleOut out) { login(MAKER,"admin"); outs.submit(out.getId()); login(APPROVER,"admin","material_accountant"); outs.approve(out.getId(),"test"); }

    @Test void partialDeliveryReservesDraftAndCompletesExactlyTen() {
        BizSaleOut a=create("4"), b=create("6");
        assertEquals(0,orders.availableForOut(1,20,ORDER,null).getTotal());
        var line=orders.detail(ID).getLines().get(0); number("10",line.getReservedOutQty()); number("0",line.getAvailableOutQty());
        assertThrows(BizException.class,()->create("1"));
        approve(a); number("4",orders.detail(ID).getOutQty());
        assertEquals("PARTIAL_OUT",orders.detail(ID).getStatus());
        approve(b); number("10",orders.detail(ID).getOutQty()); assertEquals("OUT",orders.detail(ID).getStatus());
        number("90",db.queryForObject("SELECT qty FROM inv_balance WHERE material_id=? AND warehouse_id=?",BigDecimal.class,ID,ID));
        assertThrows(BizException.class,()->create("0.1"));
    }
    @Test void deleteReleasesDraftButWithdrawalRetainsReservation() {
        var a=create("10"); outs.submit(a.getId()); outs.withdraw(a.getId());
        assertThrows(BizException.class,()->create("1"));
        outs.delete(a.getId()); number("10",orders.detail(ID).getLines().get(0).getAvailableOutQty());
        assertNotNull(create("10").getId());
    }
    @Test void rejectionReleasesAndResubmissionChecksOtherDrafts() {
        var a=create("6"); outs.submit(a.getId()); login(APPROVER,"admin","material_accountant"); outs.reject(a.getId(),"test");
        login(MAKER,"admin"); var b=create("6");
        assertThrows(BizException.class,()->outs.submit(a.getId()));
        assertEquals("REJECTED",outs.detail(a.getId()).getStatus());
        outs.delete(b.getId()); outs.submit(a.getId()); assertEquals("PENDING",outs.detail(a.getId()).getStatus());
    }
    @Test void malformedInputRollsBackWithoutDraftOrLines() {
        assertThrows(BizException.class,()->outs.fromSo(ID,ID,List.of(line("6"),line("6"))));
        for(String q:List.of("0","-1","0.00001","10.0001")) assertThrows(BizException.class,()->create(q));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_sale_out WHERE source_so_id=?",Integer.class,ID));
        assertNotNull(create("0.0001").getId());
    }
    @Test void simultaneousSixPlusSixAllowsOnlyOneDraft() throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task=()->{login(MAKER,"admin");try {ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));create("6");return true;}catch(BizException e){return false;}finally{StpUtil.logout();RequestContextHolder.resetRequestAttributes();}};
            var a=pool.submit(task); var b=pool.submit(task); assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            number("6",db.queryForObject("SELECT SUM(total_qty) FROM biz_sale_out WHERE source_so_id=?",BigDecimal.class,ID));
        } finally { pool.shutdownNow(); login(MAKER,"admin"); }
    }
    @Test void cancelledApprovalBecomesReservedDraft() {
        var out=create("6"); approve(out);
        workflow.requestCancel("SALE_OUT",out.getId(),out.getDocNo(),"test reverse");
        assertEquals("DRAFT",outs.detail(out.getId()).getStatus());
        var line=orders.detail(ID).getLines().get(0);number("0",line.getOutQty());number("6",line.getReservedOutQty());number("4",line.getAvailableOutQty());
        assertThrows(BizException.class,()->create("5"));
    }
    @Test void legacyOverlappingPendingOutCannotOverpostStockOrOrder() {
        var a=create("6"); var b=create("4");
        outs.submit(a.getId()); outs.submit(b.getId());
        // 旧版本已生成的超额待审单，审批末端仍必须整单回滚。
        db.update("UPDATE biz_sale_out_line SET qty=6 WHERE out_id=?",b.getId());
        db.update("UPDATE biz_sale_out SET total_qty=6 WHERE id=?",b.getId());
        login(APPROVER,"admin","material_accountant");outs.approve(a.getId(),"first");
        assertThrows(BizException.class,()->outs.approve(b.getId(),"excess"));
        number("6",orders.detail(ID).getOutQty());assertEquals("PENDING",outs.detail(b.getId()).getStatus());
        number("94",db.queryForObject("SELECT qty FROM inv_balance WHERE material_id=? AND warehouse_id=?",BigDecimal.class,ID,ID));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inv_transaction WHERE material_id=? AND biz_doc_type='SALE_OUT'",Integer.class,ID));
    }

    @Test void xlsxContainsAllMaterialLinesTypedNumbersAndLiteralText() throws Exception {
        for(int n=0;n<25;n++) create("0.1");
        db.update("INSERT INTO biz_so_line(id,so_id,line_no,material_id,qty,out_qty) VALUES(?,?,2,?,5,0)",ID+1,ID,ID);
        db.update("UPDATE biz_so SET total_qty=15 WHERE id=?",ID);
        outs.fromSo(ID,ID,List.of(line("1.25"),new SaleOutService.OutLineInput(ID+1,new BigDecimal("0.75"),"second item")));
        var f=new SalesController.SalesReportFilter();f.setKeyword(ORDER);f.setStatus("DRAFT");
        f.setStartDate(LocalDate.now());f.setEndDate(LocalDate.now());
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(controller.outExport(f).getBody()))) {
            var sheet=wb.getSheetAt(0);assertEquals(27,sheet.getLastRowNum());
            assertEquals("物料单据明细查询",sheet.getSheetName());
            assertEquals("成本单价",sheet.getRow(0).getCell(14).getStringCellValue());
            assertEquals("00099894",sheet.getRow(1).getCell(9).getStringCellValue());
            assertEquals(CellType.STRING,sheet.getRow(1).getCell(10).getCellType());assertEquals("=1+1",sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals(CellType.NUMERIC,sheet.getRow(1).getCell(13).getCellType());assertEquals(1.25,sheet.getRow(1).getCell(13).getNumericCellValue());assertEquals(.75,sheet.getRow(2).getCell(13).getNumericCellValue());
        }
        f.setStartDate(LocalDate.now().plusDays(1));
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(controller.outExport(f).getBody()))) { assertEquals(0,wb.getSheetAt(0).getLastRowNum()); }
    }
    @Test void exportHonorsWarehouseCostAndOrganizationAndRoleScope() throws Exception {
        var out=create("1");var f=new SalesController.SalesReportFilter();f.setKeyword(ORDER);
        login(MAKER,"warehouse");
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(controller.outExport(f).getBody()))) {
            var s=wb.getSheetAt(0);assertEquals(1,s.getLastRowNum());assertEquals(15,s.getRow(0).getLastCellNum());
            assertEquals("备注",s.getRow(0).getCell(14).getStringCellValue());
        }
        db.update("UPDATE biz_sale_out SET org_id=1 WHERE id=?",out.getId());
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(controller.outExport(f).getBody()))) { assertEquals(0,wb.getSheetAt(0).getLastRowNum()); }
        login(MAKER,"service_staff");assertThrows(BizException.class,()->controller.outExport(f));
    }
}
