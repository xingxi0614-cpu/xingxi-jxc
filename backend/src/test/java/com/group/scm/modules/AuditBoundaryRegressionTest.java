package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.modules.attachment.service.AttachmentService;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.purchase.entity.*;
import com.group.scm.modules.sales.service.*;
import com.group.scm.modules.sales.entity.*;
import com.group.scm.modules.invoice.service.InvoiceService;
import com.group.scm.modules.invoice.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 9月30日审查的权限/封账/金额边界。仅真实隔离 MySQL，业务行事务回滚。 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class AuditBoundaryRegressionTest {
    static final long ID = 99931, ORG = 61;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired PoService purchase;
    @Autowired com.group.scm.common.service.DocNoService docNumbers;
    @Autowired SoService sales;
    @Autowired SoPublicService publicSales;
    @Autowired SaleReturnService returns;
    @Autowired InvoiceService invoices;
    @Autowired AttachmentService attachments;
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static void eq(String v, BigDecimal actual) { assertEquals(0, n(v).compareTo(actual)); }

    void login(String... roles) {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(ID);
        StpUtil.getSession().set("roles", List.of(roles));
        StpUtil.getSession().set("orgId", ORG);
        StpUtil.getSession().set("dataScopes", List.of("ACCOUNT_SET"));
    }
    @BeforeEach void setup() {
        login("purchaser", "material_accountant");
        jdbc.update("INSERT INTO md_material(id,code,name,unit,org_id,status,deleted) VALUES(?,'AUDIT-MAT','边界回归物料','个',?,1,0)", ID, ORG);
        jdbc.update("INSERT INTO md_warehouse(id,code,name,warehouse_type,org_id,status,deleted) VALUES(?,'AUDIT-WH','边界回归仓库','MAIN',?,1,0)", ID, ORG);
    }
    @AfterEach void logout() { if (StpUtil.isLogin()) StpUtil.logout(); }

    BizPo po(String amount) {
        BizPo p = new BizPo(); p.setSupplierId(5L); p.setWarehouseId(ID); p.setOrderDate(LocalDate.now());
        BizPoLine l = new BizPoLine(); l.setMaterialId(ID); l.setQty(n("1")); l.setAmount(n(amount)); l.setTaxRate(n("13"));
        p.setLines(List.of(l)); return p;
    }
    BizSo so(String qty, String amount) {
        BizSo s = new BizSo(); s.setCustomerName("合成客户"); s.setOrderDate(LocalDate.now());
        BizSoLine l = new BizSoLine(); l.setMaterialId(ID); l.setQty(n(qty)); l.setAmount(n(amount)); l.setTaxRate(n("0"));
        s.setLines(List.of(l)); return s;
    }
    long poLine(long id) { return jdbc.queryForObject("SELECT id FROM biz_po_line WHERE po_id=? LIMIT 1", Long.class, id); }
    void close(LocalDate date) {
        jdbc.update("INSERT INTO fin_period_close(org_id,period_month,status) VALUES(?,?,'CLOSED') ON DUPLICATE KEY UPDATE status='CLOSED'", ORG, date.toString().substring(0,7));
    }

    @Test void taxCorrectionRejectsEveryInvalidRateWithoutWriting() {
        long id = purchase.create(po("113")).getId(), line = poLine(id);
        for (String rate : List.of("-200", "-100", "-0.01", "100", "100.01"))
            assertThrows(BizException.class, () -> purchase.correctTaxRates(id, List.of(new PoService.TaxFix(line,n(rate)))));
        eq("13", jdbc.queryForObject("SELECT tax_rate FROM biz_po_line WHERE id=?",BigDecimal.class,line));
        eq("100", jdbc.queryForObject("SELECT amount_excl_tax FROM biz_po_line WHERE id=?",BigDecimal.class,line));
        for (String rate : List.of("0", "6", "9", "13")) {
            purchase.correctTaxRates(id,List.of(new PoService.TaxFix(line,n(rate))));
            eq("113",jdbc.queryForObject("SELECT amount_excl_tax+tax_amount FROM biz_po_line WHERE id=?",BigDecimal.class,line));
        }
    }
    @Test void taxCorrectionRejectsClosedPeriod() {
        long id=purchase.create(po("113")).getId(), line=poLine(id); close(LocalDate.now());
        assertThrows(BizException.class,()->purchase.correctTaxRates(id,List.of(new PoService.TaxFix(line,n("6")))));
        eq("13",jdbc.queryForObject("SELECT tax_rate FROM biz_po_line WHERE id=?",BigDecimal.class,line));
    }
    @Test void taxCorrectionRejectsPendingAndForeignLines() {
        long id=purchase.create(po("113")).getId(), line=poLine(id);
        assertThrows(BizException.class,()->purchase.correctTaxRates(id,List.of(new PoService.TaxFix(line+100,n("6")))));
        jdbc.update("UPDATE biz_po SET status='PENDING' WHERE id=?",id);
        sqlSession.clearCache();
        assertThrows(BizException.class,()->purchase.correctTaxRates(id,List.of(new PoService.TaxFix(line,n("6")))));
    }
    @Test void purchaseCannotStoreRoundedZero() {
        for(String amount:List.of("0","0.001","0.004","0.005"))
            assertThrows(BizException.class,()->purchase.create(po(amount)));
        eq("0.01",purchase.create(po("0.01")).getTotalAmt());
    }
    @Test void saleCannotStoreRoundedZero() {
        assertThrows(BizException.class,()->sales.create(so("1","0.001")));
        eq("0.01",sales.create(so("1","0.01")).getTotalAmt());
    }
    @Test void invoiceCannotStoreRoundedZero() {
        jdbc.update("INSERT INTO biz_recv(id,doc_no,org_id,supplier_id,warehouse_id,source_po_id,source_po_no,recv_date,recv_type,status) VALUES(?,'AUDIT-RECV',?,5,?,1,'AUDIT-PO',CURDATE(),'ESTIMATE','APPROVED')",ID,ORG,ID);
        jdbc.update("INSERT INTO biz_recv_line(id,recv_id,line_no,source_po_line_id,material_id,qty,unit_price,amount) VALUES(?,?,1,1,?,1,1,1)",ID,ID,ID);
        BizInvoice v=new BizInvoice();v.setInvoiceDate(LocalDate.now());
        BizInvoiceLine l=new BizInvoiceLine();l.setRecvLineId(ID);l.setQty(n("1"));l.setUnitPrice(n("0"));l.setTaxRate(n("0"));l.setTotalAmt(n("0.001"));v.setLines(List.of(l));
        assertThrows(BizException.class,()->invoices.create(v));
    }
    @Test void saleUpdateHasSameCustomerScopeAsCreate() {
        jdbc.update("INSERT INTO md_supplier(id,org_id,code,name,is_customer,is_shared,status,deleted) VALUES(?,10,'AUDIT-FOREIGN','外账套合成客户',1,0,1,0)",ID);
        BizSo s=sales.create(so("1","100")); BizSo dto=so("1","100"); dto.setCustomerId(ID);
        assertThrows(BizException.class,()->sales.create(dto));
        assertThrows(BizException.class,()->sales.update(s.getId(),dto));
        assertNull(jdbc.queryForObject("SELECT customer_id FROM biz_so WHERE id=?",Long.class,s.getId()));
    }
    void attachmentFixture() {
        jdbc.update("INSERT INTO biz_invoice(id,doc_no,org_id,supplier_id,invoice_date,status) VALUES(?,'AUDIT-ATT-INV',?,5,CURDATE(),'APPROVED')",ID,ORG);
        jdbc.update("INSERT INTO biz_attachment(id,biz_type,biz_id,org_id,file_name,cos_key) VALUES(?,'INV',?,?,'合成发票.pdf','no-object')",ID,ID,ORG);
    }
    @Test void warehouseCannotListOrDownloadInvoiceAttachments() {
        attachmentFixture(); login("warehouse");
        assertEquals(4003,assertThrows(BizException.class,()->attachments.list("INV",ID)).getCode());
        assertEquals(4003,assertThrows(BizException.class,()->attachments.open(ID)).getCode());
    }
    @Test void warehouseReadsOwnDocAttachmentsButNotPurchaseContracts() {
        jdbc.update("INSERT INTO biz_recv(id,doc_no,org_id,supplier_id,warehouse_id,source_po_id,source_po_no,recv_date,recv_type,status) VALUES(?,'AUDIT-ATT-RECV',?,5,?,1,'AUDIT-PO',CURDATE(),'ESTIMATE','APPROVED')",ID,ORG,ID);
        jdbc.update("INSERT INTO biz_po(id,doc_no,supplier_id,warehouse_id,org_id,order_date,status) VALUES(?,'AUDIT-ATT-PO',5,?,?,CURDATE(),'APPROVED')",ID,ID,ORG);
        jdbc.update("INSERT INTO biz_attachment(id,biz_type,biz_id,org_id,file_name,cos_key) VALUES(?,'RECV',?,?,'验收照片.jpg','no-object')",ID,ID,ORG);
        jdbc.update("INSERT INTO biz_attachment(id,biz_type,biz_id,org_id,file_name,cos_key) VALUES(?,'PO',?,?,'采购合同.pdf','no-object')",ID+1,ID,ORG);
        login("warehouse");
        assertEquals(1,attachments.list("RECV",ID).size());
        assertEquals(4003,assertThrows(BizException.class,()->attachments.list("PO",ID)).getCode());
        login("tech_manager");
        assertEquals(4003,assertThrows(BizException.class,()->attachments.list("RECV",ID)).getCode());
    }
    @Test void attachmentRequiresLiveMotherAndMatchingOrg() {
        attachmentFixture();assertEquals(1,attachments.list("INV",ID).size()); assertNotNull(attachments.meta(ID));
        jdbc.update("UPDATE biz_attachment SET org_id=10 WHERE id=?",ID);
        sqlSession.clearCache();
        assertThrows(BizException.class,()->attachments.meta(ID));
        jdbc.update("UPDATE biz_attachment SET org_id=? WHERE id=?",ORG,ID);
        jdbc.update("UPDATE biz_invoice SET deleted=1 WHERE id=?",ID);
        sqlSession.clearCache();
        assertThrows(BizException.class,()->attachments.meta(ID));
    }
    long source(long orderLine,long qty,String cost,int suffix) {
        long oid=ID+suffix;
        jdbc.update("INSERT INTO biz_sale_out(id,doc_no,org_id,warehouse_id,out_date,total_qty,total_amt,status) VALUES(?,?,?, ?,CURDATE(),?,?,'APPROVED')",oid,"AUDIT-OUT-"+suffix,ORG,ID,qty,n(cost));
        jdbc.update("INSERT INTO biz_sale_out_line(id,out_id,line_no,source_so_line_id,material_id,qty,unit_price,amount) VALUES(?,?,1,?,?,?,0.3333,?)",oid,oid,orderLine,ID,qty,n(cost));return oid;
    }
    BizSaleReturn ret(long oid,String qty) {
        return returns.fromSaleOut(new SaleReturnService.ReturnReq(oid,List.of(new SaleReturnService.ReturnLineInput(oid,n(qty),"")),""));
    }
    @Test void fullSplitReturnPreservesCostAndRefund() {
        BizSo s=sales.create(so("3","1"));long sl=jdbc.queryForObject("SELECT id FROM biz_so_line WHERE so_id=?",Long.class,s.getId());
        long oid=source(sl,3,"1",0);eq("0.33",ret(oid,"1").getTotalAmt());eq("0.33",ret(oid,"1").getTotalAmt());eq("0.34",ret(oid,"1").getTotalAmt());
        eq("1",jdbc.queryForObject("SELECT SUM(amount) FROM biz_sale_return_line WHERE source_out_line_id=?",BigDecimal.class,oid));
        eq("1",jdbc.queryForObject("SELECT SUM(sale_amount) FROM biz_sale_return_line WHERE source_out_line_id=?",BigDecimal.class,oid));
    }
    @Test void refundTailIsSharedAcrossOutboundBatches() {
        BizSo s=sales.create(so("3","1"));long sl=jdbc.queryForObject("SELECT id FROM biz_so_line WHERE so_id=?",Long.class,s.getId());
        long a=source(sl,1,"0.33",0),b=source(sl,2,"0.67",1);
        eq("0.33",ret(a,"1").getRefundAmt());eq("0.33",ret(b,"1").getRefundAmt());eq("0.34",ret(b,"1").getRefundAmt());
    }
    @Test void rejectedReturnReleasesAmountAllocation() {
        BizSo s=sales.create(so("3","1"));long sl=jdbc.queryForObject("SELECT id FROM biz_so_line WHERE so_id=?",Long.class,s.getId());long oid=source(sl,3,"1",0);
        BizSaleReturn first=ret(oid,"1");jdbc.update("UPDATE biz_sale_return SET status='REJECTED' WHERE id=?",first.getId());
        eq("1",ret(oid,"3").getTotalAmt());
    }
    @Test void concurrentPublicAndInternalNumbersShareOneSequence() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var calls = java.util.stream.IntStream.range(0, 24)
                    .<java.util.concurrent.Callable<String>>mapToObj(i -> () -> docNumbers.nextForOrg("SO", ORG)).toList();
            var results = new java.util.HashSet<String>();
            for (var future : pool.invokeAll(calls)) assertTrue(results.add(future.get()), "并发单据号重复");
            assertEquals(24, results.size());
        } finally { pool.shutdownNow(); }
    }
    @Test void publicSalesEnforcesPeriodAndCentsWithoutLogin() {
        jdbc.update("INSERT INTO lr_link(id,org_id,link_type,token,label,warehouse_id,status,deleted) VALUES(? ,?,'SO','audit-public-regression','合成测试链接',?,1,0)",ID,ORG,ID);
        StpUtil.logout();SoPublicService.SubmitDTO d=new SoPublicService.SubmitDTO();d.token="audit-public-regression";d.customerName="合成客户";d.orderDate=LocalDate.now();
        SoPublicService.SubmitLine l=new SoPublicService.SubmitLine();l.materialId=ID;l.qty=n("1");l.amount=n("0.001");d.lines=List.of(l);
        assertThrows(BizException.class,()->publicSales.submit(d));
        l.amount=n("1");close(d.orderDate);assertThrows(BizException.class,()->publicSales.submit(d));
    }
}
