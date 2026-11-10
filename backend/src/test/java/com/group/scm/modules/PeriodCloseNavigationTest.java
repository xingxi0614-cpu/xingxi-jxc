package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.outbound.service.OutReqService;
import com.group.scm.modules.outbound.service.LoanService;
import com.group.scm.modules.inventory.service.PurchaseReturnService;
import com.group.scm.modules.workflow.service.WorkflowService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL regressions for the close-page document lookup contract. */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class PeriodCloseNavigationTest {
    static final long ORG = 99710L, OTHER = 99711L, UID = 99712L;
    @Autowired JdbcTemplate jdbc;
    @Autowired PeriodCloseService close;
    @Autowired OutReqService requests;
    @Autowired LoanService loans;
    @Autowired PurchaseReturnService returns;
    @Autowired WorkflowService workflow;

    void login(long uid, String role) {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(uid);
        StpUtil.getSession().set("roles", List.of(role));
        StpUtil.getSession().set("orgId", ORG);
        StpUtil.getSession().set("dataScopes", List.of("SELF"));
    }
    @BeforeEach void setup() {
        for (long id : new long[]{ORG, OTHER}) {
            jdbc.update("INSERT INTO sys_org(id,parent_id,code,name,type,status,deleted) VALUES(?,0,?,?,'COMPANY',1,0)", id, "CLOSE-"+id, "查单验收"+id);
            jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id,status,deleted) VALUES(?,?,?,'MAIN',?,1,0)", id,"CLOSE-WH-"+id,"查单仓库",id);
        }
        login(UID, "material_accountant");
        request(ORG, ORG, "CLOSE-SA-1", "OUT");
        request(OTHER, OTHER, "CLOSE-SA-OTHER", "OUT");
        revision(ORG, ORG, "CLOSE-SAX-1", "PENDING", 0);
        revision(ORG+2, ORG, "CLOSE-SAX-HISTORY", "APPROVED", 0);
        revision(ORG+3, ORG, "CLOSE-SAX-DELETED", "PENDING", 1);
        revision(OTHER, OTHER, "CLOSE-SAX-OTHER", "PENDING", 0);
    }
    @AfterEach void logout() { if (StpUtil.isLogin()) StpUtil.logout(); }
    void request(long id, long org, String no, String status) {
        jdbc.update("INSERT INTO biz_out_req(id,doc_no,applicant_id,dept_id,org_id,warehouse_id,apply_date,purpose,status,deleted) VALUES(?,?,?,?,?,?,'2038-01-10','检索验收',?,0)",id,no,UID,org,org,org,status);
    }
    void revision(long id, long req, String no, String status, int deleted) {
        jdbc.update("INSERT INTO biz_out_req_revision(id,doc_no,source_req_id,source_req_no,org_id,warehouse_id,revision_date,reason,status,deleted) SELECT ?,?,id,doc_no,org_id,warehouse_id,'2038-01-11','检索验收',?,? FROM biz_out_req WHERE id=?",id,no,status,deleted,req);
    }
    long search(String no, String status, String revisionStatus) {
        return requests.page(1,20,status,no,null,null,null,null,null,null,null,null,null,null,null,revisionStatus).getTotal();
    }
    @Test void materialAccountantHasLoanLandingMenuWithoutWarehouseRole() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id JOIN sys_menu m ON m.id=rm.menu_id WHERE r.code='material_accountant' AND m.path='/outbound/loan'", Integer.class));
        assertFalse(com.group.scm.common.SecurityUtil.hasRole("warehouse"));
    }

    @Test void revisionNumberFindsAlreadyShippedParent() {
        assertEquals(1,search("CLOSE-SAX-1",null,null));
        assertEquals(1,search("CLOSE-SA-1",null,null));
        assertEquals(1,search("CLOSE-SAX",null,"PENDING"));
        assertEquals(1,search("CLOSE-SAX-HISTORY",null,null));
        assertEquals(0,search("CLOSE-SAX-DELETED",null,null));
        assertEquals(0,search("CLOSE-SAX-1","PENDING",null)); // Original status is independent.
        assertEquals(1,search("CLOSE-SAX-1","OUT","PENDING"));
    }
    @Test void batchMixingOriginalAndRevisionNumbersIsDistinctAndScoped() {
        assertEquals(1,search("CLOSE-SA-1,CLOSE-SAX-1,CLOSE-SAX-HISTORY,CLOSE-SAX-OTHER",null,null));
        assertEquals(0,search("CLOSE-SAX-OTHER",null,null));
        assertEquals(0,search("' OR '1'='1",null,null));
        assertEquals(0,search("bad',other",null,null));
        assertEquals(0,search("does-not-exist",null,null));
    }
    @Test void loanBatchFromCloseReturnsOnlyRequestedDocuments() {
        for (int i=1;i<=3;i++) jdbc.update("INSERT INTO biz_loan(doc_no,borrower,purpose,dept_id,org_id,warehouse_id,borrow_date,status,deleted) VALUES(?,'验收','验收',?,?,?,'2038-01-10','PENDING',0)","CLOSE-LOAN-"+i,ORG,ORG,ORG);
        var page=loans.page(1,20,null,"CLOSE-LOAN-1,CLOSE-LOAN-2",null,null,null,null,null,null,null);
        assertEquals(2,page.getTotal());
        assertEquals(Set.of("CLOSE-LOAN-1","CLOSE-LOAN-2"),new HashSet<>(page.getRecords().stream().map(r->r.getDocNo()).toList()));
    }
    @Test void purchaseReturnBatchFromCloseReturnsOnlyRequestedDocuments() {
        for (int i=1;i<=3;i++) jdbc.update("INSERT INTO biz_purchase_return(doc_no,source_recv_id,source_recv_no,recv_type,org_id,warehouse_id,return_date,status,deleted) VALUES(?,1,'TEST-CR','ESTIMATE',?,?,'2038-01-10','PENDING',0)","CLOSE-PRET-"+i,ORG,ORG);
        assertEquals(2,returns.page(1,20,null,"CLOSE-PRET-1,CLOSE-PRET-2",null,null,null,null,null,null,null,null,null).getTotal());
    }
    void cancellation(long id,long req,long approver) {
        jdbc.update("INSERT INTO wf_cancel_request(id,biz_type,biz_id,biz_no,applicant_id,origin_approver_id,reason,status) SELECT ?,'OREQ',id,doc_no,?,?, '查单验收','PENDING' FROM biz_out_req WHERE id=?",id,UID,approver,req);
    }
    @SuppressWarnings("unchecked")
    @Test void pendingCancellationDoesNotCrashPeriodCheckAndSamplesAreStrings() {
        cancellation(ORG,ORG,UID+1);
        cancellation(OTHER,OTHER,UID+1);
        Map<String,Object> check=close.checkPeriod("2038-01");
        var items=(List<Map<String,Object>>)check.get("items");
        var item=items.stream().filter(r->"CANCEL_REQ".equals(r.get("docType"))).findFirst().orElseThrow();
        assertEquals(1,item.get("count"));
        assertEquals(List.of("CLOSE-SA-1"),item.get("samples"));
        assertEquals(false,check.get("canClose"));
        assertThrows(BizException.class,()->close.closePeriod("2038-01","不应关闭"));
    }
    @Test void cancellationLookupSurvivesMissingNotificationsAndPreservesPermissions() {
        cancellation(ORG,ORG,UID+1); cancellation(OTHER,OTHER,UID+1);
        var rows=workflow.pendingCancelRequests("CLOSE-SA-1,CLOSE-SA-OTHER");
        assertEquals(1,rows.size()); assertEquals(false,rows.get(0).get("canProcess"));
        assertThrows(BizException.class,()->workflow.processCancel(ORG,false,"拒绝越权",null));
        login(UID+1,"warehouse");
        assertEquals(true,workflow.pendingCancelRequests("CLOSE-SA-1").get(0).get("canProcess"));
        workflow.processCancel(ORG,false,"测试驳回",null);
        assertTrue(workflow.pendingCancelRequests("CLOSE-SA-1").isEmpty());
    }
}
