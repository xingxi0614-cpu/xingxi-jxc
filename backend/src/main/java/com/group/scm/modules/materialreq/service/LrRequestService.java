package com.group.scm.modules.materialreq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.materialreq.entity.LrRequest;
import com.group.scm.modules.materialreq.entity.LrRequestLine;
import com.group.scm.modules.materialreq.mapper.LrRequestLineMapper;
import com.group.scm.modules.materialreq.mapper.LrRequestMapper;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import com.group.scm.modules.outbound.mapper.BizOutReqLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 领料申请收件箱：采购→仓管→财务依次线上确认，全部通过后生成「已审批/可发料」的出库申请。
 * 本服务永不触碰库存——生成的出库申请也只是计划，实际扣库存仍在材料出库环节。
 */
@Service
@RequiredArgsConstructor
public class LrRequestService {

    private final LrRequestMapper requestMapper;
    private final LrRequestLineMapper lineMapper;
    private final OrgScopeService orgScope;
    private final DocNoService docNoService;
    private final BizOutReqMapper outReqMapper;
    private final BizOutReqLineMapper outReqLineMapper;
    private final JdbcTemplate jdbc;
    private final com.group.scm.modules.outbound.service.PickupService pickup;
    private final com.group.scm.modules.notif.service.NotifService notifService;

