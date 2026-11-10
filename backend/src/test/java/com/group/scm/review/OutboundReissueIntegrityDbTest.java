package com.group.scm.review;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.outbound.entity.*;
import com.group.scm.modules.outbound.service.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = TestDatabaseGuard.SpringInitializer.class)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class OutboundReissueIntegrityDbTest {
    static final long ID=99951, MAKER=999511, APPROVER=999512;
    @Autowired JdbcTemplate db;
    @Autowired OutReqService requests;
    @Autowired OutService outs;
    @Autowired ReturnService returns;
    @Autowired WorkflowService workflow;
    @Autowired PlatformTransactionManager txManager;

    void login(long id) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(),new MockHttpServletResponse()));
        StpUtil.login(id);
        StpUtil.getSession().set("roles",id==MAKER?List.of("admin","purchaser","warehouse"):List.of("admin","material_accountant"));
        StpUtil.getSession().set("orgId",ID);StpUtil.getSession().set("dataScopes",List.of("SELF"));
    }
    void logout() {StpUtil.logout();RequestContextHolder.resetRequestAttributes();}
    @BeforeEach void seed() {
        TestDatabaseGuard.requireEnvironment();
        db.update("INSERT INTO sys_org(id,parent_id,code,name,type,approval_mode) VALUES(?,0,'OGUARD','Outbound guard','COMPANY','SIMPLE')",ID);
        db.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id) VALUES(?,'OGUARD','Guard warehouse','MAIN',?)",ID,ID);
        db.update("INSERT INTO md_material(id,code,name,org_id,unit) VALUES(?,'OGUARD','Guard material',?,'件')",ID,ID);
        db.update("INSERT INTO md_project(id,org_id,code,name) VALUES(?,?,'OGUARD','Guard project')",ID,ID);
        db.update("INSERT INTO sys_user(id,username,password,name,org_id) VALUES(?,'out-guard-maker','test-only','Maker',?),(?,'out-guard-approver','test-only','Approver',?)",MAKER,ID,APPROVER,ID);
        db.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='material_accountant'",APPROVER);
        db.update("INSERT INTO inv_balance(material_id,warehouse_id,qty,amount,avg_price) VALUES(?,?,100,1000,10)",ID,ID);
        db.update("INSERT INTO inv_cost_pool(org_id,material_id,qty,amount,avg_price) VALUES(?,?,100,1000,10)",ID,ID);
        db.update("INSERT INTO inv_cost_lock(org_id) VALUES(?)",ID);
        login(MAKER);
    }
    @AfterEach void cleanup() {
        logout();
        for(String table:List.of("wf_record","wf_instance","wf_cancel_request","notif")) db.update("DELETE FROM "+table+" WHERE biz_no LIKE 'OGUARD-%'");
        db.update("DELETE FROM doc_relation WHERE source_no LIKE 'OGUARD-%' OR target_no LIKE 'OGUARD-%'");
        db.update("DELETE FROM biz_return_line WHERE return_id IN (SELECT id FROM biz_return WHERE org_id=?)",ID);
        db.update("DELETE FROM biz_return WHERE org_id=?",ID);
        db.update("DELETE FROM biz_out_line WHERE out_id IN (SELECT id FROM biz_out WHERE org_id=?)",ID);
        db.update("DELETE FROM biz_out WHERE org_id=?",ID);
        db.update("DELETE FROM biz_out_req_line WHERE out_req_id IN (SELECT id FROM biz_out_req WHERE org_id=?)",ID);
        db.update("DELETE FROM biz_out_req WHERE org_id=?",ID);
        db.update("DELETE FROM inv_transaction WHERE material_id=?",ID);
        db.update("DELETE FROM inv_balance WHERE material_id=?",ID);
        db.update("DELETE FROM inv_cost_pool WHERE org_id=?",ID);db.update("DELETE FROM inv_cost_lock WHERE org_id=?",ID);
        db.update("DELETE FROM sys_user_role WHERE user_id IN (?,?)",MAKER,APPROVER);
        db.update("DELETE FROM sys_user WHERE id IN (?,?)",MAKER,APPROVER);
        db.update("DELETE FROM md_project WHERE id=?",ID);db.update("DELETE FROM md_material WHERE id=?",ID);
        db.update("DELETE FROM md_warehouse WHERE id=?",ID);db.update("DELETE FROM sys_org WHERE id=?",ID);
    }
    BigDecimal n(String x) {return new BigDecimal(x);}
    void number(String expected,BigDecimal actual) {assertEquals(0,n(expected).compareTo(actual));}
    BizOutReq dto(String reissue) {
        BizOutReq req=new BizOutReq();req.setWarehouseId(ID);req.setProjectId(ID);req.setBusinessCategory("运营类");
        req.setMaterialApplicant("Guard");req.setPurpose("Regression");req.setApplyDate(LocalDate.now());
        BizOutReqLine line=new BizOutReqLine();line.setMaterialId(ID);line.setQty(BigDecimal.TEN);line.setReissueQty(n(reissue));
        req.setLines(List.of(line));return req;
    }
    BizOutReq approvedRequest() {
        login(MAKER);BizOutReq req=requests.create(dto("0"));requests.submit(req.getId());
        login(APPROVER);requests.approve(req.getId(),"test");login(MAKER);return requests.detail(req.getId());
    }
    BizOut create(BizOutReq req,String qty) {
        OutService.OutLineInput line=new OutService.OutLineInput();line.reqLineId=req.getLines().get(0).getId();line.qty=n(qty);
        return outs.fromOreq(req.getId(),List.of(line),"生活用料");
    }
    void approve(BizOut out) {login(MAKER);outs.submit(out.getId());login(APPROVER);outs.approve(out.getId(),"test");login(MAKER);}
    BizReturn returnFor(BizOut out,String qty,boolean reissue) {
        BizReturn dto=new BizReturn();dto.setSourceOutId(out.getId());dto.setReissue(reissue?1:0);
        BizReturnLine line=new BizReturnLine();line.setSourceOutLineId(outs.detail(out.getId()).getLines().get(0).getId());line.setQty(n(qty));
        dto.setLines(List.of(line));BizReturn ret=returns.fromOut(dto);returns.submit(ret.getId());
        login(APPROVER);returns.approve(ret.getId(),"test");login(MAKER);return ret;
    }
    @Test void clientReissueProgressIsClearedOnCreateAndEdit() {
        BizOutReq req=requests.create(dto("100"));number("0",requests.detail(req.getId()).getLines().get(0).getReissueQty());
        requests.update(req.getId(),dto("-100"));number("0",requests.detail(req.getId()).getLines().get(0).getReissueQty());
        requests.submit(req.getId());login(APPROVER);requests.approve(req.getId(),"test");login(MAKER);
        req=requests.detail(req.getId());create(req,"10");BizOutReq finalReq=req;
        assertThrows(BizException.class,()->create(finalReq,"10"));
    }
    @Test void genuineReissueSupportsRepickAndAccurateDisplayWithoutReopeningDeletion() {
        BizOutReq req=approvedRequest();BizOut out=create(req,"10");approve(out);returnFor(out,"4",true);
        BizOutReq detail=requests.detail(req.getId());number("6",detail.getOutQty());number("10",detail.getGrossOutQty());number("6",detail.getNetOutQty());
        number("10",detail.getLines().get(0).getGrossOutQty());number("6",detail.getLines().get(0).getNetOutQty());
        approve(create(req,"4"));detail=requests.detail(req.getId());number("10",detail.getOutQty());number("14",detail.getGrossOutQty());number("10",detail.getNetOutQty());
        assertThrows(BizException.class,()->create(req,"0.0001"));
    }
    @Test void fullReissueCannotCancelEditOrDeleteOriginalRequest() {
        BizOutReq req=approvedRequest();BizOut out=create(req,"10");approve(out);returnFor(out,"10",true);
        assertEquals("APPROVED",requests.detail(req.getId()).getStatus());number("0",requests.detail(req.getId()).getOutQty());
        login(APPROVER);assertThrows(BizException.class,()->workflow.requestCancel("OREQ",req.getId(),req.getDocNo(),"test"));login(MAKER);
        // Even an old, already reopened source must not have its original referenced rows replaced or deleted.
        db.update("UPDATE biz_out_req SET status='DRAFT' WHERE id=?",req.getId());
        assertThrows(BizException.class,()->requests.update(req.getId(),dto("0")));
        assertThrows(BizException.class,()->requests.delete(req.getId()));
        assertNotNull(db.queryForObject("SELECT id FROM biz_out_req_line WHERE id=?",Long.class,req.getLines().get(0).getId()));
    }
    @Test void nonReissueReturnDoesNotReleaseQuotaAndRejectedOutCanResubmit() {
        BizOutReq req=approvedRequest();BizOut first=create(req,"4");outs.submit(first.getId());login(APPROVER);outs.reject(first.getId(),"test");
        login(MAKER);BizOut other=create(req,"10");assertThrows(BizException.class,()->outs.submit(first.getId()));outs.delete(other.getId());
        approve(first);returnFor(first,"4",false);number("4",requests.detail(req.getId()).getOutQty());number("0",requests.detail(req.getId()).getNetOutQty());
        approve(create(req,"6"));assertThrows(BizException.class,()->create(req,"1"));
    }
    @Test void legacyOverlappingPendingApprovalsCannotOverpostConcurrently() throws Exception {
        BizOutReq req=approvedRequest();BizOut a=create(req,"6"),b=create(req,"4");outs.submit(a.getId());outs.submit(b.getId());
        db.update("UPDATE biz_out_line SET qty=6 WHERE out_id=?",b.getId());db.update("UPDATE biz_out SET total_qty=6 WHERE id=?",b.getId());
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(long outId:List.of(a.getId(),b.getId())) results.add(pool.submit(()->{
                login(APPROVER);try{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));outs.approve(outId,"test");return true;}
                catch(BizException e){return false;}finally{logout();}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();assertNotEquals(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();login(MAKER);}
        number("6",requests.detail(req.getId()).getOutQty());number("94",db.queryForObject("SELECT qty FROM inv_balance WHERE material_id=? AND warehouse_id=?",BigDecimal.class,ID,ID));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inv_transaction WHERE material_id=? AND biz_doc_type='OUT'",Integer.class,ID));
    }
    @Test void buildWaitsForCancellationAndDeletionThenReadsFreshSource() throws Exception {
        for(boolean delete:List.of(false,true)) {
            BizOutReq req=approvedRequest();ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
            List<Future<Throwable>> pending=new ArrayList<>();
            try {
                new TransactionTemplate(txManager).executeWithoutResult(tx->{
                    db.queryForList("SELECT id FROM biz_out_req WHERE id=? FOR UPDATE",Long.class,req.getId());
                    login(APPROVER);workflow.requestCancel("OREQ",req.getId(),req.getDocNo(),"test race");login(MAKER);
                    if(delete) requests.delete(req.getId());
                    pending.add(pool.submit(()->{login(MAKER);try{started.countDown();create(req,"1");return null;}catch(Throwable e){return e;}finally{logout();}}));
                    try {assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(0).get(150,TimeUnit.MILLISECONDS));}
                    catch(InterruptedException e){throw new RuntimeException(e);}
                });
                assertInstanceOf(BizException.class,pending.get(0).get(15,TimeUnit.SECONDS));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_out WHERE source_req_id=?",Integer.class,req.getId()));
            } finally {pool.shutdownNow();login(MAKER);}
        }
    }
    @Test void cancellationWaitingForBuildSeesItsNewDownstreamReference() throws Exception {
        BizOutReq req=approvedRequest();ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);
        List<Future<Throwable>> pending=new ArrayList<>();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx->{
                create(req,"1"); // holds source and the new out until this surrounding transaction commits
                pending.add(pool.submit(()->{login(APPROVER);try{started.countDown();workflow.requestCancel("OREQ",req.getId(),req.getDocNo(),"race");return null;}
                    catch(Throwable e){return e;}finally{logout();}}));
                try {assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(0).get(150,TimeUnit.MILLISECONDS));}
                catch(InterruptedException e){throw new RuntimeException(e);}
            });
            assertInstanceOf(BizException.class,pending.get(0).get(15,TimeUnit.SECONDS));
            assertEquals("APPROVED",requests.detail(req.getId()).getStatus());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM biz_out WHERE source_req_id=?",Integer.class,req.getId()));
        } finally {pool.shutdownNow();login(MAKER);}
    }
}
