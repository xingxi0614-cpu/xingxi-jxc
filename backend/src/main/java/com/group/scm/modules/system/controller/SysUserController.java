package com.group.scm.modules.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysUserMapper;
import com.group.scm.modules.system.service.OnlineSessionService;
import com.group.scm.modules.system.service.SysUserImportService;
import com.group.scm.modules.system.service.SysUserImportTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/sys/users")
@RequiredArgsConstructor
public class SysUserController {

    private final com.group.scm.modules.system.service.RoleAssignmentService roleAssignment;
    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;
    private final OnlineSessionService onlineSessionService;
    private final SysUserImportService userImportService;
    private final SysUserImportTemplateService userImportTemplateService;
    private final BCryptPasswordEncoder pwdEncoder = new BCryptPasswordEncoder();

    @GetMapping
    public Result<IPage<SysUser>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        SecurityUtil.requireAccountAdmin();
        Page<SysUser> p = new Page<>(current, size);
        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        qw.eq("deleted", 0);
        // 用户管理的「启用/停用」页签(2026-10-17):不传 = 全部(原来的行为)
        if (status != null) qw.eq("status", status);
        if (!SecurityUtil.isAdmin()) {
            java.util.List<Long> orgIds = orgScope.visibleOrgIds();
            if (orgIds.isEmpty()) qw.apply("1 = 0");
            else qw.in("org_id", orgIds);
            qw.notExists("""
                    SELECT 1
                    FROM sys_user_role ur
                    JOIN sys_role r ON r.id = ur.role_id
                    WHERE ur.user_id = sys_user.id
                      AND r.code = 'admin'
                      AND r.deleted = 0
                      AND r.status = 1
                    """);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            qw.and(w -> w.like("username", like).or().like("name", like));
        }
        qw.orderByDesc("id");
        IPage<SysUser> page = userMapper.selectPage(p, qw);
        for (SysUser user : page.getRecords()) {
            user.setPassword(null);
            user.setRoleIds(userMapper.listRoleIds(user.getId()));
            user.setRoleCodes(userMapper.listRoleCodes(user.getId()));
            user.setOrgName(orgNameById(user.getOrgId()));
        }
        return Result.ok(page);
    }

    @OpLog(module = "用户管理", action = "批量导入用户")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SysUserImportService.ImportResult> importUsers(@RequestPart("file") MultipartFile file) {
        SecurityUtil.requireAccountAdmin();
        return Result.ok(userImportService.importExcel(file));
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> userImportTemplate() {
        SecurityUtil.requireAccountAdmin();
        return excelTemplate("用户账号导入模板.xlsx", userImportTemplateService.userTemplate());
    }

    @OpLog(module = "用户管理", action = "新建用户")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping
    public Result<?> create(@RequestBody SysUser user) {
        SecurityUtil.requireAccountAdmin();
        Result<?> usernameCheck = validateUsernameForCreate(user);
        if (usernameCheck != null) return usernameCheck;
        Result<?> orgCheck = validateOrg(user.getOrgId());
        if (orgCheck != null) return orgCheck;
        roleAssignment.validate(user.getRoleIds());
        Result<?> passwordCheck = validateNewPassword(user.getPassword(), user.getUsername());
        if (passwordCheck != null) return passwordCheck;
        user.setPassword(pwdEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            return duplicateUsernameResult(user.getUsername());
        }
        saveRoles(user.getId(), user.getRoleIds());
        return Result.ok();
    }

    @OpLog(module = "用户管理", action = "编辑用户")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody SysUser user) {
        SecurityUtil.requireAccountAdmin();
        assertTargetUserVisible(id);
        Result<?> usernameCheck = validateUsernameForUpdate(id, user);
        if (usernameCheck != null) return usernameCheck;
        Result<?> orgCheck = validateOrg(user.getOrgId());
        if (orgCheck != null) return orgCheck;
        roleAssignment.validate(user.getRoleIds());
        user.setId(id);
        user.setPassword(null);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException e) {
            return duplicateUsernameResult(user.getUsername());
        }
        saveRoles(id, user.getRoleIds());
        logoutOrRefreshSelf(id, user.getOrgId());
        return Result.ok();
    }

    @OpLog(module = "用户管理", action = "重置密码", recordBody = false)
    @PostMapping("/{id}/reset-password")
    public Result<?> resetPassword(@PathVariable Long id, @RequestBody(required = false) ResetPasswordDTO dto) {
        SecurityUtil.requireAccountAdmin();
        assertTargetUserVisible(id);
        String newPassword = dto == null ? null : dto.password;
        Result<?> passwordCheck = validateNewPassword(newPassword, null);
        if (passwordCheck != null) return passwordCheck;
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(pwdEncoder.encode(newPassword));
        user.setFailCount(0);
        userMapper.updateById(user);
        userMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", id)
                .set("fail_count", 0)
                .set("locked_until", null));
        logoutUserQuietly(id);
        return Result.ok(Map.of("reset", true));
    }

    @OpLog(module = "用户管理", action = "解锁账号")
    @PostMapping("/{id}/unlock")
    public Result<?> unlock(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        assertTargetUserVisible(id);
        userMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", id)
                .set("fail_count", 0)
                .set("locked_until", null));
        return Result.ok();
    }

    @OpLog(module = "用户管理", action = "删除用户")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        assertTargetUserVisible(id);
        if (SecurityUtil.uid() != null && SecurityUtil.uid().equals(id)) {
            return Result.fail(4000, "当前登录的管理员账号不能在这里删除");
        }
        logoutUserQuietly(id);
        // 软删除 deleted 写本行 id，不写 1：唯一键是 uk_username(username, deleted)，
        // 写死 1 时回收站里已有同名账号就撞键、这个人永远删不掉（曾出现同一批人反复删除全部失败）。
        // 与 md_project/md_supplier 同一写法，见迁移 20261001_001。
        userMapper.update(null, new UpdateWrapper<SysUser>().eq("id", id).setSql("deleted = id"));
        return Result.ok();
    }

    private void saveRoles(Long userId, java.util.List<Long> roleIds) {
        userMapper.deleteRoles(userId);
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long roleId : new java.util.LinkedHashSet<>(roleIds)) {
            userMapper.insertRole(userId, roleId);
        }
    }

    private void assertTargetUserVisible(Long id) {
        if (SecurityUtil.isAdmin()) return;
        SysUser target = userMapper.selectById(id);
        if (target == null) throw new BizException(4040, "用户不存在");
        orgScope.assertOrgVisible(target.getOrgId());
        java.util.List<String> roleCodes = userMapper.listRoleCodes(id);
        if (roleCodes.contains("admin")) {
            throw new BizException(4003, "账套管理员不能维护系统管理员账号");
        }
    }

    private Result<?> validateUsernameForCreate(SysUser user) {
        Result<?> basicCheck = normalizeAndValidateUsername(user);
        if (basicCheck != null) return basicCheck;
        return userMapper.findByUsername(user.getUsername()) == null ? null : duplicateUsernameResult(user.getUsername());
    }

    private Result<?> validateUsernameForUpdate(Long id, SysUser user) {
        Result<?> basicCheck = normalizeAndValidateUsername(user);
        if (basicCheck != null) return basicCheck;
        SysUser existing = userMapper.findByUsername(user.getUsername());
        if (existing != null && !existing.getId().equals(id)) return duplicateUsernameResult(user.getUsername());
        return null;
    }

    private Result<?> normalizeAndValidateUsername(SysUser user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return Result.fail(4000, "账号不能为空");
        }
        String username = user.getUsername().trim();
        if (username.length() > 40) {
            return Result.fail(4000, "账号不能超过 40 个字符");
        }
        if (!username.matches("^[A-Za-z0-9_\\-]+$")) {
            return Result.fail(4000, "账号只能包含字母、数字、下划线或短横线");
        }
        user.setUsername(username);
        return null;
    }

    private Result<?> duplicateUsernameResult(String username) {
        String suggestion = suggestUsername(username);
        return Result.fail(4000, "账号 " + username + " 已存在，建议使用：" + suggestion);
    }

    private String suggestUsername(String username) {
        String base = username == null ? "user" : username.trim();
        Matcher matcher = Pattern.compile("^(.*?)(\\d+)$").matcher(base);
        int start = 1;
        if (matcher.matches()) {
            base = matcher.group(1);
            try {
                start = Integer.parseInt(matcher.group(2)) + 1;
            } catch (NumberFormatException ignored) {
                start = 1;
            }
        }
        if (base.isBlank()) base = "user";
        for (int i = start; i < start + 10000; i++) {
            String candidate = base + i;
            if (candidate.length() <= 40 && userMapper.findByUsername(candidate) == null) {
                return candidate;
            }
        }
        String suffix = String.valueOf(System.currentTimeMillis()).substring(7);
        return (base.length() + suffix.length() > 40 ? base.substring(0, 40 - suffix.length()) : base) + suffix;
    }

    /**
     * Bug #7 修复：密码强度校验（与 AuthService 保持一致）。
     * 放开「仅字母数字」限制，允许特殊字符：8-64 位、含字母、含数字或特殊字符、
     * 不在弱密码黑名单、不包含用户名。
     */
    private Result<?> validateNewPassword(String password, String username) {
        if (password == null || password.isBlank()) {
            return Result.fail("新密码不能为空");
        }
        if (password.length() < 8) {
            return Result.fail("新密码至少需要 8 位");
        }
        if (password.length() > 64) {
            return Result.fail("新密码最多 64 位");
        }
        if (!password.matches(".*[A-Za-z].*")) {
            return Result.fail("新密码必须包含字母");
        }
        if (!password.matches(".*[0-9!@#$%^&*()_+\\-=\\[\\]{}|;':\",./<>?].*")) {
            return Result.fail("新密码必须包含数字或特殊字符");
        }
        String lower = password.toLowerCase();
        if (lower.equals("123456") || lower.equals("12345678") || lower.equals("password")
                || lower.equals("admin123") || lower.equals("qwerty123") || lower.equals("password1")) {
            return Result.fail("该密码过于常见，请使用更复杂的密码");
        }
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase())) {
            return Result.fail("新密码不能包含用户名");
        }
        return null;
    }

    /**
     * 改完账号后让新权限生效。
     *
     * <p>改的是别人 → 把对方踢下线,让他重登后拿到新权限(原来的做法)。
     * 改的是自己 → <b>不能踢自己</b>:曾发现账套管理员给自己加角色时
     * 每加一个就被自己踢下线一次(有人连中 5 次),而且被注销后连这条操作日志都记不到操作人。
     * 改成就地刷新自己会话里的权限,新角色立刻生效,人不掉线。</p>
     *
     * <p>已知小瑕疵:在线用户列表里显示的角色是登录那一刻的快照,改自己角色后要等下次登录才刷新。
     * 不影响权限判定(权限读的是会话里的 roles/perms),故未一并处理。</p>
     */
    private void logoutOrRefreshSelf(Long userId, Long newOrgId) {
        Long me = SecurityUtil.uid();
        if (me != null && me.equals(userId)) {
            refreshCurrentSession(userId, newOrgId);
            return;
        }
        logoutUserQuietly(userId);
    }

    /** 就地刷新当前会话的角色/权限/数据范围/所属组织(字段与登录时写入的那一套保持一致)。 */
    private void refreshCurrentSession(Long userId, Long newOrgId) {
        try {
            StpUtil.getSession().set("roles", userMapper.listRoleCodes(userId));
            StpUtil.getSession().set("perms", userMapper.listPermCodes(userId));
            StpUtil.getSession().set("dataScopes", userMapper.listDataScopes(userId));
            if (newOrgId != null) StpUtil.getSession().set("orgId", newOrgId);
        } catch (Exception ignored) {
            // 刷新失败不影响本次保存;退而求其次踢下线,让他重登拿新权限
            logoutUserQuietly(userId);
        }
    }

    private void logoutUserQuietly(Long userId) {
        try {
            if (userId != null) StpUtil.logout(userId);
        } catch (Exception ignored) {
        }
        onlineSessionService.removeUser(userId);
    }

    private Result<?> validateOrg(Long orgId) {
        if (orgId == null) return Result.fail(4000, "请选择人员归属");
        if (!SecurityUtil.isAdmin()) {
            try {
                orgScope.assertOrgVisible(orgId);
            } catch (BizException e) {
                return Result.fail(4003, "只能选择本账套内的人员归属");
            }
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1",
                Integer.class, orgId);
        return count != null && count > 0 ? null : Result.fail(4000, "人员归属不存在或已停用，请重新选择");
    }

    /**
     * 用户列表"人员归属"列的显示值,格式「公司/部门」。
     *
     * <p>库里"财务部""技术部"各有 9 个(每家公司一套),只显示部门名的话屏幕上 9 行长得一模一样,
     * 叠加同名同姓(同一个人在两家公司各有一个账号)就完全分不清谁是哪家的 —— 2026-09-08 修。
     * 顶层公司本身没有上级,仍只显示自己的名字。</p>
     */
    private String orgNameById(Long orgId) {
        if (orgId == null) return "";
        return jdbcTemplate.query("""
                        SELECT o.name AS org_name, o.type AS org_type, p.name AS parent_name
                        FROM sys_org o
                        LEFT JOIN sys_org p ON p.id = o.parent_id AND p.deleted = 0
                        WHERE o.id = ? AND o.deleted = 0
                        LIMIT 1
                        """,
                rs -> {
                    if (!rs.next()) return "";
                    String own = rs.getString("org_name");
                    if (own == null) return "";
                    // 只有部门才需要冠上公司名;集团/公司本身显示自己的名字就够了
                    if (!"DEPT".equals(rs.getString("org_type"))) return own;
                    String parent = rs.getString("parent_name");
                    return (parent == null || parent.isBlank()) ? own : parent + "/" + own;
                }, orgId);
    }

    private ResponseEntity<byte[]> excelTemplate(String fileName, byte[] bytes) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    public static class ResetPasswordDTO {
        public String password;
    }
}
