package com.group.scm.modules.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 更新说明「逐条带看」。
 *
 * <p><b>为什么要有</b>：更新弹窗一长串文字，大多数人直接点「知道了」。真正会改变做法的「规则变化」
 * （如 1.8.0 的"盘点单没做完不能月结"）没人看就等于没通知。所以每条可以带人到页面上看，
 * 标了 must 的必须点开看完才能关弹窗；并记下谁看过，管理员和财务能查谁还没看。</p>
 *
 * <p>条目内容放在 sys_release_note.guide_json（随版本迁移写入），看过的记录在 sys_release_note_read。
 * 「必须看完才能关」只是前端的关窗条件，不挡任何业务操作。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseGuideService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    private final ObjectMapper objectMapper;

    /** 本版本的带看条目 + 当前用户已看过的条目 key。没有带看条目时 items 为空，前端按老样子显示正文。 */
    public Map<String, Object> guide(String versionNo) {
        List<Map<String, Object>> items = items(versionNo);
        List<String> read = items.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT item_key FROM sys_release_note_read WHERE version_no = ? AND user_id = ?",
                String.class, versionNo, SecurityUtil.uid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionNo", versionNo);
        out.put("items", items);
        out.put("readKeys", read);
        return out;
    }

    /** 记一条「看过了」。只认本版本里真有的条目，防止被塞进随意的 key；重复记不报错。 */
    public void markRead(String versionNo, String itemKey) {
        boolean known = items(versionNo).stream().anyMatch(i -> String.valueOf(i.get("key")).equals(itemKey));
        if (!known) throw new BizException("这条更新说明不存在，请刷新页面后再看");
        jdbc.update("INSERT IGNORE INTO sys_release_note_read (version_no, user_id, item_key) VALUES (?, ?, ?)",
                versionNo, SecurityUtil.uid(), itemKey);
    }

    /**
     * 必看条目还没看完的人（只列当前用户可见账套内、启用中的账号）。
     * 给管理员、账套管理员、财务用：规则变化出了问题时，能说清楚通知到了谁、谁没看。
     */
    public Map<String, Object> unread(String versionNo) {
        if (!SecurityUtil.isAdmin() && !SecurityUtil.isAccountAdmin() && !SecurityUtil.hasFinanceAccess()) {
            throw new BizException(4003, "只有管理员、账套管理员或财务可以查看未读名单");
        }
        List<String> must = new ArrayList<>();
        for (Map<String, Object> i : items(versionNo)) if (Boolean.TRUE.equals(i.get("must"))) must.add(String.valueOf(i.get("key")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mustCount", must.size());
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (must.isEmpty() || orgIds.isEmpty()) {
            out.put("users", List.of());
            return out;
        }
        List<Object> args = new ArrayList<>();
        args.add(versionNo);
        args.addAll(must);
        args.addAll(orgIds);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.name, u.username, o.name AS orgName,
                       (SELECT COUNT(*) FROM sys_release_note_read r
                         WHERE r.user_id = u.id AND r.version_no = ? AND r.item_key IN (%s)) AS readCount
                FROM sys_user u LEFT JOIN sys_org o ON o.id = u.org_id
                WHERE u.deleted = 0 AND u.status = 1 AND u.org_id IN (%s)
                ORDER BY o.name, u.name
                """.formatted(ph(must.size()), ph(orgIds.size())), args.toArray());
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (((Number) r.get("readCount")).intValue() < must.size()) users.add(r);
        }
        out.put("users", users);
        out.put("totalUsers", rows.size());
        return out;
    }

    private List<Map<String, Object>> items(String versionNo) {
        if (versionNo == null || versionNo.isBlank()) return List.of();
        List<String> json = jdbc.queryForList(
                "SELECT guide_json FROM sys_release_note WHERE version_no = ? AND deleted = 0 AND status = 1 LIMIT 1",
                String.class, versionNo);
        if (json.isEmpty() || json.get(0) == null || json.get(0).isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json.get(0), new TypeReference<>() {});
            // 去掉没有 key 的、重复 key 的条目，前端靠 key 记已读
            Set<String> seen = new HashSet<>();
            List<Map<String, Object>> clean = new ArrayList<>();
            for (Map<String, Object> i : list) {
                Object k = i.get("key");
                if (k != null && seen.add(String.valueOf(k))) clean.add(i);
            }
            return clean;
        } catch (Exception e) {
            // 条目写坏了就当没有：弹窗退回只显示正文，不能因此让所有人登录后卡住
            log.warn("更新说明 {} 的带看条目 JSON 解析失败：{}", versionNo, e.getMessage());
            return List.of();
        }
    }

    private static String ph(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
