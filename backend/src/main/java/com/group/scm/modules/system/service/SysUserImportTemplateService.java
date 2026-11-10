package com.group.scm.modules.system.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysUserImportTemplateService {

    private final RoleAssignmentService roleAssignment;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    private record TemplateField(String name, boolean required, String note, String example) {}

    public byte[] userTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle requiredHeaderStyle = requiredHeaderStyle(workbook);
            CellStyle titleStyle = titleStyle(workbook);

            List<TemplateField> fields = List.of(
                    new TemplateField("登录账号", true, "必填，唯一；账号已存在时按该账号更新，不存在时新增。只能包含字母、数字、下划线或短横线。", "liming"),
                    new TemplateField("姓名", true, "人员真实姓名。", "李明"),
                    new TemplateField("人员归属", true, "填写组织编码或组织名称；账套管理员只能填写本账套可见组织。", "运营部"),
                    new TemplateField("岗位角色", true, "填写角色编码或角色名称；多个角色用逗号、顿号、斜杠或换行分隔。", "网点客服,仓管"),
                    new TemplateField("初始密码", false, "新增账号必填；更新已有账号时为空则不改密码，填写则重置密码。密码至少 6 位，必须同时包含字母和数字。", "Liming2026"),
                    new TemplateField("手机", false, "选填。", "13800000000"),
                    new TemplateField("邮箱", false, "选填。", "user@example.com"),
                    new TemplateField("状态", false, "可填启用/停用，留空默认启用。", "启用")
            );

            createInputSheet(workbook, fields, headerStyle, requiredHeaderStyle);
            createNotesSheet(workbook, fields, titleStyle, headerStyle);
            createOrgSheet(workbook, headerStyle);
            createRoleSheet(workbook, headerStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("生成用户导入模板失败：" + SafeErrorMessage.ofLogged(e, "用户导入模板"));
        }
    }

    private void createInputSheet(Workbook workbook, List<TemplateField> fields,
                                  CellStyle headerStyle, CellStyle requiredHeaderStyle) {
        Sheet input = workbook.createSheet("填写模板");
        Row header = input.createRow(0);
        for (int i = 0; i < fields.size(); i++) {
            TemplateField field = fields.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(field.name());
            cell.setCellStyle(field.required() ? requiredHeaderStyle : headerStyle);
            input.setColumnWidth(i, Math.max(14, field.name().length() + 8) * 256);
        }
        Row sample = input.createRow(1);
        for (int i = 0; i < fields.size(); i++) {
            sample.createCell(i).setCellValue(fields.get(i).example());
        }
        input.createFreezePane(0, 1);
    }

    private void createNotesSheet(Workbook workbook, List<TemplateField> fields,
                                  CellStyle titleStyle, CellStyle headerStyle) {
        Sheet notes = workbook.createSheet("字段说明");
        Row titleRow = notes.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("用户账号导入模板");
        titleCell.setCellStyle(titleStyle);

        Row noteHeader = notes.createRow(2);
        String[] noteHeaders = { "字段名称", "是否必填", "填写说明", "示例" };
        for (int i = 0; i < noteHeaders.length; i++) {
            Cell cell = noteHeader.createCell(i);
            cell.setCellValue(noteHeaders[i]);
            cell.setCellStyle(headerStyle);
        }
        for (int i = 0; i < fields.size(); i++) {
            TemplateField field = fields.get(i);
            Row row = notes.createRow(i + 3);
            row.createCell(0).setCellValue(field.name());
            row.createCell(1).setCellValue(field.required() ? "必填" : "选填");
            row.createCell(2).setCellValue(field.note());
            row.createCell(3).setCellValue(field.example());
        }
        notes.setColumnWidth(0, 18 * 256);
        notes.setColumnWidth(1, 12 * 256);
        notes.setColumnWidth(2, 66 * 256);
        notes.setColumnWidth(3, 28 * 256);
        notes.createFreezePane(0, 3);
    }

    private void createOrgSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("组织清单");
        Row header = sheet.createRow(0);
        String[] headers = { "组织编码", "组织名称", "组织类型", "上级组织", "状态" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        List<Long> visibleOrgIds = SecurityUtil.isAdmin() ? List.of() : orgScope.visibleOrgIds();
        String scopeSql = SecurityUtil.isAdmin()
                ? ""
                : (visibleOrgIds.isEmpty() ? " AND 1 = 0" : " AND o.id IN (" + placeholders(visibleOrgIds.size()) + ")");
        Object[] args = visibleOrgIds.toArray();
        List<Map<String, Object>> orgs = jdbcTemplate.queryForList("""
                SELECT o.code, o.name, o.type, COALESCE(p.name, '') AS parent_name, o.status
                FROM sys_org o
                LEFT JOIN sys_org p ON p.id = o.parent_id AND p.deleted = 0
                WHERE o.deleted = 0
                %s
                ORDER BY o.parent_id, o.sort, o.id
                """.formatted(scopeSql), args);
        int rowIndex = 1;
        for (Map<String, Object> org : orgs) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(text(org.get("code")));
            row.createCell(1).setCellValue(text(org.get("name")));
            row.createCell(2).setCellValue(text(org.get("type")));
            row.createCell(3).setCellValue(text(org.get("parent_name")));
            row.createCell(4).setCellValue("0".equals(text(org.get("status"))) ? "停用" : "启用");
        }
        for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 20 * 256);
    }

    private void createRoleSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("角色清单");
        Row header = sheet.createRow(0);
        String[] headers = { "角色编码", "角色名称", "数据范围", "备注" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        int rowIndex = 1;
        for (var role : roleAssignment.assignableRoles()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(text(role.getCode()));
            row.createCell(1).setCellValue(text(role.getName()));
            row.createCell(2).setCellValue(text(role.getDataScope()));
            row.createCell(3).setCellValue(text(role.getRemark()));
        }
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 46 * 256);
    }

    private CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle requiredHeaderStyle(Workbook workbook) {
        CellStyle style = headerStyle(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        return style;
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
