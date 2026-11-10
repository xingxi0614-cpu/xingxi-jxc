package com.group.scm.modules.materialreq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.materialreq.entity.PpRequest;
import com.group.scm.modules.materialreq.entity.PpRequestLine;
import com.group.scm.modules.materialreq.mapper.PpRequestLineMapper;
import com.group.scm.modules.materialreq.mapper.PpRequestMapper;
import com.group.scm.modules.notif.service.NotifService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采购计划收件箱：业务提报需求 → 采购结合「当前库存/最低库存」看建议采购量、调最终采购量 → 确认(可打印)。
 * 采购计划是计划，不动库存、不生成正式单据。
 * 建议采购量 = max(0, 需求 + 最低库存 − 当前库存)。
 */
@Service
@RequiredArgsConstructor
public class PpRequestService {

    private final PpRequestMapper reqMapper;
    private final PpRequestLineMapper lineMapper;
    private final OrgScopeService orgScope;
    private final NotifService notifService;
    private final JdbcTemplate jdbc;

    /**
     * 公司整体采购计划汇总：把期间内多份提报按物料合并（需求求和），配公司全部仓库存合计与
     * 建议采购量（=max(0, 需求合计+最低库存−现有库存)），给采购看"一个公司的整体计划"。
     * 默认统计 待确认+已确认（驳回的不计）。
     */
    public Map<String, Object> summary(Long orgId, List<String> statuses,
                                       java.time.LocalDate startDate, java.time.LocalDate endDate) {
        List<Long> visible = orgScope.visibleOrgIds();
        // 采购计划提报单挂在【公司】(如某公司 org=19),而采购员账号通常挂在【部门】(如技术部 org=20)。
        // 若直接用登录人的 orgId 去查,永远查不到本公司的提报 → 汇总一片空白(实测踩过)。
        // 故未显式指定 orgId 时,自动从所在部门上溯到其所属公司(账套根)。
        Long org = orgId;
        if (org == null) {
            org = orgScope.currentAccountSetOrgId();
            if (org == null) org = SecurityUtil.orgId();
        }
        if (org == null || !visible.contains(org)) org = visible.isEmpty() ? -1L : visible.get(0);
        List<String> sts = (statuses == null || statuses.isEmpty()) ? List.of("SUBMITTED", "CONFIRMED") : statuses;
        String stPh = String.join(",", java.util.Collections.nCopies(sts.size(), "?"));

        StringBuilder sql = new StringBuilder(
            "SELECT l.material_id, m.code, m.name, m.spec, m.unit, COALESCE(m.safety_qty,0) safety_qty, "
          + "SUM(l.demand_qty) demand_qty, SUM(COALESCE(l.final_qty,0)) final_qty, "
          + "COUNT(DISTINCT h.id) req_cnt, "
          + "GROUP_CONCAT(DISTINCT h.applicant_name ORDER BY h.applicant_name SEPARATOR '、') applicants, "
          + "COALESCE((SELECT SUM(b.qty) FROM inv_balance b "
          + "          JOIN md_warehouse w ON w.id = b.warehouse_id AND w.deleted = 0 AND w.org_id = h.org_id "
          + "          WHERE b.material_id = l.material_id), 0) stock_qty "
          + "FROM pp_request h JOIN pp_request_line l ON l.request_id = h.id "
          + "JOIN md_material m ON m.id = l.material_id "
          + "WHERE h.deleted = 0 AND h.org_id = ? AND h.status IN (" + stPh + ")");
        List<Object> params = new java.util.ArrayList<>();
        params.add(org);
        params.addAll(sts);
        if (startDate != null) { sql.append(" AND h.created_at >= ?"); params.add(startDate.atStartOfDay()); }
        if (endDate != null)   { sql.append(" AND h.created_at < ?"); params.add(endDate.plusDays(1).atStartOfDay()); }
        sql.append(" GROUP BY l.material_id, m.code, m.name, m.spec, m.unit, m.safety_qty, h.org_id ORDER BY m.code");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        for (Map<String, Object> r : rows) {
            BigDecimal demand = (BigDecimal) r.get("demand_qty");
            BigDecimal safety = (BigDecimal) r.get("safety_qty");
            BigDecimal stock = (BigDecimal) r.get("stock_qty");
            BigDecimal suggest = demand.add(safety).subtract(stock);
            r.put("suggest_qty", suggest.signum() > 0 ? suggest : BigDecimal.ZERO);
        }

        List<Map<String, Object>> companies = new java.util.ArrayList<>();
        if (!visible.isEmpty()) {
            String vph = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
            companies = jdbc.queryForList(
                "SELECT id, name FROM sys_org WHERE deleted = 0 AND status = 1 AND type = 'COMPANY' AND id IN (" + vph + ") ORDER BY sort, id",
                visible.toArray());
        }
        String orgName = jdbc.queryForList("SELECT name FROM sys_org WHERE id = ?", String.class, org)
                .stream().findFirst().orElse("");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("companies", companies);
        out.put("orgId", org);
        out.put("orgName", orgName);
        return out;
    }

