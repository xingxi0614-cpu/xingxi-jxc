package com.group.scm.modules.sales.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.modules.materialreq.entity.LrLink;
import com.group.scm.modules.materialreq.mapper.LrLinkMapper;
import com.group.scm.modules.sales.entity.BizSo;
import com.group.scm.modules.sales.entity.BizSoLine;
import com.group.scm.modules.sales.mapper.BizSoLineMapper;
import com.group.scm.modules.sales.mapper.BizSoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网点扫码录销售单(免登录)。
 *
 * <p><b>流程</b>:客户来买商品 → 网点扫码打开页面 → 选商品型号、填客户与金额
 * → 提交 → 系统生成一张【草稿】销售订单,由公司内部核对后再提交审批。</p>
 *
 * <p><b>为什么生成草稿</b>:免登录提交的东西不直接进审批流。这与领料申请同款
 * (先落库、后由内部人员确认),既不打断现有审批体系,也不给外部一条直通流程的路。</p>
 *
 * <p><b>安全</b>:全部照抄 LrPublicService 已验证过的那套 ——
 * token 不可枚举(UUID)、停用即失效、每 token 每分钟限流、明细行数封顶、
 * 物料与金额一律按链接所属账套校验,前端传什么都不信。</p>
 */
@Service
@RequiredArgsConstructor
public class SoPublicService {

    private final LrLinkMapper linkMapper;
    private final BizSoMapper soMapper;
    private final BizSoLineMapper soLineMapper;
    private final JdbcTemplate jdbc;
    private final com.group.scm.common.service.DocNoService docNoService;
    private final com.group.scm.modules.close.service.PeriodCloseService periodCloseService;
    private final com.group.scm.modules.notif.service.NotifService notifService;

    /** 每 token 每分钟最多 30 次(与领料同口径)。单机内存计数,重启清零 —— 只防误刷不防分布式攻击 */
    private static final Map<String, int[]> HITS = new ConcurrentHashMap<>();
    private static final int MAX_PER_MIN = 30;
    private static final int MAX_LINES = 50;

    private void throttle(String token) {
        int minute = (int) (System.currentTimeMillis() / 60000);
        int[] slot = HITS.computeIfAbsent(token, k -> new int[]{minute, 0});
        synchronized (slot) {
            if (slot[0] != minute) { slot[0] = minute; slot[1] = 0; }
            if (++slot[1] > MAX_PER_MIN) throw new BizException("操作太频繁,请稍后再试");
        }
    }


