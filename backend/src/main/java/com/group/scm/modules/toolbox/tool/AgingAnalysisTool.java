package com.group.scm.modules.toolbox.tool;

import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 账龄分析:上传应收/应付明细,自动分账龄区间并汇总。
 *
 * 【怎么用】上传一个 Excel,里面要有【日期】和【金额】两列(表头包含"日期"和"金额"即可,
 * 大小写、前后缀都不管)。有"客户""供应商""单位"这类列会一并按往来单位汇总。
 *
 * 【为什么按表头找列,不让用户选】财务导出来的表列名五花八门但都带这几个字;
 * 让用户在界面上一列列指认反而更麻烦、更容易指错。找不到就明确告诉他缺哪一列 —— 不猜、不瞎跑。
 *
 * 【账龄区间】30天内 / 31-60 / 61-90 / 91-180 / 181-365 / 1年以上,与常见审计口径一致。
 */
@Component
public class AgingAnalysisTool implements ToolRunner {

    private static final String[] BUCKETS = {"30天内", "31-60天", "61-90天", "91-180天", "181-365天", "1年以上"};

    @Override public String code() { return "aging"; }
    @Override public String name() { return "账龄分析表"; }
    @Override public String desc() {
        return "上传应收/应付明细(要有「日期」和「金额」两列),自动分账龄区间汇总,出 Excel。审计、催款、对账都用得上。";
    }
    @Override public boolean needFile() { return true; }
    @Override public String group() { return "审核分析"; }

