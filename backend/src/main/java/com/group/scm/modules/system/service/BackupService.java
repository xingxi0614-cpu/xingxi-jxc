package com.group.scm.modules.system.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * 数据备份/导出：
 * - 完整数据 Excel（当前账套可见范围的 物料/供应商/仓库/现存量/出入库流水，含数量/单价/金额），供本地留底与对接财务软件。
 * - 整库 SQL 备份（mysqldump 现场生成 + gzip，含全部账套，管理员专用，用于技术性整库恢复）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    @Value("${spring.datasource.url}")
    private String dbUrl;
    @Value("${spring.datasource.username}")
    private String dbUser;
    @Value("${spring.datasource.password}")
    private String dbPassword;
    /** mysqldump 可执行文件路径（默认 /usr/bin/mysqldump，可用 scm.mysqldump-path 覆盖） */
    @Value("${scm.mysqldump-path:/usr/bin/mysqldump}")
    private String mysqldumpPath;

    /**
     * 安全审查 S08(2026-10-18):整库/完整数据导出都把文件整个放在内存里,以前同时点几次就同时跑几份,
     * 和 ERP 共用一个进程的内存。现在同一时间各只跑一份(后来的直接提示稍后再试),mysqldump 限时 10 分钟。
     */
    private static final java.util.concurrent.Semaphore EXCEL_SLOT = new java.util.concurrent.Semaphore(1);
    private static final java.util.concurrent.Semaphore SQL_SLOT = new java.util.concurrent.Semaphore(1);
    private static final long DUMP_TIMEOUT_MINUTES = 10;

    public byte[] exportExcel() {
        if (!EXCEL_SLOT.tryAcquire()) throw new BizException("已有完整数据导出正在进行，请稍后再试");
        try {
            return doExportExcel();
        } finally {
            EXCEL_SLOT.release();
        }
    }

    private byte[] doExportExcel() {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds == null || orgIds.isEmpty()) throw new BizException("当前账号没有可备份的账套数据");
        // orgIds 来自服务端按权限计算的 Long 列表，非用户输入，可安全拼接
        String in = orgIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle hs = headerStyle(wb);
            // 2026-10-18(计算审查 U30):档案以前只导"本公司建档"的,集团共用档案(别家建、本公司在用)漏掉了——
            // 现存量、流水里有这个物料,物料档案页却查不到。现在 = 本公司建档 + 授权给本公司的 + 本公司库存/单据实际用到的。
            // 被引用的档案即使后来被删除(deleted=1)也导出,标"已删除",否则流水里的编码在备份里对不上档案。
            sheet(wb, hs, "物料档案",
                    new String[]{"物料编码", "物料名称", "规格型号", "计量单位", "物料类型", "状态", "档案来源"},
                    jdbcTemplate.queryForList("""
                            SELECT m.code,m.name,m.spec,m.unit,m.material_type,
                                   CASE WHEN m.deleted<>0 THEN '已删除' ELSE m.status END status,
                                   CASE WHEN m.org_id IN (%1$s) THEN '本公司建档' ELSE '集团共用(本公司在用)' END src
                            FROM md_material m
                            WHERE (m.deleted=0 AND m.org_id IN (%1$s))
                               OR m.id IN (SELECT material_id FROM md_material_org_scope WHERE org_id IN (%1$s))
                               OR m.id IN (SELECT b.material_id FROM inv_balance b JOIN md_warehouse w ON w.id=b.warehouse_id WHERE w.org_id IN (%1$s))
                               OR m.id IN (SELECT t.material_id FROM inv_transaction t JOIN md_warehouse w ON w.id=t.warehouse_id WHERE w.org_id IN (%1$s))
                               OR m.id IN (SELECT material_id FROM inv_cost_pool WHERE org_id IN (%1$s))
                            ORDER BY m.code
                            """.formatted(in)),
                    new String[]{"code", "name", "spec", "unit", "material_type", "status", "src"});
            // 供应商/客户(共用一张表)同理:本公司建档 + 授权范围 + 本公司采购/销售单据实际用到的
            sheet(wb, hs, "供应商档案",
                    new String[]{"编码", "名称", "简称", "税号", "开户行", "银行账号", "联系人", "联系电话", "地址", "状态", "档案来源"},
                    jdbcTemplate.queryForList("""
                            SELECT s.code,s.name,s.short_name,s.tax_no,s.bank_name,s.bank_account,s.contact_name,s.contact_phone,s.address,
                                   CASE WHEN s.deleted<>0 THEN '已删除' ELSE s.status END status,
                                   CASE WHEN s.org_id IN (%1$s) THEN '本公司建档' ELSE '集团共用(本公司在用)' END src
                            FROM md_supplier s
                            WHERE (s.deleted=0 AND s.org_id IN (%1$s))
                               OR s.id IN (SELECT supplier_id FROM md_supplier_org_scope WHERE org_id IN (%1$s))
                               OR s.id IN (SELECT supplier_id FROM biz_po WHERE org_id IN (%1$s))
                               OR s.id IN (SELECT supplier_id FROM biz_recv WHERE org_id IN (%1$s))
                               OR s.id IN (SELECT supplier_id FROM biz_invoice WHERE org_id IN (%1$s))
                               OR s.id IN (SELECT supplier_id FROM biz_payment WHERE org_id IN (%1$s))
                               OR s.id IN (SELECT customer_id FROM biz_so WHERE org_id IN (%1$s) AND customer_id IS NOT NULL)
                               OR s.id IN (SELECT customer_id FROM biz_receipt WHERE org_id IN (%1$s) AND customer_id IS NOT NULL)
                            ORDER BY s.code
                            """.formatted(in)),
                    new String[]{"code", "name", "short_name", "tax_no", "bank_name", "bank_account", "contact_name", "contact_phone", "address", "status", "src"});
            sheet(wb, hs, "仓库档案",
                    new String[]{"编码", "名称", "类型", "地址", "状态"},
                    jdbcTemplate.queryForList("SELECT code,name,warehouse_type,address,status FROM md_warehouse WHERE deleted=0 AND org_id IN (" + in + ") ORDER BY code"),
                    new String[]{"code", "name", "warehouse_type", "address", "status"});
            sheet(wb, hs, "现存量",
                    new String[]{"物料编码", "物料名称", "规格型号", "计量单位", "仓库", "结存数量", "结存单价", "结存金额"},
                    jdbcTemplate.queryForList("SELECT m.code mat_code,m.name mat_name,m.spec,m.unit,w.name wh_name,b.qty,b.avg_price,b.amount FROM inv_balance b JOIN md_material m ON m.id=b.material_id JOIN md_warehouse w ON w.id=b.warehouse_id WHERE w.org_id IN (" + in + ") ORDER BY m.code,w.name"),
                    new String[]{"mat_code", "mat_name", "spec", "unit", "wh_name", "qty", "avg_price", "amount"});
            // 2026-10-15 存货按公司核算后,金额以公司成本账为准(现存量里的仓库金额是按数量分摊的参考值)
            sheet(wb, hs, "公司成本账",
                    new String[]{"公司", "物料编码", "物料名称", "规格型号", "计量单位", "公司结存数量", "公司结存单价", "公司结存金额"},
                    jdbcTemplate.queryForList("SELECT o.name org_name,m.code mat_code,m.name mat_name,m.spec,m.unit,p.qty,p.avg_price,p.amount FROM inv_cost_pool p JOIN md_material m ON m.id=p.material_id JOIN sys_org o ON o.id=p.org_id WHERE p.org_id IN (" + in + ") ORDER BY o.name,m.code"),
                    new String[]{"org_name", "mat_code", "mat_name", "spec", "unit", "qty", "avg_price", "amount"});
            sheet(wb, hs, "出入库流水",
                    new String[]{"时间", "物料编码", "物料名称", "仓库", "类型", "单据号", "数量", "单价", "金额", "结存数量", "结存金额", "结存单价", "其中尾差", "说明"},
                    jdbcTemplate.queryForList("SELECT t.created_at,m.code mat_code,m.name mat_name,w.name wh_name,t.trans_type,t.biz_doc_no,t.qty,t.unit_price,t.amount,t.bal_qty,t.bal_amount,t.bal_avg_price,t.tail_amt,t.remark FROM inv_transaction t JOIN md_material m ON m.id=t.material_id JOIN md_warehouse w ON w.id=t.warehouse_id WHERE w.org_id IN (" + in + ") ORDER BY t.id"),
                    new String[]{"created_at", "mat_code", "mat_name", "wh_name", "trans_type", "biz_doc_no", "qty", "unit_price", "amount", "bal_qty", "bal_amount", "bal_avg_price", "tail_amt", "remark"});
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出完整数据失败：" + SafeErrorMessage.ofLogged(e, "导出完整数据"));
        }
    }

    /** 现场 mysqldump 整库备份并 gzip（管理员专用，含全部账套数据） */
    public byte[] exportSqlGz() {
        if (!SQL_SLOT.tryAcquire()) throw new BizException("已有整库备份正在进行，请稍后再试");
        try {
            return doExportSqlGz();
        } finally {
            SQL_SLOT.release();
        }
    }

    private byte[] doExportSqlGz() {
        String[] hpd = parseJdbcUrl(dbUrl);
        File errFile = null;
        try {
            errFile = File.createTempFile("mysqldump-err", ".log");
            ProcessBuilder pb = new ProcessBuilder(
                    mysqldumpPath, "--single-transaction", "--no-tablespaces", "--default-character-set=utf8mb4",
                    "-h", hpd[0], "-P", hpd[1], "-u", dbUser, hpd[2]);
            pb.environment().put("MYSQL_PWD", dbPassword == null ? "" : dbPassword);
            pb.redirectError(errFile); // stderr 转文件，避免与 stdout 抢缓冲导致死锁
            Process p = pb.start();
            // 超时看门狗:mysqldump 卡住(锁等待、网络)时 10 分钟后强制结束,读取端随之结束,不会无限占着线程和内存
            Process proc = p;
            var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            watchdog.schedule(proc::destroyForcibly, DUMP_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            int code;
            try {
                try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
                    p.getInputStream().transferTo(g);
                }
                if (!p.waitFor(1, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly();
                code = p.isAlive() ? -1 : p.exitValue();
            } finally {
                watchdog.shutdownNow();
            }
            if (code != 0) {
                // 安全审查 S06 残留:mysqldump 的错误输出可能带数据库地址、账号,只写服务器日志,不回给页面
                String err = Files.readString(errFile.toPath());
                log.error("整库备份失败,mysqldump 退出码 {}:{}", code, err.length() > 2000 ? err.substring(0, 2000) : err.trim());
                throw new BizException("整库备份失败（退出码 " + code + "），详情已记入服务器日志，请联系管理员");
            }
            return gz.toByteArray();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("整库备份失败：" + SafeErrorMessage.ofLogged(e, "整库备份"));
        } finally {
            if (errFile != null) errFile.delete();
        }
    }

    private void sheet(Workbook wb, CellStyle hs, String name, String[] headers, List<Map<String, Object>> rows, String[] keys) {
        Sheet s = wb.createSheet(name);
        Row h = s.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(hs);
            s.setColumnWidth(i, 18 * 256);
        }
        int r = 1;
        for (Map<String, Object> row : rows) {
            Row rr = s.createRow(r++);
            for (int i = 0; i < keys.length; i++) {
                Object v = row.get(keys[i]);
                // 2026-10-15:数量/金额原来一律写成文本(如 "7.0000"),Excel 里不能求和。
                // 数据库里是数值类型的按数字写(常规格式,整数不带小数点);编码、账号等字符列仍是文本,前导 0 不丢。
                if (v instanceof Number n) rr.createCell(i).setCellValue(n.doubleValue());
                else rr.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
            }
        }
        s.createFreezePane(0, 1);
    }

    private CellStyle headerStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        st.setAlignment(HorizontalAlignment.CENTER);
        return st;
    }

    /** jdbc:mysql://host:port/db?... → [host, port, db] */
    private String[] parseJdbcUrl(String url) {
        String s = url.substring(url.indexOf("//") + 2);
        int slash = s.indexOf('/');
        String hostPort = s.substring(0, slash);
        String db = s.substring(slash + 1);
        int q = db.indexOf('?');
        if (q >= 0) db = db.substring(0, q);
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        String port = hostPort.contains(":") ? hostPort.substring(hostPort.indexOf(':') + 1) : "3306";
        return new String[]{host, port, db};
    }
}
