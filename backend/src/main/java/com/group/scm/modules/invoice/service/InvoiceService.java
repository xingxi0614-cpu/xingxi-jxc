package com.group.scm.modules.invoice.service;

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
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.invoice.entity.BizInvoice;
import com.group.scm.modules.invoice.entity.BizInvoiceLine;
import com.group.scm.modules.invoice.mapper.BizInvoiceLineMapper;
import com.group.scm.modules.invoice.mapper.BizInvoiceMapper;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 发票服务（核心：暂估冲销自动调差）。
 *
 * <p>关键流程：</p>
 * <ol>
 *   <li>财务录入发票，关联到具体入库行（recvLineId）</li>
 *   <li>提交审批 → 财务审批</li>
 *   <li>审批通过时：</li>
 *   <ul>
 *     <li>如果关联的入库是「暂估」：差额 = (发票单价 - 暂估单价) × 数量 → 调整库存金额、重算结存单价</li>
 *     <li>如果是「发票入库」：直接核销</li>
 *   </ul>
 *   <li>更新入库单的 invoice_id / invoice_no，状态转 MATCHED</li>
 *   <li>在单据链添加 OFFSET 关系（发票 → 入库）</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final BizInvoiceMapper invMapper;
    private final BizInvoiceLineMapper lineMapper;
    private final BizRecvMapper recvMapper;
    private final BizRecvLineMapper recvLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final com.group.scm.modules.inventory.service.RecvLineUsage recvUsage;
    private final DocRelationService relationService;
    private final PoService poService;
    private final PeriodCloseService periodCloseService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    @Transactional(rollbackFor = Exception.class)
    public BizInvoice create(BizInvoice dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty())
            throw new BizException("至少录入一行");

        dto.setDocNo(docNoService.next("INV"));
        if (dto.getInvoiceDate() == null) dto.setInvoiceDate(LocalDate.now());
        dto.setStatus("DRAFT");
        // 计算合计
        BigDecimal totalQty = BigDecimal.ZERO, netAmt = BigDecimal.ZERO, taxAmt = BigDecimal.ZERO;
        Long sourceOrgId = null;
        java.util.Map<Long, BigDecimal> currentInvoiceQty = new java.util.HashMap<>();
        int i0 = -1;
        for (var l : dto.getLines()) {
            i0++;
            if (l.getRecvLineId() == null) throw new BizException("发票行必须关联入库明细");
            BizRecvLine recvLine = recvLineMapper.selectById(l.getRecvLineId());
            if (recvLine == null) throw new BizException("入库行不存在：" + l.getRecvLineId());
            BizRecv recv = recvMapper.selectById(recvLine.getRecvId());
            if (recv != null) orgScope.assertOrgVisible(recv.getOrgId());
            if (recv == null || !"APPROVED".equals(recv.getStatus())) throw new BizException("只能对已审批入库单开票");
            if (sourceOrgId == null) {
                sourceOrgId = recv.getOrgId();
            } else if (!sourceOrgId.equals(recv.getOrgId())) {
                throw new BizException("同一张发票不能核销不同账套/组织的入库单");
            }
            // 跨月收票允许：核销只回写进度计数，暂估冲差全额计入【当期】损益（2026-08-21 政策），不动入库单所在期间的账
            if (dto.getSupplierId() == null) dto.setSupplierId(recv.getSupplierId());
            if (!dto.getSupplierId().equals(recv.getSupplierId())) throw new BizException("发票供应商与入库单供应商不一致");
            BigDecimal already = recvLine.getInvoicedQty() == null ? BigDecimal.ZERO : recvLine.getInvoicedQty();
            BigDecimal occupied = recvLineMapper.activeInvoiceQty(recvLine.getId());
            if (occupied == null) occupied = BigDecimal.ZERO;
            BigDecimal currentOccupied = currentInvoiceQty.getOrDefault(recvLine.getId(), BigDecimal.ZERO);
            // 2026-08-25:已退供应商的数量不会再有发票,必须从可开票余量里扣掉,否则退货后还能核销、暂估被重复冲
            BigDecimal returned = recvLine.getReturnedQty() == null ? BigDecimal.ZERO : recvLine.getReturnedQty();
            BigDecimal remain = recvLine.getQty().subtract(already).subtract(returned).subtract(occupied).subtract(currentOccupied);
            if (l.getQty() == null || l.getQty().signum() <= 0) throw new BizException("发票数量必须大于 0");
            if (l.getQty().compareTo(remain) > 0) throw new BizException("发票数量超过入库行可开票数量，剩余：" + remain + "。已有草稿或待审发票占用数量：" + occupied);
            if (l.getUnitPrice() == null || l.getUnitPrice().signum() < 0) throw new BizException("发票单价不能为负");
            if (l.getTaxRate() == null) l.setTaxRate(BigDecimal.ZERO);
            if (l.getTaxRate().signum() < 0) throw new BizException("发票税率不能为负");
            l.setMaterialId(recvLine.getMaterialId());
            /*
             * 🔴 2026-08-05 改为【金额说了算,单价只作显示】(业务部门需求)。
             *
             * 起因:发票上的不含税单价常是多位小数(如 1.8584),系统按 2 位显示,
             * 用「数量 × 两位单价」乘出来的金额与发票的价税合计对不上几分钱,
             * 而财务必须保证「系统里的发票金额 = 实际发票金额」。
             *
             * 新口径:录入人照发票填【价税合计】,系统倒算不含税金额与税额:
             *     不含税金额 = 价税合计 ÷ (1+税率)   税额 = 价税合计 − 不含税金额
             * 这样三者恒等,永远不会再冒出分位差;而单价退化为参考值。
             * 没填价税合计的(老接口/批量导入)按原来的「数量×单价×(1+税率)」算,行为不变。
             */
            BigDecimal rate = l.getTaxRate();
            BigDecimal calcNet = l.getQty().multiply(l.getUnitPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal calcTax = calcNet.multiply(rate).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal calcTotal = calcNet.add(calcTax);
            l.setCalcTotalAmt(calcTotal);

            BigDecimal typed = l.getTotalAmt();
            if (typed != null && typed.signum() > 0)
                typed = com.group.scm.common.FinancialInput.positiveMoney(typed, "第 " + (i0 + 1) + " 行价税合计");
            // 🔴 负数必须明确拒绝,不能当成"没填"。
            //    原来写在下面 else 分支里的那句判断是【永远执行不到的死代码】——
            //    signum() <= 0 在上一个条件里就已经被归进"没填"了,负数会被静默按
            //    数量×单价重算,录入人以为自己填了个负数、系统却按另一个数入账。
            if (typed != null && typed.signum() < 0) {
                throw new BizException("第 " + (i0 + 1) + " 行价税合计不能为负数,请核对后重填。");
            }
            // 0 元发票行一律拒绝(2026-09-29 定)：现实中没有 0 元增值税发票，
            // 且 0 元核销会把整笔暂估全额冲进损益(2026-08-21 差异口径)
            if ((typed == null || typed.signum() == 0) && calcTotal.signum() <= 0) {
                throw new BizException("第 " + (i0 + 1) + " 行发票金额必须大于 0，请按发票填写价税合计或单价。");
            }
            if (typed == null || typed.signum() <= 0 || typed.compareTo(calcTotal) == 0) {
                // 没填 / 填的与系统算的一致 → 走原路径
                l.setAmount(calcNet);
                l.setTaxAmount(calcTax);
                l.setAmtSource("AUTO");
            } else {
                // 手工填了价税合计 → 以它为准倒算
                // 防止有人借"精度调整"之名大幅改动成本:超过 1% 直接拒绝
                BigDecimal diff = typed.subtract(calcTotal).abs();
                if (calcTotal.signum() > 0
                        && diff.divide(calcTotal, 6, java.math.RoundingMode.HALF_UP)
                               .compareTo(new BigDecimal("0.01")) > 0) {
                    throw new BizException(String.format(
                            "第 %d 行价税合计 %s 与按数量单价算出的 %s 相差超过 1%%,不像是小数精度差异。"
                          + "请核对数量或单价是否录错;确需按此金额入账,请调整单价后再录。",
                            i0 + 1, typed.toPlainString(), calcTotal.toPlainString()));
                }
                BigDecimal net = typed.multiply(new BigDecimal(100))
                        .divide(new BigDecimal(100).add(rate), 2, java.math.RoundingMode.HALF_UP);
                l.setAmount(net);
                l.setTaxAmount(typed.subtract(net));   // 相减而不是再算一次,保证 净额+税额 恒等于价税合计
                l.setAmtSource("MANUAL");
            }
            currentInvoiceQty.put(recvLine.getId(), currentOccupied.add(l.getQty()));
            totalQty = totalQty.add(l.getQty());
            netAmt = netAmt.add(l.getAmount());
            taxAmt = taxAmt.add(l.getTaxAmount());
        }
        if (sourceOrgId == null) throw new BizException("发票行必须关联入库明细");
        dto.setOrgId(sourceOrgId);
        periodCloseService.assertPeriodOpen(dto.getInvoiceDate(), dto.getOrgId(), "采购发票", dto.getDocNo());
        dto.setTotalQty(totalQty);
        dto.setNetAmt(netAmt);
        dto.setTaxAmt(taxAmt);
        dto.setTotalAmt(netAmt.add(taxAmt));
        dto.setAdjustAmt(BigDecimal.ZERO);
        invMapper.insert(dto);
        int i = 1;
        for (var l : dto.getLines()) {
            l.setInvoiceId(dto.getId()); l.setLineNo(i++);
            lineMapper.insert(l);
        }
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizInvoice v = invMapper.selectById(id);
        if (v != null) orgScope.assertOrgVisible(v.getOrgId());
        if (v == null) throw new BizException("发票不存在");
        if (!"DRAFT".equals(v.getStatus())) throw new BizException("当前状态不能提交");
        periodCloseService.assertPeriodOpen(v.getInvoiceDate(), v.getOrgId(), "采购发票", v.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (invMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizInvoice>()
                .eq("id", id).eq("status", "DRAFT").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("INV", id, v.getDocNo(), v.getTotalAmt());
    }

    /** 取消提交：提交人在发票审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizInvoice v = invMapper.selectById(id);
        if (v == null) throw new BizException("发票不存在");
        orgScope.assertOrgVisible(v.getOrgId());
        if (!"PENDING".equals(v.getStatus())) throw new BizException("只有待审批的发票可以取消提交");
        periodCloseService.assertPeriodOpen(v.getInvoiceDate(), v.getOrgId(), "采购发票", v.getDocNo());
        wfService.withdraw("INV", id);
        v.setStatus("DRAFT");
        invMapper.updateById(v);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizInvoice v = invMapper.selectById(id);
        if (v == null) throw new BizException("发票不存在");
        orgScope.assertOrgVisible(v.getOrgId());
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(v.getCreatedBy(), v.getStatus());
        if (!"DRAFT".equals(v.getStatus()) && !"REJECTED".equals(v.getStatus()) && !("CANCELLED".equals(v.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的发票可以删除");
        }
        periodCloseService.assertPeriodOpen(v.getInvoiceDate(), v.getOrgId(), "采购发票", v.getDocNo());
        lineMapper.delete(new QueryWrapper<BizInvoiceLine>().eq("invoice_id", id));
        invMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("INV", id);
    }

    /**
     * 审批通过 → 自动核销 + 暂估冲销调差。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizInvoice invoice = invMapper.selectById(id);
        if (invoice == null) throw new BizException("发票不存在");
        orgScope.assertOrgVisible(invoice.getOrgId());
        periodCloseService.assertPeriodOpen(invoice.getInvoiceDate(), invoice.getOrgId(), "采购发票", invoice.getDocNo());
        wfService.approve("INV", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (invMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizInvoice>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizInvoice v = invMapper.selectById(bizId);
            BigDecimal totalAdjust = BigDecimal.ZERO;
            // 本张发票里各成本池已被前面几行认领的在库数量(计算审查 U02 修法 A,见 WaInventoryService#adjustEstimateDiff)
            java.util.Map<String, BigDecimal> usedStock = new java.util.HashMap<>();
            // 本张发票各行已分走的入库额:同一入库行出现多行时,后面的行要扣掉前面行分走的(P0 复查 P2)
            var docAlloc = new com.group.scm.modules.inventory.service.RecvLineUsage.DocAllocation();
            List<BizInvoiceLine> lns = lineMapper.selectList(new QueryWrapper<BizInvoiceLine>().eq("invoice_id", bizId));
            for (BizInvoiceLine il : lns) {
                BizRecvLine rl = recvLineMapper.selectById(il.getRecvLineId());
                if (rl == null) throw new BizException("入库行不存在：" + il.getRecvLineId());
                BizRecv r = recvMapper.selectById(rl.getRecvId());
                if (r != null) orgScope.assertOrgVisible(r.getOrgId());
                if (r == null || !"APPROVED".equals(r.getStatus())) throw new BizException("只能核销已审批入库单");
                if (!v.getOrgId().equals(r.getOrgId())) throw new BizException("发票账套与入库单账套不一致，不能核销");
                if (!v.getSupplierId().equals(r.getSupplierId())) throw new BizException("发票供应商与入库单供应商不一致");
                if (il.getQty() == null || il.getQty().signum() <= 0) throw new BizException("发票数量必须大于 0");
                // 🔴 2026-10-18(计算审查 U14/U22):先拿公司成本锁、再锁入库行(当前读),再按「原金额用量账」分入库额。
                //    以前用普通查询读到的数(两张同时审批时可能是过时快照),再按累计数量首尾相减反推,
                //    撤销较早的发票后重开、或两张并发审批,会冒出没有真实价差的假调整。
                waService.lockCompanyCostByWarehouse(r.getWarehouseId());
                com.group.scm.modules.inventory.service.RecvLineUsage.Line locked = recvUsage.lock(rl.getId());
                BigDecimal remain = locked.remainingQty();
                if (il.getQty().compareTo(remain) > 0) throw new BizException("发票数量超过入库行未开票数量，剩余：" + remain);

                /*
                 * ★ 采购成本差额(2026-10-16 B 方案,暂估开票与货票同到【同一规则】)★
                 * 差额 = 发票不含税金额 − 入库金额按本次开票数量折算;在库部分调库存单价、已领用部分进损益
                 * (拆分在 WaInventoryService#adjustEstimateDiff / CostMath#splitDiff)。
                 * 货票同到以前只核销不算差额:应付按发票、库存按订单价,差额凭空消失。
                 *
                 * 折算用【原金额用量账】(2026-10-18 起,见 RecvLineUsage):从"入库额 − 已审批发票用量 − 已审批退货额"里
                 * 按剩余比例分,最后一笔拿走全部剩余。以前的"累计数量首尾相减"在撤销较早发票、并发审批时会冒假差额(U14/U22)。
                 * (2026-08-05 起差额以金额为准,不用「单价差 × 数量」:发票金额可能照价税合计手工填过。)
                 */
                boolean estimate = "ESTIMATE".equals(r.getRecvType());
                BigDecimal recvAmtForQty = recvUsage.allocateForDoc(docAlloc, locked, v.getId(), null, il.getQty());
                // 记下本行用掉的入库额:后面的发票/退货从剩余里分;本发票取消审批后它自动不再计入
                il.setRecvAmtUsed(recvAmtForQty);
                lineMapper.updateById(il);
                BigDecimal amountDiff = il.getAmount().subtract(recvAmtForQty);
                BigDecimal priceDiff = il.getUnitPrice() == null || rl.getUnitPrice() == null ? BigDecimal.ZERO
                        : il.getUnitPrice().subtract(rl.getUnitPrice());
                String pool = waService.poolKey(rl.getMaterialId(), r.getWarehouseId());
                BigDecimal[] split = waService.adjustEstimateDiff(
                        rl.getMaterialId(), r.getWarehouseId(), priceDiff, amountDiff, il.getQty(), recvAmtForQty,
                        usedStock.getOrDefault(pool, BigDecimal.ZERO),
                        "INV", v.getId(), v.getDocNo(),
                        String.format("%s %s：入库价 %s → 发票价 %s", estimate ? "暂估开票" : "货票同到开票",
                                r.getDocNo(), rl.getUnitPrice(), il.getUnitPrice()));
                BigDecimal delta = split[0];   // 总差额 = 进库存 split[1] + 进损益 split[2]
                usedStock.merge(pool, split[3], BigDecimal::add);
                totalAdjust = totalAdjust.add(delta);

                if (!estimate && r.getInvoiceId() == null) {
                    r.setInvoiceId(v.getId()); r.setInvoiceNo(v.getDocNo());
                }
                // 入库行已开票数 += 本次发票数量
                if (recvLineMapper.addInvoicedQty(rl.getId(), il.getQty()) != 1) {
                    throw new BizException("发票数量超过入库行未开票数量，请刷新后重试");
                }
                markRecvInvoicedIfComplete(r, v);
                // 单据链:暂估 = OFFSET(冲暂估,金额记差额);货票同到 = MATCH(核销,金额记发票额)
                relationService.addRelation("INV", v.getId(), v.getDocNo(),
                        "RECV", r.getId(), r.getDocNo(),
                        estimate ? "OFFSET" : "MATCH", il.getQty(), estimate ? delta : il.getAmount());
                // 回写订单已开票数
                poService.addInvoiced(r.getSourcePoId(), rl.getSourcePoLineId(), il.getQty());
            }
            v.setAdjustAmt(totalAdjust);
            v.setStatus("MATCHED");
            invMapper.updateById(v);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizInvoice invoice = invMapper.selectById(id);
        if (invoice == null) throw new BizException("发票不存在");
        orgScope.assertOrgVisible(invoice.getOrgId());
        periodCloseService.assertPeriodOpen(invoice.getInvoiceDate(), invoice.getOrgId(), "采购发票", invoice.getDocNo());
        wfService.reject("INV", id, comment, (no, bizId) -> {
            BizInvoice v = invMapper.selectById(bizId);
            v.setStatus("REJECTED");
            invMapper.updateById(v);
        });
    }

    public BizInvoice detail(Long id) {
        BizInvoice v = invMapper.selectById(id);
        if (v == null) return null;
        orgScope.assertOrgVisible(v.getOrgId());
        v.setLines(lineMapper.selectList(new QueryWrapper<BizInvoiceLine>().eq("invoice_id", id).orderByAsc("line_no")));
        return v;
    }

    public IPage<BizInvoice> page(long current, long size, String status, Long supplierId, String keyword,
                                  java.time.LocalDate startDate, java.time.LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizInvoice> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        if (startDate != null) qw.ge("invoice_date", startDate);
        if (endDate != null) qw.le("invoice_date", endDate);
        if (keyword != null && !keyword.isBlank()) {
            {
                java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
                // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
                if (_docNos != null) qw.in("doc_no", _docNos);
                else qw.and(w -> w.like("doc_no", keyword).or().like("invoice_no", keyword));
            }
        }
        // 列表排序：只认白名单，防止列名被拼进 ORDER BY；不在白名单时回落到默认的按录入倒序
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizInvoice.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        return invMapper.selectPage(new Page<>(current, size), qw);
    }

    private void markRecvInvoicedIfComplete(BizRecv recv, BizInvoice invoice) {
        List<BizRecvLine> lines = recvLineMapper.selectList(new QueryWrapper<BizRecvLine>().eq("recv_id", recv.getId()));
        boolean complete = true;
        for (BizRecvLine line : lines) {
            BigDecimal invoiced = line.getInvoicedQty() == null ? BigDecimal.ZERO : line.getInvoicedQty();
            if (invoiced.compareTo(line.getQty()) < 0) {
                complete = false;
                break;
            }
        }
        recv.setInvoiceId(invoice.getId());
        recv.setInvoiceNo(invoice.getDocNo());
        if (complete) recv.setRecvType("INVOICE");
        recvMapper.updateById(recv);
    }
}
