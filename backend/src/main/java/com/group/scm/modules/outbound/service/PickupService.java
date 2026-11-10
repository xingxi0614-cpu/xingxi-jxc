package com.group.scm.modules.outbound.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.notif.service.NotifService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 调拨申请的执行额度；同一申请所有写入先锁申请行，库存事实只由交接过账产生。 */
@Service
@RequiredArgsConstructor
public class PickupService {
    private final JdbcTemplate jdbc;
    private final OrgScopeService scope;
    private final NotifService notif;
    private final ObjectMapper json;
    public static final String KIND = "PICKUP";
    public static BigDecimal n(Object v) { return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()); }
    public int setting(String key, int fallback) {
        var rows=jdbc.queryForList("SELECT param_value FROM sys_param WHERE param_key=?",String.class,key);
        try { int v=Integer.parseInt(rows.get(0)); return v>=1 && v<=365 ? v : fallback; } catch(Exception e) {return fallback;}
    }
    private BizOutReq currentRequest(Long id) {
        String lock=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()?" FOR UPDATE":"";
        var rows=jdbc.query("SELECT * FROM biz_out_req WHERE id=? AND deleted=0"+lock,new org.springframework.jdbc.core.BeanPropertyRowMapper<>(BizOutReq.class),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public BizOutReq require(Long id) {
        var r=currentRequest(id);
        if(r==null) throw new BizException("申请不存在");
        scope.assertOrgVisible(r.getOrgId());
        return r;
    }
    public boolean leader(BizOutReq r) {
        if(SecurityUtil.isAccountAdmin()) return true;
        String role=leaderRole(r.getBusinessCategory());
        return SecurityUtil.hasRole(role);
    }
    private String leaderRole(String category) {
        if(category!=null && (category.contains("工程") || category.equals("PROJECT"))) return "engineering_leader";
        if(category!=null && (category.contains("增值") || category.equals("VALUE_ADDED"))) return "market_leader";
        return "operation_leader";
    }
    public void canDecide(BizOutReq r) {
        if(!Objects.equals(r.getApplicantId(),SecurityUtil.uid()) && !leader(r)) throw new BizException(4003,"请由申请人或本业务负责人处理剩余领料");
    }
    public void lockTransfer(Long transferId) {
        var ids=jdbc.queryForList("SELECT source_req_id FROM biz_transfer WHERE id=? AND deleted=0",Long.class,transferId);
        if(!ids.isEmpty() && ids.get(0)!=null) require(ids.get(0));
    }
    public BizOutReq activate(Long id, boolean legacyChecked) {
        var r=require(id);
        if(!List.of("APPROVED","PARTIAL_OUT").contains(r.getStatus())) throw new BizException("申请尚未批准或已经结束");
        if("DIRECT".equals(r.getFulfillmentMode()) || n(r.getOutQty()).signum()>0 || count("SELECT COUNT(*) FROM biz_out WHERE source_req_id=? AND deleted=0 AND status IN ('DRAFT','PENDING','APPROVED')",id)>0)
            throw new BizException("此申请已用于材料出库，不能再用转库重复领取");
        if(count("SELECT COUNT(*) FROM biz_out_req_revision WHERE source_req_id=? AND deleted=0 AND status IN ('DRAFT','PENDING')",id)>0)
            throw new BizException("请先处理申请的在办修订单");
        if("LEGACY".equals(r.getFulfillmentMode()) && !legacyChecked) throw new BizException("旧申请需先核清历史转库，确认未重复领取后再登记");
        if(!"TRANSFER".equals(r.getFulfillmentMode())) {
            jdbc.update("UPDATE biz_out_req SET fulfillment_mode='TRANSFER',pickup_due_date=COALESCE(pickup_due_date,?),pickup_notice_stage=0 WHERE id=?",LocalDate.now().plusDays(setting("pickup.confirm.days",7)),id);
            event(id,"ENABLE","APPROVED",legacyChecked?"已核清历史未关联转库":"启用分批领料",null,null);
        }
        return require(id);
    }
    public void fillProgress(BizOutReq r) {
        var totals=jdbc.queryForMap("SELECT COALESCE(SUM(transferred_qty),0) transferred,COALESCE(SUM(closed_qty),0) closed FROM biz_out_req_line WHERE out_req_id=?",r.getId());
        r.setTransferredQty(n(totals.get("transferred")));r.setClosedQty(n(totals.get("closed")));
    }
    public void initialized(Long id) {
        jdbc.update("UPDATE biz_out_req SET pickup_due_date=COALESCE(pickup_due_date,?) WHERE id=? AND fulfillment_mode='UNSET'",LocalDate.now().plusDays(setting("pickup.confirm.days",7)),id);
    }
    public List<Map<String,Object>> lines(Long id) {
        return jdbc.queryForList("""
          SELECT l.id,l.material_id materialId,m.name materialName,m.spec,m.unit,l.qty,
            l.transferred_qty transferredQty,l.closed_qty closedQty,
            COALESCE(t.busy,0) busyQty,
            GREATEST(l.qty-l.transferred_qty-l.closed_qty-COALESCE(t.busy,0),0) availableQty
          FROM biz_out_req_line l JOIN md_material m ON m.id=l.material_id
          LEFT JOIN (SELECT tl.source_req_line_id,SUM(tl.qty) busy FROM biz_transfer_line tl
            JOIN biz_transfer t ON t.id=tl.transfer_id AND t.deleted=0
            WHERE t.source_req_id=? AND t.status IN ('DRAFT','PENDING','READY') GROUP BY tl.source_req_line_id) t ON t.source_req_line_id=l.id
          WHERE l.out_req_id=? ORDER BY l.line_no,l.id
          """,id,id);
    }
    public void assertNoClose(Long id) {
        if(count("SELECT COUNT(*) FROM biz_req_fulfillment_event WHERE req_id=? AND event_type='CLOSE' AND status='PENDING'",id)>0)
            throw new BizException("剩余不领申请正在确认，请先处理再登记领料");
    }
    public Map<String,Object> detail(Long id) {
        var r=require(id);
        var result=new LinkedHashMap<String,Object>();
        result.put("mode",r.getFulfillmentMode());result.put("dueDate",r.getPickupDueDate());result.put("extensions",r.getPickupExtensions());
        result.put("ended","CLOSED".equals(r.getStatus()));
        result.put("overdue",r.getPickupDueDate()!=null && r.getPickupDueDate().isBefore(LocalDate.now()) && !"CLOSED".equals(r.getStatus()));
        result.put("canDecide",Objects.equals(r.getApplicantId(),SecurityUtil.uid()) || leader(r));result.put("leader",leader(r));
        result.put("canWarehouse",SecurityUtil.hasRole("warehouse"));
        result.put("lines",lines(id));
        result.put("events",jdbc.queryForList("SELECT e.*,u.name actorName,v.name reviewerName FROM biz_req_fulfillment_event e LEFT JOIN sys_user u ON u.id=e.created_by LEFT JOIN sys_user v ON v.id=e.reviewed_by WHERE e.req_id=? ORDER BY e.id DESC",id));
        result.put("transfers",jdbc.queryForList("SELECT id,doc_no docNo,status,to_warehouse_id toWarehouseId,handover_receiver receiver,handover_at handoverAt FROM biz_transfer WHERE source_req_id=? AND deleted=0 ORDER BY id DESC",id));
        return result;
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void extend(Long id, LocalDate due, String reason, boolean legacyChecked) {
        var r=require(id);canDecide(r);r=activate(id,legacyChecked);assertNoClose(id);
        if(due==null || !due.isAfter(LocalDate.now()) || due.isAfter(LocalDate.now().plusDays(365))) throw new BizException("请选择明天起一年内的预计领取日期");
        if(r.getPickupDueDate()!=null && !due.isAfter(r.getPickupDueDate())) throw new BizException("新日期须晚于当前领取期限");
        if(r.getPickupExtensions()!=null && r.getPickupExtensions()>=setting("pickup.extension.limit",1) && !leader(r)) throw new BizException("已用完普通延期次数，请业务负责人决定领取日期或结束");
        String memo=leader(r) && n(r.getPickupExtensions()).intValue()>=setting("pickup.extension.limit",1) ? reason(reason) : "还要领";
        jdbc.update("UPDATE biz_out_req SET pickup_due_date=?,pickup_extensions=pickup_extensions+1,pickup_notice_stage=0 WHERE id=?",due,id);
        event(id,"EXTEND","APPROVED",memo,null,due);closeTodos(id);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void requestClose(Long id,List<Long> lineIds,String reason,boolean legacyChecked) {
        var r=require(id);canDecide(r);activate(id,legacyChecked);assertNoClose(id);
        String memo=reason(reason);
        if(lineIds==null || lineIds.isEmpty() || new HashSet<>(lineIds).size()!=lineIds.size()) throw new BizException("请选择不再领取的物料，不能重复选择");
        var selected=new ArrayList<Map<String,Object>>();
        for(Long lineId:lineIds) {
            var l=lines(id).stream().filter(x->Objects.equals(((Number)x.get("id")).longValue(),lineId)).findFirst().orElseThrow(()->new BizException("物料不属于此申请"));
            if(n(l.get("busyQty")).signum()>0) throw new BizException("该物料还有在办转库或待交接，请先处理");
            if(n(l.get("availableQty")).signum()<=0) throw new BizException("该物料已无剩余可关闭数量");
            selected.add(Map.of("id",lineId,"qty",n(l.get("availableQty"))));
        }
        try {event(id,"CLOSE","PENDING",memo,json.writeValueAsString(selected),null);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BizException("无法保存余量明细");}
        jdbc.update("UPDATE biz_out_req SET pickup_notice_stage=0 WHERE id=?",id);
        closeTodos(id);notif.pushToRole("warehouse",r.getOrgId(),KIND,"剩余不领待确认："+r.getDocNo(),"OREQ",r.getDocNo(),id);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reviewClose(Long id,Long eventId,boolean approve,String reason) {
        SecurityUtil.requireRole("warehouse");var r=require(id);
        var events=jdbc.queryForList("SELECT * FROM biz_req_fulfillment_event WHERE id=? AND req_id=? AND event_type='CLOSE' AND status='PENDING' FOR UPDATE",eventId,id);
        if(events.isEmpty()) throw new BizException("该处理已完成，请刷新");
        var e=events.get(0);
        if(approve) {
            try {
                var selected=json.readTree(e.get("lines_json").toString());
                for(var item:selected) {
                    long lineId=item.get("id").asLong();BigDecimal qty=item.get("qty").decimalValue();
                    var l=lines(id).stream().filter(x->((Number)x.get("id")).longValue()==lineId).findFirst().orElseThrow(()->new BizException("申请明细已变化"));
                    if(n(l.get("busyQty")).signum()>0 || n(l.get("availableQty")).compareTo(qty)!=0) throw new BizException("待领数量已变化，请退回后重新确认");
                    jdbc.update("UPDATE biz_out_req_line SET closed_qty=closed_qty+? WHERE id=? AND out_req_id=?",qty,lineId,id);
                }
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new BizException("余量记录异常，请联系管理员");}
        }
        jdbc.update("UPDATE biz_req_fulfillment_event SET status=?,reviewed_by=?,reviewed_at=NOW(),review_reason=? WHERE id=?",approve?"APPROVED":"REJECTED",SecurityUtil.uid(),approve?"核实剩余未领取":reason(reason),eventId);
        closeTodos(id);recompute(id);
        if(!approve) notif.pushTodoToUser(r.getApplicantId(),KIND,"剩余不领已退回，请重新确认："+r.getDocNo(),"OREQ",r.getDocNo(),id);
        jdbc.update("UPDATE biz_out_req SET pickup_notice_stage=0 WHERE id=?",id);
    }
    public void recompute(Long id) {
        var ls=lines(id);boolean done=!ls.isEmpty() && ls.stream().allMatch(l->n(l.get("qty")).compareTo(n(l.get("transferredQty")).add(n(l.get("closedQty"))))==0 && n(l.get("busyQty")).signum()==0);
        if(done) {jdbc.update("UPDATE biz_out_req SET status='CLOSED' WHERE id=? AND fulfillment_mode='TRANSFER'",id);closeTodos(id);}
    }
    public void posted(Long id,Long transferId) {
        jdbc.update("""
          UPDATE biz_out_req_line l JOIN biz_transfer_line t ON t.source_req_line_id=l.id
          SET l.transferred_qty=l.transferred_qty+t.qty WHERE t.transfer_id=? AND l.out_req_id=?
          """,transferId,id);
        recompute(id);
    }
    public void event(Long id,String type,String status,String reason,String lines,LocalDate due) {
        jdbc.update("INSERT INTO biz_req_fulfillment_event(req_id,event_type,status,reason,lines_json,due_date,created_by) VALUES(?,?,?,?,?,?,?)",id,type,status,reason,lines,due,SecurityUtil.uid());
    }
    private String reason(String v) {if(v==null || v.isBlank() || v.trim().length()>200)throw new BizException("请填写200字以内的处理原因");return v.trim();}
    private int count(String sql,Object...args) {return jdbc.queryForObject(sql,Integer.class,args);}
    public void closeTodos(Long id) {jdbc.update("UPDATE notif SET is_read=1,read_at=NOW() WHERE biz_type='OREQ' AND biz_id=? AND todo_kind=? AND is_read=0",id,KIND);}
    /** 定时任务无用户会话，逐单事务锁由调度器的 TransactionTemplate 提供。 */
    public void remind(Long id) {
        var r=currentRequest(id);if(r==null || !List.of("APPROVED","PARTIAL_OUT").contains(r.getStatus()) || !List.of("UNSET","TRANSFER").contains(r.getFulfillmentMode())) return;
        if(count("SELECT COUNT(*) FROM biz_req_fulfillment_event WHERE req_id=? AND event_type='CLOSE' AND status='PENDING'",id)>0) {
            if(n(r.getPickupNoticeStage()).intValue()<2 && count("SELECT COUNT(*) FROM biz_req_fulfillment_event WHERE req_id=? AND event_type='CLOSE' AND status='PENDING' AND created_at<DATE_SUB(NOW(),INTERVAL ? DAY)",id,setting("pickup.confirm.days",7))>0) {
                notif.pushToRole(leaderRole(r.getBusinessCategory()),r.getOrgId(),KIND,"剩余不领待仓管核实已超期，请协调处理："+r.getDocNo(),"OREQ",r.getDocNo(),id);
                notif.pushToRole("account_admin",r.getOrgId(),KIND,"剩余不领核实超期，请督办仓管："+r.getDocNo(),"OREQ",r.getDocNo(),id);
                jdbc.update("UPDATE biz_out_req SET pickup_notice_stage=2 WHERE id=?",id);
            }
            return;
        }
        if(r.getPickupDueDate()==null || !r.getPickupDueDate().isBefore(LocalDate.now())) return;
        int stage=r.getPickupNoticeStage()==null?0:r.getPickupNoticeStage();
        boolean ownerActive=count("SELECT COUNT(*) FROM sys_user WHERE id=? AND deleted=0 AND status=1",r.getApplicantId())>0;
        boolean escalated=!ownerActive || r.getPickupDueDate().plusDays(setting("pickup.confirm.days",7)).isBefore(LocalDate.now()) || n(r.getPickupExtensions()).intValue()>=setting("pickup.extension.limit",1);
        int next=escalated?2:1;if(stage>=next)return;
        closeTodos(id);
        if(escalated) {
            String role=leaderRole(r.getBusinessCategory());
            notif.pushToRole(role,r.getOrgId(),KIND,"领料余量逾期，请决定继续或结束："+r.getDocNo(),"OREQ",r.getDocNo(),id);
            notif.pushToRole("account_admin",r.getOrgId(),KIND,"领料余量需负责人处理（岗位缺位请接手）："+r.getDocNo(),"OREQ",r.getDocNo(),id);
        } else notif.pushTodoToUser(r.getApplicantId(),KIND,"还有材料没领，请确认还要不要："+r.getDocNo(),"OREQ",r.getDocNo(),id);
        jdbc.update("UPDATE biz_out_req SET pickup_notice_stage=? WHERE id=?",next,id);
    }
}