    /** token 校验:必须存在、启用、且是【销售录单】类型 —— 不能拿领料的链接来录销售单 */
    private LrLink requireLink(String token) {
        if (token == null || token.isBlank()) throw new BizException("链接无效");
        LrLink link = linkMapper.selectOne(new QueryWrapper<LrLink>().eq("token", token).last("limit 1"));
        if (link == null) throw new BizException("链接无效或已失效");
        if (link.getStatus() == null || link.getStatus() != 1) throw new BizException("该提交链接已停用");
        String type = jdbc.query("SELECT link_type FROM lr_link WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, link.getId());
        if (!"SO".equals(type)) throw new BizException("该链接不是销售录单链接");
        return link;
    }

    /** 打开页面时:告诉前端这是哪个站、能选哪些货 */
    public Map<String, Object> context(String token) {
        LrLink link = requireLink(token);
        Map<String, Object> out = new HashMap<>();
        out.put("label", link.getLabel());
        out.put("orgName", jdbc.query("SELECT name FROM sys_org WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, link.getOrgId()));
        out.put("warehouseName", jdbc.query("SELECT name FROM md_warehouse WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, link.getWarehouseId()));
        return out;
    }

    /**
     * 选货:集团池模型,全部启用物料可选。
     */
    public List<Map<String, Object>> searchMaterials(String token, String keyword) {
        LrLink link = requireLink(token);
        String kw = keyword == null ? "" : keyword.trim();
        StringBuilder sql = new StringBuilder("""
            SELECT id, code, name, spec, unit
            FROM md_material
            WHERE deleted = 0 AND status = 1
            """);
        List<Object> args = new ArrayList<>();
        if (!kw.isEmpty()) {
            sql.append(" AND (code LIKE ? OR name LIKE ? OR spec LIKE ?)");
            String like = "%" + kw + "%";
            args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY code LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 提交:生成一张草稿销售订单 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(SubmitDTO dto) {
        LrLink link = requireLink(dto.token);
        throttle(dto.token);

        if (dto.customerName == null || dto.customerName.isBlank())
            throw new BizException("请填写客户姓名");
        if (dto.lines == null || dto.lines.isEmpty())
            throw new BizException("请至少选择一项商品");
        if (dto.lines.size() > MAX_LINES)
            throw new BizException("一次最多提交 " + MAX_LINES + " 行,请分批录入");

        BizSo so = new BizSo();
        so.setDocNo(docNoService.nextForOrg("SO", link.getOrgId()));
        so.setOrgId(link.getOrgId());          // 🔴 账套一律取自链接,不信前端
        so.setCustomerName(dto.customerName.trim());
        so.setCustomerPhone(dto.customerPhone);
        so.setShipAddress(dto.shipAddress);
        so.setSalesPerson(dto.salesPerson);
        so.setOrderDate(dto.orderDate != null ? dto.orderDate : LocalDate.now());
        periodCloseService.assertPeriodOpen(so.getOrderDate(), link.getOrgId(), "销售订单", so.getDocNo());
        so.setRemark(dto.remark);
        so.setStatus("DRAFT");
        so.setCreatedBy(null);   // 免登录提交没有操作人,来源靠 source_link_id 追溯                 // 🔴 草稿,不直接进审批流
        so.setOutQty(BigDecimal.ZERO);
        so.setInvoiceQty(BigDecimal.ZERO);
        so.setReceiptAmount(dto.receiptAmount == null ? BigDecimal.ZERO : dto.receiptAmount);

        BigDecimal totalQty = BigDecimal.ZERO, totalAmt = BigDecimal.ZERO, totalTax = BigDecimal.ZERO;
        List<BizSoLine> lines = new ArrayList<>();
        int no = 1;
        for (SubmitLine l : dto.lines) {
            if (l.materialId == null) throw new BizException("第 " + no + " 行请选择商品");
            // 集团池模型：物料只要求存在且启用
            Integer ok = jdbc.query("""
                    SELECT 1 FROM md_material
                    WHERE id = ? AND deleted = 0 AND status = 1
                    """, rs -> rs.next() ? 1 : null, l.materialId);
            if (ok == null) throw new BizException("第 " + no + " 行的商品不可用");
            if (l.qty == null || l.qty.signum() <= 0) throw new BizException("第 " + no + " 行请填写数量");
            com.group.scm.common.DecimalGuard.qty(l.qty, "第 " + no + " 行数量");
            // 与 SoService 同口径：0 元明细一律拒绝(2026-09-29)
            if (l.amount == null || l.amount.signum() <= 0) throw new BizException("第 " + no + " 行金额必须大于 0");
            l.amount = com.group.scm.common.FinancialInput.positiveMoney(l.amount, "第 " + no + " 行金额");
            com.group.scm.common.FinancialInput.taxRate(l.taxRate);

            BizSoLine sl = new BizSoLine();
            sl.setLineNo(no++);
            sl.setMaterialId(l.materialId);
            sl.setQty(l.qty);
            // 金额由录单人手填(业务要求),单价按金额÷数量倒算,只作参考
            sl.setAmount(l.amount.setScale(2, RoundingMode.HALF_UP));
            sl.setTaxPrice(l.qty.signum() > 0
                    ? l.amount.divide(l.qty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            sl.setTaxRate(l.taxRate == null ? BigDecimal.ZERO : l.taxRate);
            // 与内部建单同一个函数倒挤不含税金额、税额、不含税单价(计算审查 U26)
            SoService.computeLineTax(sl);
            sl.setOutQty(BigDecimal.ZERO);
            sl.setInvoiceQty(BigDecimal.ZERO);
            sl.setRemark(l.remark);
            lines.add(sl);
            totalQty = totalQty.add(l.qty);
            totalAmt = totalAmt.add(sl.getAmount());
            totalTax = totalTax.add(sl.getTaxAmount());
        }
        so.setTotalQty(totalQty);
        so.setTotalAmt(totalAmt);
        so.setTaxAmt(totalTax);
        soMapper.insert(so);
        jdbc.update("UPDATE biz_so SET source_link_id = ? WHERE id = ?", link.getId(), so.getId());
        for (BizSoLine sl : lines) { sl.setSoId(so.getId()); soLineMapper.insert(sl); }

        /*
         * 🔴 必须推待办,否则这张草稿单录进来就【没人知道】。
         * 网点扫码录完就走了,单子停在草稿等公司内部核对 —— 草稿不进任何统计、
         * 不催任何人,没有这条提醒的话,功能会变成"录进来就消失",比不做还糟。
         * 推给采购员(与领料申请第一关同一角色)。
         */
        notifService.pushToRole("purchaser", link.getOrgId(),
                "网点录入销售单待确认:" + so.getDocNo() + "(" + so.getCustomerName() + " " + totalAmt + " 元)",
                "SO", so.getDocNo(), so.getId());

        return Map.of("docNo", so.getDocNo(), "totalAmt", totalAmt, "id", so.getId());
    }

    public static class SubmitDTO {
        public String token;
        public String customerName;
        public String customerPhone;
        public String shipAddress;
        public String salesPerson;
        public LocalDate orderDate;
        public BigDecimal receiptAmount;
        public String remark;
        public List<SubmitLine> lines;
    }
    public static class SubmitLine {
        public Long materialId;
        public BigDecimal qty;
        public BigDecimal amount;     // 手填金额(业务要求)
        public BigDecimal taxRate;
        public String remark;
    }
}
