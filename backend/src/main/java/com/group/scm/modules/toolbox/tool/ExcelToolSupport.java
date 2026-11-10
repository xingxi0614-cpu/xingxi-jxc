package com.group.scm.modules.toolbox.tool;

import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「传表格」类工具共用的读写小件(2026-10-20 从查重、合并两个工具里抽出来;账龄分析是更早写的,保持原样不动)。
 * 读文件一律走 ExcelImportGuard(限大小、防压缩炸弹,见 2026-10-18 S08)。
 */
final class ExcelToolSupport {
    private ExcelToolSupport() { }

    /** 一次最多处理多少数据行:再多就不是"小工具"该干的活了,也防止把服务器内存吃满 */
    static final int MAX_ROWS = 100_000;
    private static final DataFormatter FMT = new DataFormatter();

    static void requireFile(Path inputFile) {
        if (inputFile == null || !Files.exists(inputFile)) throw new BizException(4000, "请先上传 Excel 文件");
    }

    /** 单元格的显示文字(和用户在 Excel 里看到的一致:日期按单元格格式、数字不带科学计数) */
    static String text(Cell c) {
        if (c == null) return "";
        try {
            if (c.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c)) {
                return BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            return FMT.formatCellValue(c).trim();
        } catch (Exception e) { return ""; }
    }

    /** 表头行 = 前 10 行里第一行有 ≥2 个非空格子的(第一行常是大标题,只占一格) */
    static int headerRow(Sheet sh) {
        for (int r = sh.getFirstRowNum(); r <= Math.min(sh.getFirstRowNum() + 9, sh.getLastRowNum()); r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
            int filled = 0;
            for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) if (!text(row.getCell(c)).isEmpty()) filled++;
            if (filled >= 2) return r;
        }
        return -1;
    }

    static List<String> headers(Row row) {
        List<String> out = new ArrayList<>();
        if (row == null) return out;
        for (int c = 0; c < row.getLastCellNum(); c++) out.add(text(row.getCell(c)));
        return out;
    }

    static boolean blank(Row row) {
        if (row == null) return true;
        for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) if (!text(row.getCell(c)).isEmpty()) return false;
        return true;
    }

    static void put(Row r, int i, Object v, CellStyle st) {
        Cell c = r.createCell(i);
        if (v instanceof Number n) c.setCellValue(n.doubleValue());
        else c.setCellValue(v == null ? "" : v.toString());
        if (st != null) c.setCellStyle(st);
    }

    static CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }
}
