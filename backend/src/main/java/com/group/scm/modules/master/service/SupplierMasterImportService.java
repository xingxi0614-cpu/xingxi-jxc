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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SupplierMasterImportService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    public void ensureSupplierSchema() {
        ensureDeletedColumnWidth();
        addColumnIfMissing("org_name", "ALTER TABLE md_supplier ADD COLUMN org_name VARCHAR(80) NULL AFTER short_name");
        addColumnIfMissing("org_id", "ALTER TABLE md_supplier ADD COLUMN org_id BIGINT NULL AFTER short_name");
        addColumnIfMissing("is_shared", "ALTER TABLE md_supplier ADD COLUMN is_shared TINYINT NOT NULL DEFAULT 0 AFTER org_name");
        addColumnIfMissing("mnemonic_code", "ALTER TABLE md_supplier ADD COLUMN mnemonic_code VARCHAR(40) NULL AFTER org_name");
        addColumnIfMissing("supplier_category", "ALTER TABLE md_supplier ADD COLUMN supplier_category VARCHAR(80) NULL AFTER mnemonic_code");
        addColumnIfMissing("region_category", "ALTER TABLE md_supplier ADD COLUMN region_category VARCHAR(80) NULL AFTER supplier_category");
        addColumnIfMissing("is_customer", "ALTER TABLE md_supplier ADD COLUMN is_customer TINYINT NOT NULL DEFAULT 0 AFTER region_category");
        addColumnIfMissing("supplier_type", "ALTER TABLE md_supplier ADD COLUMN supplier_type VARCHAR(40) NULL AFTER is_customer");
        addColumnIfMissing("finance_org", "ALTER TABLE md_supplier ADD COLUMN finance_org VARCHAR(80) NULL AFTER supplier_type");
        ensureSupplierScopeTable();
        backfillSupplierOrg();
    }

    public ImportResult importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的供应商 Excel 文件");
        ensureSupplierSchema();
        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook workbook = opened.workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new BizException("导入文件没有可读取的工作表");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) throw new BizException("未找到表头：至少需要包含“所属组织”“供应商名称”“供应商基本分类”“供应商类型”");

            Map<String, Integer> headers = headerIndex(sheet.getRow(headerRowIndex), formatter);
            int orgCol = optionalColumn(headers, "所属组织");
            int codeCol = optionalColumn(headers, "供应商编码", "编码");
            int nameCol = requireColumn(headers, "供应商名称", "名称");
            int categoryCol = requireColumn(headers, "供应商基本分类", "基本分类");
            int typeCol = requireColumn(headers, "供应商类型", "类型");

            ImportResult result = new ImportResult();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                SupplierImportRow item = new SupplierImportRow();
                item.code = cellText(row, codeCol, formatter);
                item.name = cellText(row, nameCol, formatter);
                item.orgName = cellText(row, orgCol, formatter);
                item.supplierCategory = cellText(row, categoryCol, formatter);
                item.supplierType = cellText(row, typeCol, formatter);
                if (isBlank(item.code) && isBlank(item.name) && isBlank(item.supplierCategory)) continue;
                if (isBlank(item.name) || isBlank(item.supplierCategory) || isBlank(item.supplierType)) {
                    result.addError("第 " + (i + 1) + " 行缺少供应商名称、供应商基本分类或供应商类型");
                    result.skipped++;
                    continue;
                }
                item.shortName = value(row, headers, formatter, "供应商简称", "简称");
                item.mnemonicCode = value(row, headers, formatter, "助记码");
                item.regionCategory = value(row, headers, formatter, "地区分类");
                item.statusText = value(row, headers, formatter, "供应商状态", "状态");
                item.isCustomerText = value(row, headers, formatter, "是否客户");
                item.financeOrg = value(row, headers, formatter, "对应财务组织", "财务组织");
                importOne(item, result);
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("供应商导入失败：" + SafeErrorMessage.ofLogged(e, "供应商导入"));
        }
    }

    private void importOne(SupplierImportRow row, ImportResult result) {
        String code = clean(row.code);
        if (isBlank(code)) code = generateNextSupplierCode();
        String orgName = clean(row.orgName);
        boolean useDefaultOrg = isBlank(orgName);
        Long orgId = resolveOrgId(orgName);
        if (useDefaultOrg) orgName = resolveCompanyOrgName();
        Long existingId = findExistingSupplierId(code, clean(row.name));
        Object[] args = {
                clean(row.name),
                nullable(row.shortName),
                orgId,
                nullable(orgName),
                isGroupOrg(orgId) ? 1 : 0,
                nullable(row.mnemonicCode),
                nullable(row.supplierCategory),
                nullable(row.regionCategory),
                parseYes(row.isCustomerText),
                nullable(row.supplierType),
                nullable(row.financeOrg),
                parseStatus(row.statusText)
        };
        if (existingId == null) {
            jdbcTemplate.update("""
                    INSERT INTO md_supplier
                      (code, name, short_name, org_id, org_name, is_shared, mnemonic_code, supplier_category,
                       region_category, is_customer, supplier_type, finance_org, status, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    code, args[0], args[1], args[2], args[3], args[4],
                    args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
            Long supplierId = findExistingSupplierId(code, clean(row.name));
            grantSupplierScope(supplierId, orgId);
            result.inserted++;
        } else {
            grantSupplierScope(existingId, orgId);
            result.updated++;
        }
    }

    private Long findExistingSupplierId(String code, String name) {
        if (!isBlank(code)) {
            Long byCode = jdbcTemplate.query("""
                    SELECT id
                    FROM md_supplier
                    WHERE code = ? AND deleted = 0
                    LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, code);
            if (byCode != null) return byCode;
        }
        if (isBlank(name)) return null;
        return jdbcTemplate.query("""
                SELECT id
                FROM md_supplier
                WHERE name = ? AND deleted = 0
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, name);
    }

    private void grantSupplierScope(Long supplierId, Long orgId) {
        if (supplierId == null || orgId == null) return;
        jdbcTemplate.update("INSERT IGNORE INTO md_supplier_org_scope (supplier_id, org_id) VALUES (?, ?)",
                supplierId, orgId);
    }

    private void ensureSupplierScopeTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS md_supplier_org_scope (
                  supplier_id BIGINT NOT NULL,
                  org_id BIGINT NOT NULL,
                  PRIMARY KEY (supplier_id, org_id)
                )
                """);
    }

    private Long resolveOrgId(String orgName) {
        if (isBlank(orgName)) return resolveCompanyOrgId();
        Long orgId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1 AND name = ?
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, orgName);
        if (orgId == null) throw new BizException("所属组织不存在或已停用：" + orgName);
        if (!SecurityUtil.isAdmin()) orgScope.assertOrgVisible(orgId);
        return orgId;
    }

    private Long resolveCompanyOrgId() {
        Long accountOrgId = orgScope.currentAccountSetOrgId();
        if (!SecurityUtil.isAdmin() && accountOrgId != null) return accountOrgId;
        Long deptId = SecurityUtil.orgId();
        if (deptId == null || deptId == 0) return defaultOrgId();
        Long companyId = walkUpToCompany(deptId);
        return companyId != null ? companyId : defaultOrgId();
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

    private String resolveCompanyOrgName() {
        Long companyId = resolveCompanyOrgId();
        return orgName(companyId);
    }

    private void backfillSupplierOrg() {
        jdbcTemplate.update("""
                UPDATE md_supplier s
                JOIN sys_org o ON o.name = s.org_name AND o.deleted = 0 AND o.status = 1
                SET s.org_id = o.id
                WHERE s.deleted = 0 AND s.org_id IS NULL
                  AND s.org_name IS NOT NULL AND s.org_name <> ''
                """);
        Long fallbackOrgId = defaultOrgId();
        if (fallbackOrgId != null) {
            String fallbackOrgName = orgName(fallbackOrgId);
            jdbcTemplate.update("""
                    UPDATE md_supplier
                    SET org_id = ?,
                        org_name = COALESCE(NULLIF(org_name, ''), ?)
                    WHERE deleted = 0 AND org_id IS NULL
                    """, fallbackOrgId, fallbackOrgName);
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO md_supplier_org_scope (supplier_id, org_id)
                SELECT id, org_id
                FROM md_supplier
                WHERE deleted = 0 AND org_id IS NOT NULL
                """);
    }

    private Long defaultOrgId() {
        Long currentOrgId = orgScope.currentAccountSetOrgId();
        if (!SecurityUtil.isAdmin() && currentOrgId != null) return currentOrgId;
        return jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
    }

    private String orgName(Long orgId) {
        return jdbcTemplate.query("""
                SELECT name
                FROM sys_org
                WHERE id = ? AND deleted = 0
                LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : "", orgId);
    }

    private boolean isGroupOrg(Long orgId) {
        if (orgId == null) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_org
                WHERE id = ? AND deleted = 0 AND status = 1 AND type = 'GROUP'
                """, Integer.class, orgId);
        return count != null && count > 0;
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int max = Math.min(sheet.getLastRowNum(), 30);
        for (int i = 0; i <= max; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, Integer> headers = headerIndex(row, formatter);
            if (headers.containsKey("供应商名称")
                    && (headers.containsKey("供应商基本分类") || headers.containsKey("基本分类"))
                    && (headers.containsKey("供应商类型") || headers.containsKey("类型"))) return i;
        }
        return -1;
    }

    private String generateNextSupplierCode() {
        List<String> codes = jdbcTemplate.queryForList("""
                SELECT code
                FROM md_supplier
                WHERE deleted = 0
                  AND code IS NOT NULL
                  AND code REGEXP '^[A-Za-z]*[0-9]+$'
                """, String.class);
        String bestPrefix = "S";
        int bestWidth = 3;
        long bestNumber = 0;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^([A-Za-z]*)(\\d+)$");
        for (String code : codes) {
            if (code == null) continue;
            java.util.regex.Matcher matcher = pattern.matcher(code.trim());
            if (!matcher.matches()) continue;
            try {
                long number = Long.parseLong(matcher.group(2));
                if (number > bestNumber) {
                    bestNumber = number;
                    bestPrefix = matcher.group(1) == null ? "" : matcher.group(1);
                    bestWidth = Math.max(3, matcher.group(2).length());
                }
            } catch (NumberFormatException ignored) {
                // Ignore invalid historical supplier codes.
            }
        }
        return bestPrefix + String.format("%0" + bestWidth + "d", bestNumber + 1);
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

    private void addColumnIfMissing(String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'md_supplier'
                  AND COLUMN_NAME = ?
                """, Integer.class, column);
        if (exists == null || exists == 0) jdbcTemplate.execute(ddl);
    }

    private void ensureDeletedColumnWidth() {
        String dataType = jdbcTemplate.query("""
                SELECT DATA_TYPE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'md_supplier'
                  AND COLUMN_NAME = 'deleted'
                LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : "");
        if (!"bigint".equalsIgnoreCase(dataType)) {
            jdbcTemplate.execute("ALTER TABLE md_supplier MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0");
        }
    }

    private Integer parseStatus(String value) {
        String text = clean(value);
        if (text.contains("停") || text.contains("禁") || text.contains("冻") || text.equals("0")) return 0;
        return 1;
    }

    private Integer parseYes(String value) {
        String text = clean(value);
        return (text.equals("是") || text.equalsIgnoreCase("Y") || text.equalsIgnoreCase("YES") || text.equals("1")) ? 1 : 0;
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

    private static class SupplierImportRow {
        private String orgName;
        private String code;
        private String name;
        private String shortName;
        private String mnemonicCode;
        private String supplierCategory;
        private String regionCategory;
        private String statusText;
        private String isCustomerText;
        private String supplierType;
        private String financeOrg;
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