    public IPage<PpRequest> page(long current, long size, String status, String keyword) {
        QueryWrapper<PpRequest> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("doc_no", k).or().like("applicant_name", k).or().like("dept", k));
        }
        qw.orderByDesc("id");
        IPage<PpRequest> page = reqMapper.selectPage(new Page<>(current, size), qw);
        enrich(page.getRecords());
        return page;
    }

    public PpRequest detail(Long id) {
        PpRequest r = reqMapper.selectById(id);
        if (r == null) throw new BizException("采购计划不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        List<PpRequestLine> lines = lineMapper.selectList(
                new QueryWrapper<PpRequestLine>().eq("request_id", id).orderByAsc("line_no"));
        enrichLines(lines, r.getWarehouseId());
        r.setLines(lines);
        enrich(List.of(r));
        return r;
    }

    /** 采购确认：逐行填最终采购量(不填则取建议量)，状态→已确认。仅采购(或管理员)。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id, List<LineQty> finals) {
        PpRequest r = reqMapper.selectById(id);
        if (r == null) throw new BizException("采购计划不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        if (!"SUBMITTED".equals(r.getStatus())) throw new BizException("当前状态不可确认：" + r.getStatus());
        if (!SecurityUtil.isAdmin() && !SecurityUtil.hasRole("purchaser")) {
            throw new BizException("采购计划需由「采购」确认");
        }
        Map<Long, BigDecimal> finalMap = new HashMap<>();
        if (finals != null) for (LineQty f : finals) if (f != null && f.lineId != null) finalMap.put(f.lineId, f.finalQty);

        List<PpRequestLine> lines = lineMapper.selectList(
                new QueryWrapper<PpRequestLine>().eq("request_id", id).orderByAsc("line_no"));
        enrichLines(lines, r.getWarehouseId());
        for (PpRequestLine ln : lines) {
            BigDecimal fq = finalMap.get(ln.getId());
            if (fq == null) fq = ln.getSuggestQty();            // 不填默认取建议采购量
            if (fq == null || fq.signum() < 0) fq = BigDecimal.ZERO;
            ln.setFinalQty(fq);
            lineMapper.updateById(ln);
        }
        r.setStatus("CONFIRMED");
        r.setConfirmBy(currentUserName());
        r.setConfirmAt(LocalDateTime.now());
        reqMapper.updateById(r);
        notifService.closeBusinessTodos("PP", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        PpRequest r = reqMapper.selectById(id);
        if (r == null) throw new BizException("采购计划不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        if (!"SUBMITTED".equals(r.getStatus())) throw new BizException("当前状态不可驳回：" + r.getStatus());
        if (!SecurityUtil.isAdmin() && !SecurityUtil.hasRole("purchaser")) {
            throw new BizException("采购计划需由「采购」处理");
        }
        r.setRejectBy(currentUserName());
        r.setRejectAt(LocalDateTime.now());
        r.setRejectReason(reason == null ? "" : reason.trim());
        r.setStatus("REJECTED");
        reqMapper.updateById(r);
        notifService.closeBusinessTodos("PP", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        PpRequest r = reqMapper.selectById(id);
        if (r == null) return;
        orgScope.assertOrgVisible(r.getOrgId());
        if (!"REJECTED".equals(r.getStatus())) throw new BizException("只有已驳回的采购计划可以删除");
        notifService.closeBusinessTodos("PP", id);
        lineMapper.delete(new QueryWrapper<PpRequestLine>().eq("request_id", id));
        reqMapper.deleteById(id);
    }

    private String currentUserName() {
        Long uid = SecurityUtil.uid();
        if (uid == null) return null;
        try {
            return jdbc.query("SELECT name FROM sys_user WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, uid);
        } catch (Exception e) { return null; }
    }

    private void enrich(List<PpRequest> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> orgIds = rows.stream().map(PpRequest::getOrgId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> orgMap = LrPickerService.orgNames(jdbc, orgIds);
        List<Long> whIds = rows.stream().map(PpRequest::getWarehouseId).filter(Objects::nonNull).distinct().toList();
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

    /** 富集物料信息 + 当前库存 + 最低库存 + 建议采购量。 */
    private void enrichLines(List<PpRequestLine> lines, Long warehouseId) {
        if (lines == null || lines.isEmpty()) return;
        for (PpRequestLine ln : lines) {
            if (ln.getMaterialId() == null) continue;
            jdbc.query("""
                    SELECT m.code, m.name, m.spec, m.unit, COALESCE(m.safety_qty, 0) safety_qty,
                           COALESCE(b.qty, 0) stock_qty
                    FROM md_material m
                    LEFT JOIN inv_balance b ON b.material_id = m.id AND b.warehouse_id = ?
                    WHERE m.id = ?
                    """, (ResultSet rs) -> {
                ln.setMaterialCode(rs.getString("code"));
                ln.setMaterialName(rs.getString("name"));
                ln.setMaterialSpec(rs.getString("spec"));
                ln.setMaterialUnit(rs.getString("unit"));
                ln.setSafetyQty(rs.getBigDecimal("safety_qty"));
                ln.setStockQty(rs.getBigDecimal("stock_qty"));
            }, warehouseId, ln.getMaterialId());
            BigDecimal demand = ln.getDemandQty() == null ? BigDecimal.ZERO : ln.getDemandQty();
            BigDecimal safety = ln.getSafetyQty() == null ? BigDecimal.ZERO : ln.getSafetyQty();
            BigDecimal stock = ln.getStockQty() == null ? BigDecimal.ZERO : ln.getStockQty();
            BigDecimal suggest = demand.add(safety).subtract(stock);
            if (suggest.signum() < 0) suggest = BigDecimal.ZERO;
            ln.setSuggestQty(suggest);
        }
    }

    public static class LineQty {
        public Long lineId;
        public BigDecimal finalQty;
    }
}
