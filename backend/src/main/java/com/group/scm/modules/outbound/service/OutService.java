package com.group.scm.modules.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.inventory.service.SubmitStockGuard;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.outbound.entity.*;
import com.group.scm.modules.outbound.mapper.*;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 材料出库服务。
 * <p>审批通过时调用 WaInventoryService.outbound：</p>
 * <ul>
 *   <li>用当时的加权平均结存单价计算成本</li>
 *   <li>实际扣减库存</li>
 *   <li>不改变结存单价</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OutService {

    private final BizOutMapper outMapper;
    private final BizOutLineMapper outLineMapper;
    private final BizOutReqMapper reqMapper;
    private final BizOutReqLineMapper reqLineMapper;
    private final BizReturnLineMapper returnLineMapper;
    private final OutReqIntegrity integrity;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final WaInventoryService waService;
    private final OutReqService outReqService;
    private final DocRelationService relationService;
    private final PeriodCloseService periodCloseService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final com.group.scm.modules.inventory.service.SubmitStockGuard stockGuard;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BizOut fromOreq(Long oreqId, List<OutLineInput> inputs, String outType) {
        BizOutReq req = reqMapper.findForUpdate(oreqId);
        if (req != null) orgScope.assertOrgVisible(req.getOrgId());
        if (req == null) throw new BizException("申请不存在");
        if (!"APPROVED".equals(req.getStatus()) && !"PARTIAL_OUT".equals(req.getStatus()))
            throw new BizException("申请未审批或已关闭");
        if("TRANSFER".equals(req.getFulfillmentMode())) throw new BizException("此申请按转库领料，请在申请详情登记，不能重复材料出库");
        jdbcTemplate.update("UPDATE biz_out_req SET fulfillment_mode='DIRECT' WHERE id=?",oreqId);
        jdbcTemplate.update("UPDATE notif SET is_read=1,read_at=NOW() WHERE biz_type='OREQ' AND biz_id=? AND todo_kind='PICKUP'",oreqId);
        if (inputs == null || inputs.isEmpty()) throw new BizException("请至少选择一行出库");
        // 跨月申请允许出库：出库按【出库日期】入账，只校验出库单自己的期间；回写申请的已出库数只是进度计数
        LocalDate outDate = LocalDate.now();
        periodCloseService.assertPeriodOpen(outDate, req.getOrgId(), "材料出库单", null);

        BizOut out = new BizOut();
        out.setDocNo(docNoService.next("OUT"));
        out.setSourceReqId(oreqId);
        out.setSourceReqNo(req.getDocNo());
        out.setWarehouseId(req.getWarehouseId());
        out.setDeptId(req.getDeptId());
        out.setOrgId(req.getOrgId());
        out.setProjectId(req.getProjectId());
        out.setProjectName(req.getProjectName());
        out.setConstructionUnit(req.getConstructionUnit());
        out.setOutType(resolveOutType(outType, req.getOutType()));
        out.setBusinessCategory(req.getBusinessCategory());
        out.setMaterialReceiver(req.getMaterialApplicant());
        out.setOutDate(outDate);
        out.setStatus("DRAFT");
        outMapper.insert(out);

        BigDecimal totalQty = BigDecimal.ZERO;
        int i = 1;
        for (OutLineInput in : inputs) {
            if (in == null || in.reqLineId == null) throw new BizException("申请行不能为空");
            BizOutReqLine rl = reqLineMapper.selectById(in.reqLineId);
            if (rl == null) throw new BizException("申请行不存在");
            if (!oreqId.equals(rl.getOutReqId())) throw new BizException("申请行不属于当前出库申请：" + in.reqLineId);
            if (in.qty == null || in.qty.signum() <= 0 || in.qty.stripTrailingZeros().scale() > 4) throw new BizException("出库数量必须大于 0 且最多4位小数");
            BigDecimal activeOutQty = outLineMapper.sumActiveOutQtyByReqLine(rl.getId());
            BigDecimal postedOutQty = rl.getOutQty() == null ? BigDecimal.ZERO : rl.getOutQty();
            BigDecimal usedQty = activeOutQty == null ? postedOutQty : activeOutQty.max(postedOutQty);
            BigDecimal remain = rl.getQty().subtract(usedQty);
            if (in.qty.compareTo(remain) > 0) throw new BizException("第 " + rl.getLineNo() + " 行 " + com.group.scm.common.MaterialLabel.of(jdbcTemplate, rl.getMaterialId())
                    + "：本次出库 " + com.group.scm.common.MaterialLabel.qty(in.qty) + " 超过申请未出数量 " + com.group.scm.common.MaterialLabel.qty(remain.max(BigDecimal.ZERO))
                    + "。如确需多领，请先让采购修订出库申请");

            BizOutLine ln = new BizOutLine();
            ln.setOutId(out.getId()); ln.setLineNo(i++);
            ln.setSourceReqLineId(rl.getId());
            ln.setMaterialId(rl.getMaterialId());
            ln.setQty(in.qty);
            ln.setUnitPrice(BigDecimal.ZERO); // 审批时实际填入
            ln.setAmount(BigDecimal.ZERO);
            outLineMapper.insert(ln);

            totalQty = totalQty.add(in.qty);
        }
        out.setTotalQty(totalQty);
        out.setTotalAmt(BigDecimal.ZERO);
        outMapper.updateById(out);
        return out;
    }

    /** 出库类型在出库环节确定：必须在 out.type.options 白名单内（决定成本科目，不能随意传串）。老申请带的类型作兜底。 */
    private String resolveOutType(String outType, String fallback) {
        String t = outType != null && !outType.isBlank() ? outType.trim() : fallback;
        if (t == null || t.isBlank()) throw new BizException("请选择出库类型");
        String options = jdbcTemplate.query(
                "SELECT param_value FROM sys_param WHERE param_key = 'out.type.options'",
                rs -> rs.next() ? rs.getString(1) : null);
        if (options != null && !options.isBlank()) {
            boolean ok = java.util.Arrays.stream(options.split("[,，]")).map(String::trim).anyMatch(t::equals);
            if (!ok) throw new BizException("出库类型「" + t + "」不在可选范围内，请在下拉中选择");
        }
        return t;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void submit(Long id) {
        integrity.lockSourceForOut(id);
        BizOut o = outMapper.selectById(id);
        if (o != null) orgScope.assertOrgVisible(o.getOrgId());
        if (o == null) throw new BizException("出库单不存在");
        if (!List.of("DRAFT", "REJECTED").contains(o.getStatus())) throw new BizException("当前状态不能提交");
        BizOutReq source = requireSource(o);
        List<BizOutLine> lines = outLineMapper.selectList(new QueryWrapper<BizOutLine>().eq("out_id", id));
        validateQuantities(source, lines, id, true);
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "材料出库单", o.getDocNo());
        // 提交时就查库存（账面 − 其他待审批占用），不够不进审批队列；审批过账时库存引擎仍会再拦一次（见 SubmitStockGuard）
        stockGuard.assertAvailable(SubmitStockGuard.OUT, id, o.getWarehouseId(),
                lines.stream().map(l -> new SubmitStockGuard.Need(l.getMaterialId(), l.getQty())).toList(), null);
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (outMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizOut>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("OUT", id, o.getDocNo(), BigDecimal.ZERO);
    }

    /** 取消提交：提交人在出库单审批通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        integrity.lockSourceForOut(id);
        BizOut o = outMapper.selectById(id);
        if (o == null) throw new BizException("出库单不存在");
        orgScope.assertOrgVisible(o.getOrgId());
        if (!"PENDING".equals(o.getStatus())) throw new BizException("只有待审批的出库单可以取消提交");
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "材料出库单", o.getDocNo());
        wfService.withdraw("OUT", id);
        o.setStatus("DRAFT");
        outMapper.updateById(o);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        integrity.lockSourceForOut(id);
        BizOut o = outMapper.selectById(id);
        if (o == null) throw new BizException("出库单不存在");
        orgScope.assertOrgVisible(o.getOrgId());
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(o.getCreatedBy(), o.getStatus());
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()) && !("CANCELLED".equals(o.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的出库单可以删除");
        }
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "材料出库单", o.getDocNo());
        outLineMapper.delete(new QueryWrapper<BizOutLine>().eq("out_id", id));
        outMapper.deleteById(id);
        notifService.closeBusinessTodos("OUT", id);   // 删单同时关闭其待办,避免留下点开为空的孤儿待办
    }

    /**
     * 审批通过 → 实时按当前加权平均单价扣库存
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void approve(Long id, String comment) {
        integrity.lockSourceForOut(id);
        BizOut out = outMapper.selectById(id);
        if (out == null) throw new BizException("出库单不存在");
        orgScope.assertOrgVisible(out.getOrgId());
        BizOutReq source = requireSource(out);
        periodCloseService.assertPeriodOpen(out.getOutDate(), out.getOrgId(), "材料出库单", out.getDocNo());
        wfService.approve("OUT", id, comment, (no, bizId) -> {
            List<BizOutLine> lns = outLineMapper.selectList(new QueryWrapper<BizOutLine>().eq("out_id", bizId));
            validateQuantities(source, lns, bizId, false);
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (outMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizOut>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizOut o = outMapper.selectById(bizId);
            BigDecimal totalAmt = BigDecimal.ZERO;
            for (BizOutLine ln : lns) {
                BigDecimal[] r = waService.outbound(ln.getMaterialId(), o.getWarehouseId(),
                        ln.getQty(), "OUT", o.getId(), o.getDocNo(), ln.getId(), "材料出库");
                ln.setUnitPrice(r[0]);
                ln.setAmount(r[1]);
                outLineMapper.updateById(ln);
                totalAmt = totalAmt.add(r[1]);

                // 回写申请
                outReqService.addOut(o.getSourceReqId(), ln.getSourceReqLineId(), ln.getQty());
            }
            o.setTotalAmt(totalAmt);
            o.setStatus("APPROVED");
            outMapper.updateById(o);

            relationService.addRelation("OREQ", o.getSourceReqId(), o.getSourceReqNo(),
                    "OUT", o.getId(), o.getDocNo(),
                    "DOWN_PUSH", o.getTotalQty(), totalAmt);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        integrity.lockSourceForOut(id);
        BizOut o = outMapper.selectById(id);
        if (o != null) orgScope.assertOrgVisible(o.getOrgId());
        if (o == null) throw new BizException("出库单不存在");
        periodCloseService.assertPeriodOpen(o.getOutDate(), o.getOrgId(), "材料出库单", o.getDocNo());
        wfService.reject("OUT", id, comment, (no, bizId) -> {
            BizOut out = outMapper.selectById(bizId);
            out.setStatus("REJECTED");
            outMapper.updateById(out);
        });
    }

    private BizOutReq requireSource(BizOut out) {
        BizOutReq req = out.getSourceReqId() == null ? null : reqMapper.findForUpdate(out.getSourceReqId());
        if (req == null || !List.of("APPROVED", "PARTIAL_OUT").contains(req.getStatus())) {
            throw new BizException("原出库申请未审批、已全部出库或已关闭，不能提交或审批本出库单");
        }
        if ("TRANSFER".equals(req.getFulfillmentMode())) throw new BizException("原申请使用转库领料，不能重复材料出库");
        return req;
    }

    /** 持有申请头锁后复核真实已审出库和真实再领退料，不能相信客户端可接触的回补计数。 */
    private void validateQuantities(BizOutReq req, List<BizOutLine> lines, Long outId, boolean includeReservations) {
        if (lines.isEmpty()) throw new BizException("出库明细不能为空");
        java.util.Map<Long, BigDecimal> quantities = new java.util.HashMap<>();
        for (BizOutLine line : lines) {
            if (line.getSourceReqLineId() == null || line.getQty() == null || line.getQty().signum() <= 0
                    || line.getQty().stripTrailingZeros().scale() > 4) throw new BizException("出库行来源或数量无效");
            BizOutReqLine source = reqLineMapper.selectById(line.getSourceReqLineId());
            if (source == null || !req.getId().equals(source.getOutReqId())
                    || !source.getMaterialId().equals(line.getMaterialId())) throw new BizException("出库明细与原申请行不匹配，请核对来源");
            quantities.merge(source.getId(), line.getQty(), BigDecimal::add);
        }
        for (var item : quantities.entrySet()) {
            BizOutReqLine source = reqLineMapper.selectById(item.getKey());
            BigDecimal actual = outLineMapper.sumApprovedOutQtyByReqLine(source.getId())
                    .subtract(returnLineMapper.sumApprovedReissueQtyByReqLine(source.getId()));
            if (actual.signum() < 0) throw new BizException("原申请实际出库与再领退料数量异常，请核对历史单据");
            BigDecimal used = actual.max(source.getOutQty() == null ? BigDecimal.ZERO : source.getOutQty());
            if (includeReservations) used = used.add(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(l.qty),0) FROM biz_out_line l JOIN biz_out o ON o.id=l.out_id
                    WHERE l.source_req_line_id=? AND o.deleted=0 AND o.status IN ('DRAFT','PENDING') AND o.id<>?
                    """, BigDecimal.class, source.getId(), outId));
            if (used.add(item.getValue()).compareTo(source.getQty()) > 0) throw new BizException("第 " + source.getLineNo()
                    + " 行出库数量超过原申请可出数量，请先处理其他出库单或修订申请");
        }
    }

    public BizOut detail(Long id) {
        BizOut o = outMapper.selectById(id);
        if (o == null) return null;
        orgScope.assertOrgVisible(o.getOrgId());
        o.setLines(outLineMapper.selectList(new QueryWrapper<BizOutLine>().eq("out_id", id).orderByAsc("line_no")));
        fillFromSourceReq(List.of(o));
        return o;
    }

    public IPage<BizOut> page(long current, long size, String status, String keyword, Long warehouseId,
                              String sourceReqNo, Long projectId, String constructionUnit, String outType,
                              String businessCategory, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder,
                              String reqText) {
        QueryWrapper<BizOut> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        java.util.List<String> _docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        // 逗号分隔=月末结账「去处理」带来的问题单号集合，只显示这几张
        if (_docNos != null) qw.in("doc_no", _docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (sourceReqNo != null && !sourceReqNo.isBlank()) qw.like("source_req_no", sourceReqNo);
        if (projectId != null) qw.eq("project_id", projectId);
        if (constructionUnit != null && !constructionUnit.isBlank()) qw.like("construction_unit", constructionUnit.trim());
        if (outType != null && !outType.isBlank()) qw.eq("out_type", outType);
        if (businessCategory != null && !businessCategory.isBlank()) qw.eq("business_category", businessCategory);
        if (startDate != null) qw.ge("out_date", startDate);
        if (endDate != null) qw.le("out_date", endDate);
        // 用途/备注关键词:这两段只存在出库申请上,按来源申请反查。
        // 仓管拿它查"这个人领过没有"(用户反馈查不了重复领料)。参数化传值,不拼接 SQL。
        if (reqText != null && !reqText.isBlank()) {
            String like = "%" + reqText.trim() + "%";
            qw.apply("source_req_id IN (SELECT r.id FROM biz_out_req r WHERE r.deleted = 0 AND (r.purpose LIKE {0} OR r.remark LIKE {1}))",
                    like, like);
        }
        // 列表排序：只认白名单，防止列名被拼进 ORDER BY；不在白名单时回落到默认的按录入倒序
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizOut.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        IPage<BizOut> result = outMapper.selectPage(new Page<>(current, size), qw);
        fillFromSourceReq(result.getRecords());
        return result;
    }

    /**
     * 把来源出库申请上的「用途」「备注」(含明细行备注)带到出库单上显示。
     *
     * <p><b>为什么查询时读,而不是生成出库单时复制一份</b>(2026-09-10):
     * {@link #fromOreq} 原来只复制了项目/单位/出库类型/业务分类/领料人,漏了用途和备注 ——
     * 来源申请写了备注的,出库单上一张都看不到;出库单页面、打印单、
     * 手机端也都没有"用途"一栏,仓管打印领料单时查不到"这批料领给了谁"。
     * 改成查询时从申请单读:① 历史单据不用补数据,上线即显示;② 申请单修订后出库单跟着对;
     * ③ 不往 biz_out 里写东西,不会和出库单自己的 remark 打架。</p>
     *
     * <p>申请单和出库单同属一个账套,出库单能看到,它的来源申请就能看到,不存在越权读取。
     * 一次 IN 查询取完,不逐行查库。</p>
     */
    private void fillFromSourceReq(List<BizOut> outs) {
        if (outs == null || outs.isEmpty()) return;
        List<Long> reqIds = outs.stream().map(BizOut::getSourceReqId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!reqIds.isEmpty()) {
            java.util.Map<Long, BizOutReq> reqs = new java.util.HashMap<>();
            for (BizOutReq r : reqMapper.selectBatchIds(reqIds)) reqs.put(r.getId(), r);
            for (BizOut o : outs) {
                BizOutReq r = reqs.get(o.getSourceReqId());
                if (r == null) continue;
                o.setPurpose(r.getPurpose());
                o.setReqRemark(r.getRemark());
            }
        }
        List<BizOutLine> lines = outs.stream().filter(o -> o.getLines() != null)
                .flatMap(o -> o.getLines().stream()).toList();
        List<Long> reqLineIds = lines.stream().map(BizOutLine::getSourceReqLineId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (reqLineIds.isEmpty()) return;
        java.util.Map<Long, String> lineRemarks = new java.util.HashMap<>();
        for (BizOutReqLine rl : reqLineMapper.selectBatchIds(reqLineIds)) lineRemarks.put(rl.getId(), rl.getRemark());
        for (BizOutLine l : lines) l.setReqRemark(lineRemarks.get(l.getSourceReqLineId()));
    }

    public static class OutLineInput {
        public Long reqLineId;
        public BigDecimal qty;
    }
}
