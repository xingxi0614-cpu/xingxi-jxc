package com.group.scm.modules.master.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class MasterImportTemplateService {

    private record TemplateField(String name, boolean required, String note, String example) {}

    public byte[] materialTemplate() {
        return workbook("物料档案导入模板", List.of(
                new TemplateField("物料编码", true, "导入时用于判断新增或更新，必须唯一。", "150001"),
                new TemplateField("物料名称", true, "物料的正式名称。", "不锈钢管"),
                new TemplateField("物料类型", false, "可按企业习惯维护，如主材、辅材、备品备件等；为空不影响导入。", "主材"),
                new TemplateField("规格型号", false, "规格、型号等描述。", "DN50 304"),
                new TemplateField("单位", true, "物料的主计量单位。", "米")
        ));
    }

    public byte[] openingStockTemplate() {
        return workbook("期初库存导入模板", List.of(
                new TemplateField("物料编码", true, "系统里已存在的物料编码（先在基础资料→物料档案维护/导入）。", "150001"),
                new TemplateField("数量", true, "期初结存数量，必须大于 0。", "200"),
                new TemplateField("单价", true, "期初结存单价（不含税成本口径），可为 0。", "15.00")
        ));
    }

    public byte[] projectTemplate() {
        return workbook("工程项目档案导入模板", List.of(
                new TemplateField("所属组织", false, "可为空，空时默认当前用户所在公司。", ""),
                new TemplateField("工程项目编码", true, "用于判断新增还是更新，必须唯一。", "GC-2026-001"),
                new TemplateField("工程项目名称", true, "工程项目的正式名称。", "一号厂房改造工程"),
                new TemplateField("工程项目类别", true, "如生产、工程、维修、改造等。", "改造"),
                new TemplateField("简称", false, "项目简称，用于报表等处展示。", "厂房改造"),
                new TemplateField("助记码", false, "用于快速检索。", "CFGZ"),
                new TemplateField("上级项目", false, "上级项目编码或名称，无上级可留空或填顶级项目。", ""),
                new TemplateField("状态", false, "可填启用或停用，留空默认启用。", "启用"),
                new TemplateField("备注", false, "其他补充说明。", "")
        ));
    }

    public byte[] supplierTemplate() {
        return workbook("供应商档案导入模板", List.of(
                new TemplateField("所属组织", false, "可为空，空时默认为当前用户所属公司。", "示例公司"),
                new TemplateField("供应商编码", false, "可不填；为空时系统自动生成。", "S001"),
                new TemplateField("供应商名称", true, "供应商的正式名称。", "示例集团结算中心"),
                new TemplateField("供应商简称", false, "为空时可使用供应商名称。", "示例集团结算中心"),
                new TemplateField("助记码", false, "用于快速检索。", "SLJT"),
                new TemplateField("供应商基本分类", true, "供应商分类。", "内部单位"),
                new TemplateField("地区分类", false, "地区或区域分类。", "华东"),
                new TemplateField("供应商状态", false, "可填“核准”或“停用”；为空默认核准。", "核准"),
                new TemplateField("是否客户", false, "可填“是”或“否”；为空默认否。", "是"),
                new TemplateField("供应商类型", true, "如内部单位、外部单位。", "内部单位"),
                new TemplateField("对应业务组织", false, "对应的业务归属组织。", "示例集团结算中心")
        ));
    }

    private byte[] workbook(String title, List<TemplateField> fields) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle requiredHeaderStyle = requiredHeaderStyle(workbook);
            CellStyle titleStyle = titleStyle(workbook);

            Sheet input = workbook.createSheet("填写模板");
            Row header = input.createRow(0);
            for (int i = 0; i < fields.size(); i++) {
                TemplateField field = fields.get(i);
                Cell cell = header.createCell(i);
                cell.setCellValue(field.name());
                cell.setCellStyle(field.required() ? requiredHeaderStyle : headerStyle);
                input.setColumnWidth(i, Math.max(14, field.name().length() + 6) * 256);
            }
            input.createFreezePane(0, 1);

            Sheet notes = workbook.createSheet("字段说明");
            Row titleRow = notes.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
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
            notes.setColumnWidth(0, 22 * 256);
            notes.setColumnWidth(1, 12 * 256);
            notes.setColumnWidth(2, 48 * 256);
            notes.setColumnWidth(3, 24 * 256);
            notes.createFreezePane(0, 3);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("生成导入模板失败：" + SafeErrorMessage.ofLogged(e, "基础资料导入模板"));
        }
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
}
