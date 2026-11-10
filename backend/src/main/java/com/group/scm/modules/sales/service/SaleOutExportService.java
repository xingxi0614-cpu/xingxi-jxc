package com.group.scm.modules.sales.service;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** 从业务表逐行导出，不依赖列表页的摘要或分页；所有范围和成本权限由后端执行。 */
@Service
@RequiredArgsConstructor
public class SaleOutExportService {
    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    static final int MAX_ROWS = 50000;

    public byte[] export(String status, String keyword, Long customerId, LocalDate startDate, LocalDate endDate) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        boolean cost = SecurityUtil.hasFinanceAccessOrAdmin();
        List<Object> args = new ArrayList<>(orgIds);
        List<Map<String, Object>> rows = List.of();
        if (!orgIds.isEmpty()) {
            String scope = orgIds.stream().map(id -> "?").collect(Collectors.joining(","));
            StringBuilder sql = new StringBuilder("""
                    SELECT o.doc_no, o.source_so_no, o.out_date, org.name company, w.name warehouse,
                      COALESCE(NULLIF(o.customer_name,''),o.customer_unit) customer, o.sales_person,
                      o.status, l.line_no, m.code material_code, m.name material_name, m.spec, m.unit,
                      l.qty, l.remark
                    """);
            if (cost) sql.append(", l.unit_price, l.amount ");
            sql.append("""
                    FROM biz_sale_out o JOIN biz_sale_out_line l ON l.out_id=o.id
                    LEFT JOIN md_material m ON m.id=l.material_id
                    LEFT JOIN md_warehouse w ON w.id=o.warehouse_id
                    LEFT JOIN sys_org org ON org.id=o.org_id
                    WHERE o.deleted=0 AND o.org_id IN (
                    """).append(scope).append(") ");
            if (status != null && !status.isBlank()) { sql.append("AND o.status=? "); args.add(status); }
            if (keyword != null && !keyword.isBlank()) {
                List<String> docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                if (docNos != null) {
                    sql.append("AND o.doc_no IN (").append(docNos.stream().map(n -> "?").collect(Collectors.joining(","))).append(") ");
                    args.addAll(docNos);
                } else {
                    sql.append("AND (o.doc_no LIKE ? OR o.customer_name LIKE ? OR o.source_so_no LIKE ?) ");
                    for (int i=0; i<3; i++) args.add("%" + keyword + "%");
                }
            }
            if (customerId != null) { sql.append("AND o.customer_id=? "); args.add(customerId); }
            if (startDate != null) { sql.append("AND o.out_date>=? "); args.add(startDate); }
            if (endDate != null) { sql.append("AND o.out_date<=? "); args.add(endDate); }
            sql.append("ORDER BY o.out_date DESC,o.id DESC,l.line_no,l.id LIMIT ").append(MAX_ROWS+1);
            rows = jdbc.queryForList(sql.toString(), args.toArray());
        }
        if (rows.size() > MAX_ROWS) throw new BizException("明细超过50000行，请缩小日期或关键词范围后导出");
        return workbook(rows, cost);
    }

    byte[] workbook(List<Map<String, Object>> rows, boolean cost) {
        List<String> labels = new ArrayList<>(List.of("公司", "出库单号", "来源订单", "出库日期", "客户", "仓库", "业务员", "状态", "行号", "物料编码", "物料名称", "规格型号", "单位", "出库数量"));
        List<String> keys = new ArrayList<>(List.of("company", "doc_no", "source_so_no", "out_date", "customer", "warehouse", "sales_person", "status", "line_no", "material_code", "material_name", "spec", "unit", "qty"));
        if (cost) { labels.addAll(List.of("成本单价", "成本金额")); keys.addAll(List.of("unit_price", "amount")); }
        labels.add("备注"); keys.add("remark");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("物料单据明细查询");
            CellStyle text = wb.createCellStyle();
            text.setBorderBottom(BorderStyle.THIN); text.setBorderRight(BorderStyle.THIN);
            text.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            text.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            // 🔴 2026-10-15 业务反馈：数量统一用 "#,##0.####" 时，Excel/WPS 把整数显示成 "7."(格式里的小数点没有数字可填)。
            //    整数单独用 "#,##0"，带小数的才用 "#,##0.####"。行号是序号，不要千分位。
            CellStyle number = wb.createCellStyle(); number.cloneStyleFrom(text);
            number.setDataFormat(wb.createDataFormat().getFormat("#,##0.####"));
            CellStyle integer = wb.createCellStyle(); integer.cloneStyleFrom(text);
            integer.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            CellStyle plainInt = wb.createCellStyle(); plainInt.cloneStyleFrom(text);
            plainInt.setDataFormat(wb.createDataFormat().getFormat("0"));
            CellStyle money = wb.createCellStyle(); money.cloneStyleFrom(text);
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle header = wb.createCellStyle(); header.cloneStyleFrom(text);
            header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font=wb.createFont(); font.setBold(true); header.setFont(font);
            Row head=sheet.createRow(0);
            for (int c=0;c<keys.size();c++) {
                Cell cell=head.createCell(c); cell.setCellValue(labels.get(c)); cell.setCellStyle(header);
                sheet.setColumnWidth(c, (Set.of("doc_no","source_so_no","material_name","remark").contains(keys.get(c)) ? 28 : 18)*256);
            }
            Map<String,String> statuses=Map.of("DRAFT","草稿","PENDING","待审批","APPROVED","已审批","REJECTED","已驳回");
            int r=1;
            for (Map<String,Object> record: rows) {
                Row row=sheet.createRow(r++);
                for (int c=0;c<keys.size();c++) {
                    String key=keys.get(c); Object value=record.get(key); Cell cell=row.createCell(c);
                    if (value instanceof Number n) { cell.setCellValue(n.doubleValue()); cell.setCellStyle(numberStyle(key, n, money, number, integer, plainInt)); }
                    else { cell.setCellValue("status".equals(key) ? statuses.getOrDefault(String.valueOf(value), String.valueOf(value)) : Objects.toString(value,"")); cell.setCellStyle(text); }
                }
            }
            sheet.createFreezePane(0,1);
            sheet.setAutoFilter(new CellRangeAddress(0,Math.max(0,r-1),0,keys.size()-1));
            wb.write(bytes); return bytes.toByteArray();
        } catch (IOException e) { throw new BizException("生成Excel失败，请稍后重试"); }
    }

    /** 金额两位；行号纯整数；其余数量/单价：整数不带小数点，有小数的最多 4 位。 */
    static CellStyle numberStyle(String key, Number n, CellStyle money, CellStyle number, CellStyle integer, CellStyle plainInt) {
        if ("amount".equals(key)) return money;
        if ("line_no".equals(key)) return plainInt;
        java.math.BigDecimal v = n instanceof java.math.BigDecimal b ? b : new java.math.BigDecimal(n.toString());
        return v.stripTrailingZeros().scale() <= 0 ? integer : number;
    }
}
