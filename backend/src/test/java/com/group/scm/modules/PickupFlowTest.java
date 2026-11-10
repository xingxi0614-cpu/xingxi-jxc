package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.inventory.service.*;
import com.group.scm.modules.inventory.entity.*;
import com.group.scm.modules.outbound.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class PickupFlowTest {

    /**
     * 「提交时就查库存」(SubmitStockGuard)故意另开一个只读小事务读最新库存;本类在测试事务里造的库存
     * 还没提交,它读不到,会把正常单据误判为库存不足。这里换成放行,专心测本类的流程。
     * 该检查本身由全流程测试 scripts/e2e-local.sh 第 35 章覆盖(不够不进审批、扣待审占用、并发)。
     */
    @MockitoBean private com.group.scm.modules.inventory.service.SubmitStockGuard submitStockGuard;
 static final long ID=98701L, WH2=98702L, FIN=98703L;
 @Autowired JdbcTemplate jdbc;
 @Autowired TransferService transfers;
 @Autowired PickupService pickup;
 @Autowired WaInventoryService inventory;
 @Autowired OutService outs;
 @Autowired com.group.scm.modules.materialreq.service.LrRequestService onlineRequests;
 static BigDecimal n(Object v){return new BigDecimal(v.toString());}
 void eq(String expected,BigDecimal actual){assertEquals(0,n(expected).compareTo(actual));}
 void login(long id,String... roles){if(StpUtil.isLogin())StpUtil.logout();StpUtil.login(id);StpUtil.getSession().set("roles",List.of(roles));StpUtil.getSession().set("orgId",1L);StpUtil.getSession().set("dataScopes",List.of("ACCOUNT_SET"));}
 @BeforeEach void setup(){
  login(ID,"warehouse");
  jdbc.update("INSERT INTO sys_user(id,username,password,name,org_id,status,deleted) VALUES(?,'pickup-warehouse','disabled','领料验收仓管',1,1,0),(?,'pickup-finance','disabled','领料验收会计',1,1,0)",ID,FIN);
  jdbc.update("INSERT INTO md_material(id,code,name,unit,org_id,status,deleted) VALUES(?,'PICKUP-MAT','领料验收物料','个',1,1,0)",ID);
  jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id,status,deleted) VALUES(?,'PICKUP-SOURCE','领料验收原仓','MAIN',1,1,0),(?,'PICKUP-TARGET','领料验收目标仓','SECONDARY',1,1,0)",ID,WH2);
  inventory.inbound(ID,ID,n("100"),n("1.23"),n("123"),"INIT","INIT",ID,"PICKUP-INIT",null,"");
  jdbc.update("INSERT INTO biz_out_req(id,doc_no,applicant_id,dept_id,org_id,warehouse_id,apply_date,status,total_qty,out_qty,fulfillment_mode,business_category,purpose) VALUES(?,'PICKUP-REQ',?,1,1,?,CURDATE(),'APPROVED',10,0,'UNSET','工程类','验收')",ID,ID,ID);
  jdbc.update("INSERT INTO biz_out_req_line(id,out_req_id,line_no,material_id,qty,out_qty,original_qty,revised_qty) VALUES(?,?,1,?,10,0,10,0)",ID,ID,ID);
  // Keep a real one-node approval in the dedicated test transaction; rollback restores templates.
  jdbc.update("UPDATE wf_template SET nodes_json='[{\"node\":\"REVIEW\",\"approverRole\":\"material_accountant\",\"name\":\"材料会计\"}]' WHERE biz_type='TRANSFER'");
 }
 @AfterEach void logout(){if(StpUtil.isLogin())StpUtil.logout();}
 TransferService.PickupLine line(String q){var l=new TransferService.PickupLine();l.reqLineId=ID;l.qty=n(q);return l;}
 BizTransfer register(String q){var d=new TransferService.PickupInput();d.toWarehouseId=WH2;d.lines=List.of(line(q));return transfers.fromRequest(ID,d);}
 void approve(BizTransfer t){login(FIN,"material_accountant");transfers.approve(t.getId(),"核实");login(ID,"warehouse");}
 void handover(BizTransfer t,String qty){var d=new TransferService.HandoverInput();d.receiver="接收人";d.lines=List.of(line(qty));transfers.confirmHandover(t.getId(),d);}
 long pendingClose(){return jdbc.queryForObject("SELECT id FROM biz_req_fulfillment_event WHERE req_id=? AND event_type='CLOSE' AND status='PENDING'",Long.class,ID);}
 @Test void partialHandoverThenClosePreservesBothWarehousesAndCost(){
  var first=register("7");approve(first);eq("100",inventory.currentBalance(ID,ID).getQty());assertEquals("READY",transfers.detail(first.getId()).getStatus());
  handover(first,"5");eq("95",inventory.currentBalance(ID,ID).getQty());eq("5",inventory.currentBalance(ID,WH2).getQty());
  var second=register("3");approve(second);handover(second,"3");
  pickup.requestClose(ID,List.of(ID),"工程量减少",false);pickup.reviewClose(ID,pendingClose(),true,"");
  assertEquals("CLOSED",pickup.require(ID).getStatus());eq("8",n(pickup.lines(ID).get(0).get("transferredQty")));eq("2",n(pickup.lines(ID).get(0).get("closedQty")));
  eq("123",inventory.currentBalance(ID,ID).getAmount().add(inventory.currentBalance(ID,WH2).getAmount()));
  assertThrows(BizException.class,()->register("1"));
 }
 @Test void duplicateConfirmDoesNotMoveStockTwice(){var t=register("5");approve(t);handover(t,"5");assertThrows(BizException.class,()->handover(t,"5"));eq("95",inventory.currentBalance(ID,ID).getQty());}
 @Test void approvalAloneDoesNotMoveStock(){var t=register("10");approve(t);eq("100",inventory.currentBalance(ID,ID).getQty());eq("0",inventory.currentBalance(ID,WH2).getQty());}
 @Test void zeroPickupCanCloseWholeLine(){pickup.requestClose(ID,List.of(ID),"项目取消",false);pickup.reviewClose(ID,pendingClose(),true,"");eq("100",inventory.currentBalance(ID,ID).getQty());assertEquals("CLOSED",pickup.require(ID).getStatus());}
 @Test void activeTransferPreventsClosureAndOverbooking(){register("6");assertThrows(BizException.class,()->register("5"));assertThrows(BizException.class,()->pickup.requestClose(ID,List.of(ID),"不要了",false));}
 @Test void pendingClosePreventsNewPickup(){pickup.requestClose(ID,List.of(ID),"不要了",false);assertThrows(BizException.class,()->register("1"));}
 @Test void cancelReadyReleasesAllowanceWithoutStockMovement(){var t=register("10");approve(t);transfers.cancelPickup(t.getId(),"今日未领");eq("10",n(pickup.lines(ID).get(0).get("availableQty")));eq("100",inventory.currentBalance(ID,ID).getQty());}
 @Test void cannotUseMaterialOutToDoubleConsumeTransferRequest(){register("1");var l=new OutService.OutLineInput();l.reqLineId=ID;l.qty=n("1");assertThrows(BizException.class,()->outs.fromOreq(ID,List.of(l),"工程领料"));}
 @Test void legacyMustBeExplicitlyChecked(){jdbc.update("UPDATE biz_out_req SET fulfillment_mode='LEGACY' WHERE id=?",ID);assertThrows(BizException.class,()->register("1"));}
 @Test void wrongPersonCannotCancelAnotherPersonsRequirement(){login(FIN,"warehouse");assertThrows(BizException.class,()->pickup.requestClose(ID,List.of(ID),"不要了",false));}
 @Test void financeCannotPretendToBeWarehouseForHandover(){var t=register("1");approve(t);login(FIN,"material_accountant");assertThrows(BizException.class,()->handover(t,"1"));}
 @Test void crossOrgReadIsDenied(){jdbc.update("UPDATE biz_out_req SET org_id=98799 WHERE id=?",ID);assertThrows(BizException.class,()->pickup.detail(ID));}
 @Test void repeatExtensionRequiresBusinessLeader(){pickup.extend(ID,LocalDate.now().plusDays(10),"",false);assertThrows(BizException.class,()->pickup.extend(ID,LocalDate.now().plusDays(20),"",false));login(FIN,"engineering_leader");pickup.extend(ID,LocalDate.now().plusDays(20),"等待施工",false);assertEquals(2,pickup.require(ID).getPickupExtensions());}
 @Test void reminderIsDeduplicatedAndEscalatesWithoutClosing(){pickup.initialized(ID);jdbc.update("UPDATE biz_out_req SET pickup_due_date=DATE_SUB(CURDATE(),INTERVAL 1 DAY) WHERE id=?",ID);pickup.remind(ID);pickup.remind(ID);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notif WHERE biz_type='OREQ' AND biz_id=? AND todo_kind='PICKUP' AND is_read=0",Integer.class,ID));jdbc.update("UPDATE biz_out_req SET pickup_due_date=DATE_SUB(CURDATE(),INTERVAL 20 DAY) WHERE id=?",ID);pickup.remind(ID);assertEquals(2,pickup.require(ID).getPickupNoticeStage());assertEquals("APPROVED",pickup.require(ID).getStatus());}
 @Test void closureDoesNotRequireReopeningOldApplicationMonth(){jdbc.update("UPDATE biz_out_req SET apply_date='2020-01-01' WHERE id=?",ID);pickup.requestClose(ID,List.of(ID),"旧申请不再领取",false);pickup.reviewClose(ID,pendingClose(),true,"");assertEquals("CLOSED",pickup.require(ID).getStatus());}
 @Test void payloadCannotInjectUnrelatedLine(){var d=new TransferService.PickupInput();d.toWarehouseId=WH2;var l=line("1");l.reqLineId=ID+123;d.lines=List.of(l);assertThrows(BizException.class,()->transfers.fromRequest(ID,d));}
 @Test void detailShowsAuditAndQuantitiesWithoutCosts(){register("2");var d=pickup.detail(ID);assertTrue(d.containsKey("events"));assertFalse(d.toString().contains("unit_price"));assertFalse(d.toString().contains("total_amt"));}
 @Test void rejectedBatchCannotBeResubmittedAfterAllowanceUsed(){var t=register("8");login(FIN,"material_accountant");transfers.reject(t.getId(),"重新确认");login(ID,"warehouse");register("5");assertThrows(BizException.class,()->transfers.submit(t.getId()));}
 @Test void pendingClosureEscalatesEvenBeforeExtendedDueDate(){pickup.extend(ID,LocalDate.now().plusDays(30),"",false);pickup.requestClose(ID,List.of(ID),"不要了",false);jdbc.update("UPDATE biz_req_fulfillment_event SET created_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id=?",pendingClose());pickup.remind(ID);assertEquals(2,pickup.require(ID).getPickupNoticeStage());assertEquals("APPROVED",pickup.require(ID).getStatus());assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM biz_req_fulfillment_event WHERE id=?",String.class,pendingClose()));}
 @Test void partialLineClosureKeepsOtherMaterialAvailable(){jdbc.update("INSERT INTO biz_out_req_line(id,out_req_id,line_no,material_id,qty,out_qty,original_qty,revised_qty) VALUES(?,?,2,?,4,0,4,0)",ID+1,ID,ID);pickup.requestClose(ID,List.of(ID),"第一项不用了",false);pickup.reviewClose(ID,pendingClose(),true,"");assertEquals("APPROVED",pickup.require(ID).getStatus());eq("4",n(pickup.lines(ID).get(1).get("availableQty")));}
 @Test void handoverUsesActualDateAndClosesTodo(){var t=register("5");approve(t);jdbc.update("UPDATE biz_transfer SET transfer_date='2020-01-01' WHERE id=?",t.getId());handover(t,"3");assertEquals(LocalDate.now(),transfers.detail(t.getId()).getTransferDate());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notif WHERE biz_type='TRANSFER' AND biz_id=? AND type='TODO' AND is_read=0",Integer.class,t.getId()));}
 @Test void invalidQuantitiesAreRejected(){assertThrows(BizException.class,()->register("-1"));assertThrows(BizException.class,()->register("0.00001"));var t=register("5");approve(t);assertThrows(BizException.class,()->handover(t,"6"));eq("100",inventory.currentBalance(ID,ID).getQty());}
 @Test void onlineRequestGeneratedPlanStartsWithDeadlineAndCanTransfer(){
  jdbc.update("INSERT INTO lr_request(id,doc_no,org_id,warehouse_id,picker_name,project_name,biz_type,apply_date,status,purchase_by_id) VALUES(?,'PICKUP-ONLINE',1,?,'验收人','验收工程','工程类',CURDATE(),'WAREHOUSE_OK',?)",ID,ID,ID);
  jdbc.update("INSERT INTO lr_request_line(request_id,line_no,material_id,qty) VALUES(?,1,?,10)",ID,ID);
  login(FIN,"material_accountant");onlineRequests.confirm(ID);
  Long req=jdbc.queryForObject("SELECT out_req_id FROM lr_request WHERE id=?",Long.class,ID);
  assertEquals("UNSET",pickup.require(req).getFulfillmentMode());assertEquals(LocalDate.now().plusDays(7),pickup.require(req).getPickupDueDate());assertEquals(ID,pickup.require(req).getApplicantId());
  login(ID,"warehouse");var d=new TransferService.PickupInput();d.toWarehouseId=WH2;var l=line("5");l.reqLineId=jdbc.queryForObject("SELECT id FROM biz_out_req_line WHERE out_req_id=?",Long.class,req);d.lines=List.of(l);assertEquals("PENDING",transfers.fromRequest(req,d).getStatus());
 }

}
