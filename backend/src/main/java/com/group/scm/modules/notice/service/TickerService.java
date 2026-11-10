package com.group.scm.modules.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.notice.entity.SysTicker;
import com.group.scm.modules.notice.mapper.SysTickerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顶栏滚动通知(2026-10-21 用户敲定)。
 *
 * <p>口径:
 * <ul>
 *   <li>发布/修改/停用/删除:只有系统管理员(定:「由整个 ERP 系统系统管理员发布才行」)。
 *       账套管理员也不行——这里不用公告那套可分配的权限码,一律 requireAdmin,后端强制。</li>
 *   <li>谁能看到:发给本公司(或「全部公司」)、已启用、今天在起止日期内(含两头)。过了结束日期自动不显示,不用人删。</li>
 *   <li>重要的排最前,同级按开始日期新的在前;最多取 10 条,顶栏放不下更多。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TickerService {

    private static final Set<String> LEVELS = Set.of("NORMAL", "IMPORTANT");
    private static final int LIVE_LIMIT = 10;

    private final SysTickerMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;

    /** 顶栏用:当前登录人现在该看到的通知。系统管理员看全部在显示期内的(方便核对效果)。 */
    public List<Map<String, Object>> live() {
        LocalDate today = LocalDate.now();
        if (SecurityUtil.isAdmin()) {
            return jdbc.queryForList(LIVE_SQL.formatted(""), today, today, LIVE_LIMIT);
        }
        Long company = orgScope.currentAccountSetOrgId();
        // 账号没挂到任何公司 → 一条也不给(宁可少给,不可串公司)
        if (company == null) return List.of();
        return jdbc.queryForList(LIVE_SQL.formatted(
                "AND (t.all_orgs = 1 OR EXISTS (SELECT 1 FROM sys_ticker_org o WHERE o.ticker_id = t.id AND o.org_id = ?))"),
                today, today, company, LIVE_LIMIT);
    }

    private static final String LIVE_SQL = """
            SELECT t.id, t.title, t.content, t.level, t.start_date AS startDate, t.end_date AS endDate
            FROM sys_ticker t
            WHERE t.deleted = 0 AND t.status = 1 AND t.start_date <= ? AND t.end_date >= ?
              %s
            ORDER BY (t.level = 'IMPORTANT') DESC, t.start_date DESC, t.id DESC
            LIMIT ?
            """;

    public List<SysTicker> adminList() {
        SecurityUtil.requireAdmin();
        List<SysTicker> rows = mapper.selectList(new QueryWrapper<SysTicker>()
                .orderByDesc("start_date").orderByDesc("id").last("LIMIT 500"));
        for (SysTicker t : rows) {
            if (Integer.valueOf(1).equals(t.getAllOrgs())) {
                t.setOrgNames("全部公司");
            } else {
                t.setOrgIds(jdbc.queryForList("SELECT org_id FROM sys_ticker_org WHERE ticker_id = ? ORDER BY org_id", Long.class, t.getId()));
                t.setOrgNames(String.join("、", jdbc.queryForList(
                        "SELECT o.name FROM sys_ticker_org x JOIN sys_org o ON o.id = x.org_id WHERE x.ticker_id = ? ORDER BY o.sort, o.id",
                        String.class, t.getId())));
            }
            if (t.getCreatedBy() != null) {
                t.setCreatedByName(jdbc.queryForList("SELECT name FROM sys_user WHERE id = ?", String.class, t.getCreatedBy())
                        .stream().findFirst().orElse(null));
            }
        }
        return rows;
    }

    /** 发布页的公司勾选项:在用的公司 */
    public List<Map<String, Object>> companies() {
        SecurityUtil.requireAdmin();
        return jdbc.queryForList("SELECT id, name FROM sys_org WHERE deleted = 0 AND status = 1 AND type = 'COMPANY' ORDER BY sort, id");
    }

    @Transactional(rollbackFor = Exception.class)
    public SysTicker create(SysTicker dto) {
        SecurityUtil.requireAdmin();
        SysTicker t = new SysTicker();
        apply(t, dto);
        t.setStatus(1);
        mapper.insert(t);
        saveOrgs(t.getId(), t.getAllOrgs(), dto.getOrgIds());
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SysTicker dto) {
        SecurityUtil.requireAdmin();
        SysTicker t = require(id);
        apply(t, dto);
        t.setUpdatedBy(SecurityUtil.uid());
        mapper.updateById(t);
        // updateById 跳过 null:正文清空要显式写
        jdbc.update("UPDATE sys_ticker SET content = ? WHERE id = ?", t.getContent(), id);
        saveOrgs(id, t.getAllOrgs(), dto.getOrgIds());
    }

    public void setStatus(Long id, boolean enabled) {
        SecurityUtil.requireAdmin();
        require(id);
        jdbc.update("UPDATE sys_ticker SET status = ?, updated_by = ? WHERE id = ? AND deleted = 0", enabled ? 1 : 0, SecurityUtil.uid(), id);
    }

    public void remove(Long id) {
        SecurityUtil.requireAdmin();
        require(id);
        mapper.deleteById(id);
    }

    private SysTicker require(Long id) {
        SysTicker t = id == null ? null : mapper.selectById(id);
        if (t == null) throw new BizException(4040, "这条通知不存在或已删除");
        return t;
    }

    /** 校验并写入可编辑字段(标题/正文/重要程度/起止日期/范围) */
    private void apply(SysTicker t, SysTicker dto) {
        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        if (title.isEmpty()) throw new BizException(4000, "标题不能为空");
        if (title.length() > 60) throw new BizException(4000, "标题不能超过 60 字（滚动条上一眼看完，建议 30 字以内）");
        String content = dto.getContent() == null || dto.getContent().isBlank() ? null : dto.getContent().trim();
        if (content != null && content.length() > 2000) throw new BizException(4000, "正文不能超过 2000 字");
        String level = dto.getLevel() == null ? "NORMAL" : dto.getLevel();
        if (!LEVELS.contains(level)) throw new BizException(4000, "重要程度只能是普通或重要");
        if (dto.getStartDate() == null || dto.getEndDate() == null) throw new BizException(4000, "请填写显示的开始和结束日期");
        if (dto.getEndDate().isBefore(dto.getStartDate())) throw new BizException(4000, "结束日期不能早于开始日期");
        boolean all = !Integer.valueOf(0).equals(dto.getAllOrgs());
        if (!all && (dto.getOrgIds() == null || dto.getOrgIds().isEmpty())) throw new BizException(4000, "请至少选一家公司，或选「全部公司」");
        t.setTitle(title);
        t.setContent(content);
        t.setLevel(level);
        t.setStartDate(dto.getStartDate());
        t.setEndDate(dto.getEndDate());
        t.setAllOrgs(all ? 1 : 0);
    }

    private void saveOrgs(Long tickerId, Integer allOrgs, List<Long> orgIds) {
        jdbc.update("DELETE FROM sys_ticker_org WHERE ticker_id = ?", tickerId);
        if (Integer.valueOf(1).equals(allOrgs)) return;
        Set<Long> companies = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM sys_org WHERE deleted = 0 AND status = 1 AND type = 'COMPANY'", Long.class));
        for (Long org : new LinkedHashSet<>(orgIds)) {
            if (!companies.contains(org)) throw new BizException(4000, "所选公司不存在或已停用");
            jdbc.update("INSERT INTO sys_ticker_org (ticker_id, org_id) VALUES (?, ?)", tickerId, org);
        }
    }
}
