package com.group.scm.modules.inventory.service;

import com.group.scm.common.SafeErrorMessage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.notif.service.NotifService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.entity.BizStocktake;
import com.group.scm.modules.inventory.entity.BizStocktakeLine;
import com.group.scm.modules.inventory.mapper.BizStocktakeLineMapper;
import com.group.scm.modules.inventory.mapper.BizStocktakeMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 盘点单：仓管建单(快照该仓有结存物料的账面数/结存单价) → 打印盘点表(可盲盘)线下清点
 * → 录入或 Excel 导入实盘数 → 提交 → 财务确认(记名) → 按快照单价自动生成
 * 盘盈(ADJUST 入库)/盘亏(ADJUST 出库)，库存与流水可溯源。实盘数留空的行视为未盘、不调整。
 */
@Service
@RequiredArgsConstructor
public class StocktakeService {

    private final BizStocktakeMapper mapper;
    private final BizStocktakeLineMapper lineMapper;
    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;
    private final WaInventoryService waService;
    private final PeriodCloseService periodCloseService;
    private final DocNoService docNoService;
    private final NotifService notifService;

    public IPage<BizStocktake> page(long current, long size, String status, Long warehouseId, String keyword,
                                    String creatorName, java.time.LocalDate startDate, java.time.LocalDate endDate,
                                    String sortField, String sortOrder) {
        QueryWrapper<BizStocktake> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (warehouseId != null) qw.eq("warehouse_id", warehouseId);
        var docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        if (docNos != null) qw.in("doc_no", docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        // 2026-10-16 查询条件要覆盖表格列:盘点日期、制单人(按姓名模糊,参数化防注入)
        if (startDate != null) qw.ge("take_date", startDate);
        if (endDate != null) qw.le("take_date", endDate);
        if (creatorName != null && !creatorName.isBlank()) {
            qw.exists("SELECT 1 FROM sys_user u WHERE u.id = biz_stocktake.created_by AND u.name LIKE {0}", "%" + creatorName.trim() + "%");
        }
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizStocktake.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizStocktake> page = mapper.selectPage(new Page<>(current, size), qw);
        enrichHeads(page.getRecords());
        return page;
    }

    public BizStocktake detail(Long id) {
        BizStocktake h = require(id);
        enrichHeads(List.of(h));
        List<BizStocktakeLine> lines = lineMapper.selectList(
                new QueryWrapper<BizStocktakeLine>().eq("stocktake_id", id).orderByAsc("line_no"));
        enrichLines(lines);
        h.setLines(lines);
        return h;
    }

    /** 建单：快照仓库当前有结存物料的账面数与结存单价。 */
    @Transactional(rollbackFor = Exception.class)
    public BizStocktake create(Long warehouseId, LocalDate takeDate, boolean includeZero, String remark) {
        if (warehouseId == null) throw new BizException("请选择盘点仓库");
        // 先用当前读取仓库归属，再锁组织；普通 SELECT 会在等月结锁之前建立旧快照，
        // 等月结提交后仍可能误判为未结账。鉴权紧随锁后执行，事务失败即释放锁。
        // 按公司核算后全站加锁顺序 = 公司成本锁 → 仓库 → 组织(月结锁);先拿公司成本锁,避免与过账互锁
        waService.lockCompanyCostByWarehouse(warehouseId);
        Long orgId = jdbc.queryForList("SELECT org_id FROM md_warehouse WHERE id = ? AND deleted = 0 FOR UPDATE", Long.class, warehouseId)
                .stream().findFirst().orElseThrow(() -> new BizException("仓库不存在"));
        periodCloseService.lockPeriodShared(orgId);
        orgScope.assertWarehouseVisible(warehouseId);
        waService.assertWarehouseMovementAllowed(warehouseId, "STOCKTAKE", null);
        LocalDate date = takeDate != null ? takeDate : LocalDate.now();
        periodCloseService.assertPeriodOpen(date, orgId, "盘点单", "STOCKTAKE");

        BizStocktake h = new BizStocktake();
        h.setDocNo(docNoService.next("STOCKTAKE"));
        h.setOrgId(orgId);
        h.setWarehouseId(warehouseId);
        h.setTakeDate(date);
        h.setStatus("DRAFT");
        h.setRemark(remark);
        h.setGainAmt(BigDecimal.ZERO);
        h.setLossAmt(BigDecimal.ZERO);
        mapper.insert(h);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.material_id, b.qty, b.avg_price FROM inv_balance b "
              + "JOIN md_material m ON m.id = b.material_id AND m.deleted = 0 "
              + "WHERE b.warehouse_id = ?" + (includeZero ? "" : " AND b.qty <> 0")
              + " ORDER BY m.code FOR UPDATE", warehouseId);
        if (rows.isEmpty()) throw new BizException("该仓库没有可盘点的结存物料" + (includeZero ? "" : "（可勾选「包含零结存」重试）"));
        int no = 1;
        for (Map<String, Object> r : rows) {
            BizStocktakeLine ln = new BizStocktakeLine();
            ln.setStocktakeId(h.getId());
            ln.setLineNo(no++);
            ln.setMaterialId(((Number) r.get("material_id")).longValue());
            ln.setBookQty((BigDecimal) r.get("qty"));
            ln.setUnitPrice((BigDecimal) r.get("avg_price"));
            lineMapper.insert(ln);
        }
        h.setLineCnt(rows.size());
        mapper.updateById(h);
        return detail(h.getId());
    }

