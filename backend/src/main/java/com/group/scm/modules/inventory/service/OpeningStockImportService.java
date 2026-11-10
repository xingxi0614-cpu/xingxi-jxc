package com.group.scm.modules.inventory.service;

import com.group.scm.common.SafeErrorMessage;

import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 期初库存 Excel 导入（新账套开账用）。
 *
 * <p>列：物料编码(必填) / 数量(必填>0) / 单价(必填≥0，不含税成本口径)。
 * 仓库在页面上选定后整表导入。每行走 {@link WaInventoryService#inbound} 记 INIT 流水，
 * 与手工期初同一套加权平均逻辑，可在收发流水中溯源。</p>
 *
 * <p>防呆：该物料在该仓已有非零结存时跳过该行（避免重复导入把期初叠加翻倍），
 * 需要调整时走盘点/库存调整，不要重复导。</p>
 */
@Service
@RequiredArgsConstructor
public class OpeningStockImportService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    private final WaInventoryService waService;
    private final PeriodCloseService periodCloseService;

    /*
     * 🔴 2026-10-18(计算审查 U12):两个页面/两个管理员同时导同一份期初,以前两边都先查到"该仓零结存",
     *    再各自入账,库存数量和金额翻倍。现在:整个文件一个事务 + 先拿公司成本锁(与过账同一把锁)
     *    + READ_COMMITTED(排到锁之后读到对方刚提交的结存),后到的那次逐行判为"已有结存"跳过。
     *    整个文件一个事务也意味着中途出异常整批回滚,不再留下"前半截已入账"的残局。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ImportResult importExcel(Long warehouseId, MultipartFile file) {
        if (warehouseId == null) throw new BizException("请选择要导入期初的仓库");
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的期初库存 Excel 文件");
        orgScope.assertWarehouseVisible(warehouseId);
        List<Map<String, Object>> whs = jdbc.queryForList(
                "SELECT org_id, name FROM md_warehouse WHERE id = ? AND deleted = 0", warehouseId);
        if (whs.isEmpty()) throw new BizException("仓库不存在");
        Long orgId = ((Number) whs.get(0).get("org_id")).longValue();
        waService.lockCompanyCostByWarehouse(warehouseId);
        periodCloseService.assertPeriodOpen(LocalDate.now(), orgId, "期初库存导入", "OPENING-IMPORT");

        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook workbook = opened.workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new BizException("导入文件没有可读取的工作表");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);

            int headerRowIndex = -1;
            Map<String, Integer> headers = new HashMap<>();
            for (int i = sheet.getFirstRowNum(); i <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 10); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, Integer> idx = new HashMap<>();
                for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
                    String text = clean(formatter.formatCellValue(row.getCell(c)));
                    if (!text.isEmpty()) idx.put(text, c);
                }
                if (idx.containsKey("物料编码") && idx.containsKey("数量")) { headerRowIndex = i; headers = idx; break; }
            }
            if (headerRowIndex < 0) throw new BizException("未找到表头：需要包含「物料编码」「数量」「单价」三列（先下载模板）");
            int codeCol = headers.get("物料编码");
            int qtyCol = headers.get("数量");
            Integer priceColBoxed = headers.getOrDefault("单价", headers.get("单价(不含税)"));
            if (priceColBoxed == null) throw new BizException("未找到「单价」列（期初结存单价，不含税成本口径）");
            int priceCol = priceColBoxed;

            ImportResult result = new ImportResult();
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String code = clean(formatter.formatCellValue(row.getCell(codeCol)));
                String qtyText = clean(formatter.formatCellValue(row.getCell(qtyCol)));
                String priceText = clean(formatter.formatCellValue(row.getCell(priceCol)));
                if (code.isEmpty() && qtyText.isEmpty()) continue;
                int rowNo = i + 1;
                if (code.isEmpty()) { result.fail(rowNo, "物料编码为空"); continue; }
                BigDecimal qty = parseNumber(qtyText);
                BigDecimal price = parseNumber(priceText);
                if (qty == null || qty.signum() <= 0) { result.fail(rowNo, "数量必须是大于 0 的数字"); continue; }
                // 计算审查 U29:数量列只存 4 位小数,超出会被库里截成 0、金额却照记,形成"数量 0 金额非 0"
                if (qty.stripTrailingZeros().scale() > 4) { result.fail(rowNo, "数量最多保留 4 位小数（当前 " + qty.toPlainString() + "）"); continue; }
                if (price == null || price.signum() < 0) { result.fail(rowNo, "单价必须是不小于 0 的数字"); continue; }

                // 集团池模型：物料全库可选，只要求存在且启用
                List<Map<String, Object>> mats = jdbc.queryForList(
                        "SELECT m.id FROM md_material m WHERE m.code = ? AND m.deleted = 0 AND m.status = 1",
                        code);
                if (mats.isEmpty()) { result.fail(rowNo, "物料编码「" + code + "」不存在或已停用"); continue; }
                Long matId = ((Number) mats.get(0).get("id")).longValue();

                List<Map<String, Object>> bal = jdbc.queryForList(
                        "SELECT qty FROM inv_balance WHERE material_id = ? AND warehouse_id = ?", matId, warehouseId);
                if (!bal.isEmpty() && ((BigDecimal) bal.get(0).get("qty")).signum() != 0) {
                    result.fail(rowNo, "物料「" + code + "」在该仓已有结存，已跳过（如需调整请用库存调整，勿重复导期初）");
                    continue;
                }

                waService.inbound(matId, warehouseId, qty, price,
                        "INIT", "OPENING", null, "期初导入", null, "期初库存导入(Excel)");
                result.inserted++;
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("期初库存导入失败：" + SafeErrorMessage.ofLogged(e, "期初库存导入"));
        }
    }

    private BigDecimal parseNumber(String text) {
        if (text == null || text.isEmpty()) return null;
        try { return new BigDecimal(text.replace(",", "").replace("，", "")); }
        catch (Exception e) { return null; }
    }

    private String clean(String value) {
        if (value == null) return "";
        String text = value.startsWith("\uFEFF") ? value.substring(1) : value;
        return text.trim();
    }

    @Data
    public static class ImportResult {
        private int inserted;
        private int skipped;
        private final List<String> errors = new ArrayList<>();

        void fail(int rowNo, String msg) {
            skipped++;
            if (errors.size() < 20) errors.add("第 " + rowNo + " 行：" + msg);
        }
    }
}
