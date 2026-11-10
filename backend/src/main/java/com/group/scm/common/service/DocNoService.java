package com.group.scm.common.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.modules.system.entity.SysDocRule;
import com.group.scm.modules.system.mapper.SysDocRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单据号生成器：基于 sys_doc_rule 配置 + 公司/日期维度流水。
 *
 * <p>规则示例：</p>
 * <pre>
 *   {company}-{prefix}-{YYYYMMDD}-{0000} → ABC-CD-20260607-0001
 * </pre>
 *
 * <p>并发安全：sys_doc_sequence 按 doc_type + scope_key + serial_date 锁行。</p>
 */
@Service
@RequiredArgsConstructor
public class DocNoService {

    private final SysDocRuleMapper ruleMapper;
    private final JdbcTemplate jdbcTemplate;

    /** REQUIRES_NEW 独立事务，避免与业务事务回滚后号码也回滚（号码可不连续，不可重复）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public synchronized String next(String docType) {
        return generate(docType, SecurityUtil.orgId());
    }

    /** 公开链接等服务端可信来源使用明确组织，不读取或伪造登录态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public synchronized String nextForOrg(String docType, Long orgId) {
        Integer enabled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1", Integer.class, orgId);
        if (enabled == null || enabled != 1) throw new BizException("单据所属组织不存在或已停用");
        return generate(docType, orgId);
    }

    private String generate(String docType, Long orgId) {
        SysDocRule rule = ruleMapper.selectById(docType);
        if (rule == null) throw new BizException("单据编码规则未配置：" + docType);

        LocalDate today = LocalDate.now();
        String company = companyPrefixFor(orgId);
        long currentNo = nextSequence(docType, company, today);
        rule.setCurrentNo(currentNo);
        rule.setLastReset(today);
        ruleMapper.updateById(rule);

        return formatNo(rule.getPattern(), rule.getPrefix(), company, currentNo, today);
    }

    private long nextSequence(String docType, String scopeKey, LocalDate today) {
        // 唯一键的原子 upsert 同时覆盖首次并发建序列和后续取号，支持多后端实例。
        jdbcTemplate.update("""
                INSERT INTO sys_doc_sequence (doc_type, scope_key, serial_date, current_no)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE current_no = current_no + 1
                """, docType, scopeKey, Date.valueOf(today));
        return jdbcTemplate.queryForObject("""
                SELECT current_no
                FROM sys_doc_sequence
                WHERE doc_type = ? AND scope_key = ? AND serial_date = ?
                FOR UPDATE
                """, Long.class, docType, scopeKey, Date.valueOf(today));
    }

    /**
     * 某个组织归属的公司代号（沿组织树往上找 type=COMPANY 的那一层，如 ABC / XYZ）。
     *
     * <p>单据号一直用它当前缀（ABC-CT-20260826-0001），2026-09-04 起工程项目编码的
     * 建议前缀也取这里 —— 两处必须是同一套代号，不许各写各的。</p>
     */
    public String companyPrefixFor(Long currentOrgId) {
        if (currentOrgId == null) return "ORG";
        Map<Long, OrgRef> orgs = jdbcTemplate.query("""
                        SELECT id, parent_id, code, type
                        FROM sys_org
                        WHERE deleted = 0 AND status = 1
                        """,
                rs -> {
                    Map<Long, OrgRef> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getLong("id"), new OrgRef(
                                rs.getLong("id"),
                                rs.getLong("parent_id"),
                                rs.getString("code"),
                                rs.getString("type")
                        ));
                    }
                    return map;
                });
        OrgRef current = orgs.get(currentOrgId);
        if (current == null) return normalizeCompanyCode("ORG" + currentOrgId);

        OrgRef cursor = current;
        OrgRef company = "COMPANY".equalsIgnoreCase(current.type()) ? current : null;
        while (cursor.parentId() != null && cursor.parentId() != 0 && orgs.containsKey(cursor.parentId())) {
            cursor = orgs.get(cursor.parentId());
            if ("COMPANY".equalsIgnoreCase(cursor.type())) {
                company = cursor;
            }
        }
        return normalizeCompanyCode((company == null ? current : company).code());
    }

    private String normalizeCompanyCode(String code) {
        String normalized = String.valueOf(code == null ? "" : code)
                .replaceFirst("(?i)^COMP[-_]*", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase();
        if (normalized.isBlank()) return "ORG";
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private String formatNo(String pattern, String prefix, String company, long currentNo, LocalDate date) {
        String s = pattern.replace("{prefix}", prefix);
        s = s.replace("{company}", company);
        s = s.replace("{YYYY}", date.format(DateTimeFormatter.ofPattern("yyyy")));
        s = s.replace("{YYYYMM}", date.format(DateTimeFormatter.ofPattern("yyyyMM")));
        s = s.replace("{YYYYMMDD}", date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        // 占位流水：{0000} → 4 位、{00000} → 5 位
        int idx = s.indexOf("{0");
        if (idx >= 0) {
            int end = s.indexOf("}", idx);
            int width = end - idx - 1;
            String num = String.format("%0" + width + "d", currentNo);
            s = s.substring(0, idx) + num + s.substring(end + 1);
        }
        return s;
    }

    private record OrgRef(Long id, Long parentId, String code, String type) {}
}
