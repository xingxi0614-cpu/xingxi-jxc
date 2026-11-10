package com.group.scm.modules.sales.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.sales.entity.BizSo;
import com.group.scm.modules.sales.entity.BizSoLine;
import com.group.scm.modules.sales.mapper.BizSoLineMapper;
import com.group.scm.modules.sales.mapper.BizSoMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 销售订单服务。镜像采购订单(PoService)，客户以个人为主：
 * 订单直接手填客户姓名/电话/收货地址，单位客户可选填关联往来单位。
 */
@Service
@RequiredArgsConstructor
public class SoService {

    private final BizSoMapper soMapper;
    private final SalesOutAvailability outAvailability;
    private final BizSoLineMapper soLineMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final PeriodCloseService periodCloseService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    @Transactional(rollbackFor = Exception.class)
    public BizSo create(BizSo dto) {
        validateRequired(dto);
        Long orgId = SecurityUtil.orgId();
        if (orgId == null) throw new BizException("当前账号缺少账套归属，不能创建销售订单");
        orgScope.assertOrgVisible(orgId);
        // 客户单位(复用往来单位 md_supplier)须属当前账套可见范围,防挂到别账套客户
        if (dto.getCustomerId() != null) orgScope.assertCustomerVisible(dto.getCustomerId());
        dto.setId(null);
        dto.setDocNo(docNoService.next("SO"));
        dto.setOrgId(orgId);
        if (dto.getOrderDate() == null) dto.setOrderDate(LocalDate.now());
        periodCloseService.assertPeriodOpen(dto.getOrderDate(), orgId, "销售订单", dto.getDocNo());
        applyTotals(dto);
        dto.setOutQty(BigDecimal.ZERO);
        dto.setInvoiceQty(BigDecimal.ZERO);
        if (dto.getReceiptAmount() == null) dto.setReceiptAmount(BigDecimal.ZERO);
        dto.setStatus("DRAFT");
        soMapper.insert(dto);
        insertLines(dto.getId(), dto.getLines());
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BizSo dto) {
        BizSo cur = requireExisting(id);
        if (!"DRAFT".equals(cur.getStatus()) && !"REJECTED".equals(cur.getStatus()))
            throw new BizException("只有草稿或已驳回的销售订单可以修改");
        if (!SecurityUtil.isAdmin() && cur.getCreatedBy() != null && !cur.getCreatedBy().equals(SecurityUtil.uid()))
            throw new BizException("只有制单人或管理员可以修改该销售订单");
        periodCloseService.assertPeriodOpen(cur.getOrderDate(), cur.getOrgId(), "销售订单", cur.getDocNo());
        validateRequired(dto);
        if (dto.getCustomerId() != null) orgScope.assertCustomerVisible(dto.getCustomerId());
        dto.setId(id);
        dto.setDocNo(cur.getDocNo());
        dto.setOrgId(cur.getOrgId());
        dto.setStatus(cur.getStatus());
        dto.setOutQty(cur.getOutQty());
        dto.setInvoiceQty(cur.getInvoiceQty());
        if (dto.getReceiptAmount() == null) dto.setReceiptAmount(BigDecimal.ZERO);
        if (dto.getOrderDate() == null) dto.setOrderDate(cur.getOrderDate());
        periodCloseService.assertPeriodOpen(dto.getOrderDate(), cur.getOrgId(), "销售订单", cur.getDocNo());
        applyTotals(dto);
        soMapper.updateById(dto);
        soLineMapper.delete(new QueryWrapper<BizSoLine>().eq("so_id", id));
        insertLines(id, dto.getLines());
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizSo so = requireExisting(id);
        if (!"DRAFT".equals(so.getStatus()) && !"REJECTED".equals(so.getStatus()))
            throw new BizException("当前状态不能提交：" + so.getStatus());
        periodCloseService.assertPeriodOpen(so.getOrderDate(), so.getOrgId(), "销售订单", so.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (soMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizSo>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("SO", id, so.getDocNo(), so.getTotalAmt());
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizSo so = requireExisting(id);
        if (!"PENDING".equals(so.getStatus())) throw new BizException("只有待审批的销售订单可以取消提交");
        periodCloseService.assertPeriodOpen(so.getOrderDate(), so.getOrgId(), "销售订单", so.getDocNo());
        wfService.withdraw("SO", id);
        so.setStatus("DRAFT");
        soMapper.updateById(so);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizSo so = requireExisting(id);
        periodCloseService.assertPeriodOpen(so.getOrderDate(), so.getOrgId(), "销售订单", so.getDocNo());
        wfService.approve("SO", id, comment, (no, bizId) -> {
            BizSo s = soMapper.selectById(bizId);
            s.setStatus("APPROVED");
            soMapper.updateById(s);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizSo so = requireExisting(id);
        periodCloseService.assertPeriodOpen(so.getOrderDate(), so.getOrgId(), "销售订单", so.getDocNo());
        wfService.reject("SO", id, comment, (no, bizId) -> {
            BizSo s = soMapper.selectById(bizId);
            s.setStatus("REJECTED");
            soMapper.updateById(s);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizSo so = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(so.getCreatedBy(), so.getStatus());
        if (!"DRAFT".equals(so.getStatus()) && !"REJECTED".equals(so.getStatus()))
            throw new BizException("只有草稿或已驳回的销售订单可以删除");
        if ((so.getOutQty() != null && so.getOutQty().signum() > 0)
                || (so.getInvoiceQty() != null && so.getInvoiceQty().signum() > 0))
            throw new BizException("已有出库或开票记录的销售订单不能删除");
        periodCloseService.assertPeriodOpen(so.getOrderDate(), so.getOrgId(), "销售订单", so.getDocNo());
        soLineMapper.delete(new QueryWrapper<BizSoLine>().eq("so_id", id));
        soMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("SO", id);
    }

    /** 出库时由 SaleOutService 回调，回写已出库数量 + 自动改状态 */
    public void addOut(Long soId, Long soLineId, BigDecimal qty) {
        if (soLineId != null && soLineMapper.addOutQty(soLineId, qty) != 1)
            throw new BizException("出库数量超过销售订单未出库数量，请刷新订单后重试");
        if (soId != null) soMapper.addOutQty(soId, qty);
    }

    /** 开票时由 SaleInvoiceService 回调，回写已开票数量 */
    public void addInvoice(Long soId, Long soLineId, BigDecimal qty) {
        if (soLineId != null) soLineMapper.addInvoiceQty(soLineId, qty);
        if (soId != null) soMapper.addInvoiceQty(soId, qty);
    }

    public BizSo detail(Long id) {
        BizSo so = requireExisting(id);
        so.setLines(soLineMapper.selectList(new QueryWrapper<BizSoLine>().eq("so_id", id).orderByAsc("line_no")));
        outAvailability.attach(id, so.getLines());
        return so;
    }

    public IPage<BizSo> page(long current, long size, String status, String keyword, Long customerId,
                             LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizSo> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED");
        if (keyword != null && !keyword.isBlank())
            qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("customer_unit", keyword));
        if (customerId != null) qw.eq("customer_id", customerId);
        if (startDate != null) qw.ge("order_date", startDate);
        if (endDate != null) qw.le("order_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizSo.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizSo> page = soMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    /** 供销售出库参照：已审批、仍有未出库数量的订单 */
    public IPage<BizSo> availableForOut(long current, long size, String keyword, Long customerId) {
        QueryWrapper<BizSo> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        qw.in("status", "APPROVED", "PARTIAL_OUT");
        qw.apply("""
                EXISTS (SELECT 1 FROM biz_so_line sl WHERE sl.so_id=biz_so.id
                  AND sl.qty > COALESCE(sl.out_qty,0) +
                    (SELECT COALESCE(SUM(ol.qty),0) FROM biz_sale_out_line ol
                     JOIN biz_sale_out o ON o.id=ol.out_id
                     WHERE ol.source_so_line_id=sl.id AND o.source_so_id=biz_so.id
                       AND o.deleted=0 AND o.status IN ('DRAFT','PENDING')))
                """);
        if (keyword != null && !keyword.isBlank())
            qw.and(w -> w.like("doc_no", keyword).or().like("customer_name", keyword).or().like("customer_unit", keyword));
        if (customerId != null) qw.eq("customer_id", customerId);
        qw.orderByDesc("id");
        IPage<BizSo> page = soMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    private void attachMaterialSummary(List<BizSo> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(BizSo::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        String ph = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT sl.so_id sid, " +
                "GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name, IF(m.spec IS NULL OR m.spec = '', '', CONCAT('(', m.spec, ')'))) " +
                "ORDER BY m.code SEPARATOR '、') summary, " +
                "GROUP_CONCAT(m.code ORDER BY m.code SEPARATOR '、') codes, " +
                "GROUP_CONCAT(m.name ORDER BY m.code SEPARATOR '、') names, " +
                "GROUP_CONCAT(IFNULL(NULLIF(m.spec,''),'-') ORDER BY m.code SEPARATOR '、') specs " +
                "FROM biz_so_line sl JOIN md_material m ON m.id = sl.material_id " +
                "WHERE sl.so_id IN (" + ph + ") GROUP BY sl.so_id";
        Map<Long, String[]> map = new HashMap<>();
        jdbcTemplate.query(sql, (java.sql.ResultSet rs) -> { map.put(rs.getLong("sid"), new String[]{rs.getString("summary"), rs.getString("codes"), rs.getString("names"), rs.getString("specs")}); }, ids.toArray());
        for (BizSo r : records) {
            if (r.getId() == null) continue;
            String[] v = map.get(r.getId());
            if (v != null) { r.setMaterialSummary(v[0]); r.setMaterialCodes(v[1]); r.setMaterialNames(v[2]); r.setMaterialSpecs(v[3]); }
        }
    }

    private BizSo requireExisting(Long id) {
        BizSo so = soMapper.selectById(id);
        if (so == null) throw new BizException("销售订单不存在");
        orgScope.assertOrgVisible(so.getOrgId());
        return so;
    }

    /** 客户档案下拉：往来单位中 is_customer=1 的记录（本账套可见 + 集团共享），带联系人/电话/地址/税率。
     *  客户多为个人（姓名/电话/地址），2026-08-24 起按账套隔离恢复——不能让别的公司看到本公司客户的联系方式。 */
    public List<Map<String, Object>> customers(String keyword) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        String ph = orgIds.stream().map(x -> "?").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder(
                "SELECT id, code, name, contact_name, contact_phone, address, tax_no, default_tax " +
                "FROM md_supplier WHERE deleted = 0 AND status = 1 AND is_customer = 1 " +
                "AND (org_id IN (" + ph + ") OR is_shared = 1) ");
        List<Object> args = new java.util.ArrayList<>(orgIds);
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (name LIKE ? OR code LIKE ? OR mnemonic_code LIKE ?) ");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw); args.add(kw); args.add(kw);
        }
        sql.append("ORDER BY name LIMIT 200");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        jdbcTemplate.query(sql.toString(), (java.sql.ResultSet rs) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("contactName", rs.getString("contact_name"));
            m.put("contactPhone", rs.getString("contact_phone"));
            m.put("address", rs.getString("address"));
            m.put("taxNo", rs.getString("tax_no"));
            m.put("defaultTax", rs.getBigDecimal("default_tax"));
            rows.add(m);
        }, args.toArray());
        return rows;
    }

    private void validateRequired(BizSo dto) {
        boolean hasCustomer = (dto.getCustomerName() != null && !dto.getCustomerName().isBlank())
                || dto.getCustomerId() != null
                || (dto.getCustomerUnit() != null && !dto.getCustomerUnit().isBlank());
        if (!hasCustomer) throw new BizException("请填写客户姓名或选择客户单位");
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("订单明细不能为空");
    }

    /** 计算表头合计并逐行倒挤不含税/税额/单价 */
    private void applyTotals(BizSo dto) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (BizSoLine line : dto.getLines()) {
            validateLine(line);
            computeLineTax(line);
            // 0 元明细一律拒绝(2026-09-29 定)：0 元销售=收入少记，赠送客户税法上视同销售，不能录 0
            if (line.getAmount().signum() <= 0) throw new BizException("价税合计必须大于 0（销售订单不允许 0 元明细）");
            line.setAmount(com.group.scm.common.FinancialInput.positiveMoney(line.getAmount(), "价税合计"));
            totalQty = totalQty.add(line.getQty());
            totalAmt = totalAmt.add(line.getAmount());
            totalTax = totalTax.add(line.getTaxAmount());
        }
        if (totalQty.signum() <= 0) throw new BizException("至少需要一行有效的物料明细");
        dto.setTotalQty(totalQty);
        dto.setTotalAmt(totalAmt);
        dto.setTaxAmt(totalTax);
    }

    /**
     * 含税净价×数量=价税合计(未填价税合计时倒推)，再按税率倒挤不含税/税额/不含税单价。
     * 公开录单(SoPublicService)也调这里,两个入口同一口径(计算审查 U26:公开录单以前不算,净额/税额都是 0)。
     */
    static void computeLineTax(BizSoLine line) {
        BigDecimal qty = line.getQty();
        BigDecimal taxPrice = line.getTaxPrice() == null ? BigDecimal.ZERO : line.getTaxPrice();
        line.setTaxPrice(taxPrice);
        BigDecimal amount = line.getAmount();
        if ((amount == null || amount.signum() == 0) && taxPrice.signum() > 0)
            amount = taxPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        if (amount == null) amount = BigDecimal.ZERO;
        if (amount.signum() > 0) amount = com.group.scm.common.FinancialInput.positiveMoney(amount, "价税合计");
        line.setAmount(amount);
        BigDecimal taxRate = line.getTaxRate() == null ? BigDecimal.ZERO : line.getTaxRate();
        line.setTaxRate(taxRate);
        BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        BigDecimal exclTax = amount.divide(divisor, 2, RoundingMode.HALF_UP);
        line.setAmountExclTax(exclTax);
        line.setTaxAmount(amount.subtract(exclTax));
        line.setUnitPrice(qty.signum() == 0 ? BigDecimal.ZERO : exclTax.divide(qty, 2, RoundingMode.HALF_UP));
    }

    private void validateLine(BizSoLine line) {
        if (line.getMaterialId() == null) throw new BizException("物料不能为空");
        orgScope.assertMaterialVisible(line.getMaterialId());
        if (line.getQty() == null || line.getQty().signum() <= 0) throw new BizException("数量必须大于 0");
        // 服务端金额/税率边界校验：拒绝负单价、负金额、非法税率(负数或 >=100，后者会让 1+税率/100 的倒挤除零/反向)
        if (line.getTaxPrice() != null && line.getTaxPrice().signum() < 0) throw new BizException("含税单价不能为负");
        if (line.getAmount() != null && line.getAmount().signum() < 0) throw new BizException("价税合计不能为负");
        BigDecimal taxRate = line.getTaxRate();
        if (taxRate != null && (taxRate.signum() < 0 || taxRate.compareTo(new BigDecimal("100")) >= 0))
            throw new BizException("税率必须在 0 到 100 之间");
    }

    private void insertLines(Long soId, List<BizSoLine> lines) {
        if (lines == null) return;
        int i = 1;
        for (BizSoLine line : lines) {
            line.setId(null);
            line.setSoId(soId);
            line.setLineNo(i++);
            /*
             * 2026-08-05 防御性加固,与 PoService.insertLines 同一处理。
             * ⚠️ 采购侧已实测证明「前端可伪造已执行量」是【误报】(上游已挡住);
             * 销售侧写法完全相同,**未单独实测**,按同样结论对待。
             * 保留原因:把隐含保护写显式 + E2E 断言作为回归护栏。无副作用
             * (update():74-75 只放行 DRAFT/REJECTED,不可能已出库或已开票)。
             */
            line.setOutQty(BigDecimal.ZERO);
            line.setInvoiceQty(BigDecimal.ZERO);
            soLineMapper.insert(line);
        }
    }
}
