package com.group.scm.modules.auth.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.ClientDeviceUtil;
import com.group.scm.modules.auth.dto.ChangePasswordDTO;
import com.group.scm.modules.auth.dto.LoginDTO;
import com.group.scm.modules.auth.dto.LoginVO;
import com.group.scm.modules.auth.dto.MenuTreeVO;
import com.group.scm.modules.auth.dto.ProfileUpdateDTO;
import com.group.scm.modules.auth.dto.UserInfoVO;
import com.group.scm.modules.system.entity.SysMenu;
import com.group.scm.modules.system.entity.SysOrg;
import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysMenuMapper;
import com.group.scm.modules.system.mapper.SysOrgMapper;
import com.group.scm.modules.system.mapper.SysUserMapper;
import com.group.scm.modules.system.service.OnlineSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** Bug #6/#14 修复：失败阈值从 3 提高到 5，前 5 次仅累计、不锁定，降低正常用户误锁概率 */
    private static final int MAX_FAIL = 5;
    /** 连续失败达到此次数才升级为永久锁定（需管理员解锁/重置） */
    private static final int ADMIN_REQUIRED_FAIL = 15;
    /**
     * Bug #6 修复：渐进式时间锁定时长（分钟），下标即累计失败次数。
     * 第 1-5 次不锁定；第 6 次锁 15 分钟；第 7 次 1 小时；第 8 次 2 小时；
     * 第 9 次 8 小时；第 10 次及以上 24 小时；满 15 次升级为永久锁定。
     */
    private static final int[] LOCK_MINUTES = {0, 0, 0, 0, 0, 15, 60, 120, 480, 1440};
    private static final LocalDateTime ADMIN_UNLOCK_REQUIRED = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysOrgMapper orgMapper;
    private final OnlineSessionService onlineSessionService;
    private final BCryptPasswordEncoder pwdEncoder = new BCryptPasswordEncoder();

    public LoginVO login(LoginDTO dto, String ip) {
        SysUser user = userMapper.findByUsername(dto.getUsername());
        if (user == null) throw new BizException(4040, "账号或密码错误");
        if (user.getStatus() != null && user.getStatus() == 0) throw new BizException(4030, "账号已停用");
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BizException(4030, lockedMessage(user.getLockedUntil()));
        }
        if (!pwdEncoder.matches(dto.getPassword(), user.getPassword())) {
            int failCount = Math.min((user.getFailCount() == null ? 0 : user.getFailCount()) + 1, ADMIN_REQUIRED_FAIL);
            user.setFailCount(failCount);
            LocalDateTime lockUntil = calcLockedUntil(failCount);
            if (lockUntil != null) {
                user.setLockedUntil(lockUntil);
            }
            userMapper.updateById(user);
            throw new BizException(4040, "账号或密码错误");
        }

        // 密码正确：清除失败计数/锁定
        user.setFailCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);

        // 同类终端已有人在线：未确认强制登录时，返回 4090 让前端弹确认框。
        // 只看同类终端——手机上登录不该因为"电脑还开着"就来问一遍，两者本就允许共存。
        String device = ClientDeviceUtil.currentDevice();
        if (!dto.isForce() && !StpUtil.getTokenValueListByLoginId(user.getId(), device).isEmpty()) {
            throw new BizException(4090, "当前账号已在其他" + ClientDeviceUtil.label(device)
                    + "登录，是否强制登录并将对方挤下线？");
        }

        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);

        // 同类终端互斥：先把这一类终端上的旧会话请下线，另一类终端不受影响。
        StpUtil.logout(user.getId(), device);
        StpUtil.login(user.getId(), new SaLoginModel()
                .setDevice(device)
                .setActiveTimeout(onlineSessionService.activeTimeoutSeconds(device)));
        List<String> roleCodes = userMapper.listRoleCodes(user.getId());
        StpUtil.getSession().set("roles", roleCodes);
        StpUtil.getSession().set("perms", userMapper.listPermCodes(user.getId()));
        StpUtil.getSession().set("dataScopes", userMapper.listDataScopes(user.getId()));
        StpUtil.getSession().set("orgId", user.getOrgId());
        onlineSessionService.recordLogin(user, roleCodes, ip, StpUtil.getTokenValue(), device);

        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setTokenName(StpUtil.getTokenName());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setExpiresIn(StpUtil.getTokenTimeout());
        return vo;
    }

    public void logout() {
        onlineSessionService.removeCurrent();
        StpUtil.logout();
    }

    public UserInfoVO me() {
        Long uid = StpUtil.getLoginIdAsLong();
        SysUser user = userMapper.selectById(uid);
        if (user == null) throw new BizException(4001, "用户不存在");
        SysOrg org = orgMapper.selectById(user.getOrgId());

        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setOrgId(user.getOrgId());
        vo.setOrgName(org == null ? "" : org.getName());
        vo.setCompanyName(org == null ? "" : findCompanyName(org));
        vo.setRoles(userMapper.listRoleCodes(uid));
        vo.setPerms(userMapper.listPermCodes(uid));
        vo.setMenus(buildMenuTree(menuMapper.listByUser(uid)));
        return vo;
    }

    public UserInfoVO updateProfile(ProfileUpdateDTO dto) {
        Long uid = StpUtil.getLoginIdAsLong();
        SysUser user = userMapper.selectById(uid);
        if (user == null) throw new BizException(4001, "用户不存在");
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(4000, "姓名不能为空");
        }
        String phone = trimToNull(dto.getPhone());
        if (phone != null && !phone.matches("^[0-9+\\-\\s]{6,20}$")) {
            throw new BizException(4000, "手机号格式不正确");
        }
        String email = trimToNull(dto.getEmail());
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BizException(4000, "邮箱格式不正确");
        }
        String avatar = trimToNull(dto.getAvatar());
        if (avatar != null && avatar.length() > 255) {
            throw new BizException(4000, "头像地址过长");
        }
        user.setName(dto.getName().trim());
        user.setPhone(phone);
        user.setEmail(email);
        user.setAvatar(avatar);
        userMapper.updateById(user);
        return me();
    }

    public void changePassword(ChangePasswordDTO dto) {
        Long uid = StpUtil.getLoginIdAsLong();
        SysUser user = userMapper.selectById(uid);
        if (user == null) throw new BizException(4001, "用户不存在");
        if (dto.getOldPassword() == null || !pwdEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BizException(4000, "原密码不正确");
        }
        validateNewPassword(dto.getNewPassword(), user.getUsername());
        user.setPassword(pwdEncoder.encode(dto.getNewPassword()));
        user.setFailCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);
        // 🔴 2026-10-18(安全审查 S04):改密后其它终端的旧会话一律下线,当前这台保留(不把自己踢掉)。
        onlineSessionService.logoutOtherSessions(uid, StpUtil.getTokenValue());
    }

    /**
     * Bug #6 修复：按累计失败次数计算锁定到期时间。
     * 返回 null 表示本次不锁定（仅累计失败次数）。
     */
    private LocalDateTime calcLockedUntil(int failCount) {
        if (failCount >= ADMIN_REQUIRED_FAIL) {
            return ADMIN_UNLOCK_REQUIRED;
        }
        int idx = Math.min(failCount, LOCK_MINUTES.length - 1);
        int minutes = LOCK_MINUTES[idx];
        if (minutes <= 0) return null;
        return LocalDateTime.now().plusMinutes(minutes);
    }

    /** 锁定提示：永久锁定提示联系管理员，时间锁定提示剩余分钟数。 */
    private String lockedMessage(LocalDateTime lockedUntil) {
        if (lockedUntil.getYear() >= 2999) {
            return "账号已锁定，请联系管理员解锁或重置密码";
        }
        long minutes = Math.max(1, java.time.Duration.between(LocalDateTime.now(), lockedUntil).toMinutes() + 1);
        return "密码连续输错，账号已临时锁定，请 " + minutes + " 分钟后重试，或联系管理员重置密码";
    }

    /**
     * Bug #7 修复：密码强度校验。
     * 放开「仅字母数字」限制，允许并鼓励特殊字符：
     *   - 长度 8-64 位
     *   - 至少包含一个字母
     *   - 至少包含一个数字或特殊字符
     *   - 不在常见弱密码黑名单中
     *   - 不包含用户名
     */
    private void validateNewPassword(String password, String username) {
        if (password == null || password.isBlank()) {
            throw new BizException(4000, "新密码不能为空");
        }
        if (password.length() < 8) {
            throw new BizException(4000, "新密码至少需要 8 位");
        }
        if (password.length() > 64) {
            throw new BizException(4000, "新密码最多 64 位");
        }
        if (!password.matches(".*[A-Za-z].*")) {
            throw new BizException(4000, "新密码必须包含字母");
        }
        if (!password.matches(".*[0-9!@#$%^&*()_+\\-=\\[\\]{}|;':\",./<>?].*")) {
            throw new BizException(4000, "新密码必须包含数字或特殊字符");
        }
        String lower = password.toLowerCase();
        if (lower.equals("123456") || lower.equals("12345678") || lower.equals("password")
                || lower.equals("admin123") || lower.equals("qwerty123") || lower.equals("password1")) {
            throw new BizException(4000, "该密码过于常见，请使用更复杂的密码");
        }
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase())) {
            throw new BizException(4000, "新密码不能包含用户名");
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<MenuTreeVO> buildMenuTree(List<SysMenu> menus) {
        Map<Long, MenuTreeVO> map = new HashMap<>();
        List<MenuTreeVO> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            MenuTreeVO vo = new MenuTreeVO();
            vo.setId(menu.getId());
            vo.setParentId(menu.getParentId());
            vo.setName(menu.getName());
            vo.setPath(menu.getPath());
            vo.setComponent(menu.getComponent());
            vo.setIcon(menu.getIcon());
            vo.setPermCode(menu.getPermCode());
            vo.setType(menu.getType());
            vo.setSort(menu.getSort() == null ? 0 : menu.getSort());
            map.put(vo.getId(), vo);
        }
        for (MenuTreeVO menu : map.values()) {
            if (menu.getParentId() == null || menu.getParentId() == 0 || !map.containsKey(menu.getParentId())) {
                roots.add(menu);
            } else {
                map.get(menu.getParentId()).getChildren().add(menu);
            }
        }
        roots.sort(Comparator.comparingInt(MenuTreeVO::getSort));
        roots.forEach(root -> root.getChildren().sort(Comparator.comparingInt(MenuTreeVO::getSort)));
        return roots;
    }

    /**
     * 从当前组织向上追溯到公司层级，返回公司名称。
     * 如果找不到 COMPANY 类型则回退到当前组织名称。
     */
    private String findCompanyName(SysOrg org) {
        if (org == null) return "";
        if ("COMPANY".equals(org.getType())) return org.getName();
        SysOrg current = org;
        int guard = 0;
        while (current.getParentId() != null && current.getParentId() != 0 && guard < 16) {
            current = orgMapper.selectById(current.getParentId());
            guard++;
            if (current == null) break;
            if ("COMPANY".equals(current.getType())) return current.getName();
        }
        // 未找到公司则回退到组织名称
        return org.getName();
    }
}
