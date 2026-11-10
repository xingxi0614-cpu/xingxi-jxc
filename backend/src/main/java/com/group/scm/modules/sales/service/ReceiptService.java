package com.group.scm.modules.sales.service;

import com.group.scm.common.ResultCap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.sales.entity.BizReceipt;
import com.group.scm.modules.sales.mapper.BizReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 收款登记（销售应收核销台账）。只登记不做资金；金额为含税口径(对应应收)。
 * 应收余额 = 累计已审批销售发票价税合计 − 累计收款。
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final BizReceiptMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;
    private final PeriodCloseService periodCloseService;

    /**
     * 收款合计:与 {@link #list} 同一组筛选条件,但对全部数据求和,不受列表"最多 500 笔"的限制。
     * 🔴 2026-10-18(计算审查 U15):收款页「全部/期间收款」卡片以前直接把列表加起来,超过 500 笔就静默少计。
     */
    public java.util.Map<String, Object> summary(Long orgId, Long customerId, LocalDate startDate, LocalDate endDate) {
        QueryWrapper<BizReceipt> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (orgId != null) { orgScope.assertOrgVisible(orgId); qw.eq("org_id", orgId); }
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("receipt_date", startDate);
        if (endDate != null) qw.le("receipt_date", endDate);
        qw.select("COUNT(*) AS cnt", "COALESCE(SUM(amount), 0) AS amt");
        java.util.Map<String, Object> row = mapper.selectMaps(qw).stream().findFirst().orElse(java.util.Map.of());
        return java.util.Map.of(
                "count", ((Number) row.getOrDefault("cnt", 0)).longValue(),
                "amount", row.getOrDefault("amt", BigDecimal.ZERO));
    }

    public List<BizReceipt> list(Long orgId, Long customerId, LocalDate startDate, LocalDate endDate) {
        QueryWrapper<BizReceipt> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (orgId != null) { orgScope.assertOrgVisible(orgId); qw.eq("org_id", orgId); }
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("receipt_date", startDate);
        if (endDate != null) qw.le("receipt_date", endDate);
        qw.orderByDesc("receipt_date").orderByDesc("id");
        qw.last("LIMIT 500");
        List<BizReceipt> rows = mapper.selectList(qw);
        enrich(rows);
        return ResultCap.mark(rows, 500);
    }

    @Transactional(rollbackFor = Exception.class)
    public BizReceipt create(BizReceipt dto) {
        Long orgId = dto.getOrgId() != null ? dto.getOrgId() : SecurityUtil.orgId();
        if (orgId == null) throw new BizException("请选择收款公司");
        orgScope.assertOrgVisible(orgId);
        boolean hasCustomer = dto.getCustomerId() != null
                || (dto.getCustomerName() != null && !dto.getCustomerName().isBlank());
        if (!hasCustomer) throw new BizException("请选择客户单位或填写客户名称");
        // 客户单位(复用往来单位 md_supplier)须属当前账套可见范围,防挂到别账套客户
        if (dto.getCustomerId() != null) orgScope.assertCustomerVisible(dto.getCustomerId());
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BizException("收款金额须大于 0");
        com.group.scm.common.DecimalGuard.money(dto.getAmount(), "收款金额");
        BizReceipt e = new BizReceipt();
        e.setOrgId(orgId);
        e.setCustomerId(dto.getCustomerId());
        e.setCustomerName(dto.getCustomerName());
        e.setReceiptDate(dto.getReceiptDate() != null ? dto.getReceiptDate() : LocalDate.now());
        // 期间封锁：已结账月份不允许再登记收款，否则已结账期间的应收台账会事后改变（与付款登记 PaymentService 同口径）
        // 带锁检查:与月末结账互斥(计算审查 U06,与付款登记同一写法)
        periodCloseService.assertPeriodOpenLocked(e.getReceiptDate(), orgId, "收款登记", null);
        e.setAmount(dto.getAmount());
        e.setMethod(dto.getMethod());
        e.setRefNo(dto.getRefNo());
        e.setRemark(dto.getRemark());
        mapper.insert(e);
        enrich(List.of(e));
        return e;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizReceipt e = mapper.selectById(id);
        if (e == null) throw new BizException("收款记录不存在");
        orgScope.assertOrgVisible(e.getOrgId());
        // 期间封锁：已结账月份的收款记录不允许删除
        periodCloseService.assertPeriodOpenLocked(e.getReceiptDate(), e.getOrgId(), "收款登记", null);
        mapper.deleteById(id);
    }

    /** 应收余额（按客户单位）：累计已审批发票价税合计 − 累计收款。仅统计有客户单位(customer_id)的往来。 */
    public List<Map<String, Object>> arBalance(Long orgId) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgId != null) {
            orgScope.assertOrgVisible(orgId);
            orgIds = List.of(orgId);
        }
        if (orgIds.isEmpty()) return List.of();
        String ph = orgIds.stream().map(x -> "?").collect(Collectors.joining(","));

        /*
         * 🔴 2026-10-18 两处口径(定):
         *  · Q01 按「公司 + 客户」分别算余额:同一客户在 A 公司欠 100、在 B 公司预收 100,
         *    以前"全部公司"视角按客户合成 0,欠款、预收两张卡都显示 0。两家是两个法人,不能互相抵。
         *  · U25 退货只冲「已开票部分」:来源出库开过已审批发票的退货才从应收里减。
         *    以前不管开没开票都减,货发出去还没开票就退回,客户余额变负数、被当成"多收/预收"。
         */
        Map<String, BigDecimal> inv = new HashMap<>(), ret = new HashMap<>(), rec = new HashMap<>();
        Map<String, long[]> keys = new LinkedHashMap<>();
        java.util.function.BiConsumer<Map<String, BigDecimal>, ResultSet> put = (target, rs) -> {
            try {
                long org = rs.getLong("org_id"), cid = rs.getLong("customer_id");
                String k = org + ":" + cid;
                keys.putIfAbsent(k, new long[]{org, cid});
                target.put(k, rs.getBigDecimal("amt"));
            } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        };
        jdbc.query("SELECT org_id, customer_id, SUM(total_amt) amt FROM biz_sale_invoice " +
                        "WHERE deleted = 0 AND status = 'APPROVED' AND customer_id IS NOT NULL AND org_id IN (" + ph + ") " +
                        "GROUP BY org_id, customer_id",
                (ResultSet rs) -> { put.accept(inv, rs); }, orgIds.toArray());
        jdbc.query("SELECT r.org_id, r.customer_id, SUM(r.refund_amt) amt FROM biz_sale_return r " +
                        "WHERE r.deleted = 0 AND r.status = 'APPROVED' AND r.customer_id IS NOT NULL AND r.org_id IN (" + ph + ") " +
                        "AND EXISTS (SELECT 1 FROM biz_sale_invoice v WHERE v.source_out_id = r.source_out_id " +
                        "            AND v.deleted = 0 AND v.status = 'APPROVED') " +
                        "GROUP BY r.org_id, r.customer_id",
                (ResultSet rs) -> { put.accept(ret, rs); }, orgIds.toArray());
        jdbc.query("SELECT org_id, customer_id, SUM(amount) amt FROM biz_receipt " +
                        "WHERE deleted = 0 AND customer_id IS NOT NULL AND org_id IN (" + ph + ") " +
                        "GROUP BY org_id, customer_id",
                (ResultSet rs) -> { put.accept(rec, rs); }, orgIds.toArray());

        if (keys.isEmpty()) return List.of();
        Map<Long, String> nameMap = nameMap("md_supplier", keys.values().stream().map(k -> k[1]).distinct().toList());
        Map<Long, String> orgNames = nameMap("sys_org", keys.values().stream().map(k -> k[0]).distinct().toList());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : keys.entrySet()) {
            BigDecimal invAmt = inv.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal retAmt = ret.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal recAmt = rec.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal balance = invAmt.subtract(retAmt).subtract(recAmt);
            long cid = e.getValue()[1], org = e.getValue()[0];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orgId", org);
            row.put("orgName", orgNames.getOrDefault(org, ""));
            row.put("customerId", cid);
            row.put("customerName", nameMap.getOrDefault(cid, "客户#" + cid));
            row.put("invoiceAmt", invAmt);
            row.put("returnAmt", retAmt);
            row.put("receiptAmt", recAmt);
            row.put("balance", balance);
            out.add(row);
        }
        out.sort((a, b) -> ((BigDecimal) b.get("balance")).compareTo((BigDecimal) a.get("balance")));
        return out;
    }

    private void enrich(List<BizReceipt> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<Long, String> cusMap = nameMap("md_supplier", rows.stream().map(BizReceipt::getCustomerId).filter(Objects::nonNull).distinct().toList());
        rows.forEach(r -> r.setCustomerUnit(cusMap.get(r.getCustomerId())));
    }

    private Map<Long, String> nameMap(String table, List<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        String ph = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT id, name FROM " + table + " WHERE id IN (" + ph + ")",
                (ResultSet rs) -> { map.put(rs.getLong("id"), rs.getString("name")); }, ids.toArray());
        return map;
    }
}