    public IPage<LrRequest> page(long current, long size, String status, String keyword) {
        QueryWrapper<LrRequest> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("doc_no", k).or().like("picker_name", k).or().like("project_name", k));
        }
        qw.orderByDesc("id");
        IPage<LrRequest> page = requestMapper.selectPage(new Page<>(current, size), qw);
        enrich(page.getRecords());
        return page;
    }

    public LrRequest detail(Long id) {
        LrRequest r = requestMapper.selectById(id);
        if (r == null) throw new BizException("领料申请不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        List<LrRequestLine> lines = lineMapper.selectList(
                new QueryWrapper<LrRequestLine>().eq("request_id", id).orderByAsc("line_no"));
        enrichLines(lines, r.getWarehouseId());
        r.setLines(lines);
        enrich(List.of(r));
        return r;
    }

    /** 当前用户确认（按状态推进 采购→仓管→财务；财务确认后生成出库申请）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        LrRequest r = requestMapper.selectById(id);
        if (r == null) throw new BizException("领料申请不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        String name = currentUserName();
        LocalDateTime now = LocalDateTime.now();
        // 流转前先关闭本单当前待办，再推给下一关角色
        notifService.closeBusinessTodos("LR", r.getId());
        switch (r.getStatus() == null ? "" : r.getStatus()) {
            case "SUBMITTED" -> {
                requireRole("purchaser", "采购");
                r.setPurchaseBy(name);
                r.setPurchaseById(SecurityUtil.uid());
                r.setPurchaseAt(now);
                r.setStatus("PURCHASE_OK");
                // 并发防线:条件更新,双人/双击同确认只有一个成功
                claimTransition(id, "SUBMITTED", r);
                notifService.pushToRole("warehouse", r.getOrgId(),
                        "领料申请待仓管确认：" + r.getDocNo(), "LR", r.getDocNo(), r.getId());
            }
            case "PURCHASE_OK" -> {
                requireRole("warehouse", "仓管");
                r.setWarehouseBy(name);
                r.setWarehouseAt(now);
                r.setStatus("WAREHOUSE_OK");
                claimTransition(id, "PURCHASE_OK", r);
                notifService.pushToRole("material_accountant", r.getOrgId(),
                        "领料申请待财务确认：" + r.getDocNo(), "LR", r.getDocNo(), r.getId());
            }
            case "WAREHOUSE_OK" -> {
                requireRole("material_accountant", "财务（材料会计）");
                r.setFinanceBy(name);
                r.setFinanceAt(now);
                r.setStatus("APPROVED");
                // 关键:条件更新必须在 generateOutReq 之前——赢得 CAS 的事务才生成出库申请,防止双击生成两张
                claimTransition(id, "WAREHOUSE_OK", r);
                generateOutReq(r);
            }
            default -> throw new BizException("当前状态不可确认：" + r.getStatus());
        }
    }

    /** 条件更新领料申请状态:仅当当前状态仍为 fromStatus 时生效,否则抛错中止(防并发重复确认与副作用) */
    private void claimTransition(Long id, String fromStatus, LrRequest r) {
        int updated = requestMapper.update(r, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<LrRequest>()
                .eq("id", id).eq("status", fromStatus));
        if (updated != 1) throw new BizException("该领料申请刚被处理（可能已被并发确认），请刷新后查看");
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        LrRequest r = requestMapper.selectById(id);
        if (r == null) throw new BizException("领料申请不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        String st = r.getStatus() == null ? "" : r.getStatus();
        if (!st.equals("SUBMITTED") && !st.equals("PURCHASE_OK") && !st.equals("WAREHOUSE_OK")) {
            throw new BizException("当前状态不可驳回：" + r.getStatus());
        }
        // 当前环节对应角色（或管理员）可驳回
        boolean ok = SecurityUtil.isAdmin()
                || (st.equals("SUBMITTED") && SecurityUtil.hasRole("purchaser"))
                || (st.equals("PURCHASE_OK") && SecurityUtil.hasRole("warehouse"))
                || (st.equals("WAREHOUSE_OK") && SecurityUtil.hasRole("material_accountant"));
        if (!ok) throw new BizException("当前环节无权驳回");
        r.setRejectBy(currentUserName());
        r.setRejectAt(LocalDateTime.now());
        r.setRejectReason(reason == null ? "" : reason.trim());
        r.setStatus("REJECTED");
        requestMapper.updateById(r);
        notifService.closeBusinessTodos("LR", r.getId());
        // 退回单据进经办人(采购)待办，提醒去核对/清理被驳回的领料申请
        notifService.pushToRole("purchaser", r.getOrgId(),
                "领料申请被驳回：" + r.getDocNo() + (r.getRejectReason().isBlank() ? "" : ("，原因：" + r.getRejectReason())),
                "LR", r.getDocNo(), r.getId());
    }

    /** 删除已驳回的领料申请（清理用）。其它状态不允许删。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LrRequest r = requestMapper.selectById(id);
        if (r == null) return;
        orgScope.assertOrgVisible(r.getOrgId());
        if (!"REJECTED".equals(r.getStatus())) {
            throw new BizException("只有已驳回的领料申请可以删除；其它状态请走确认/驳回流程");
        }
        notifService.closeBusinessTodos("LR", id);
        lineMapper.delete(new QueryWrapper<LrRequestLine>().eq("request_id", id));
        requestMapper.deleteById(id);
    }

    private void requireRole(String role, String label) {
        if (!SecurityUtil.isAdmin() && !SecurityUtil.hasRole(role)) {
            throw new BizException("该环节需由「" + label + "」确认");
        }
    }

    /** 生成「已审批/可发料」的出库申请（仅计划，不动库存）。 */
    private void generateOutReq(LrRequest r) {
        BizOutReq o = new BizOutReq();
        o.setDocNo(docNoService.next("OREQ"));
        o.setOrgId(r.getOrgId());
        o.setDeptId(r.getOrgId());
        o.setWarehouseId(r.getWarehouseId());
        // 发起人=采购(收件箱受理人)，非财务确认者：删/改/重提权与退回待办都归采购
        o.setApplicantId(r.getPurchaseById() != null ? r.getPurchaseById() : SecurityUtil.uid());
        o.setMaterialApplicant(r.getPickerName());
        o.setProjectId(r.getProjectId());
        o.setProjectName(r.getProjectName());
        o.setOutType(r.getOutType());
        o.setBusinessCategory(r.getBizType());
        o.setPurpose(r.getProjectName());
        o.setApplyDate(r.getApplyDate() != null ? r.getApplyDate() : LocalDate.now());
        o.setStatus("APPROVED");
        o.setFulfillmentMode("UNSET");
        StringBuilder rk = new StringBuilder("由领料申请 ").append(r.getDocNo()).append(" 生成");
        if (r.getSite() != null && !r.getSite().isBlank()) rk.append("，施工地点：").append(r.getSite());
        if (r.getApplicantName() != null && !r.getApplicantName().isBlank()) rk.append("，经办：").append(r.getApplicantName());
        o.setRemark(rk.toString());

        List<LrRequestLine> lns = lineMapper.selectList(
                new QueryWrapper<LrRequestLine>().eq("request_id", r.getId()).orderByAsc("line_no"));
        BigDecimal total = BigDecimal.ZERO;
        for (LrRequestLine ln : lns) total = total.add(ln.getQty() == null ? BigDecimal.ZERO : ln.getQty());
        o.setTotalQty(total);
        o.setOutQty(BigDecimal.ZERO);
        outReqMapper.insert(o);
        pickup.initialized(o.getId());

        int i = 1;
        for (LrRequestLine ln : lns) {
            BizOutReqLine bl = new BizOutReqLine();
            bl.setOutReqId(o.getId());
            bl.setLineNo(i++);
            bl.setMaterialId(ln.getMaterialId());
            bl.setQty(ln.getQty());
            bl.setOriginalQty(ln.getQty());
            bl.setOutQty(BigDecimal.ZERO);
            bl.setRemark(ln.getRemark());
            outReqLineMapper.insert(bl);
        }
        r.setOutReqId(o.getId());
        r.setOutReqNo(o.getDocNo());
        /*
         * 🔴 2026-08-18 修:这两个字段必须【落库】,不能只设在内存对象上。
         *
         * 原来 claimTransition(唯一的 UPDATE)在本方法之前就执行完了,之后再 setXxx 没人写回,
         * 于是 lr_request.out_req_id 永远是 NULL。后果:
         *   ① 领料申请上看不到「已生成出库申请 XXX」,两张单在数据上断链;
         *   ② WorkflowService.cancelLrGeneratedOutReq 靠 `WHERE out_req_id = ?` 反查来定位,
         *      查不到就 return null → 这张「已审批/可发料」的出库申请【永远撤不掉】,
         *      填错了也只能一直挂着。
         * 2026-08-04 与 08-18 两轮审计各自独立发现同一处,这次落实修复。
         */
        requestMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<LrRequest>()
                .eq("id", r.getId())
                .set("out_req_id", o.getId())
                .set("out_req_no", o.getDocNo()));
    }

    private String currentUserName() {
        Long uid = SecurityUtil.uid();
        if (uid == null) return null;
        try {
            return jdbc.query("SELECT name FROM sys_user WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, uid);
        } catch (Exception e) {
            return null;
        }
    }

    private void enrich(List<LrRequest> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> orgIds = rows.stream().map(LrRequest::getOrgId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> orgMap = LrPickerService.orgNames(jdbc, orgIds);
        List<Long> whIds = rows.stream().map(LrRequest::getWarehouseId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> whMap = new HashMap<>();
        if (!whIds.isEmpty()) {
            String ph = whIds.stream().map(x -> "?").collect(Collectors.joining(","));
            jdbc.query("SELECT id, name FROM md_warehouse WHERE id IN (" + ph + ")",
                    (ResultSet rs) -> { whMap.put(rs.getLong("id"), rs.getString("name")); }, whIds.toArray());
        }
        rows.forEach(r -> {
            r.setOrgName(orgMap.get(r.getOrgId()));
            r.setWarehouseName(whMap.get(r.getWarehouseId()));
        });
    }

    private void enrichLines(List<LrRequestLine> lines, Long warehouseId) {
        if (lines == null || lines.isEmpty()) return;
        for (LrRequestLine ln : lines) {
            if (ln.getMaterialId() == null) continue;
            jdbc.query("""
                    SELECT m.code, m.name, m.spec, m.unit, COALESCE(b.qty, 0) stock_qty
                    FROM md_material m
                    LEFT JOIN inv_balance b ON b.material_id = m.id AND b.warehouse_id = ?
                    WHERE m.id = ?
                    """, (ResultSet rs) -> {
                ln.setMaterialCode(rs.getString("code"));
                ln.setMaterialName(rs.getString("name"));
                ln.setMaterialSpec(rs.getString("spec"));
                ln.setMaterialUnit(rs.getString("unit"));
                ln.setStockQty(rs.getBigDecimal("stock_qty"));
            }, warehouseId, ln.getMaterialId());
        }
    }
}
