package com.group.scm.modules.profile;

import com.group.scm.common.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MySecurityService {

    private final JdbcTemplate jdbc;

    /**
     * 「账号与安全」面板的数据:最近登录时间/IP + 我最近做过的操作。
     *
     * 【为什么给普通人看这个】让本人能自己发现异常:"这个时间这个地方不是我登的"。
     * 只返回【我自己】的记录(user_id = 登录态),不是管理员日志页,看不到别人的。
     */
    public Map<String, Object> security() {
        Map<String, Object> out = new HashMap<>();
        Long uid = SecurityUtil.uid();
        if (uid == null) return out;
        try {
            List<Map<String, Object>> u = jdbc.queryForList(
                    "SELECT last_login_at, last_login_ip, username, name FROM sys_user WHERE id=?", uid);
            if (!u.isEmpty()) {
                out.put("lastLoginAt", u.get(0).get("last_login_at"));
                out.put("lastLoginIp", u.get(0).get("last_login_ip"));
                out.put("username", u.get(0).get("username"));
                out.put("name", u.get(0).get("name"));
            }
        } catch (Exception ignored) { }
        try {
            // 我最近的操作,给本人自查用;只查自己的 user_id
            out.put("recent", jdbc.queryForList(
                    "SELECT module, action, doc_no, ip, created_at, success FROM sys_log "
                  + "WHERE user_id=? ORDER BY id DESC LIMIT 10", uid));
        } catch (Exception e) {
            out.put("recent", List.of());
        }
        return out;
    }
}
