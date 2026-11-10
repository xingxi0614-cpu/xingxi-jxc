package com.group.scm.modules.master.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProjectMasterImportService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    public ImportResult importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的工程项目 Excel 文件");
        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook workbook = opened.workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new BizException("导入文件没有可读取的工作表");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) throw new BizException("未找到表头：至少需要包含“所属组织”“工程项目名称”“工程项目类别”");

            Map<String, Integer> headers = headerIndex(sheet.getRow(headerRowIndex), formatter);
            int orgCol = optionalColumn(headers, "所属组织");
            int codeCol = requireColumn(headers, "工程项目编码", "项目编码", "编码");
            int nameCol = requireColumn(headers, "工程项目名称", "项目名称", "名称");
            int categoryCol = requireColumn(headers, "工程项目类别", "项目类别", "类别");

            ImportResult result = new ImportResult();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                ProjectImportRow item = new ProjectImportRow();
                item.orgName = cellText(row, orgCol, formatter);
                item.code = cellText(row, codeCol, formatter);
                item.name = cellText(row, nameCol, formatter);
                item.category = cellText(row, categoryCol, formatter);
                if (isBlank(item.code) && isBlank(item.name) && isBlank(item.category)) continue;
                if (isBlank(item.code) || isBlank(item.name) || isBlank(item.category)) {
                    result.addError("第 " + (i + 1) + " 行缺少工程项目编码、工程项目名称或工程项目类别");
                    result.skipped++;
                    continue;
                }
                item.shortName = value(row, headers, formatter, "简称");
                item.mnemonicCode = value(row, headers, formatter, "助记码");
                item.parentText = value(row, headers, formatter, "上级项目", "上级");
                item.statusText = value(row, headers, formatter, "状态");
                item.remark = value(row, headers, formatter, "备注");
                importOne(item, result);
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("工程项目导入失败：" + SafeErrorMessage.ofLogged(e, "工程项目导入"));
        }
    }

    private void importOne(ProjectImportRow row, ImportResult result) {
        String orgName = clean(row.orgName);
        Long orgId;
        if (isBlank(orgName)) {
            orgId = resolveCompanyOrgId();
            orgName = resolveCompanyOrgName();
        } else {
            orgId = resolveOrgId(orgName);
        }
        String code = clean(row.code);
        Long parentId = resolveParentId(clean(row.parentText));

        Long existingId = jdbcTemplate.query("""
                SELECT id FROM md_project WHERE code = ? AND org_id = ? AND deleted = 0 LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, code, orgId);
        if (existingId == null && isBlank(row.code)) {
            existingId = jdbcTemplate.query("""
                    SELECT id FROM md_project WHERE name = ? AND org_id = ? AND deleted = 0 LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, clean(row.name), orgId);
        }
        int status = parseStatus(row.statusText);
        if (existingId == null) {
            // 上面只在「本公司」范围内找同编码的项目；而 md_project.uk_code 是全集团唯一，
            // 编码被别家公司占着时这里找不到、直接 INSERT，撞出来的是一句谁也看不懂的数据库报错。
            // 先探一下，把话说清楚。
            Long occupiedBy = isBlank(code) ? null : jdbcTemplate.query("""
                    SELECT org_id FROM md_project WHERE code = ? AND deleted = 0 LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, code);
            if (occupiedBy != null) {
                throw new BizException("工程项目编码 " + code + " 已被其他公司占用"
                        + "（编码在全集团范围内不能重复），请在导入表里换一个编码，建议带上公司前缀");
            }
            jdbcTemplate.update("""
                    INSERT INTO md_project (parent_id, org_id, code, name, short_name, mnemonic_code, category, status, remark, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, parentId, orgId, code, clean(row.name),
                    nullable(row.shortName), nullable(row.mnemonicCode), clean(row.category), status, nullable(row.remark));
            result.inserted++;
        } else {
            jdbcTemplate.update("""
                    UPDATE md_project SET parent_id = ?, org_id = ?, name = ?, short_name = ?, mnemonic_code = ?, category = ?, status = ?, remark = ?
                    WHERE id = ?
                    """, parentId, orgId, clean(row.name),
                    nullable(row.shortName), nullable(row.mnemonicCode), clean(row.category), status, nullable(row.remark), existingId);
            result.updated++;
        }
    }

    private Long resolveOrgId(String orgName) {
        if (isBlank(orgName)) throw new BizException("工程项目所属组织不能为空");
        Long orgId = jdbcTemplate.query("""
                SELECT id FROM sys_org WHERE deleted = 0 AND status = 1 AND name = ? ORDER BY id LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, orgName);
        if (orgId == null) throw new BizException("所属组织不存在或已停用：" + orgName);
        if (!SecurityUtil.isAdmin()) orgScope.assertOrgVisible(orgId);
        return orgId;
    }

    /** 获取当前用户所属公司（沿 org 树向上追溯 COMPANY 类型节点）的组织 ID。 */
    private Long resolveCompanyOrgId() {
        Long deptId = SecurityUtil.orgId();
        if (deptId == null || deptId == 0) {
            Long fallback = defaultOrgId();
            if (fallback == null) throw new BizException("无法确定当前用户所属公司，请在 Excel 中填写所属组织");
            return fallback;
        }
        Long companyId = walkUpToCompany(deptId);
        return companyId != null ? companyId : defaultOrgId();
    }

    private String resolveCompanyOrgName() {
        Long companyId = resolveCompanyOrgId();
        return jdbcTemplate.query("SELECT name FROM sys_org WHERE id = ? AND deleted = 0 LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : "", companyId);
    }

    private Long walkUpToCompany(Long orgId) {
        Long current = orgId;
        int guard = 0;
        while (current != null && current != 0 && guard < 16) {
            String type = jdbcTemplate.query("SELECT type FROM sys_org WHERE id = ? AND deleted = 0 LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null, current);
            if ("COMPANY".equals(type)) return current;
            Long parentId = jdbcTemplate.query("SELECT parent_id FROM sys_org WHERE id = ? AND deleted = 0 LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, current);
            current = (parentId != null && parentId != 0) ? parentId : null;
            guard++;
        }
        return null;
    }

    private Long defaultOrgId() {
        return jdbcTemplate.query("SELECT id FROM sys_org WHERE deleted = 0 AND status = 1 ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
    }

    private Long resolveParentId(String parentText) {
        if (isBlank(parentText) || "顶级项目".equals(parentText) || "无".equals(parentText) || "0".equals(parentText))
            return 0L;
        Long id = jdbcTemplate.query("""
                SELECT id FROM md_project WHERE (code = ? OR name = ?) AND deleted = 0 ORDER BY id LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, parentText, parentText);
        return id != null ? id : 0L;
    }


    // ---- helpers ----

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int max = Math.min(sheet.getLastRowNum(), 30);
        for (int i = 0; i <= max; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, Integer> headers = headerIndex(row, formatter);
            if ((headers.containsKey("工程项目编码") || headers.containsKey("项目编码") || headers.containsKey("编码"))
                    && (headers.containsKey("工程项目名称") || headers.containsKey("项目名称") || headers.containsKey("名称"))
                    && (headers.containsKey("工程项目类别") || headers.containsKey("项目类别") || headers.containsKey("类别")))
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

    private String nullable(String value) {
        String text = clean(value);
        return isBlank(text) ? null : truncate(text, 120);
    }

    private String clean(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.endsWith(".0") && text.matches("\\d+\\.0")) return text.substring(0, text.length() - 2);
        return text;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class ProjectImportRow {
        private String orgName;
        private String code;
        private String name;
        private String shortName;
        private String mnemonicCode;
        private String category;
        private String parentText;
        private String statusText;
        private String remark;
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
