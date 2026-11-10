package com.group.scm.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.inventory.entity.BizRecv;
import com.group.scm.modules.inventory.entity.BizRecvLine;
import com.group.scm.modules.inventory.mapper.BizRecvLineMapper;
import com.group.scm.modules.inventory.mapper.BizRecvMapper;
import com.group.scm.modules.purchase.entity.BizPo;
import com.group.scm.modules.purchase.entity.BizPoLine;
import com.group.scm.modules.purchase.mapper.BizPoLineMapper;
import com.group.scm.modules.purchase.mapper.BizPoMapper;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购入库服务。
 *
 * <p>关键能力：</p>
 * <ul>
 *   <li>{@link #fromPo} - 由采购订单下推（支持分批到货：本次入库 ≤ 未入库数）</li>
 *   <li>{@link #approve} - 审批通过 → 触发 WaInventoryService 实时更新库存与加权平均</li>
 *   <li>暂估入库（recvType=ESTIMATE）等待发票核销；发票入库（INVOICE）货票同到</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RecvService {

    private final BizRecvMapper recvMapper;
    private final BizRecvLineMapper lineMapper;
    private final BizPoMapper poMapper;
    private final BizPoLineMapper poLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final PoService poService;
    private final DocRelationService relationService;
    private final PeriodCloseService periodCloseService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    /**
     * 由订单下推生成入库单（支持分批：可只对部分行/部分数量）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BizRecv fromPo(Long poId, String recvType, List<RecvLineInput> lineInputs, String remark) {
        BizPo po = poMapper.selectById(poId);
        if (po != null) orgScope.assertOrgVisible(po.getOrgId());
        if (po == null) throw new BizException("订单不存在");
        if (!"APPROVED".equals(po.getStatus()) && !"PARTIAL_RECEIVED".equals(po.getStatus()))
            throw new BizException("该采购订单尚未审批通过（或已关闭），不能入库。请先到【采购订单】提交并完成审批，再回来下推入库。（帮助中心→操作流程指引有完整顺序）");
        if (lineInputs == null || lineInputs.isEmpty()) throw new BizException("请至少选择一行入库");
        // 跨月订单允许入库：入库按【入库日期】入账，只校验入庫单自己的期间；
        // 订单日期所在的期间是否结账不影响（回写订单的已入库数只是进度计数，不动历史账）。
        LocalDate recvDate = LocalDate.now();
        periodCloseService.assertPeriodOpen(recvDate, po.getOrgId(), "采购入库单", null);

        String normalizedRecvType = normalizeRecvType(recvType);

        BizRecv recv = new BizRecv();
        recv.setDocNo(docNoService.next("RECV"));
        recv.setSourcePoId(poId);
        recv.setSourcePoNo(po.getDocNo());
        recv.setSupplierId(po.getSupplierId());
        recv.setWarehouseId(po.getWarehouseId());
        recv.setOrgId(po.getOrgId());
        recv.setRecvDate(recvDate);
        recv.setRecvType(normalizedRecvType);
        recv.setStatus("DRAFT");
        recv.setRemark(remark);

        BigDecimal totalQty = BigDecimal.ZERO, totalAmt = BigDecimal.ZERO;
        recvMapper.insert(recv);

        int i = 1;
        for (RecvLineInput in : lineInputs) {
            BizPoLine pl = poLineMapper.selectById(in.poLineId);
            if (pl == null) throw new BizException("订单行不存在：" + in.poLineId);
            if (!poId.equals(pl.getPoId())) throw new BizException("订单行不属于当前采购订单：" + in.poLineId);
            if (in.qty == null || in.qty.signum() <= 0) throw new BizException("入库数量必须大于 0");
            BigDecimal received = pl.getReceivedQty() == null ? BigDecimal.ZERO : pl.getReceivedQty();
            BigDecimal occupied = poLineMapper.activeReceivingQty(pl.getId());
            if (occupied == null) occupied = BigDecimal.ZERO;
            BigDecimal remain = pl.getQty().subtract(received).subtract(occupied);
            if (in.qty.compareTo(remain) > 0)
                throw new BizException("第 " + pl.getLineNo() + " 行 " + com.group.scm.common.MaterialLabel.of(jdbcTemplate, pl.getMaterialId())
                        + "：本次入库 " + com.group.scm.common.MaterialLabel.qty(in.qty) + " 超过可入库数 " + com.group.scm.common.MaterialLabel.qty(remain.max(BigDecimal.ZERO))
                        + (occupied.signum() > 0
                            ? "。这张订单已有草稿或待审的入库单占用了 " + com.group.scm.common.MaterialLabel.qty(occupied) + "，请先提交审批或删除那些入库单"
                            : "。订单数量已基本入完，请核对是否重复入库"));

            BizRecvLine ln = new BizRecvLine();
            ln.setRecvId(recv.getId()); ln.setLineNo(i++);
            ln.setSourcePoLineId(pl.getId());
            ln.setMaterialId(pl.getMaterialId());
            ln.setQty(in.qty);
            ln.setUnitPrice(pl.getUnitPrice());
            // 入库不含税金额：按来源采购订单行的「精确不含税金额」等比例分摊，
            // 不用"不含税单价(已四舍五入到分)×数量"，避免丢分位、保证含税=原始录入含税、税额对得上。
            BigDecimal exclAmt;
            if (pl.getAmountExclTax() != null && pl.getQty() != null && pl.getQty().signum() != 0) {
                if (in.qty.compareTo(remain) == 0) {
                    // 收尾入库（本次收完该订单行剩余数量）：用「订单行总额 − 已分摊金额」兜底，
                    // 消除分批入库各自四舍五入攒出的分位尾差，保证多次入库合计 = 订单行不含税总额
                    // 🔴 2026-10-18(计算审查 U04):已分摊金额要扣掉【已审批采购退货】退回的原入库额。
                    //    以前只加不减:订单 100 件/1000 全部入库,退 20 件(释放 20 件额度),再补入 20 件走到这里,
                    //    算成 1000 − 1000 = 0,数量回到 100 件、金额只有 800。退掉的那部分额度连同金额一起还回来。
                    BigDecimal priorAmt = jdbcTemplate.queryForObject("""
                            SELECT COALESCE(SUM(rl.amount), 0)
                                 - COALESCE((SELECT SUM(prl.recv_amt)
                                             FROM biz_purchase_return_line prl
                                             JOIN biz_purchase_return pr ON pr.id = prl.return_id AND pr.deleted = 0
                                             JOIN biz_recv_line rl2 ON rl2.id = prl.source_recv_line_id
                                             JOIN biz_recv r2 ON r2.id = rl2.recv_id AND r2.deleted = 0
                                             WHERE rl2.source_po_line_id = ? AND pr.status = 'APPROVED'
                                               AND r2.status IN ('DRAFT', 'PENDING', 'APPROVED') AND r2.id <> ?), 0)
                            FROM biz_recv_line rl
                            JOIN biz_recv r ON r.id = rl.recv_id AND r.deleted = 0
                            WHERE rl.source_po_line_id = ?
                              AND r.status IN ('DRAFT', 'PENDING', 'APPROVED')
                              AND r.id <> ?
                            """, BigDecimal.class, pl.getId(), recv.getId(), pl.getId(), recv.getId());
                    exclAmt = pl.getAmountExclTax().subtract(priorAmt == null ? BigDecimal.ZERO : priorAmt)
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    if (exclAmt.signum() < 0) {
                        exclAmt = pl.getAmountExclTax().multiply(in.qty)
                                .divide(pl.getQty(), 2, java.math.RoundingMode.HALF_UP);
                    }
                } else {
                    exclAmt = pl.getAmountExclTax().multiply(in.qty)
                            .divide(pl.getQty(), 2, java.math.RoundingMode.HALF_UP);
                }
            } else {
                exclAmt = in.qty.multiply(pl.getUnitPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
            }
            ln.setAmount(exclAmt);
            ln.setAmountInclTax(inclAmountFor(pl, recv.getId(), in.qty, in.qty.compareTo(remain) == 0, exclAmt));
            ln.setInvoicedQty(BigDecimal.ZERO);
            lineMapper.insert(ln);

            totalQty = totalQty.add(in.qty);
            totalAmt = totalAmt.add(ln.getAmount());
        }
        recv.setTotalQty(totalQty);
        recv.setTotalAmt(totalAmt);
        recvMapper.updateById(recv);
        return recv;
    }

    /**
     * 入库行含税金额(2026-10-18,计算审查 U32):按来源订单行含税额分,本次收完订单行剩余数量时拿走全部剩余。
     * 以前不存,查询时每行各自按比例四舍五入:订单 3 件含税 100 分三次入,含税合计 99.99、税额 −0.01。
     * 已用掉的 = 本订单行其它有效入库行的含税额 − 已审批退货退回的部分(退货释放额度后补入库,含税额跟着还回来,与 U04 同理)。
     */
    private BigDecimal inclAmountFor(BizPoLine pl, Long recvId, BigDecimal qty, boolean tail, BigDecimal exclAmt) {
        if (pl.getAmount() == null || pl.getQty() == null || pl.getQty().signum() == 0) {
            BigDecimal rate = pl.getTaxRate() == null ? BigDecimal.ZERO : pl.getTaxRate();
            return exclAmt.multiply(BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP)))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal byRatio = pl.getAmount().multiply(qty).divide(pl.getQty(), 2, java.math.RoundingMode.HALF_UP);
        if (!tail) return byRatio;
        BigDecimal prior = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(rl.amount_incl_tax, ROUND(? * rl.qty / ?, 2))), 0)
                     - COALESCE((SELECT SUM(ROUND(COALESCE(rl2.amount_incl_tax, ? * rl2.qty / ?) * prl.qty / rl2.qty, 2))
                                 FROM biz_purchase_return_line prl
                                 JOIN biz_purchase_return pr ON pr.id = prl.return_id AND pr.deleted = 0 AND pr.status = 'APPROVED'
                                 JOIN biz_recv_line rl2 ON rl2.id = prl.source_recv_line_id AND rl2.qty > 0
                                 JOIN biz_recv r2 ON r2.id = rl2.recv_id AND r2.deleted = 0
                                 WHERE rl2.source_po_line_id = ? AND r2.status IN ('DRAFT', 'PENDING', 'APPROVED') AND r2.id <> ?), 0)
                FROM biz_recv_line rl
                JOIN biz_recv r ON r.id = rl.recv_id AND r.deleted = 0
                WHERE rl.source_po_line_id = ? AND r.status IN ('DRAFT', 'PENDING', 'APPROVED') AND r.id <> ?
                """, BigDecimal.class, pl.getAmount(), pl.getQty(), pl.getAmount(), pl.getQty(),
                pl.getId(), recvId, pl.getId(), recvId);
        BigDecimal rest = pl.getAmount().subtract(prior == null ? BigDecimal.ZERO : prior).setScale(2, java.math.RoundingMode.HALF_UP);
        return rest.signum() >= 0 ? rest : byRatio;
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizRecv r = recvMapper.selectById(id);
        if (r != null) orgScope.assertOrgVisible(r.getOrgId());
        if (r == null) throw new BizException("入库单不存在");
        if (!"DRAFT".equals(r.getStatus())) throw new BizException("当前状态不能提交：" + r.getStatus());
        periodCloseService.assertPeriodOpen(r.getRecvDate(), r.getOrgId(), "采购入库单", r.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (recvMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizRecv>()
                .eq("id", id).eq("status", "DRAFT").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("RECV", id, r.getDocNo(), r.getTotalAmt());
    }

    /** 取消提交：提交人在入库单审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizRecv r = recvMapper.selectById(id);
        if (r == null) throw new BizException("入库单不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        if (!"PENDING".equals(r.getStatus())) throw new BizException("只有待审批的入库单可以取消提交");
        periodCloseService.assertPeriodOpen(r.getRecvDate(), r.getOrgId(), "采购入库单", r.getDocNo());
        wfService.withdraw("RECV", id);
        r.setStatus("DRAFT");
        recvMapper.updateById(r);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizRecv r = recvMapper.selectById(id);
        if (r == null) throw new BizException("入库单不存在");
        orgScope.assertOrgVisible(r.getOrgId());
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(r.getCreatedBy(), r.getStatus());
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus()) && !("CANCELLED".equals(r.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的入库单可以删除");
        }
        periodCloseService.assertPeriodOpen(r.getRecvDate(), r.getOrgId(), "采购入库单", r.getDocNo());
        lineMapper.delete(new QueryWrapper<BizRecvLine>().eq("recv_id", id));
        recvMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("RECV", id);
    }

    /**
     * 审批通过 → 实时更新库存（加权平均）+ 回写订单已入库数 + 单据链关系
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizRecv recv = recvMapper.selectById(id);
        if (recv == null) throw new BizException("入库单不存在");
        orgScope.assertOrgVisible(recv.getOrgId());
        periodCloseService.assertPeriodOpen(recv.getRecvDate(), recv.getOrgId(), "采购入库单", recv.getDocNo());
        wfService.approve("RECV", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (recvMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizRecv>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizRecv r = recvMapper.selectById(bizId);
            r.setStatus("APPROVED");
            recvMapper.updateById(r);
            // 🔴 计算审查 U05:来源采购订单被取消审批(退回草稿)后,旧入库单以前照样能过账——
            //    未获有效批准的采购形成了库存,订单还被改成"已入库"。过账前加锁复核订单状态。
            if (r.getSourcePoId() != null) {
                String poStatus = poMapper.lockStatus(r.getSourcePoId());
                if (!java.util.Set.of("APPROVED", "PARTIAL_RECEIVED", "RECEIVED").contains(poStatus)) {
                    throw new BizException("来源采购订单 " + r.getSourcePoNo() + " 当前不是已审批状态（可能已取消审批），本入库单不能过账。请驳回本单，待订单重新审批后再处理");
                }
            }

            // 关键：每行调用 WaInventoryService.inbound
            String transType = "ESTIMATE".equals(r.getRecvType()) ? "IN_ESTIMATE" : "IN_INVOICE";
            String remark = "ESTIMATE".equals(r.getRecvType()) ? "暂估入库" : "发票入库";
            List<BizRecvLine> lns = lineMapper.selectList(new QueryWrapper<BizRecvLine>().eq("recv_id", bizId));
            for (BizRecvLine ln : lns) {
                // 按入库行的「精确不含税金额」入账（fromPo 已按采购订单不含税金额等比分摊），
                // 不让"单价×数量"再次四舍五入丢分位——库存成本与采购订单不含税分毫对齐。
                waService.inbound(ln.getMaterialId(), r.getWarehouseId(),
                        ln.getQty(), ln.getUnitPrice(), ln.getAmount(),
                        transType, "RECV", r.getId(), r.getDocNo(), ln.getId(), remark);
                // 回写 PO
                poService.addReceived(r.getSourcePoId(), ln.getSourcePoLineId(), ln.getQty());
            }

            // 单据链关系
            relationService.addRelation("PO", r.getSourcePoId(), r.getSourcePoNo(),
                    "RECV", r.getId(), r.getDocNo(),
                    "DOWN_PUSH", r.getTotalQty(), r.getTotalAmt());
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizRecv recv = recvMapper.selectById(id);
        if (recv == null) throw new BizException("入库单不存在");
        orgScope.assertOrgVisible(recv.getOrgId());
        periodCloseService.assertPeriodOpen(recv.getRecvDate(), recv.getOrgId(), "采购入库单", recv.getDocNo());
        wfService.reject("RECV", id, comment, (no, bizId) -> {
            BizRecv r = recvMapper.selectById(bizId);
            r.setStatus("REJECTED");
            recvMapper.updateById(r);
        });
    }

    public BizRecv detail(Long id) {
        BizRecv r = recvMapper.selectById(id);
        if (r == null) return null;
        orgScope.assertOrgVisible(r.getOrgId());
        List<BizRecvLine> lines = lineMapper.selectList(new QueryWrapper<BizRecvLine>().eq("recv_id", id).orderByAsc("line_no"));
        // 富集"含税"：入库行 amount/unitPrice 为不含税，按来源采购订单行的税率推算含税，供"不含税+含税"同单展示
        BigDecimal totalIncl = BigDecimal.ZERO, totalTax = BigDecimal.ZERO;
        for (BizRecvLine ln : lines) {
            BigDecimal rate = BigDecimal.ZERO;
            BizPoLine pl = ln.getSourcePoLineId() == null ? null : poLineMapper.selectById(ln.getSourcePoLineId());
            if (pl != null && pl.getTaxRate() != null) rate = pl.getTaxRate();
            BigDecimal excl = ln.getAmount() == null ? BigDecimal.ZERO : ln.getAmount();   // 不含税金额(=入库成本)
            BigDecimal incl;
            if (ln.getAmountInclTax() != null) {
                incl = ln.getAmountInclTax();   // 入库时已按订单含税额分好并存下(U32);下面两支只兜没存的老数据
            } else if (pl != null && pl.getAmount() != null && pl.getQty() != null && pl.getQty().signum() != 0 && ln.getQty() != null) {
                // 含税按来源采购订单行的「含税金额」等比例分摊（=原始录入含税，避免"四舍五入不含税×(1+税率)"对不上原始含税）
                incl = pl.getAmount().multiply(ln.getQty()).divide(pl.getQty(), 2, java.math.RoundingMode.HALF_UP);
            } else {
                incl = excl.multiply(BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP)))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
            ln.setTaxRate(rate);
            ln.setAmountInclTax(incl);
            ln.setTaxAmount(incl.subtract(excl));            // 税额=含税-不含税(入库成本)
            totalIncl = totalIncl.add(incl);
            totalTax = totalTax.add(incl.subtract(excl));
        }
        r.setLines(lines);
        r.setTotalAmtInclTax(totalIncl);
        r.setTaxAmt(totalTax);
        return r;
    }

    public IPage<BizRecv> page(long current, long size, String status, String recvType, String keyword,
                               Long supplierId, Long warehouseId, String sourcePoNo, Long materialId, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizRecv> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        if (recvType != null && !recvType.isBlank()) qw.eq("recv_type", recvType);
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) qw.in("doc_no", _docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (sourcePoNo != null && !sourcePoNo.isBlank()) qw.like("source_po_no", sourcePoNo);
        // 按物料查:明细里含这个物料的入库单(2026-10-16 查询条件要覆盖表格列)
        if (materialId != null) qw.exists("SELECT 1 FROM biz_recv_line rl WHERE rl.recv_id = biz_recv.id AND rl.material_id = {0}", materialId);
        if (startDate != null) qw.ge("recv_date", startDate);
        if (endDate != null) qw.le("recv_date", endDate);
        // 列表排序：只认白名单，防止列名被拼进 ORDER BY；不在白名单时回落到默认的按录入倒序
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizRecv.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizRecv> page = recvMapper.selectPage(new Page<>(current, size), qw);
        attachMaterialSummary(page.getRecords());
        return page;
    }

    /** 列表富集：为本页每张入库单查物料摘要(统一格式：编码 名称(规格)，顿号分隔)，便于不展开明细即可快速分辨。 */
    private void attachMaterialSummary(List<BizRecv> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(BizRecv::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        String ph = ids.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT rl.recv_id rid, " +
                "GROUP_CONCAT(DISTINCT CONCAT(m.code, ' ', m.name, IF(m.spec IS NULL OR m.spec = '', '', CONCAT('(', m.spec, ')'))) " +
                "ORDER BY m.code SEPARATOR '、') summary, " +
                "GROUP_CONCAT(m.code ORDER BY m.code SEPARATOR '、') codes, " +
                "GROUP_CONCAT(m.name ORDER BY m.code SEPARATOR '、') names, " +
                "GROUP_CONCAT(IFNULL(NULLIF(m.spec,''),'-') ORDER BY m.code SEPARATOR '、') specs " +
                "FROM biz_recv_line rl JOIN md_material m ON m.id = rl.material_id " +
                "WHERE rl.recv_id IN (" + ph + ") GROUP BY rl.recv_id";
        java.util.Map<Long, String[]> map = new java.util.HashMap<>();
        jdbcTemplate.query(sql, (java.sql.ResultSet rs) -> {
            map.put(rs.getLong("rid"), new String[]{rs.getString("summary"), rs.getString("codes"), rs.getString("names"), rs.getString("specs")});
        }, ids.toArray());
        for (BizRecv r : records) {
            if (r.getId() == null) continue;
            String[] v = map.get(r.getId());
            if (v != null) { r.setMaterialSummary(v[0]); r.setMaterialCodes(v[1]); r.setMaterialNames(v[2]); r.setMaterialSpecs(v[3]); }
        }
    }

    public List<BizRecvLine> lines(Long recvId) {
        BizRecv r = recvMapper.selectById(recvId);
        if (r == null) return List.of();
        orgScope.assertOrgVisible(r.getOrgId());
        return lineMapper.selectList(new QueryWrapper<BizRecvLine>().eq("recv_id", recvId).orderByAsc("line_no"));
    }

    /** 列出可被发票核销的暂估入库行（已审批 + 暂估 + 未开票或开票不全） */
    public List<BizRecv> listEstimateForInvoicing(Long supplierId) {
        QueryWrapper<BizRecv> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        qw.eq("recv_type", "ESTIMATE").eq("status", "APPROVED");
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        // 2026-08-25:整单都已开票或已退货的不再出现在待开票列表,否则财务点进去是一张没有可选行的空单
        qw.exists("SELECT 1 FROM biz_recv_line rl WHERE rl.recv_id = biz_recv.id "
                + "AND rl.qty > COALESCE(rl.invoiced_qty,0) + COALESCE(rl.returned_qty,0)");
        qw.orderByAsc("id");
        return recvMapper.selectList(qw);
    }

    public static class RecvLineInput {
        public Long poLineId;
        public BigDecimal qty;
    }

    private String normalizeRecvType(String recvType) {
        if (recvType == null || recvType.isBlank()) return "ESTIMATE";
        String value = recvType.trim().toUpperCase();
        if (!"ESTIMATE".equals(value) && !"INVOICE".equals(value)) {
            throw new BizException("入库类型只能是暂估入库或发票入库");
        }
        return value;
    }
}