    /** 保存实盘数（草稿/被驳回时可改）。lines: [{id, actualQty, remark}] */
    @Transactional(rollbackFor = Exception.class)
    public void saveActual(Long id, List<BizStocktakeLine> lines) {
        BizStocktake h = require(id);
        assertEditable(h);
        for (BizStocktakeLine in : lines) {
            if (in.getId() == null) continue;
            BizStocktakeLine ln = lineMapper.selectById(in.getId());
            if (ln == null || !ln.getStocktakeId().equals(id)) continue;
            if (in.getActualQty() != null && in.getActualQty().signum() < 0) throw new BizException("实盘数量不能为负");
            ln.setActualQty(in.getActualQty());
            ln.setRemark(in.getRemark());
            lineMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizStocktakeLine>()
                    .eq("id", ln.getId()).set("actual_qty", in.getActualQty()).set("remark", in.getRemark()));
        }
        if ("REJECTED".equals(h.getStatus())) { h.setStatus("DRAFT"); mapper.updateById(h); }
    }

    /** Excel 导入实盘数：按物料编码匹配本单行，列「物料编码」「实盘数」。
     *  加事务：中途某行异常时整批回滚,避免前几行实盘数已悄悄写入、接口却报"导入失败"的半更新状态。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importActual(Long id, MultipartFile file) {
        BizStocktake h = require(id);
        assertEditable(h);
        if (file == null || file.isEmpty()) throw new BizException("请选择实盘 Excel 文件");
        Map<String, Long> codeToLine = new HashMap<>();
        jdbc.queryForList("SELECT l.id, m.code FROM biz_stocktake_line l JOIN md_material m ON m.id = l.material_id WHERE l.stocktake_id = ?", id)
                .forEach(r -> codeToLine.put((String) r.get("code"), ((Number) r.get("id")).longValue()));
        int updated = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        try (var opened = com.group.scm.common.ExcelImportGuard.open(file); Workbook wb = opened.workbook()) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            int codeCol = -1, qtyCol = -1, headerRow = -1;
            for (int i = sheet.getFirstRowNum(); i <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 10); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
                    String t = fmt.formatCellValue(row.getCell(c)).trim();
                    if ("物料编码".equals(t)) codeCol = c;
                    if ("实盘数".equals(t) || "实盘数量".equals(t)) qtyCol = c;
                }
                if (codeCol >= 0 && qtyCol >= 0) { headerRow = i; break; }
                codeCol = -1; qtyCol = -1;
            }
            if (headerRow < 0) throw new BizException("未找到表头：需要「物料编码」「实盘数」两列（用本单导出的盘点表模板填写）");
            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String code = fmt.formatCellValue(row.getCell(codeCol)).trim();
                String qtyText = fmt.formatCellValue(row.getCell(qtyCol)).trim().replace(",", "");
                if (code.isEmpty()) continue;
                Long lineId = codeToLine.get(code);
                if (lineId == null) { skipped++; if (errors.size() < 10) errors.add("第" + (i + 1) + "行：物料编码「" + code + "」不在本盘点单中"); continue; }
                if (qtyText.isEmpty()) { skipped++; continue; } // 留空=未盘
                BigDecimal qty;
                try { qty = new BigDecimal(qtyText); } catch (Exception e) { skipped++; if (errors.size() < 10) errors.add("第" + (i + 1) + "行：实盘数「" + qtyText + "」不是数字"); continue; }
                if (qty.signum() < 0) { skipped++; if (errors.size() < 10) errors.add("第" + (i + 1) + "行：实盘数不能为负"); continue; }
                jdbc.update("UPDATE biz_stocktake_line SET actual_qty = ? WHERE id = ?", qty, lineId);
                updated++;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("实盘导入失败：" + SafeErrorMessage.ofLogged(e, "实盘导入"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", updated);
        r.put("skipped", skipped);
        r.put("errors", errors);
        return r;
    }

    /** 盘点表 Excel（真 xlsx，可打印后线下手填，也可填「实盘数」列后导回）。blind=盲盘(不含账面数)。
     *  单价/金额列只对非仓管角色输出（与 StocktakeController.hideCost 同一口径，仓管导出保持盲价）。 */
    public byte[] exportSheet(Long id, boolean blind) {
        BizStocktake h = detail(id);
        boolean withCost = !SecurityUtil.hasRole("warehouse");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("盘点表");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("盘点表 " + h.getDocNo() + "  仓库：" + (h.getWarehouseName() == null ? "" : h.getWarehouseName()) + "  盘点日期：" + h.getTakeDate());
            String[] heads;
            if (blind) {
                heads = new String[]{"物料编码", "物料名称", "规格型号", "单位", "实盘数", "备注"};
            } else if (withCost) {
                heads = new String[]{"物料编码", "物料名称", "规格型号", "单位", "账面数", "账面单价", "账面金额", "实盘数", "盈亏金额", "备注"};
            } else {
                heads = new String[]{"物料编码", "物料名称", "规格型号", "单位", "账面数", "实盘数", "备注"};
            }
            Row head = sheet.createRow(1);
            for (int i = 0; i < heads.length; i++) head.createCell(i).setCellValue(heads[i]);
            int r = 2;
            for (BizStocktakeLine ln : h.getLines()) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(ln.getMatCode() == null ? "" : ln.getMatCode());
                row.createCell(c++).setCellValue(ln.getMatName() == null ? "" : ln.getMatName());
                row.createCell(c++).setCellValue(ln.getMatSpec() == null ? "" : ln.getMatSpec());
                row.createCell(c++).setCellValue(ln.getMatUnit() == null ? "" : ln.getMatUnit());
                double bookQty = ln.getBookQty() == null ? 0 : ln.getBookQty().doubleValue();
                if (!blind) {
                    row.createCell(c++).setCellValue(bookQty);
                    if (withCost) {
                        double price = ln.getUnitPrice() == null ? 0 : ln.getUnitPrice().doubleValue();
                        row.createCell(c++).setCellValue(price);
                        row.createCell(c++).setCellValue(bookQty * price);
                    }
                }
                if (ln.getActualQty() != null) row.createCell(c).setCellValue(ln.getActualQty().doubleValue());
                c++;
                if (!blind && withCost) {
                    if (ln.getDiffAmt() != null) row.createCell(c).setCellValue(ln.getDiffAmt().doubleValue());
                    c++;
                }
                if (ln.getRemark() != null) row.createCell(c).setCellValue(ln.getRemark());
            }
            for (int i = 0; i < heads.length; i++) sheet.setColumnWidth(i, i == 1 ? 7000 : 3600);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("盘点表导出失败：" + SafeErrorMessage.ofLogged(e, "盘点表导出"));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizStocktake h = require(id);
        assertEditable(h);
        assertSnapshotCurrent(h);
        periodCloseService.assertPeriodOpen(h.getTakeDate(), h.getOrgId(), "盘点单", h.getDocNo());
        long filled = lineMapper.selectCount(new QueryWrapper<BizStocktakeLine>().eq("stocktake_id", id).isNotNull("actual_qty"));
        if (filled == 0) throw new BizException("还没有录入任何实盘数，请先录入或导入实盘结果");
        // 并发防线：条件更新，双击提交只有一个成功
        if (mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizStocktake>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "SUBMITTED")) != 1) {
            throw new BizException("盘点单状态已变化（可能已被提交），请刷新后重试");
        }
        notifService.pushToRole("material_accountant", h.getOrgId(),
                "盘点单 " + h.getDocNo() + " 待财务确认（盘盈盘亏调整）", "STOCKTAKE", h.getDocNo(), h.getId());
    }

    /** 财务确认：按仓模式盘盈取快照价;按公司模式取锁定公司当前价，盘亏取实际过账成本。 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id) {
        waService.lockStocktakeApproval(id);
        BizStocktake h = require(id);
        if (!"SUBMITTED".equals(h.getStatus())) throw new BizException("只有已提交的盘点单可以确认");
        periodCloseService.assertPeriodOpen(h.getTakeDate(), h.getOrgId(), "盘点单", h.getDocNo());
        assertSnapshotCurrent(h);
        // 并发防线：条件更新，双击确认只有一个成功，防止盘盈盘亏调整入两遍
        if (mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizStocktake>()
                .eq("id", id).eq("status", "SUBMITTED").set("status", "APPROVED")) != 1) {
            throw new BizException("盘点单已被确认，请刷新后查看");
        }
        List<BizStocktakeLine> lines = lineMapper.selectList(new QueryWrapper<BizStocktakeLine>().eq("stocktake_id", id));
        BigDecimal gain = BigDecimal.ZERO, loss = BigDecimal.ZERO;
        boolean orgMode = waService.orgMode();
        for (BizStocktakeLine ln : lines) {
            if (ln.getActualQty() == null) continue; // 未盘不调整
            BigDecimal diff = ln.getActualQty().subtract(ln.getBookQty());
            if (orgMode) {
                // 按公司核算:盘点期间别的仓照常出入库,公司单价会变;盘盈按【确认时】公司单价入账(盘亏本来就按当时单价)
                ln.setUnitPrice(waService.companyPriceForStocktake(ln.getMaterialId(), h.getWarehouseId(), h.getId()));
            }
            BigDecimal price = ln.getUnitPrice() == null ? BigDecimal.ZERO : ln.getUnitPrice();
            BigDecimal diffAmt = diff.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
            ln.setDiffQty(diff);
            if (diff.signum() > 0) {
                // 按仓取快照价、按公司取上述锁定价;入账金额 = 数量×本次确认价（与 diffAmt 同口径）
                waService.inbound(ln.getMaterialId(), h.getWarehouseId(), diff, price,
                        "ADJUST", "STOCKTAKE", h.getId(), h.getDocNo(), ln.getId(), "盘盈调整 " + h.getDocNo());
                gain = gain.add(diffAmt);
            } else if (diff.signum() < 0) {
                // 盘亏按当前移动加权均价冲减；盈亏金额取实际冲减额，保证盘点单与库存账分毫一致
                BigDecimal[] posted = waService.outboundWithType(ln.getMaterialId(), h.getWarehouseId(), diff.negate(),
                        "ADJUST", "STOCKTAKE", h.getId(), h.getDocNo(), ln.getId(), "盘亏调整 " + h.getDocNo());
                diffAmt = posted[1].negate();
                loss = loss.add(posted[1]);
            }
            ln.setDiffAmt(diffAmt);
            lineMapper.updateById(ln);
        }
        h.setStatus("APPROVED");
        h.setGainAmt(gain);
        h.setLossAmt(loss);
        h.setFinanceBy(currentUserName());
        h.setFinanceAt(LocalDateTime.now());
        mapper.updateById(h);
        notifService.closeBusinessTodos("STOCKTAKE", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        BizStocktake h = require(id);
        if (!"SUBMITTED".equals(h.getStatus())) throw new BizException("只有已提交的盘点单可以驳回");
        // 并发防线：驳回与确认赛跑时只有一方成功，防止已确认的盘点被改回驳回
        if (mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizStocktake>()
                .eq("id", id).eq("status", "SUBMITTED")
                .set("status", "REJECTED").set("reject_reason", reason)) != 1) {
            throw new BizException("盘点单已被处理，请刷新后查看");
        }
        notifService.closeBusinessTodos("STOCKTAKE", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizStocktake h = require(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(h.getCreatedBy(), h.getStatus());
        if (!"DRAFT".equals(h.getStatus()) && !"REJECTED".equals(h.getStatus())) {
            throw new BizException("只有草稿或被驳回的盘点单可以删除");
        }
        lineMapper.delete(new QueryWrapper<BizStocktakeLine>().eq("stocktake_id", id));
        mapper.deleteById(id);
        notifService.closeBusinessTodos("STOCKTAKE", id);
    }

    private BizStocktake require(Long id) {
        BizStocktake h = mapper.selectById(id);
        if (h == null) throw new BizException("盘点单不存在");
        orgScope.assertOrgVisible(h.getOrgId());
        return h;
    }

    /** 驳回仍冻结仓库；历史未冻结的旧快照如已失效，必须重建盘点，禁止继续扣差额。 */
    private void assertSnapshotCurrent(BizStocktake h) {
        waService.assertWarehouseMovementAllowed(h.getWarehouseId(), "STOCKTAKE", h.getId());
        // 按公司核算:被盘仓库冻结,但同公司别的仓照常过账,公司单价会变 —— 只核数量,单价在确认时取当时公司单价
        String priceCheck = waService.orgMode() ? "" : " OR l.unit_price <> COALESCE(b.avg_price,0)";
        var mismatches = jdbc.queryForList("""
                SELECT l.id FROM biz_stocktake_line l
                LEFT JOIN inv_balance b ON b.material_id=l.material_id AND b.warehouse_id=?
                WHERE l.stocktake_id=? AND (l.book_qty <> COALESCE(b.qty,0)%s) FOR UPDATE
                """.formatted(priceCheck), h.getWarehouseId(), h.getId());
        if (!mismatches.isEmpty()) throw new BizException("盘点账面快照已失效，请删除后重新建单盘点");
    }

    private void assertEditable(BizStocktake h) {
        if (!"DRAFT".equals(h.getStatus()) && !"REJECTED".equals(h.getStatus())) {
            throw new BizException("只有草稿或被驳回的盘点单可以修改实盘数");
        }
    }

    private String currentUserName() {
        Long uid = SecurityUtil.uid();
        if (uid == null) return "";
        return jdbc.queryForList("SELECT name FROM sys_user WHERE id = ?", String.class, uid)
                .stream().findFirst().orElse("");
    }

    private void enrichHeads(List<BizStocktake> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (BizStocktake h : rows) {
            h.setWarehouseName(jdbc.queryForList("SELECT name FROM md_warehouse WHERE id = ?", String.class, h.getWarehouseId()).stream().findFirst().orElse(null));
            h.setOrgName(jdbc.queryForList("SELECT name FROM sys_org WHERE id = ?", String.class, h.getOrgId()).stream().findFirst().orElse(null));
            if (h.getCreatedBy() != null) {
                h.setCreatedByName(jdbc.queryForList("SELECT name FROM sys_user WHERE id = ?", String.class, h.getCreatedBy()).stream().findFirst().orElse(null));
            }
        }
    }

    private void enrichLines(List<BizStocktakeLine> lines) {
        if (lines == null || lines.isEmpty()) return;
        for (BizStocktakeLine ln : lines) {
            Map<String, Object> m = jdbc.queryForList(
                    "SELECT code, name, spec, unit FROM md_material WHERE id = ?", ln.getMaterialId())
                    .stream().findFirst().orElse(null);
            if (m != null) {
                ln.setMatCode((String) m.get("code"));
                ln.setMatName((String) m.get("name"));
                ln.setMatSpec((String) m.get("spec"));
                ln.setMatUnit((String) m.get("unit"));
            }
        }
    }
}
