package com.group.scm.review;

import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.sales.service.SalesIncomeCostService;
import com.group.scm.modules.work.WorkAlertService;
import com.group.scm.modules.purchase.mapper.BizPoLineMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 正确业务断言：由审计时的缺陷刻画夹具转来，只使用虚构 H2 数据。
 */
class SalesIncomeCostRegressionTest {
 JdbcTemplate db;
 SalesIncomeCostService report;
 @BeforeEach void init() throws Exception {
  var ds=new org.h2.jdbcx.JdbcDataSource();
  ds.setURL("jdbc:h2:mem:review_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
  db=new JdbcTemplate((DataSource)ds);
  db.execute("CREATE TABLE sys_org(id BIGINT PRIMARY KEY,parent_id BIGINT,name VARCHAR,type VARCHAR,deleted INT)");
  db.execute("CREATE TABLE md_material(id BIGINT PRIMARY KEY,code VARCHAR,name VARCHAR,spec VARCHAR,unit VARCHAR)");
  db.execute("CREATE TABLE biz_sale_out(id BIGINT PRIMARY KEY,org_id BIGINT,status VARCHAR,deleted INT,out_date DATE,total_amt DECIMAL(18,2),doc_no VARCHAR,customer_name VARCHAR,customer_unit VARCHAR)");
  db.execute("CREATE TABLE biz_sale_out_line(id BIGINT PRIMARY KEY,out_id BIGINT,line_no INT,material_id BIGINT,qty DECIMAL(18,4),amount DECIMAL(18,2))");
  db.execute("CREATE TABLE biz_sale_invoice(id BIGINT PRIMARY KEY,org_id BIGINT,deleted INT,status VARCHAR,invoice_date DATE,source_out_id BIGINT,source_out_no VARCHAR,doc_no VARCHAR,invoice_no VARCHAR,customer_name VARCHAR,customer_unit VARCHAR,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  db.execute("CREATE TABLE biz_sale_invoice_line(id BIGINT PRIMARY KEY,invoice_id BIGINT,material_id BIGINT,qty DECIMAL(18,2),amount_excl_tax DECIMAL(18,2),tax_amount DECIMAL(18,2),amount DECIMAL(18,2),tax_rate DECIMAL(8,2),line_no INT)");
  db.execute("CREATE TABLE biz_sale_return(id BIGINT PRIMARY KEY,org_id BIGINT,deleted INT,status VARCHAR,return_date DATE,source_out_id BIGINT,source_out_no VARCHAR,doc_no VARCHAR,customer_name VARCHAR,customer_unit VARCHAR,total_amt DECIMAL(18,2),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  db.execute("CREATE TABLE biz_sale_return_line(id BIGINT PRIMARY KEY,return_id BIGINT,line_no INT,source_out_line_id BIGINT,material_id BIGINT,qty DECIMAL(18,2),sale_amount DECIMAL(18,2),amount DECIMAL(18,2))");
  db.execute("CREATE TABLE wf_instance(id BIGINT PRIMARY KEY,biz_type VARCHAR,biz_id BIGINT,status VARCHAR,finished_at TIMESTAMP(6))");
  db.update("INSERT INTO sys_org VALUES(1,0,'Synthetic company','COMPANY',0)");
  db.update("INSERT INTO md_material VALUES(1,'FAKE','Synthetic material','FAKE','piece')");
  OrgScopeService scope=new OrgScopeService(db){@Override public List<Long> visibleOrgIds(){return List.of(1L);}};
  report=new SalesIncomeCostService(db,scope);
  ReflectionTestUtils.setField(report,"salesTaxRate",new BigDecimal("13"));
 }
 void out(String cost) {
  db.update("INSERT INTO biz_sale_out VALUES(10,1,'APPROVED',0,'2026-10-01',?,'FAKE-OUT','','FAKE CUSTOMER')",new BigDecimal(cost));
  db.update("INSERT INTO biz_sale_out_line VALUES(1,10,1,1,1,?)",new BigDecimal(cost));
 }
 void ret(String date,String gross,String cost) {
  db.update("INSERT INTO biz_sale_return VALUES(20,1,0,'APPROVED',?,10,'FAKE-OUT','FAKE-RETURN','','FAKE CUSTOMER',?,DEFAULT)",date,new BigDecimal(cost));
  db.update("INSERT INTO biz_sale_return_line VALUES(1,20,1,1,1,1,?,?)",new BigDecimal(gross),new BigDecimal(cost));
  approval(20,"SALE_RETURN",date+" 10:00:00");
 }
 void invoice(String date,String gross,String net,String tax,String rate) {
  db.update("INSERT INTO biz_sale_invoice VALUES(30,1,0,'APPROVED',?,10,'FAKE-OUT','FAKE-INVOICE','FAKE INV','','FAKE CUSTOMER',DEFAULT)",date);
  db.update("INSERT INTO biz_sale_invoice_line VALUES(1,30,1,1,?,?,?,?,1)",new BigDecimal(net),new BigDecimal(tax),new BigDecimal(gross),new BigDecimal(rate));
  approval(30,"SALE_INV",date+" 09:00:00");
 }
 void approval(long id,String type,String finishedAt) {
  db.update("INSERT INTO wf_instance VALUES(?,?,?,'APPROVED',?)",id,type,id,java.sql.Timestamp.valueOf(finishedAt));
 }
 Map<String,Object> run(String end) {return report.report(1L,LocalDate.parse("2026-10-01"),LocalDate.parse(end));}
 @SuppressWarnings("unchecked") Map<String,Object> total(Map<String,Object> r){return (Map<String,Object>)r.get("total");}
 void amount(Map<String,Object> m,String key,String expected){assertEquals(0,new BigDecimal(expected).compareTo((BigDecimal)m.get(key)),key);}
 @Test void uninvoicedFullReturnDoesNotCreateNegativeRevenueOrProfit() {
  out("60");ret("2026-10-02","113","60");
  var r=run("2026-10-31");var t=total(r);
  amount(t,"revenue","0");amount(t,"tax","0");amount(t,"cost","0");amount(t,"profit","0");
  assertEquals(List.of(),r.get("uninvoiced"));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_sale_invoice",Integer.class));
 }
 @Test void futureInvoicePreservesHistoricalUninvoicedItem() {
  out("60");invoice("2026-10-04","113","100","13","13");
  var r=run("2026-10-03");amount(total(r),"revenue","0");
  assertEquals(1,((List<?>)r.get("uninvoiced")).size());
 }
 @Test void futureReturnPreservesHistoricalUninvoicedItem() {
  out("60");ret("2026-10-04","113","60");
  var r=run("2026-10-03");amount(total(r),"revenue","0");
  assertEquals(1,((List<?>)r.get("uninvoiced")).size());
 }
 @Test void sameMaterialLineRoundingClosesToZeroAfterFullReturn() {
  out("0.03");db.update("UPDATE biz_sale_out SET total_amt=0.06 WHERE id=10");
  db.update("INSERT INTO biz_sale_out_line VALUES(2,10,2,1,1,0.03)");invoice("2026-10-01","0.05","0.04","0.01","13");
  db.update("INSERT INTO biz_sale_invoice_line VALUES(2,30,1,1,0.04,0.01,0.05,13,2)");
  ret("2026-10-02","0.05","0.03");
  db.update("INSERT INTO biz_sale_return_line VALUES(2,20,2,2,1,1,0.05,0.03)");
  var t=total(run("2026-10-31"));amount(t,"revenue","0");amount(t,"tax","0");amount(t,"total","0");amount(t,"cost","0");
 }
 @Test void historicalNon13PercentFullReturnReversesOriginalNet() {
  out("60");invoice("2026-10-01","106","100","6","6");ret("2026-10-02","106","60");
  var r=run("2026-10-31");amount(total(r),"revenue","0");amount(total(r),"tax","0");
 }
 @Test void mixedTaxRateReturnKeepsWarningAcrossSameMaterialLines() {
  out("60");db.update("UPDATE biz_sale_out SET total_amt=120 WHERE id=10");
  db.update("INSERT INTO biz_sale_out_line VALUES(2,10,2,1,1,60)");
  invoice("2026-10-01","113","100","13","13");
  db.update("INSERT INTO biz_sale_invoice_line VALUES(2,30,1,1,100,6,106,6,2)");
  ret("2026-10-02","113","60");
  db.update("INSERT INTO biz_sale_return_line VALUES(2,20,2,2,1,1,106,60)");
  @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)run("2026-10-31").get("detail");
  var returned=rows.stream().filter(r -> "RETURN".equals(r.get("kind"))).findFirst().orElseThrow();
  assertEquals(true,returned.get("rate_warn"));
  assertEquals(true,returned.get("mixed_rate"));
  amount(returned,"min_tax_rate","6");
  amount(returned,"max_tax_rate","13");
  amount(returned,"tax_rate","13");
  var invoiced=rows.stream().filter(r -> "INVOICE".equals(r.get("kind"))).findFirst().orElseThrow();
  assertEquals(true,invoiced.get("mixed_rate"));
  amount(invoiced,"min_tax_rate","6");
  amount(total(run("2026-10-31")),"revenue","0");
 }
 @Test void workflowApprovalTimeOverridesStaleDocumentUpdatedAt() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  ret("2026-09-30","113","60");
  invoice("2026-10-01","113","100","13","13");
  db.update("UPDATE wf_instance SET finished_at='2026-10-02 10:00:00' WHERE id=20");
  db.update("UPDATE wf_instance SET finished_at='2026-10-03 10:00:00' WHERE id=30");
  db.update("UPDATE biz_sale_return SET updated_at='2026-10-04 10:00:00' WHERE id=20");
  db.update("UPDATE biz_sale_invoice SET updated_at='2026-10-01 10:00:00' WHERE id=30");
  var september=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"));
  amount(total(september),"revenue","0");
  amount(total(run("2026-10-31")),"revenue","0");
  amount(total(run("2026-10-31")),"tax","0");
 }
 @Test void missingWorkflowFinishFailsClosedInsteadOfGuessingApprovalOrder() {
  out("60");invoice("2026-10-01","113","100","13","13");ret("2026-10-02","113","60");
  db.update("UPDATE wf_instance SET finished_at=NULL WHERE id=30");
  assertThrows(com.group.scm.common.BizException.class,() -> run("2026-10-31"));
 }
 @Test void missingOriginalLineBlocksEveryRelatedInvoiceOrReturnWindow() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  ret("2026-09-30","113","60");
  invoice("2026-10-01","113","100","13","13");
  // Historical duplicate header has no matching line either; every approved invoice date stays relevant.
  db.update("INSERT INTO biz_sale_invoice(id,org_id,deleted,status,invoice_date,source_out_id,source_out_no,doc_no) "
   +"VALUES(31,1,0,'APPROVED','2026-11-01',10,'FAKE-OUT','FAKE-INV-SECOND')");
  db.update("UPDATE biz_sale_return_line SET source_out_line_id=999 WHERE id=1");
  var september=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30")));
  var october=assertThrows(com.group.scm.common.BizException.class,() -> run("2026-10-31"));
  var combined=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-31")));
  var november=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30")));
  assertEquals(september.getMessage(),october.getMessage());
  assertEquals(september.getMessage(),combined.getMessage());
  assertEquals(september.getMessage(),november.getMessage());
  assertTrue(october.getMessage().contains("来源行"));
  assertTrue(october.getMessage().contains("核对"));
  amount(total(report.report(1L,LocalDate.parse("2026-12-01"),LocalDate.parse("2026-12-31"))),"revenue","0");
 }
 /** 财务批准口径：业务月份与真实审批先后反序时标异常，相关月份均不得签字。 */
 @Test void lateApprovalOfPriorMonthReturnBlocksEveryRelevantWindow() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  ret("2026-09-30","113","60");
  invoice("2026-10-01","113","100","13","13");
  db.update("UPDATE wf_instance SET finished_at='2026-10-02 10:00:00' WHERE id=30");
  db.update("UPDATE wf_instance SET finished_at='2026-10-03 10:00:00' WHERE id=20");
  var sep=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30")));
  var oct=assertThrows(com.group.scm.common.BizException.class,() -> run("2026-10-31"));
  var both=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-31")));
  assertEquals(sep.getMessage(),oct.getMessage());
  assertEquals(sep.getMessage(),both.getMessage());
  assertTrue(sep.getMessage().contains("跨月审批反序"));
  assertTrue(sep.getMessage().contains("FAKE-RETURN"));
  assertTrue(sep.getMessage().contains("FAKE-INVOICE"));
  assertTrue(sep.getMessage().contains("人工处理"));
  assertTrue(sep.getMessage().contains("暂停签字"));
  var unrelated=report.report(1L,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"));
  amount(total(unrelated),"revenue","0");
 }
 @Test void invoiceMonthBeforeReturnMonthButReturnApprovedFirstAlsoBlocksBoth() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  invoice("2026-09-30","113","100","13","13");
  ret("2026-10-02","113","60");
  db.update("UPDATE wf_instance SET finished_at='2026-10-02 10:00:00' WHERE id=30");
  db.update("UPDATE wf_instance SET finished_at='2026-10-01 10:00:00' WHERE id=20");
  var sep=assertThrows(com.group.scm.common.BizException.class,
   () -> report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30")));
  var oct=assertThrows(com.group.scm.common.BizException.class,() -> run("2026-10-31"));
  assertEquals(sep.getMessage(),oct.getMessage());
 }
 @Test void sameDayInvoiceBeforeReturnRemainsTwoSeparateEntries() {
  out("60");invoice("2026-10-01","113","100","13","13");
  db.update("UPDATE biz_sale_invoice SET updated_at='2026-10-01 09:00:00' WHERE id=30");
  ret("2026-10-01","113","60");
  db.update("UPDATE biz_sale_return SET updated_at='2026-10-01 10:00:00' WHERE id=20");
  var rows=(List<?>)run("2026-10-31").get("detail");
  assertEquals(2,rows.size());
  amount(total(run("2026-10-31")),"revenue","0");
 }
 @Test void partialReturnBeforeInvoiceKeepsEarlierMonthAndOffsetsInvoiceMonth() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  db.update("UPDATE biz_sale_out_line SET qty=2 WHERE id=1");
  ret("2026-09-20","113","30");
  var sep=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"));
  amount(total(sep),"revenue","0");
  invoice("2026-10-04","226","200","26","13");
  db.update("UPDATE biz_sale_invoice_line SET qty=2 WHERE id=1");
  var sepAgain=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"));
  amount(total(sepAgain),"revenue","0");
  var oct=run("2026-10-31");
  amount(total(oct),"revenue","100");amount(total(oct),"tax","13");amount(total(oct),"cost","30");
 }
 @Test void splitReturnsAbsorbLastCentWithoutChangingPriorMonth() {
  out("60");db.update("UPDATE biz_sale_out SET out_date='2026-09-01' WHERE id=10");
  db.update("UPDATE biz_sale_out_line SET qty=3 WHERE id=1");
  invoice("2026-09-01","300","265.49","34.51","13");
  db.update("UPDATE biz_sale_invoice_line SET qty=3 WHERE id=1");
  ret("2026-09-20","100","20");
  var sep=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"));
  for(int n=2;n<=3;n++) {
   int id=20+n-1;
   db.update("INSERT INTO biz_sale_return VALUES(?,1,0,'APPROVED',?,10,'FAKE-OUT',?,'','FAKE CUSTOMER',20,DEFAULT)",id,"2026-10-0"+n,"RET-"+id);
   db.update("INSERT INTO biz_sale_return_line VALUES(?,?,1,1,1,1,100,20)",id,id);
   approval(id,"SALE_RETURN","2026-10-0"+n+" 10:00:00");
  }
  var all=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-31"));
  for(String field:List.of("revenue","tax","cost","profit")) amount(total(all),field,"0");
  var sepAgain=report.report(1L,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"));
  amount(total(sepAgain),"revenue",((BigDecimal)total(sep).get("revenue")).toPlainString());
 }
 @Test void zeroCostUninvoicedIsVisibleUntilLastQuantityReturns() {
  out("0");db.update("UPDATE biz_sale_out_line SET qty=2 WHERE id=1");
  assertEquals(1,((List<?>)run("2026-10-01").get("uninvoiced")).size());
  ret("2026-10-02","113","0");
  assertEquals(1,((List<?>)run("2026-10-02").get("uninvoiced")).size());
  db.update("INSERT INTO biz_sale_return VALUES(21,1,0,'APPROVED','2026-10-03',10,'FAKE-OUT','RET-21','','FAKE CUSTOMER',0,DEFAULT)");
  db.update("INSERT INTO biz_sale_return_line VALUES(21,21,1,1,1,1,113,0)");
  assertEquals(0,((List<?>)run("2026-10-03").get("uninvoiced")).size());
 }
 @Test void sameMaterialDifferentPriceLinesReturnTheCorrectOriginalLine() {
  out("20");db.update("UPDATE biz_sale_out SET total_amt=60 WHERE id=10");
  db.update("INSERT INTO biz_sale_out_line VALUES(2,10,2,1,1,40)");
  invoice("2026-10-01","113","100","13","13");
  db.update("INSERT INTO biz_sale_invoice_line VALUES(2,30,1,1,200,26,226,13,2)");
  db.update("INSERT INTO biz_sale_return VALUES(20,1,0,'APPROVED','2026-10-02',10,'FAKE-OUT','RET-20','','FAKE CUSTOMER',40,DEFAULT)");
  db.update("INSERT INTO biz_sale_return_line VALUES(2,20,1,2,1,1,226,40)");
  approval(20,"SALE_RETURN","2026-10-02 10:00:00");
  var total=total(run("2026-10-31"));
  amount(total,"revenue","100");amount(total,"tax","13");amount(total,"cost","20");
 }
 @Test void rejectedReceiptIsNotAlertedAsQuantityReservation() throws Exception {
  db.execute("CREATE TABLE biz_recv(id BIGINT,deleted INT,status VARCHAR,org_id BIGINT,created_at TIMESTAMP)");
  db.execute("CREATE TABLE biz_recv_line(id BIGINT,recv_id BIGINT,source_po_line_id BIGINT,qty DECIMAL(18,2))");
  db.update("INSERT INTO biz_recv VALUES(90,0,'REJECTED',1,?)",java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(8)));
  db.update("INSERT INTO biz_recv_line VALUES(1,90,91,10)");
  WorkAlertService alerts=new WorkAlertService(db,null,null,null);
  ReflectionTestUtils.setField(alerts,"draftStaleDays",7);
  List<Map<String,Object>> list=new ArrayList<>();
  ReflectionTestUtils.invokeMethod(alerts,"staleRecvDrafts",list,List.of(1L));
  assertEquals(0,list.size());

  String query=BizPoLineMapper.class.getMethod("activeReceivingQty",Long.class).getAnnotation(Select.class).value()[0].replace("#{poLineId}","?");
  assertEquals(0,BigDecimal.ZERO.compareTo(db.queryForObject(query,BigDecimal.class,91L)));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM biz_recv WHERE status='DRAFT'",Integer.class));
 }
 @Test void staleDraftAndPendingReceiptsLinkToTheirOwnStatus() {
  db.execute("CREATE TABLE biz_recv(id BIGINT,deleted INT,status VARCHAR,org_id BIGINT,created_at TIMESTAMP)");
  var old=java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(8));
  db.update("INSERT INTO biz_recv VALUES(90,0,'DRAFT',1,?),(91,0,'PENDING',1,?),(92,0,'REJECTED',1,?)",old,old,old);
  WorkAlertService alerts=new WorkAlertService(db,null,null,null);
  ReflectionTestUtils.setField(alerts,"draftStaleDays",7);
  List<Map<String,Object>> list=new ArrayList<>();
  ReflectionTestUtils.invokeMethod(alerts,"staleRecvDrafts",list,List.of(1L));
  assertEquals(2,list.size());
  assertEquals(Map.of("status","DRAFT"),list.get(0).get("query"));
  assertEquals(Map.of("status","PENDING"),list.get(1).get("query"));
 }
}
