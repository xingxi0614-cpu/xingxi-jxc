package com.group.scm.review;

import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.work.DocGuardService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DocGuardScopeRegressionTest {
 JdbcTemplate db; DocGuardService service;
 LocalDate date=LocalDate.of(2026,10,8);
 @BeforeEach void setup() {
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:guard_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
  db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE md_warehouse(id BIGINT,name VARCHAR,org_id BIGINT,deleted INT)");
  db.execute("CREATE TABLE fin_period_close(org_id BIGINT,period_month VARCHAR,status VARCHAR,deleted INT)");
  db.execute("CREATE TABLE sys_org(id BIGINT,name VARCHAR)");
  db.execute("CREATE TABLE biz_stocktake(warehouse_id BIGINT,deleted INT,status VARCHAR,id BIGINT,doc_no VARCHAR)");
  db.update("INSERT INTO md_warehouse VALUES(10,'A仓库',1,0),(20,'B仓库',2,0),(30,'同账套其他组织仓库',3,0)");
  db.update("INSERT INTO sys_org VALUES(1,'A'),(2,'B'),(3,'A部门')");
  OrgScopeService scope=new OrgScopeService(db) {
   @Override public List<Long> visibleOrgIds(){return List.of(1L,2L,3L);}
   @Override public Long currentAccountSetOrgId(){return 1L;}
   @Override public List<Long> accountSetOrgIds(Long id){return id==1L||id==3L?List.of(1L,3L):List.of(2L);}
  };
  service=new DocGuardService(db,scope);
 }
 boolean blocked(List<Map<String,Object>> issues){return issues.stream().anyMatch(i->Boolean.TRUE.equals(i.get("blocking")));}
 @Test void selectedBIsOpenEvenWhenOwnAIsClosed(){
  db.update("INSERT INTO fin_period_close VALUES(1,'2026-10','CLOSED',0)");
  assertFalse(blocked(service.check(date,List.of(),2L,null)));
  assertFalse(blocked(service.check(date,List.of(20L),null,null)));
 }
 @Test void selectedBIsClosedEvenWhenOwnAIsOpen(){
  db.update("INSERT INTO fin_period_close VALUES(2,'2026-10','CLOSED',0)");
  assertTrue(blocked(service.check(date,List.of(),2L,null)));
  assertTrue(blocked(service.check(date,List.of(20L),null,null)));
 }
 @Test void partiallyClosedAccountSetChecksActualOrganization(){
  db.update("INSERT INTO fin_period_close VALUES(3,'2026-10','CLOSED',0)");
  assertFalse(blocked(service.check(date,List.of(),1L,null)));
  assertTrue(blocked(service.check(date,List.of(),3L,null)));
 }
 @Test void activeStocktakeBlocksAnotherStocktakeButOnlyWarnsOtherDrafts(){
  db.update("INSERT INTO biz_stocktake VALUES(20,0,'DRAFT',42,'ST-42')");
  assertTrue(blocked(service.check(date,List.of(20L),null,"STOCKTAKE_CREATE")));
  assertFalse(blocked(service.check(date,List.of(20L),null,null)));
 }
}
