package com.group.scm.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.group.scm.common.BizException;
import com.group.scm.common.ClientDeviceUtil;
import com.group.scm.modules.system.entity.SysUser;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 在线会话登记簿。
 *
 * <p><b>按终端存放：</b>键是「用户 + 终端」，不是单纯的用户。同一个人手机、电脑
 * 各占一条，互不覆盖——否则手机登录会把电脑那条记录挤掉，紧接着被空闲清理一并踢下线，
 * App 就没法用了。终端类型的判定见 {@link ClientDeviceUtil}。</p>
 *
 * <p><b>超时分档：</b>电脑端沿用 30 分钟，手机端放宽到 2 小时（可配）。
 * 每条会话按自己所属终端判断是否超时，互不牵连。</p>
 */
@Service
public class OnlineSessionService {

    /** key = userId + "#" + device，见 {@link #key(Long, String)}。 */
    private final Map<String, OnlineUserVO> sessions = new ConcurrentHashMap<>();

    @Value("${scm.session.max-online-users:100}")
    private int maxOnlineUsers;

    @Value("${scm.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    @Value("${scm.session.app-idle-timeout-minutes:120}")
    private int appIdleTimeoutMinutes;

    public void recordLogin(SysUser user, List<String> roles, String ip, String token, String device) {
        if (user == null || user.getId() == null) return;
        String dev = normalizeDevice(device);
        OnlineUserVO online = new OnlineUserVO();
        online.setUserId(user.getId());
        online.setUsername(user.getUsername());
        online.setName(user.getName());
        online.setRoles(roles == null ? List.of() : roles);
        online.setIp(ip);
        online.setToken(token);
        online.setDevice(dev);
        online.setDeviceLabel(ClientDeviceUtil.label(dev));
        online.setLoginAt(LocalDateTime.now());
        online.setLastActiveAt(online.getLoginAt());
        sessions.put(key(user.getId(), dev), online);
        cleanupIdleUsers();
        enforceOnlineLimit(user.getId());
    }

    public void touchCurrent() {
        try {
            if (!StpUtil.isLogin()) return;
            OnlineUserVO online = sessions.get(key(StpUtil.getLoginIdAsLong(), currentDevice()));
            if (online != null) {
                online.setLastActiveAt(LocalDateTime.now());
            }
            cleanupIdleUsers();
        } catch (Exception ignored) {
            // Request may be anonymous or already logged out.
        }
    }

    public void removeCurrent() {
        try {
            if (StpUtil.isLogin()) {
                sessions.remove(key(StpUtil.getLoginIdAsLong(), currentDevice()));
            }
        } catch (Exception ignored) {
            // Logout should never fail only because the registry cannot be updated.
        }
    }

    /** 清掉某人在<b>所有</b>终端的登记：停用账号、改权限、管理员踢人都该全部清。 */
    public void removeUser(Long userId) {
        if (userId == null) return;
        String prefix = userId + "#";
        sessions.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 改密码后：把这个人<b>其它</b>终端的会话全部下线，只留当前这一个（2026-10-18 安全审查 S04）。
     * 以前改了密码，旧电脑/旧手机上已登录的会话照常能用 —— 密码泄露后改密等于没改。
     */
    public void logoutOtherSessions(Long userId, String keepToken) {
        if (userId == null) return;
        try {
            for (String t : StpUtil.getTokenValueListByLoginId(userId)) {
                if (!t.equals(keepToken)) StpUtil.logoutByTokenValue(t);
            }
        } catch (Exception ignored) {
            // 会话可能已过期；下线失败不影响改密结果，下面照样清登记
        }
        String prefix = userId + "#";
        sessions.entrySet().removeIf(e -> e.getKey().startsWith(prefix)
                && !java.util.Objects.equals(e.getValue().getToken(), keepToken));
    }

    public List<OnlineUserVO> list() {
        cleanupIdleUsers();
        LocalDateTime now = LocalDateTime.now();
        return sessions.values().stream()
                .map(item -> {
                    OnlineUserVO safe = item.safeCopy();
                    safe.setIdleMinutes(Duration.between(safe.getLastActiveAt(), now).toMinutes());
                    return safe;
                })
                .sorted(Comparator.comparing(OnlineUserVO::getLastActiveAt).reversed())
                .toList();
    }

    public Map<String, Object> summary() {
        cleanupIdleUsers();
        return Map.of(
                // online 是"多少人在线"（一个人手机+电脑仍算 1 人），sessions 才是会话条数
                "online", distinctUserCount(),
                "sessions", sessions.size(),
                "maxOnlineUsers", maxOnlineUsers,
                "idleTimeoutMinutes", idleTimeoutMinutes,
                "appIdleTimeoutMinutes", appIdleTimeoutMinutes
        );
    }

    /** 管理员踢人：连同该账号的手机、电脑一起下线。 */
    public void kick(Long userId) {
        if (userId == null) throw new BizException(4000, "请选择要下线的用户");
        Long current = currentUserId();
        if (userId.equals(current)) {
            throw new BizException(4000, "不能在这里下线当前管理员账号");
        }
        logoutAllDevices(userId);
        removeUser(userId);
    }

    /** 该终端的活跃超时秒数，供登录时写进 token。 */
    public long activeTimeoutSeconds(String device) {
        int minutes = ClientDeviceUtil.idleTimeoutMinutes(
                normalizeDevice(device), idleTimeoutMinutes, appIdleTimeoutMinutes);
        return minutes <= 0 ? -1 : minutes * 60L;
    }

    @Scheduled(fixedDelayString = "${scm.session.cleanup-interval-ms:60000}")
    public void cleanupIdleUsers() {
        LocalDateTime now = LocalDateTime.now();
        List<Map.Entry<String, OnlineUserVO>> expired = sessions.entrySet().stream()
                .filter(e -> isIdleExpired(e.getValue(), now))
                .toList();
        for (Map.Entry<String, OnlineUserVO> entry : expired) {
            OnlineUserVO item = entry.getValue();
            // 只下线超时的那个终端：电脑超时不该把手机上的会话一起带走
            logoutDevice(item.getUserId(), item.getDevice());
            sessions.remove(entry.getKey());
        }
    }

    private boolean isIdleExpired(OnlineUserVO item, LocalDateTime now) {
        if (item == null || item.getLastActiveAt() == null) return false;
        int limit = ClientDeviceUtil.idleTimeoutMinutes(
                normalizeDevice(item.getDevice()), idleTimeoutMinutes, appIdleTimeoutMinutes);
        if (limit <= 0) return false;
        return item.getLastActiveAt().isBefore(now.minusMinutes(limit));
    }

    /**
     * 在线人数上限。
     *
     * <p><b>按人头算，不按会话条数算</b>：分终端之后一个人最多占两条，若仍按条数比较，
     * 账号一多、各开手机 + 电脑，就会提前越过上限开始互踢。</p>
     */
    private void enforceOnlineLimit(Long currentUserId) {
        if (maxOnlineUsers <= 0) return;
        while (distinctUserCount() > maxOnlineUsers) {
            OnlineUserVO candidate = sessions.values().stream()
                    .filter(item -> !item.getUserId().equals(currentUserId))
                    .filter(item -> item.getRoles() == null || !item.getRoles().contains("admin"))
                    .min(Comparator.comparing(OnlineUserVO::getLastActiveAt))
                    .orElse(null);
            if (candidate == null) return;
            // 超限时把最闲的那个人整个请下线，避免只挤掉他一个终端、人数没降下来空转
            logoutAllDevices(candidate.getUserId());
            removeUser(candidate.getUserId());
        }
    }

    private int distinctUserCount() {
        Set<Long> users = sessions.values().stream()
                .map(OnlineUserVO::getUserId)
                .collect(Collectors.toSet());
        return users.size();
    }

    private void logoutDevice(Long userId, String device) {
        try {
            StpUtil.logout(userId, normalizeDevice(device));
        } catch (Exception ignored) {
            // The user may already be offline or the token may have expired.
        }
    }

    private void logoutAllDevices(Long userId) {
        try {
            StpUtil.logout(userId);
        } catch (Exception ignored) {
            // The user may already be offline or the token may have expired.
        }
    }

    private static String key(Long userId, String device) {
        return userId + "#" + normalizeDevice(device);
    }

    /** 旧 token 没带 device，一律按电脑端处理——拿不准时取更严格的那一档。 */
    private static String normalizeDevice(String device) {
        return ClientDeviceUtil.DEVICE_APP.equals(device)
                ? ClientDeviceUtil.DEVICE_APP
                : ClientDeviceUtil.DEVICE_PC;
    }

    private String currentDevice() {
        try {
            return normalizeDevice(StpUtil.getLoginDevice());
        } catch (Exception e) {
            return ClientDeviceUtil.DEVICE_PC;
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    public static class OnlineUserVO {
        private Long userId;
        private String username;
        private String name;
        private List<String> roles;
        private String ip;
        @JsonIgnore
        private String token;
        /** APP / PC，见 ClientDeviceUtil。 */
        private String device;
        /** 给人看的「手机 / 电脑」。 */
        private String deviceLabel;
        private LocalDateTime loginAt;
        private LocalDateTime lastActiveAt;
        private Long idleMinutes;

        private OnlineUserVO safeCopy() {
            OnlineUserVO copy = new OnlineUserVO();
            copy.setUserId(userId);
            copy.setUsername(username);
            copy.setName(name);
            copy.setRoles(roles == null ? List.of() : new ArrayList<>(roles));
            copy.setIp(ip);
            copy.setDevice(device);
            copy.setDeviceLabel(deviceLabel);
            copy.setLoginAt(loginAt);
            copy.setLastActiveAt(lastActiveAt);
            copy.setIdleMinutes(idleMinutes);
            return copy;
        }
    }
}
