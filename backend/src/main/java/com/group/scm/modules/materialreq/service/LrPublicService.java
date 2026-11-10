package com.group.scm.modules.materialreq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.modules.materialreq.entity.LrLink;
import com.group.scm.modules.materialreq.entity.LrPicker;
import com.group.scm.modules.materialreq.entity.LrRequest;
import com.group.scm.modules.materialreq.entity.LrRequestLine;
import com.group.scm.modules.materialreq.entity.PpRequest;
import com.group.scm.modules.materialreq.entity.PpRequestLine;
import com.group.scm.modules.materialreq.mapper.LrLinkMapper;
import com.group.scm.modules.materialreq.mapper.LrPickerMapper;
import com.group.scm.modules.materialreq.mapper.LrRequestLineMapper;
import com.group.scm.modules.materialreq.mapper.LrRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 免登录领料申请提交服务（仅凭链接 token，不触碰登录态）。
 * 全部为「计划」操作：只写 lr_request / lr_request_line，绝不动库存。库存只读、只返回数量、不含成本。
 */
@Service
@RequiredArgsConstructor
public class LrPublicService {

    private final LrLinkMapper linkMapper;
    private final LrPickerMapper pickerMapper;
    private final LrRequestMapper requestMapper;
    private final LrRequestLineMapper lineMapper;
    private final com.group.scm.modules.materialreq.mapper.PpRequestMapper ppMapper;
    private final com.group.scm.modules.materialreq.mapper.PpRequestLineMapper ppLineMapper;
    private final JdbcTemplate jdbc;
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final com.group.scm.modules.attachment.service.AttachmentService attachmentService;

    /** token -> [窗口起始ms, 计数]，每 token 每分钟最多 30 次提交。 */
    private final Map<String, long[]> rate = new ConcurrentHashMap<>();

    private LrLink requireLink(String token) {
        if (token == null || token.isBlank()) throw new BizException("链接无效");
        LrLink link = linkMapper.selectOne(new QueryWrapper<LrLink>().eq("token", token).last("limit 1"));
        if (link == null) throw new BizException("链接无效或已失效");
        if (link.getStatus() == null || link.getStatus() != 1) throw new BizException("该提交链接已停用");
        // 🔴 2026-10-18 修(安全审查 S02):领料与销售录单共用 lr_link 表,以前这里不看用途,
        //    拿销售录单链接也能读领料上下文(领料人、工程项目、库存)、提交领料/采购计划。
        //    link_type 列 NOT NULL DEFAULT 'LR',老链接都是 LR,严格比对不会误伤。
        if (!"LR".equals(link.getLinkType())) throw new BizException("该链接不是领料申请链接");
        return link;
    }

