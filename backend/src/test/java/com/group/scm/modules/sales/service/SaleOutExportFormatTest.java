package com.group.scm.modules.sales.service;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 销售出库明细导出的数字显示(纯函数,不需要数据库)。
 *
 * <p>由来:2026-10-15 业务反馈导出的出库数量都带个点("7."、行号"1.")。
 * 根因是数量格式写成 "#,##0.####",整数没有小数可填,Excel/WPS 就把小数点留在那儿。
 * 这里既回读显示文字,又直接检查格式代码(整数格子的格式里不许有小数点)——
 * 光看 DataFormatter 的显示测不出这个 bug,已撤回修复实测过。</p>
 */
class SaleOutExportFormatTest {

    @Test
    @DisplayName("整数数量/行号不带小数点,小数数量照常,金额两位")
    void integersHaveNoTrailingDot() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("doc_no", "ABC-XC-20260722-0001");
        row.put("line_no", 1);
        row.put("material_code", "M10003");
        row.put("qty", new BigDecimal("7.0000"));
        row.put("unit_price", new BigDecimal("3.9500"));
        row.put("amount", new BigDecimal("27.65"));
        Map<String, Object> row2 = new LinkedHashMap<>(row);
        row2.put("line_no", 2);
        row2.put("qty", new BigDecimal("24.7000"));
        row2.put("unit_price", new BigDecimal("340.0000"));

        byte[] bytes = new SaleOutExportService(null, null).workbook(List.of(row, row2), true);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row head = sheet.getRow(0);
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int c = 0; c < head.getLastCellNum(); c++) col.put(head.getCell(c).getStringCellValue(), c);

            Row r1 = sheet.getRow(1), r2 = sheet.getRow(2);
            assertEquals("1", fmt.formatCellValue(r1.getCell(col.get("行号"))));
            assertEquals("7", fmt.formatCellValue(r1.getCell(col.get("出库数量"))));
            assertEquals("3.95", fmt.formatCellValue(r1.getCell(col.get("成本单价"))));
            assertEquals("27.65", fmt.formatCellValue(r1.getCell(col.get("成本金额"))));
            assertEquals("24.7", fmt.formatCellValue(r2.getCell(col.get("出库数量"))));
            assertEquals("340", fmt.formatCellValue(r2.getCell(col.get("成本单价"))));
            // ⚠️ POI 的 DataFormatter 把 "#,##0.####" 下的 7 也显示成 "7",和 Excel/WPS("7.")不一样,
            //    只看显示文字测不出这个 bug(撤回修复后上面几条照样过)。所以直接锁格式代码:
            //    整数格子的格式里不许出现小数点,否则 Excel/WPS 就会显示成 "7."。
            assertFalse(formatOf(r1, col.get("出库数量")).contains("."), "整数数量的格式不能带小数点:" + formatOf(r1, col.get("出库数量")));
            assertFalse(formatOf(r1, col.get("行号")).contains("."), "行号格式不能带小数点");
            assertFalse(formatOf(r2, col.get("成本单价")).contains("."), "整数单价的格式不能带小数点");
            // 编码是文本,不能变成数字(否则长编码变科学计数)
            assertEquals("M10003", r1.getCell(col.get("物料编码")).getStringCellValue());
        }
    }

    private static String formatOf(Row row, int col) {
        return row.getCell(col).getCellStyle().getDataFormatString();
    }
}
