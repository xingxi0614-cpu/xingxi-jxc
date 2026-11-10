package com.group.scm.modules.outbound.service;

import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 同一工程重复出库申请的提醒(2026-10-16 对标财务软件操作手册:一个工程一张申请,计划变更走修订)。
 *
 * <p><b>只提醒、不拦</b>(定):曾有不少工程同时挂着多张未关闭的申请,直接拦会当天卡住一线;
 * 先提醒一段时间看数字能不能降下来,再定要不要拦。</p>
 * <ul>
 *   <li>只管【工程类】申请。运营类、增值销售类的"大口袋"工程(日常维修、材料销售、零星改造)本来就是多张;</li>
 *   <li>"未关闭" = 待审批 / 已审批 / 部分出库。草稿、已驳回、已出完、已作废不算;</li>
 *   <li>制单页保存时调 {@link #openOnSameProject} 弹提示;月末结账统计本月新建的重复申请,同一口径。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OutReqDuplicates {

    public static final String CATEGORY = "工程类";
    public static final String OPEN_STATUSES = "('PENDING', 'APPROVED', 'PARTIAL_OUT')";

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    /** 同一工程上其他未关闭的工程类申请(按新到旧,最多 10 张)。工程须对当前账号可见。 */
    public List<Map<String, Object>> openOnSameProject(Long projectId, Long excludeId) {
        if (projectId == null) return List.of();
        orgScope.assertProjectVisible(projectId);
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds == null || orgIds.isEmpty()) return List.of();
        String in = String.join(",", java.util.Collections.nCopies(orgIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        args.add(excludeId == null ? 0L : excludeId);
        args.addAll(orgIds);
        return jdbc.queryForList("""
                SELECT id, doc_no AS docNo, status, apply_date AS applyDate
                FROM biz_out_req
                WHERE deleted = 0 AND project_id = ? AND id <> ?
                  AND business_category = '%s' AND status IN %s AND org_id IN (%s)
                ORDER BY id DESC LIMIT 10
                """.formatted(CATEGORY, OPEN_STATUSES, in), args.toArray());
    }
}
