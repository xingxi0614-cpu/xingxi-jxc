package com.group.scm.modules.system.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysUserImportService {

    private final RoleAssignmentService roleAssignment;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;
    private final OnlineSessionService onlineSessionService;
    private final BCryptPasswordEncoder pwdEncoder = new BCryptPasswordEncoder();

    public ImportResult importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的用户 Excel 文件");
        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook workbook = opened.workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new BizException("导入文件没有可读取的工作表");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) throw new BizException("未找到表头：至少需要包含“登录账号”“姓名”“人员归属”“岗位角色”");

            Map<String, Integer> headers = headerIndex(sheet.getRow(headerRowIndex), formatter);
            int usernameCol = requireColumn(headers, "登录账号", "账号", "用户名", "用户账号");
            int nameCol = requireColumn(headers, "姓名", "人员姓名", "真实姓名");
            int orgCol = requireColumn(headers, "人员归属", "所属组织", "组织", "部门");
            int rolesCol = requireColumn(headers, "岗位角色", "角色", "岗位", "角色编码");
            int passwordCol = optionalColumn(headers, "初始密码", "密码");

            ImportResult result = new ImportResult();
            Set<String> usernamesInFile = new LinkedHashSet<>();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                UserImportRow item = new UserImportRow();
                item.username = cellText(row, usernameCol, formatter);
                item.name = cellText(row, nameCol, formatter);
                item.orgText = cellText(row, orgCol, formatter);
                item.rolesText = cellText(row, rolesCol, formatter);
                item.password = cellText(row, passwordCol, formatter);
                item.phone = value(row, headers, formatter, "手机", "手机号", "联系电话");
                item.email = value(row, headers, formatter, "邮箱", "电子邮箱");
                item.statusText = value(row, headers, formatter, "状态", "账号状态");

                if (isBlank(item.username) && isBlank(item.name) && isBlank(item.orgText) && isBlank(item.rolesText)) continue;
                String normalizedUsername = clean(item.username);
                if (!usernamesInFile.add(normalizedUsername)) {
                    result.skipped++;
                    result.addError("第 " + (i + 1) + " 行登录账号在本次文件内重复：" + normalizedUsername);
                    continue;
                }
                try {
                    transactionTemplate.executeWithoutResult(status -> importOne(item, result));
                } catch (BizException e) {
                    result.skipped++;
                    result.addError("第 " + (i + 1) + " 行：" + e.getMessage());
                } catch (Exception e) {
                    result.skipped++;
                    result.addError("第 " + (i + 1) + " 行导入失败：" + SafeErrorMessage.ofLogged(e, "用户导入"));
                }
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("用户导入失败：" + SafeErrorMessage.ofLogged(e, "用户导入"));
        }
    }

    private void importOne(UserImportRow row, ImportResult result) {
        String username = normalizeUsername(row.username);
        String name = required(row.name, "姓名不能为空");
        Long orgId = resolveOrgId(required(row.orgText, "人员归属不能为空"));
        List<Long> roleIds = resolveRoleIds(required(row.rolesText, "岗位角色不能为空"));
        Integer status = parseStatus(row.statusText);

        SysUser existing = userMapper.findByUsername(username);
        if (existing == null) {
            validatePassword(row.password, true);
            SysUser user = new SysUser();
            user.setUsername(username);
            user.setPassword(pwdEncoder.encode(clean(row.password)));
            user.setName(truncate(name, 40));
            user.setOrgId(orgId);
            user.setPhone(nullable(row.phone, 30));
            user.setEmail(nullable(row.email, 80));
            user.setStatus(status);
            userMapper.insert(user);
            saveRoles(user.getId(), roleIds);
            result.inserted++;
            return;
        }

        assertTargetUserVisible(existing);
        if (!isBlank(row.password)) validatePassword(row.password, false);
        SysUser user = new SysUser();
        user.setId(existing.getId());
        user.setUsername(username);
        if (!isBlank(row.password)) user.setPassword(pwdEncoder.encode(clean(row.password)));
        user.setName(truncate(name, 40));
        user.setOrgId(orgId);
        user.setPhone(nullable(row.phone, 30));
        user.setEmail(nullable(row.email, 80));
        user.setStatus(status);
        userMapper.updateById(user);
        saveRoles(existing.getId(), roleIds);
        logoutUserQuietly(existing.getId());
        result.updated++;
    }

    private void saveRoles(Long userId, List<Long> roleIds) {
        userMapper.deleteRoles(userId);
        for (Long roleId : new LinkedHashSet<>(roleIds)) {
            userMapper.insertRole(userId, roleId);
        }
    }

    private void assertTargetUserVisible(SysUser target) {
        if (SecurityUtil.isAdmin()) return;
        orgScope.assertOrgVisible(target.getOrgId());
        List<String> roleCodes = userMapper.listRoleCodes(target.getId());
        if (roleCodes.contains("admin")) {
            throw new BizException(4003, "账套管理员不能维护系统管理员账号");
        }
    }

    private String normalizeUsername(String value) {
        String username = required(value, "登录账号不能为空");
        if (username.length() > 40) throw new BizException("登录账号不能超过 40 个字符");
        if (!username.matches("^[A-Za-z0-9_\\-]+$")) {
            throw new BizException("登录账号只能包含字母、数字、下划线或短横线");
        }
        return username;
    }

    private Long resolveOrgId(String value) {
        Long orgId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                  AND (code = ? OR name = ?)
                ORDER BY CASE WHEN code = ? THEN 0 ELSE 1 END, id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, value, value, value);
        if (orgId == null) throw new BizException("人员归属不存在或已停用：" + value);
        if (!SecurityUtil.isAdmin()) orgScope.assertOrgVisible(orgId);
        return orgId;
    }

    private List<Long> resolveRoleIds(String value) {
        List<Long> roleIds = new ArrayList<>();
        Set<String> parts = splitRoles(value);
        for (String part : parts) {
            Long roleId = jdbcTemplate.query("""
                    SELECT id
                    FROM sys_role
                    WHERE deleted = 0 AND status = 1
                      AND (code = ? OR name = ?)
                    ORDER BY CASE WHEN code = ? THEN 0 ELSE 1 END, id
                    LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, part, part, part);
            if (roleId == null) throw new BizException("岗位角色不存在或已停用：" + part);
            roleIds.add(roleId);
        }
        if (roleIds.isEmpty()) throw new BizException("岗位角色不能为空");
        roleAssignment.validate(roleIds);
        return roleIds;
    }

    private Set<String> splitRoles(String value) {
        Set<String> parts = new LinkedHashSet<>();
        for (String part : clean(value).split("[,，、;/；/\\n\\r]+")) {
            String text = clean(part);
            if (!text.isBlank()) parts.add(text);
        }
        return parts;
    }

    private void validatePassword(String password, boolean required) {
        String text = clean(password);
        if (text.isBlank()) {
            if (required) throw new BizException("新增账号必须填写初始密码");
            return;
        }
        if (text.length() < 6) throw new BizException("初始密码至少需要 6 位");
        if (!text.matches("[A-Za-z0-9]+") || !text.matches(".*[A-Za-z].*") || !text.matches(".*\\d.*")) {
            throw new BizException("初始密码只能使用字母和数字，并且必须同时包含字母和数字");
        }
        if ("123456".equals(text) || "password".equalsIgnoreCase(text) || "admin123".equalsIgnoreCase(text)) {
            throw new BizException("不能使用系统默认弱密码");
        }
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int max = Math.min(sheet.getLastRowNum(), 30);
        for (int i = 0; i <= max; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, Integer> headers = headerIndex(row, formatter);
            if ((headers.containsKey("登录账号") || headers.containsKey("账号") || headers.containsKey("用户名"))
                    && (headers.containsKey("姓名") || headers.containsKey("人员姓名") || headers.containsKey("真实姓名"))
                    && (headers.containsKey("人员归属") || headers.containsKey("所属组织") || headers.containsKey("组织"))
                    && (headers.containsKey("岗位角色") || headers.containsKey("角色") || headers.containsKey("岗位")))
                return i;
        }
        return -1;
    }

    private Map<String, Integer> headerIndex(Row row, DataFormatter formatter) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (row == null) return map;
        for (Cell cell : row) {
            String text = clean(formatter.formatCellValue(cell));
            if (!isBlank(text)) map.putIfAbsent(text, cell.getColumnIndex());
        }
        return map;
    }

    private int requireColumn(Map<String, Integer> headers, String... names) {
        int index = optionalColumn(headers, names);
        if (index < 0) throw new BizException("导入文件缺少必填列：" + names[0]);
        return index;
    }

    private int optionalColumn(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            Integer index = headers.get(name);
            if (index != null) return index;
        }
        return -1;
    }

    private String value(Row row, Map<String, Integer> headers, DataFormatter formatter, String... names) {
        int col = optionalColumn(headers, names);
        return cellText(row, col, formatter);
    }

    private String cellText(Row row, int col, DataFormatter formatter) {
        if (row == null || col < 0) return "";
        Cell cell = row.getCell(col);
        return cell == null ? "" : clean(formatter.formatCellValue(cell));
    }

    private Integer parseStatus(String value) {
        String text = clean(value);
        if (text.contains("停") || text.contains("禁") || text.equals("0")) return 0;
        return 1;
    }

    private String required(String value, String message) {
        String text = clean(value);
        if (text.isBlank()) throw new BizException(message);
        return text;
    }

    private String nullable(String value, int max) {
        String text = clean(value);
        return text.isBlank() ? null : truncate(text, max);
    }

    private String clean(String value) {
        if (value == null) return "";
        String text = value.startsWith("\uFEFF") ? value.substring(1) : value;
        text = text.trim();
        if (text.endsWith(".0") && text.matches("\\d+\\.0")) return text.substring(0, text.length() - 2);
        return text;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private void logoutUserQuietly(Long userId) {
        try {
            if (userId != null) cn.dev33.satoken.stp.StpUtil.logout(userId);
        } catch (Exception ignored) {
        }
        onlineSessionService.removeUser(userId);
    }

    private static class UserImportRow {
        private String username;
        private String name;
        private String orgText;
        private String rolesText;
        private String password;
        private String phone;
        private String email;
        private String statusText;
    }

    @Data
    public static class ImportResult {
        private int inserted;
        private int updated;
        private int skipped;
        private final List<String> errors = new ArrayList<>();

        public void addError(String error) {
            if (errors.size() < 20) errors.add(error);
        }
    }
}