    @Override
    public List<OutFile> run(String paramsJson, Path outDir, Path inputFile) throws Exception {
        if (inputFile == null || !Files.exists(inputFile)) {
            throw new BizException(4000, "请先上传要分析的 Excel 文件");
        }
        LocalDate base = LocalDate.now();   // 以今天为基准日算账龄

        List<Row2> rows = new ArrayList<>();
        try (var opened = com.group.scm.common.ExcelImportGuard.open(Files.newInputStream(inputFile)); Workbook wb = opened.workbook()) {
            Sheet sh = wb.getSheetAt(0);
            if (sh == null) throw new BizException(4000, "这个 Excel 里没有工作表");

            // 找表头行:前 10 行里同时含"日期"和"金额"的那一行
            int headRow = -1, colDate = -1, colAmt = -1, colParty = -1;
            for (int r = 0; r <= Math.min(9, sh.getLastRowNum()); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                int d = -1, a = -1, pt = -1;
                for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                    String t = str(row.getCell(c));
                    if (t.isEmpty()) continue;
                    if (d < 0 && t.contains("日期")) d = c;
                    if (a < 0 && (t.contains("金额") || t.contains("余额"))) a = c;
                    if (pt < 0 && (t.contains("客户") || t.contains("供应商") || t.contains("往来") || t.contains("单位"))) pt = c;
                }
                if (d >= 0 && a >= 0) { headRow = r; colDate = d; colAmt = a; colParty = pt; break; }
            }
            if (headRow < 0) {
                throw new BizException(4000, "在前 10 行里没找到表头 —— 这张表需要有「日期」和「金额」(或「余额」)两列,请检查后重新上传");
            }

            for (int r = headRow + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                LocalDate d = date(row.getCell(colDate));
                BigDecimal amt = num(row.getCell(colAmt));
                if (d == null || amt == null || amt.signum() == 0) continue;   // 空行/无效行跳过,不报错
                String party = colParty >= 0 ? str(row.getCell(colParty)) : "";
                rows.add(new Row2(d, amt, party.isEmpty() ? "(未填)" : party));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(4000, "读不了这个文件,请确认是 .xlsx 或 .xls 格式的 Excel");
        }
        if (rows.isEmpty()) throw new BizException(4000, "没读到有效数据行(日期和金额都要有值)");

        // 汇总
        BigDecimal[] total = new BigDecimal[BUCKETS.length];
        int[] cnt = new int[BUCKETS.length];
        Arrays.fill(total, BigDecimal.ZERO);
        Map<String, BigDecimal[]> byParty = new LinkedHashMap<>();
        for (Row2 x : rows) {
            int b = bucket(ChronoUnit.DAYS.between(x.date, base));
            total[b] = total[b].add(x.amt);
            cnt[b]++;
            BigDecimal[] pr = byParty.computeIfAbsent(x.party, k -> newRow());
            pr[b] = pr[b].add(x.amt);
        }

        Path out = outDir.resolve("账龄分析-" + base + ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(out)) {
            CellStyle head = headStyle(wb);
            CellStyle money = moneyStyle(wb);

            Sheet s1 = wb.createSheet("账龄汇总");
            Row h0 = s1.createRow(0);
            cell(h0, 0, "账龄区间", head); cell(h0, 1, "笔数", head); cell(h0, 2, "金额", head); cell(h0, 3, "占比", head);
            BigDecimal grand = Arrays.stream(total).reduce(BigDecimal.ZERO, BigDecimal::add);
            for (int i = 0; i < BUCKETS.length; i++) {
                Row r = s1.createRow(i + 1);
                cell(r, 0, BUCKETS[i], null);
                numCell(r, 1, BigDecimal.valueOf(cnt[i]), null);
                numCell(r, 2, total[i], money);
                cell(r, 3, grand.signum() == 0 ? "0%"
                        : total[i].multiply(BigDecimal.valueOf(100)).divide(grand, 1, RoundingMode.HALF_UP) + "%", null);
            }
            Row rt = s1.createRow(BUCKETS.length + 1);
            cell(rt, 0, "合计", head);
            numCell(rt, 1, BigDecimal.valueOf(rows.size()), head);
            numCell(rt, 2, grand, head);
            for (int i = 0; i < 4; i++) s1.setColumnWidth(i, 4200);

            Sheet s2 = wb.createSheet("按往来单位");
            Row h1 = s2.createRow(0);
            cell(h1, 0, "往来单位", head);
            for (int i = 0; i < BUCKETS.length; i++) cell(h1, i + 1, BUCKETS[i], head);
            cell(h1, BUCKETS.length + 1, "合计", head);
            int ri = 1;
            for (Map.Entry<String, BigDecimal[]> e : byParty.entrySet()) {
                Row r = s2.createRow(ri++);
                cell(r, 0, e.getKey(), null);
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < BUCKETS.length; i++) { numCell(r, i + 1, e.getValue()[i], money); sum = sum.add(e.getValue()[i]); }
                numCell(r, BUCKETS.length + 1, sum, money);
            }
            s2.setColumnWidth(0, 8000);
            for (int i = 1; i <= BUCKETS.length + 1; i++) s2.setColumnWidth(i, 4000);

            wb.write(os);
        }
        return List.of(new OutFile("账龄分析-" + base + ".xlsx", out.getFileName().toString(), Files.size(out)));
    }

    private static int bucket(long days) {
        if (days <= 30) return 0;
        if (days <= 60) return 1;
        if (days <= 90) return 2;
        if (days <= 180) return 3;
        if (days <= 365) return 4;
        return 5;
    }

    private static BigDecimal[] newRow() {
        BigDecimal[] a = new BigDecimal[BUCKETS.length];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private record Row2(LocalDate date, BigDecimal amt, String party) { }

    /* ── Excel 读写小工具 ── */

    private static String str(Cell c) {
        if (c == null) return "";
        try {
            if (c.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(c)) return "";
                return BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            return c.getStringCellValue().trim();
        } catch (Exception e) { return ""; }
    }

    private static BigDecimal num(Cell c) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
            String s = c.getStringCellValue().replace(",", "").replace("，", "").trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (Exception e) { return null; }
    }

    private static LocalDate date(Cell c) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String s = c.getStringCellValue().trim().replace('/', '-').replace('.', '-');
            if (s.length() >= 8) return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception ignored) { }
        return null;
    }

    private static void cell(Row r, int i, String v, CellStyle st) {
        Cell c = r.createCell(i);
        c.setCellValue(v);
        if (st != null) c.setCellStyle(st);
    }

    private static void numCell(Row r, int i, BigDecimal v, CellStyle st) {
        Cell c = r.createCell(i);
        c.setCellValue(v.doubleValue());
        if (st != null) c.setCellStyle(st);
    }

    private static CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private static CellStyle moneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }
}
