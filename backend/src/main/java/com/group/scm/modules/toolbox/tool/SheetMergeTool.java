package com.group.scm.modules.toolbox.tool;

import com.group.scm.common.BizException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static com.group.scm.modules.toolbox.tool.ExcelToolSupport.*;

/**
 * 多工作表合并(2026-10-20 工具箱收录):把一个 Excel 里的多张工作表按表头对齐,合成一张。
 *
 * <p><b>为什么收它</b>:各公司、各月份的明细常常一张表一个工作表,做汇总时要一张张复制粘贴,列顺序还不一样。
 * 这里按表头名字对齐(列顺序不同也能对上;某张表多出来的列追加在后面),最前面加一列「来源工作表」,
 * 另附一张"各表行数"方便核对合并前后条数一致。只用已有的 Apache POI,不新增依赖。</p>
 * <p>隐藏的工作表不合并(多半是辅助表);空行跳过;格子里的内容按 Excel 里显示的文字取,数字仍写成数字可求和。</p>
 */
@Component
public class SheetMergeTool implements ToolRunner {

    @Override public String code() { return "sheet-merge"; }
    @Override public String name() { return "多工作表合并"; }
    @Override public String desc() {
        return "把一个 Excel 里各公司/各月的多张工作表按表头对齐合成一张，前面加「来源工作表」列，并列出各表行数便于核对。";
    }
    @Override public boolean needFile() { return true; }
    @Override public String group() { return "表格处理"; }

    @Override
    public List<OutFile> run(String paramsJson, Path outDir, Path inputFile) throws Exception {
        requireFile(inputFile);
        List<String> allHead = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();        // [来源表, Map<表头,值>]
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (var opened = com.group.scm.common.ExcelImportGuard.open(Files.newInputStream(inputFile)); Workbook wb = opened.workbook()) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                if (wb.isSheetHidden(si) || wb.isSheetVeryHidden(si)) continue;
                Sheet sh = wb.getSheetAt(si);
                int hr = headerRow(sh);
                if (hr < 0) { counts.put(sh.getSheetName(), 0); continue; }
                List<String> head = headers(sh.getRow(hr));
                for (String h : head) if (!h.isEmpty() && !allHead.contains(h)) allHead.add(h);
                int n = 0;
                for (int r = hr + 1; r <= sh.getLastRowNum(); r++) {
                    Row row = sh.getRow(r);
                    if (blank(row)) continue;
                    if (rows.size() >= MAX_ROWS) throw new BizException(4000, "合并后超过 " + MAX_ROWS + " 行，请拆成几个文件分别合并");
                    Map<String, Object> vals = new HashMap<>();
                    for (int c = 0; c < head.size(); c++) {
                        if (head.get(c).isEmpty()) continue;
                        Cell cell = row.getCell(c);
                        boolean number = cell != null && cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell);
                        vals.put(head.get(c), number ? (Object) cell.getNumericCellValue() : text(cell));
                    }
                    rows.add(new Object[]{sh.getSheetName(), vals});
                    n++;
                }
                counts.put(sh.getSheetName(), n);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(4000, "读不了这个文件，请确认是 .xlsx 或 .xls 格式的 Excel");
        }
        if (rows.isEmpty()) throw new BizException(4000, "没读到数据：各工作表都没有表头或都是空的");

        String fn = "合并结果-" + LocalDate.now() + ".xlsx";
        Path out = outDir.resolve(fn);
        // 行多时用流式写,内存只留 200 行
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); OutputStream os = Files.newOutputStream(out)) {
            CellStyle hs = headStyle(wb);
            Sheet s1 = wb.createSheet("合并结果");
            Row h = s1.createRow(0);
            put(h, 0, "来源工作表", hs);
            for (int c = 0; c < allHead.size(); c++) put(h, c + 1, allHead.get(c), hs);
            int ri = 1;
            for (Object[] x : rows) {
                Row r = s1.createRow(ri++);
                put(r, 0, x[0], null);
                @SuppressWarnings("unchecked") Map<String, Object> vals = (Map<String, Object>) x[1];
                for (int c = 0; c < allHead.size(); c++) {
                    Object v = vals.get(allHead.get(c));
                    if (v != null && !"".equals(v)) put(r, c + 1, v, null);
                }
            }
            Sheet s2 = wb.createSheet("各表行数");
            Row h2 = s2.createRow(0);
            put(h2, 0, "工作表", hs); put(h2, 1, "数据行数", hs);
            ri = 1;
            for (var e : counts.entrySet()) { Row r = s2.createRow(ri++); put(r, 0, e.getKey(), null); put(r, 1, e.getValue(), null); }
            Row t = s2.createRow(ri);
            put(t, 0, "合计", hs); put(t, 1, rows.size(), hs);
            s2.setColumnWidth(0, 7000);
            wb.write(os);
            wb.dispose();
        }
        return List.of(new OutFile(fn, out.getFileName().toString(), Files.size(out)));
    }
}