    /** 进入提交页所需上下文：公司、仓库、领料人清单（只数量、不含成本）。 */
    public Map<String, Object> context(String token) {
        LrLink link = requireLink(token);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("orgId", link.getOrgId());
        r.put("orgName", queryName("SELECT name FROM sys_org WHERE id = ?", link.getOrgId()));
        r.put("warehouseId", link.getWarehouseId());
        r.put("warehouseName", queryName("SELECT name FROM md_warehouse WHERE id = ?", link.getWarehouseId()));
        List<LrPicker> pickers = pickerMapper.selectList(new QueryWrapper<LrPicker>()
                .eq("org_id", link.getOrgId()).eq("status", 1).orderByAsc("name"));
        List<Map<String, Object>> ps = new ArrayList<>();
        for (LrPicker p : pickers) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            ps.add(m);
        }
        r.put("pickers", ps);
        // 工程项目（本公司启用中）+ 出库类型 + 业务分类（与出库申请必填项一致，让一线尽量选）
        List<Map<String, Object>> projects = new ArrayList<>();
        jdbc.query("SELECT id, code, name FROM md_project WHERE org_id = ? AND deleted = 0 AND status = 1 ORDER BY code",
                (ResultSet rs) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    projects.add(m);
                }, link.getOrgId());
        r.put("projects", projects);
        r.put("outTypes", options("out.type.options", "销售材料,工程领料,生活用料,运营维修领料,其他出库"));
        r.put("businessCategories", options("business.category.options", "工程类,运营类,增值销售类,其他"));
        return r;
    }

    /** 读 sys_param 逗号分隔选项（无则用兜底）。 */
    private List<String> options(String key, String fallback) {
        String value = jdbc.query("SELECT param_value FROM sys_param WHERE param_key = ?",
                rs -> rs.next() ? rs.getString(1) : fallback, key);
        if (value == null || value.isBlank()) value = fallback;
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 物料搜索（集团池：全库可选）+ 本仓当前库存（仅数量）。 */
    public List<Map<String, Object>> searchMaterials(String token, String keyword) {
        LrLink link = requireLink(token);
        String kw = keyword == null ? "" : keyword.trim();
        String like = "%" + kw + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT m.id, m.code, m.name, m.spec, m.unit, COALESCE(m.safety_qty, 0) safety_qty,
                       COALESCE(b.qty, 0) stock_qty
                FROM md_material m
                LEFT JOIN inv_balance b ON b.material_id = m.id AND b.warehouse_id = ?
                WHERE m.deleted = 0 AND m.status = 1
                  AND (? = '' OR m.code LIKE ? OR m.name LIKE ? OR m.spec LIKE ?)
                ORDER BY m.code
                LIMIT 50
                """, (ResultSet rs) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("spec", rs.getString("spec"));
            m.put("unit", rs.getString("unit"));
            m.put("stockQty", rs.getBigDecimal("stock_qty"));
            m.put("safetyQty", rs.getBigDecimal("safety_qty"));
            out.add(m);
        }, link.getWarehouseId(), kw, like, like, like);
        return out;
    }

    /** 单个物料的本仓实时库存（仅数量）。物料须存在且启用，否则一律返回 0，防止枚举物料 ID 探测。 */
    public BigDecimal stock(String token, Long materialId) {
        LrLink link = requireLink(token);
        if (materialId == null) return BigDecimal.ZERO;
        BigDecimal q = jdbc.query("""
                SELECT COALESCE(b.qty, 0) q
                FROM md_material m
                LEFT JOIN inv_balance b ON b.material_id = m.id AND b.warehouse_id = ?
                WHERE m.id = ? AND m.deleted = 0 AND m.status = 1
                """,
                rs -> rs.next() ? rs.getBigDecimal("q") : BigDecimal.ZERO,
                link.getWarehouseId(), materialId);
        return q == null ? BigDecimal.ZERO : q;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(SubmitDTO dto) {
        LrLink link = requireLink(dto.token);
        throttle(dto.token);
        if (dto.lines == null || dto.lines.isEmpty()) throw new BizException("请至少填写一行物料");
        assertLineCount(dto.lines.size());

        // 必填项与「新增出库申请」一致：领料人 + 工程项目 + 出库类型 + 业务分类
        if (dto.pickerId == null) throw new BizException("请选择领料人");
        LrPicker p = pickerMapper.selectById(dto.pickerId);
        if (p == null || !link.getOrgId().equals(p.getOrgId())) throw new BizException("领料人无效");
        if (dto.projectId == null) throw new BizException("请选择工程项目");
        String projectName = jdbc.query(
                "SELECT name FROM md_project WHERE id = ? AND org_id = ? AND deleted = 0 AND status = 1",
                rs -> rs.next() ? rs.getString(1) : null, dto.projectId, link.getOrgId());
        if (projectName == null) throw new BizException("工程项目无效或不属于本公司");
        // 出库类型不在领料申请环节采集（申请只是计划），改由仓管在「材料出库」环节确定
        if (dto.bizType == null || dto.bizType.isBlank()) throw new BizException("请选择业务分类");
        if (!options("business.category.options", "工程类,运营类,增值销售类,其他").contains(dto.bizType.trim()))
            throw new BizException("业务分类无效");

        LrRequest req = new LrRequest();
        req.setDocNo(nextDocNo());
        req.setOrgId(link.getOrgId());
        req.setLinkId(link.getId());
        req.setWarehouseId(link.getWarehouseId());
        req.setApplicantName(trim(dto.applicantName != null && !dto.applicantName.isBlank() ? dto.applicantName : p.getName(), 64));
        req.setPickerId(dto.pickerId);
        req.setPickerName(p.getName());
        req.setProjectId(dto.projectId);
        req.setProjectName(projectName);
        req.setSite(trim(dto.site, 160));
        req.setBizType(trim(dto.bizType, 64));
        req.setOutType(trim(dto.outType, 64));
        req.setApplyDate(LocalDate.now());
        req.setStatus("SUBMITTED");
        req.setRemark(trim(dto.remark, 255));
        insertRequestWithRetry(req);

        int i = 1;
        for (SubmitLine ln : dto.lines) {
            if (ln.materialId == null || ln.qty == null || ln.qty.signum() <= 0) continue;
            Integer ok = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM md_material WHERE id = ? AND deleted = 0 AND status = 1",
                    Integer.class, ln.materialId);
            if (ok == null || ok == 0) throw new BizException("物料不存在或已停用");
            LrRequestLine line = new LrRequestLine();
            line.setRequestId(req.getId());
            line.setLineNo(i++);
            line.setMaterialId(ln.materialId);
            line.setQty(ln.qty);
            line.setRemark(trim(ln.remark, 255));
            lineMapper.insert(line);
        }
        if (i == 1) throw new BizException("请至少填写一行有效物料数量");
        // 推「待处理事项」给本公司采购(第一关)
        notifService.pushToRole("purchaser", link.getOrgId(),
                "领料申请待采购确认：" + req.getDocNo(), "LR", req.getDocNo(), req.getId());
        return Map.of("docNo", req.getDocNo(), "id", req.getId(), "bizType", "LR");
    }

    /**
     * 免登录附件上传：一线提交后把照片挂到刚建的领料申请/采购计划上。
     * 凭 token 校验单据确属该链接提交(link_id 匹配)，再交 AttachmentService 存盘。
     */
    public void uploadAttachment(String token, String bizType, Long bizId, org.springframework.web.multipart.MultipartFile file) {
        LrLink link = requireLink(token);
        // 附件上传原来没走限流(提交接口走了,这里漏了)。单个 20MB × 不限次数 = 存储被刷爆。
        // 2026-08-12 审计补:与提交接口共用同一个每分钟计数。
        throttle(token);
        String table = "PP".equals(bizType) ? "pp_request" : "LR".equals(bizType) ? "lr_request" : null;
        if (table == null) throw new BizException("附件单据类型无效");
        Long linkId = jdbc.query(
                "SELECT link_id FROM " + table + " WHERE id = ? AND org_id = ? AND deleted = 0",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, bizId, link.getOrgId());
        if (linkId == null || !link.getId().equals(linkId)) throw new BizException("单据不存在或不属于本提交链接");
        attachmentService.uploadPublic(bizType, bizId, link.getOrgId(), file);
    }

    private void insertRequestWithRetry(LrRequest req) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                requestMapper.insert(req);
                return;
            } catch (DuplicateKeyException e) {
                req.setId(null);
                req.setDocNo(nextDocNo());
            }
        }
        throw new BizException("提交繁忙，请重试");
    }

    private String nextDocNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lr_request WHERE doc_no LIKE ?", Integer.class, "LR-" + day + "-%");
        return String.format("LR-%s-%04d", day, (cnt == null ? 0 : cnt) + 1);
    }

    /**
     * 免登录入口单次提交的明细行数上限(2026-08-12 审计补)。
     *
     * 原来只判空、不封顶:拿到链接的人可以一次提交几万行,单条 SQL 循环插入,
     * 数据库和收件箱都会被撑住。throttle() 只挡"提交次数",挡不住"单次体量"。
     * 200 行是按实际业务定的 —— 一张材料计划/领料单几十行顶天了,正常填写碰不到。
     */
    private static final int MAX_LINES = 200;

    private void assertLineCount(int n) {
        if (n > MAX_LINES) {
            throw new BizException("单次最多提交 " + MAX_LINES + " 行物料，当前 " + n + " 行。请拆成多张单据提交。");
        }
    }

    private void throttle(String token) {
        long now = System.currentTimeMillis();
        long[] w = rate.compute(token, (k, v) -> {
            if (v == null || now - v[0] > 60_000) return new long[]{now, 1};
            v[1]++;
            return v;
        });
        if (w[1] > 30) throw new BizException("提交过于频繁，请稍后再试");
    }

    private String trim(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String queryName(String sql, Long id) {
        if (id == null) return null;
        try {
            return jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null, id);
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 采购计划提交 ============
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ppSubmit(PpSubmitDTO dto) {
        LrLink link = requireLink(dto.token);
        throttle(dto.token);
        if (dto.lines == null || dto.lines.isEmpty()) throw new BizException("请至少填写一行物料");
        assertLineCount(dto.lines.size());
        PpRequest req = new PpRequest();
        req.setDocNo(nextPpDocNo());
        req.setOrgId(link.getOrgId());
        req.setLinkId(link.getId());
        req.setWarehouseId(link.getWarehouseId());
        req.setApplicantName(trim(dto.applicantName, 64));
        req.setDept(trim(dto.dept, 80));
        req.setStatus("SUBMITTED");
        req.setRemark(trim(dto.remark, 255));
        insertPpWithRetry(req);
        int i = 1;
        for (PpSubmitLine ln : dto.lines) {
            if (ln.materialId == null || ln.demandQty == null || ln.demandQty.signum() <= 0) continue;
            Integer ok = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM md_material WHERE id = ? AND deleted = 0 AND status = 1",
                    Integer.class, ln.materialId);
            if (ok == null || ok == 0) throw new BizException("物料不存在或已停用");
            PpRequestLine line = new PpRequestLine();
            line.setRequestId(req.getId());
            line.setLineNo(i++);
            line.setMaterialId(ln.materialId);
            line.setDemandQty(ln.demandQty);
            line.setRemark(trim(ln.remark, 255));
            ppLineMapper.insert(line);
        }
        if (i == 1) throw new BizException("请至少填写一行有效需求数量");
        notifService.pushToRole("purchaser", link.getOrgId(),
                "采购计划待确认：" + req.getDocNo(), "PP", req.getDocNo(), req.getId());
        return Map.of("docNo", req.getDocNo(), "id", req.getId(), "bizType", "PP");
    }

    private void insertPpWithRetry(PpRequest req) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                ppMapper.insert(req);
                return;
            } catch (DuplicateKeyException e) {
                req.setId(null);
                req.setDocNo(nextPpDocNo());
            }
        }
        throw new BizException("提交繁忙，请重试");
    }

    private String nextPpDocNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pp_request WHERE doc_no LIKE ?", Integer.class, "PP-" + day + "-%");
        return String.format("PP-%s-%04d", day, (cnt == null ? 0 : cnt) + 1);
    }

    public static class PpSubmitDTO {
        public String token;
        public String applicantName;
        public String dept;
        public String remark;
        public List<PpSubmitLine> lines;
    }

    public static class PpSubmitLine {
        public Long materialId;
        public BigDecimal demandQty;
        public String remark;
    }

    public static class SubmitDTO {
        public String token;
        public String applicantName;
        public String pickerName;
        public Long projectId;
        public String projectName;
        public String site;
        public String bizType;          // 业务分类
        public String outType;          // 出库类型
        public String remark;
        public Long pickerId;
        public List<SubmitLine> lines;
    }

    public static class SubmitLine {
        public Long materialId;
        public BigDecimal qty;
        public String remark;
    }
}
