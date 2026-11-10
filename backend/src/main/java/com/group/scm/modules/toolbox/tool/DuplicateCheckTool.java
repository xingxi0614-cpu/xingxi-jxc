package com.group.scm.modules.toolbox.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import static com.group.scm.modules.toolbox.tool.ExcelToolSupport.*;

/**
 * Excel 查重(2026-10-20 工具箱收录):上传一张表,找出某一列里重复出现的值。
 *
 * <p><b>为什么收它</b>:财务审核最常见的漏洞之一是同一张发票报两次、同一笔单据入两次账。
 * 在 Excel 里用条件格式也能标,但要会操作、而且看不出"重复的在第几行";这里一键出清单。
 * 只用项目里已有的 Apache POI(开源,Apache-2.0),不新增依赖。</p>
 * <p><b>按哪一列查</b>:用户填了列名就按它(表头包含这几个字即可);没填就自动找发票号码、单号、编号这类列。
 * 比较前去掉空格、全角半角统一大写,避免"同一个号码多打了个空格"被当成两个。空格子不参与。</p>
 */
@Component
public class DuplicateCheckTool implements ToolRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 自动找列的优先顺序:越靠前越像"不该重复"的号码 */
    private static final List<Pattern> AUTO = List.of(
            Pattern.compile("发票号码|发票号"), Pattern.compile("单据号|单号"), Pattern.compile("凭证号"),
            Pattern.compile("合同号|合同编号"), Pattern.compile("编号|号码|编码"));

    @Override public String code() { return "dup-check"; }
    @Override public String name() { return "Excel 查重"; }
    @Override public String desc() {
        return "上传一张表，找出发票号、单号等某一列里重复出现的值和所在行。防重复报销、重复入账。";
    }
    @Override public boolean needFile() { return true; }
    @Override public String group() { return "审核分析"; }

    @Override
    public List<OutFile> run(String paramsJson, Path outDir, Path inputFile) throws Exception {
        requireFile(inputFile);
        JsonNode p = JSON.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        String want = p.hasNonNull("column") ? p.get("column").asText().trim() : "";

        List<String> head;
        String colName;
        Map<String, List<Integer>> where = new LinkedHashMap<>();
        Map<Integer, List<String>> rowText = new HashMap<>();
        int dataRows = 0;
        try (var opened = com.group.scm.common.ExcelImportGuard.open(Files.newInputStream(inputFile)); Workbook wb = opened.workbook()) {
            Sheet sh = wb.getSheetAt(0);
            int hr = headerRow(sh);
            if (hr < 0) throw new BizException(4000, "在前 10 行里没找到表头，请确认第一张工作表有表头");
            head = headers(sh.getRow(hr));
            int col = pickColumn(head, want);
            colName = head.get(col);
            for (int r = hr + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (blank(row)) continue;
                if (++dataRows > MAX_ROWS) throw new BizException(4000, "数据超过 " + MAX_ROWS + " 行，请拆成几个文件分别查");
                String v = norm(text(row.getCell(col)));
                if (v.isEmpty()) continue;
                where.computeIfAbsent(v, k -> new ArrayList<>()).add(r + 1);   // Excel 里看到的行号从 1 开始
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < head.size(); c++) cells.add(text(row.getCell(c)));
                rowText.put(r + 1, cells);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(4000, "读不了这个文件，请确认是 .xlsx 或 .xls 格式的 Excel");
        }
        where.values().removeIf(rows -> rows.size() < 2);

        String fn = "查重结果-" + LocalDate.now() + ".xlsx";
        Path out = outDir.resolve(fn);
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(out)) {
            CellStyle hs = headStyle(wb);
            Sheet s1 = wb.createSheet("重复项汇总");
            Row h = s1.createRow(0);
            put(h, 0, "查重列：" + colName + "（共 " + dataRows + " 行数据，重复值 " + where.size() + " 个）", hs);
            Row h1 = s1.createRow(1);
            put(h1, 0, colName, hs); put(h1, 1, "出现次数", hs); put(h1, 2, "所在行号(Excel 行号)", hs);
            int ri = 2;
            if (where.isEmpty()) put(s1.createRow(ri), 0, "没有发现重复", null);
            for (var e : where.entrySet()) {
                Row r = s1.createRow(ri++);
                put(r, 0, e.getKey(), null);
                put(r, 1, e.getValue().size(), null);
                put(r, 2, String.join("、", e.getValue().stream().map(String::valueOf).toList()), null);
            }
            s1.setColumnWidth(0, 9000); s1.setColumnWidth(1, 3000); s1.setColumnWidth(2, 9000);

            Sheet s2 = wb.createSheet("重复行明细");
            Row h2 = s2.createRow(0);
            put(h2, 0, "原行号", hs);
            for (int c = 0; c < head.size(); c++) put(h2, c + 1, head.get(c), hs);
            ri = 1;
            for (var e : where.entrySet()) {
                for (int rn : e.getValue()) {
                    Row r = s2.createRow(ri++);
                    put(r, 0, rn, null);
                    List<String> cells = rowText.get(rn);
                    for (int c = 0; c < cells.size(); c++) put(r, c + 1, cells.get(c), null);
                }
            }
            wb.write(os);
        }
        return List.of(new OutFile(fn, out.getFileName().toString(), Files.size(out)));
    }

    static int pickColumn(List<String> head, String want) {
        if (!want.isEmpty()) {
            for (int c = 0; c < head.size(); c++) if (head.get(c).equals(want)) return c;
            for (int c = 0; c < head.size(); c++) if (head.get(c).contains(want)) return c;
            throw new BizException(4000, "表头里没找到「" + want + "」这一列，表头有：" + String.join("、", head.stream().filter(s -> !s.isEmpty()).toList()));
        }
        for (Pattern pt : AUTO) for (int c = 0; c < head.size(); c++) if (pt.matcher(head.get(c)).find()) return c;
        throw new BizException(4000, "没自动认出要查重的列（如发票号码、单号），请在「按哪一列查」里填上列名");
    }

    /** 去掉所有空白、全角转半角、字母统一大写 */
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (Character.isWhitespace(ch) || ch == '　') continue;
            if (ch >= '！' && ch <= '～') ch = (char) (ch - 0xFEE0);
            b.append(Character.toUpperCase(ch));
        }
        return b.toString();
    }
}
