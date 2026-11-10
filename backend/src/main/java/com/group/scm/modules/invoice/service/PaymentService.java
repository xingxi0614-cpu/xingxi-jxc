package com.group.scm.modules.invoice.service;

import com.group.scm.common.ResultCap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.invoice.entity.BizPayment;
import com.group.scm.modules.invoice.mapper.BizPaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 付款登记（应付-物资款核销台账）。
 * 只登记不做资金；金额为含税口径，供财务核对台账算「已付款(期间)/应付余额(累计)」。
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final BizPaymentMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;
    private final PeriodCloseService periodCloseService;

    public List<BizPayment> list(Long orgId, Long supplierId, LocalDate startDate, LocalDate endDate) {
        QueryWrapper<BizPayment> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (orgId != null) { orgScope.assertOrgVisible(orgId); qw.eq("org_id", orgId); }
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        if (startDate != null) qw.ge("pay_date", startDate);
        if (endDate != null) qw.le("pay_date", endDate);
        qw.orderByDesc("pay_date").orderByDesc("id");
        qw.last("LIMIT 500");
        List<BizPayment> rows = mapper.selectList(qw);
        enrich(rows);
        return ResultCap.mark(rows, 500);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public BizPayment create(BizPayment dto) {
        Long orgId = dto.getOrgId() != null ? dto.getOrgId() : SecurityUtil.orgId();
        if (orgId == null) throw new BizException("请选择付款公司");
        orgScope.assertOrgVisible(orgId);
        if (dto.getSupplierId() == null) throw new BizException("请选择供应商");
        orgScope.assertSupplierVisible(dto.getSupplierId());  // 供应商集团池：存在且启用即可选用
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) throw new BizException("付款金额须大于 0");
        com.group.scm.common.DecimalGuard.money(dto.getAmount(), "付款金额");
        BizPayment e = new BizPayment();
        e.setOrgId(orgId);
        e.setSupplierId(dto.getSupplierId());
        e.setPayDate(dto.getPayDate() != null ? dto.getPayDate() : LocalDate.now());
        // 期间封锁：已结账月份不允许再登记付款，否则已结账期间的「已付款/应付余额」台账会事后改变
        // 带锁检查:与月末结账互斥,结账提交的那一瞬间不会有付款漏进已结账月份(计算审查 U06)
        periodCloseService.assertPeriodOpenLocked(e.getPayDate(), orgId, "付款登记", null);
        e.setAmount(dto.getAmount());
        e.setMethod(dto.getMethod());
        e.setRefNo(dto.getRefNo());
        e.setRemark(dto.getRemark());
        mapper.insert(e);
        enrich(List.of(e));
        return e;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizPayment e = mapper.selectById(id);
        if (e == null) throw new BizException("付款记录不存在");
        orgScope.assertOrgVisible(e.getOrgId());
        // 期间封锁：已结账月份的付款记录不允许删除
        periodCloseService.assertPeriodOpenLocked(e.getPayDate(), e.getOrgId(), "付款登记", null);
        mapper.deleteById(id); // 逻辑删除，@OpLog 留痕
    }

    private void enrich(List<BizPayment> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<Long, String> supMap = nameMap("md_supplier", rows.stream().map(BizPayment::getSupplierId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> orgMap = nameMap("sys_org", rows.stream().map(BizPayment::getOrgId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> userMap = nameMap("sys_user", rows.stream().map(BizPayment::getCreatedBy).filter(Objects::nonNull).distinct().toList());
        rows.forEach(r -> {
            r.setSupplierName(supMap.get(r.getSupplierId()));
            r.setOrgName(orgMap.get(r.getOrgId()));
            r.setCreatedByName(userMap.get(r.getCreatedBy()));
        });
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
