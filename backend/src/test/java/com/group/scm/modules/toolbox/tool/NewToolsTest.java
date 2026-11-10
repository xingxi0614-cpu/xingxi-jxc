package com.group.scm.modules.toolbox.tool;

import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-20 工具箱新收录的三个工具:证件号码校验 / Excel 查重 / 多工作表合并。
 * 校验位算法的正例号码已用独立脚本(Python)按国家标准复算过,不是拿本类算法自证。
 */
class NewToolsTest {

    private final IdCodeCheckTool id = new IdCodeCheckTool();

    @Test
    void creditCodeValidAndInvalid() throws Exception {
        assertNull(IdCodeCheckTool.creditCodeError("91350100M000100Y43"));
        assertNull(IdCodeCheckTool.creditCodeError("91110000802100433B"));
        assertTrue(IdCodeCheckTool.creditCodeError("911100001000013680").contains("校验位不对"));
        assertTrue(IdCodeCheckTool.creditCodeError("91350100M000100I43").contains("不是信用代码能用的字符"));
        Map<String, Object> r = id.runInstant("{\"code\":\"9135 0100-m000 100Y43\"}");   // 带空格、横杠、小写也认
        assertEquals("统一社会信用代码", r.get("号码类型"));
        assertEquals("✓ 校验通过", r.get("结论"));
        assertEquals("企业", r.get("机构类别"));
    }

    @Test
    void idCardValidAndInvalid() throws Exception {
        assertNull(IdCodeCheckTool.idCardError("11010519491231002X"));
        Map<String, Object> ok = id.runInstant("{\"code\":\"11010519491231002x\"}");
        assertEquals("居民身份证号", ok.get("号码类型"));
        assertEquals("1949-12-31", ok.get("出生日期"));
        assertEquals("女", ok.get("性别"));
        Map<String, Object> bad = id.runInstant("{\"code\":\"110105194912310021\"}");
        assertTrue(String.valueOf(bad.get("结论")).contains("应是「X」"));
        assertTrue(IdCodeCheckTool.idCardError("110105194913310021").contains("出生日期"));
        assertThrows(BizException.class, () -> id.runInstant("{\"code\":\"12345\"}"));
    }

    @Test
    void dupCheckFindsDuplicatesIgnoringSpacesAndWidth(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in.xlsx");
        write(in, wb -> {
            Sheet s = wb.createSheet("明细");
            s.createRow(0).createCell(0).setCellValue("报销明细");                  // 大标题行,只占一格
            row(s, 1, "序号", "发票号码", "金额");
            row(s, 2, "1", "0441 2345", "100");
            row(s, 3, "2", "04412346", "200");
            row(s, 4, "3", "０４４１２３４５", "100");                               // 全角,和第 3 行是同一张票
        });
        var out = new DuplicateCheckTool().run("{}", dir, in);
        assertEquals(1, out.size());
        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(dir.resolve(out.get(0).file())))) {
            Row r = wb.getSheet("重复项汇总").getRow(2);
            assertEquals("04412345", r.getCell(0).getStringCellValue());
            assertEquals(2, (int) r.getCell(1).getNumericCellValue());
            assertEquals("3、5", r.getCell(2).getStringCellValue());
            assertEquals(3, wb.getSheet("重复行明细").getLastRowNum() + 1);           // 表头 + 2 行
        }
        assertThrows(BizException.class, () -> new DuplicateCheckTool().run("{\"column\":\"不存在的列\"}", dir, in));
    }

    @Test
    void sheetMergeAlignsByHeader(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in.xlsx");
        write(in, wb -> {
            Sheet a = wb.createSheet("一分公司");
            row(a, 0, "物料", "数量");
            row(a, 1, "水表", "3");
            Sheet b = wb.createSheet("二分公司");
            row(b, 0, "数量", "物料", "备注");                                       // 列顺序不同、多一列
            Row r = b.createRow(1); r.createCell(0).setCellValue(5); r.createCell(1).setCellValue("阀门"); r.createCell(2).setCellValue("急用");
            wb.setSheetHidden(wb.getSheetIndex(wb.createSheet("辅助")), true);
        });
        var out = new SheetMergeTool().run("{}", dir, in);
        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(dir.resolve(out.get(0).file())))) {
            Sheet s = wb.getSheet("合并结果");
            assertEquals(List.of("来源工作表", "物料", "数量", "备注"),
                    List.of(s.getRow(0).getCell(0).getStringCellValue(), s.getRow(0).getCell(1).getStringCellValue(),
                            s.getRow(0).getCell(2).getStringCellValue(), s.getRow(0).getCell(3).getStringCellValue()));
            assertEquals("二分公司", s.getRow(2).getCell(0).getStringCellValue());
            assertEquals("阀门", s.getRow(2).getCell(1).getStringCellValue());
            assertEquals(5.0, s.getRow(2).getCell(2).getNumericCellValue());          // 数字仍是数字
            assertEquals(2, (int) wb.getSheet("各表行数").getRow(3).getCell(1).getNumericCellValue());   // 合计,隐藏表不算
        }
    }

    private interface Filler { void fill(Workbook wb) throws Exception; }

    private static void write(Path p, Filler f) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(p)) { f.fill(wb); wb.write(os); }
    }

    private static void row(Sheet s, int i, String... v) {
        Row r = s.createRow(i);
        for (int c = 0; c < v.length; c++) r.createCell(c).setCellValue(v[c]);
    }
}
