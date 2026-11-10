package com.group.scm.modules.master.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialMasterImportService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    public void ensureMaterialCategorySchema() {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'md_material_cat'
                  AND COLUMN_NAME = 'status'
                """, Integer.class);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute("ALTER TABLE md_material_cat ADD COLUMN status TINYINT NOT NULL DEFAULT 1 AFTER sort");
        }
    }

    public void ensureMaterialOrgSchema() {
        ensureDeletedColumnWidth();
        addMaterialColumnIfMissing("org_id", "ALTER TABLE md_material ADD COLUMN org_id BIGINT NULL AFTER cat_id");
        addMaterialColumnIfMissing("is_shared", "ALTER TABLE md_material ADD COLUMN is_shared TINYINT NOT NULL DEFAULT 1 AFTER org_id");
        addMaterialColumnIfMissing("material_type", "ALTER TABLE md_material ADD COLUMN material_type VARCHAR(40) NULL COMMENT '物料类型' AFTER cat_id");
        ensureMaterialScopeTable();
        Long orgId = defaultOrgId();
        // 演示账套用户绝不做"无主物料抢占"——曾把大批正式物料属主抢成演示账套
        Long demoOrg = jdbcTemplate.queryForList(
                "SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1", String.class)
                .stream().findFirst().map(Long::valueOf).orElse(null);
        if (orgId != null && !orgId.equals(demoOrg)) {
            jdbcTemplate.update("""
                    UPDATE md_material
                    SET org_id = ?
                    WHERE deleted = 0 AND org_id IS NULL
                    """, orgId);
        }
        jdbcTemplate.update("""
                INSERT IGNORE INTO md_material_org_scope (material_id, org_id)
                SELECT id, org_id
                FROM md_material
                WHERE deleted = 0 AND org_id IS NOT NULL
                """);
    }

    public ImportResult importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的物料 Excel 文件");
        ensureMaterialOrgSchema();
        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook workbook = opened.workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new BizException("导入文件没有可读取的工作表");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int headerRowIndex = findHeaderRow(sheet, formatter);
            if (headerRowIndex < 0) throw new BizException("未找到表头：至少需要包含“物料编码”“物料名称”“单位”");

            Row header = sheet.getRow(headerRowIndex);
            Map<String, Integer> headers = headerIndex(header, formatter);
            int codeCol = requireColumn(headers, "物料编码", "编码");
            int nameCol = requireColumn(headers, "物料名称", "名称");
            int levelCol = optionalColumn(headers, "物料等级", "等级");
            int specCol = optionalColumn(headers, "规格型号", "规格");
            int modelCol = optionalColumn(headers, "型号");
            int unitCol = requireColumn(headers, "单位", "主计量单位", "计量单位");
            int materialTypeCol = optionalColumn(headers, "物料类型");
            int taxCol = optionalColumn(headers, "物料税类", "税类", "税率");
            int shortCol = optionalColumn(headers, "物料简称", "简称");

            ImportResult result = new ImportResult();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                MaterialImportRow item = new MaterialImportRow();
                item.setCode(cellText(row, codeCol, formatter));
                item.setName(cellText(row, nameCol, formatter));
                if (isBlank(item.getCode()) && isBlank(item.getName())) continue;
                item.setUnit(cellText(row, unitCol, formatter));
                if (isBlank(item.getCode()) || isBlank(item.getName()) || isBlank(item.getUnit())) {
                    result.addError("第 " + (i + 1) + " 行缺少物料编码、物料名称或单位");
                    result.skipped++;
                    continue;
                }
                item.setMaterialLevel(levelCol >= 0 ? cellText(row, levelCol, formatter) : "");
                item.setMaterialType(materialTypeCol >= 0 ? cellText(row, materialTypeCol, formatter) : "");
                item.setSpec(combineSpec(cellText(row, specCol, formatter), cellText(row, modelCol, formatter)));
                item.setTaxType(taxCol >= 0 ? cellText(row, taxCol, formatter) : "");
                item.setShortName(shortCol >= 0 ? cellText(row, shortCol, formatter) : "");
                importOne(item, result);
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("物料导入失败：" + SafeErrorMessage.ofLogged(e, "物料导入"));
        }
    }

    private void importOne(MaterialImportRow row, ImportResult result) {
        String code = clean(row.getCode());
        String name = clean(row.getName());
        String spec = clean(row.getSpec());
        String unit = defaultUnit(row.getUnit());
        Long orgId = defaultOrgId();
        if (!SecurityUtil.isAdmin()) orgScope.assertOrgVisible(orgId);

        Long existingId = findExistingMaterialId(code, name, spec, unit);
        String remark = buildRemark(row);
        if (existingId == null) {
            jdbcTemplate.update("""
                    INSERT INTO md_material
                      (code, name, spec, unit, cat_id, material_type, org_id, is_shared, default_tax, is_batch, safety_qty, status, remark, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, 0)
                    """, code, name, nullable(spec), unit, null, nullable(row.getMaterialType()),
                    orgId, isGroupOrg(orgId) ? 1 : 0, defaultTax(row.getTaxType()), remark);
            Long materialId = findExistingMaterialId(code, name, spec, unit);
            grantMaterialScope(materialId, orgId);
            result.inserted++;
        } else {
            grantMaterialScope(existingId, orgId);
            result.updated++;
        }
    }

    private Long findExistingMaterialId(String code, String name, String spec, String unit) {
        Long byCode = jdbcTemplate.query("""
                SELECT id
                FROM md_material
                WHERE code = ? AND deleted = 0
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, code);
        if (byCode != null) return byCode;
        return jdbcTemplate.query("""
                SELECT id
                FROM md_material
                WHERE deleted = 0
                  AND name = ?
                  AND COALESCE(spec, '') = ?
                  AND unit = ?
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, name, spec, unit);
    }

    private void grantMaterialScope(Long materialId, Long orgId) {
        if (materialId == null || orgId == null) return;
        jdbcTemplate.update("INSERT IGNORE INTO md_material_org_scope (material_id, org_id) VALUES (?, ?)",
                materialId, orgId);
    }

    private void ensureMaterialScopeTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS md_material_org_scope (
                  material_id BIGINT NOT NULL,
                  org_id BIGINT NOT NULL,
                  PRIMARY KEY (material_id, org_id)
                )
                """);
    }

    private void addMaterialColumnIfMissing(String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'md_material'
                  AND COLUMN_NAME = ?
                """, Integer.class, column);
        if (exists == null || exists == 0) jdbcTemplate.execute(ddl);
    }

    private void ensureDeletedColumnWidth() {
        String dataType = jdbcTemplate.query("""
                SELECT DATA_TYPE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'md_material'
                  AND COLUMN_NAME = 'deleted'
                LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : "");
        if (!"bigint".equalsIgnoreCase(dataType)) {
            jdbcTemplate.execute("ALTER TABLE md_material MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0");
        }
    }

    private Long defaultOrgId() {
        Long currentOrgId = orgScope.currentAccountSetOrgId();
        if (!SecurityUtil.isAdmin() && currentOrgId != null) return currentOrgId;
        // 1) 系统参数显式指定的默认归属组织（管理员可在系统参数页配置 master.default_material_org）
        Long configured = jdbcTemplate.query("""
                SELECT CAST(param_value AS UNSIGNED)
                FROM sys_param
                WHERE param_key = 'master.default_material_org'
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (configured != null && configured > 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0 AND status = 1",
                    Integer.class, configured);
            if (exists != null && exists > 0) return configured;
        }
        // 2) 根组织：type='GROUP' 且 parent_id=0，取 id 最小的（最早创建）
        Long groupId = jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                  AND type = 'GROUP' AND parent_id = 0
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        if (groupId != null) return groupId;
        // 3) 回退：任意 GROUP 类型
        return jdbcTemplate.query("""
                SELECT id
                FROM sys_org
                WHERE deleted = 0 AND status = 1
                ORDER BY CASE WHEN type = 'GROUP' THEN 0 ELSE 1 END, parent_id, sort, id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
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
            if (headers.containsKey("物料编码") && headers.containsKey("物料名称")
                    && (headers.containsKey("单位") || headers.containsKey("主计量单位") || headers.containsKey("计量单位"))) return i;
        }
        return -1;
    }

    private Map<String, Integer> headerIndex(Row row, DataFormatter formatter) {
        Map<String, Integer> map = new LinkedHashMap<>();
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

    private String cellText(Row row, int col, DataFormatter formatter) {
        if (row == null || col < 0) return "";
        Cell cell = row.getCell(col);
        return cell == null ? "" : clean(formatter.formatCellValue(cell));
    }

    private String combineSpec(String spec, String model) {
        spec = clean(spec);
        model = clean(model);
        if (isBlank(model)) return spec;
        if (isBlank(spec)) return model;
        return spec.contains(model) ? spec : spec + " " + model;
    }

    private BigDecimal defaultTax(String taxType) {
        String text = clean(taxType);
        if (text.contains("免")) return BigDecimal.ZERO;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text);
        if (matcher.find()) return new BigDecimal(matcher.group(1));
        return new BigDecimal("13.00");
    }

    private String buildRemark(MaterialImportRow row) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(row.getMaterialLevel())) parts.add("物料等级：" + clean(row.getMaterialLevel()));
        if (!isBlank(row.getMaterialType())) parts.add("物料类型：" + clean(row.getMaterialType()));
        if (!isBlank(row.getTaxType())) parts.add("物料税类：" + clean(row.getTaxType()));
        if (!isBlank(row.getShortName())) parts.add("简称：" + clean(row.getShortName()));
        String remark = String.join("；", parts);
        return remark.length() > 255 ? remark.substring(0, 255) : remark;
    }

    private String defaultUnit(String value) {
        String unit = clean(value);
        return isBlank(unit) ? "个" : truncate(unit, 20);
    }

    private String nullable(String value) {
        String clean = clean(value);
        return isBlank(clean) ? null : truncate(clean, 120);
    }

    private String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String clean(String value) {
        if (value == null) return "";
        String text = stripBom(value).trim();
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

    @Data
    private static class MaterialImportRow {
        private String code;
        private String name;
        private String spec;
        private String unit;
        private String materialLevel;
        private String materialType;
        private String taxType;
        private String shortName;
    }

    @Data
    public static class ImportResult {
        private int inserted;
        private int updated;
        private int skipped;
        private int categoriesCreated;
        private final List<String> errors = new ArrayList<>();

        public void addError(String error) {
            if (errors.size() < 20) errors.add(error);
        }
    }
}
