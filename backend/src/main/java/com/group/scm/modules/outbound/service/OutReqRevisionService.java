package com.group.scm.modules.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.docrelation.service.DocRelationService;
import com.group.scm.modules.outbound.entity.BizOutReq;
import com.group.scm.modules.outbound.entity.BizOutReqLine;
import com.group.scm.modules.outbound.entity.BizOutReqRevision;
import com.group.scm.modules.outbound.entity.BizOutReqRevisionLine;
import com.group.scm.modules.outbound.mapper.BizOutLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqRevisionLineMapper;
import com.group.scm.modules.outbound.mapper.BizOutReqRevisionMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutReqRevisionService {

    private final BizOutReqMapper reqMapper;
    private final BizOutReqLineMapper lineMapper;
    private final BizOutLineMapper outLineMapper;
    private final BizOutReqRevisionMapper revisionMapper;
    private final BizOutReqRevisionLineMapper revisionLineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final PeriodCloseService periodCloseService;
    private final DocRelationService relationService;

    @Transactional(rollbackFor = Exception.class)
    public BizOutReqRevision create(Long reqId, BizOutReqRevision dto) {
        BizOutReq req = requireRevisableReq(reqId);
        if("TRANSFER".equals(req.getFulfillmentMode())) throw new BizException("调拨申请已进入领料流程；减少需求请处理剩余不领，新增需求请另行申请");
        ensureNoActiveRevision(reqId);
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BizException("修订明细不能为空");
        }
        if (dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException("修订原因不能为空");
        }
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请", req.getDocNo());

        BizOutReqRevision revision = new BizOutReqRevision();
        revision.setDocNo(docNoService.next("OREQ_REV"));
        revision.setSourceReqId(req.getId());
        revision.setSourceReqNo(req.getDocNo());
        revision.setOrgId(req.getOrgId());
        revision.setWarehouseId(req.getWarehouseId());
        revision.setProjectId(req.getProjectId());
        revision.setProjectName(req.getProjectName());
        revision.setRevisionDate(dto.getRevisionDate() == null ? LocalDate.now() : dto.getRevisionDate());
        revision.setReason(dto.getReason().trim());
        revision.setRemark(dto.getRemark());
        revision.setStatus("DRAFT");
        revision.setTotalDeltaQty(BigDecimal.ZERO);
        revisionMapper.insert(revision);

        BigDecimal totalDelta = BigDecimal.ZERO;
        int i = 1;
        for (BizOutReqRevisionLine input : dto.getLines()) {
            BizOutReqRevisionLine line = buildRevisionLine(req, input);
            line.setRevisionId(revision.getId());
            line.setLineNo(i++);
            revisionLineMapper.insert(line);
            totalDelta = totalDelta.add(line.getDeltaQty());
        }
        revision.setTotalDeltaQty(totalDelta);
        revisionMapper.updateById(revision);
        return detail(revision.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizOutReqRevision revision = requireExisting(id);
        if (!"DRAFT".equals(revision.getStatus()) && !"REJECTED".equals(revision.getStatus())) {
            throw new BizException("当前修订单状态不允许提交");
        }
        BizOutReq req = requireExistingReq(revision.getSourceReqId());
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请修订", revision.getDocNo());
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (revisionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizOutReqRevision>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("OREQ_REV", id, revision.getDocNo(), BigDecimal.ZERO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizOutReqRevision revision = requireExisting(id);
        BizOutReq req = requireExistingReq(revision.getSourceReqId());
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请修订", revision.getDocNo());
        wfService.approve("OREQ_REV", id, comment, (no, bizId) -> applyApprovedRevision(bizId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizOutReqRevision revision = requireExisting(id);
        BizOutReq req = requireExistingReq(revision.getSourceReqId());
        periodCloseService.assertPeriodOpen(req.getApplyDate(), req.getOrgId(), "出库申请修订", revision.getDocNo());
        wfService.reject("OREQ_REV", id, comment, (no, bizId) -> {
            BizOutReqRevision next = revisionMapper.selectById(bizId);
            next.setStatus("REJECTED");
            revisionMapper.updateById(next);
        });
    }

    public List<BizOutReqRevision> listByReq(Long reqId) {
        BizOutReq req = requireExistingReq(reqId);
        orgScope.assertOrgVisible(req.getOrgId());
        List<BizOutReqRevision> rows = revisionMapper.selectList(new QueryWrapper<BizOutReqRevision>()
                .eq("source_req_id", reqId)
                .orderByDesc("id"));
        rows.forEach(row -> row.setLines(revisionLineMapper.selectList(
                new QueryWrapper<BizOutReqRevisionLine>().eq("revision_id", row.getId()).orderByAsc("line_no"))));
        rows.forEach(row -> row.setCanApprove("PENDING".equals(row.getStatus())
                && wfService.canApprove("OREQ_REV", row.getId())));
        return rows;
    }

    public BizOutReqRevision detail(Long id) {
        BizOutReqRevision revision = revisionMapper.selectById(id);
        if (revision == null) return null;
        orgScope.assertOrgVisible(revision.getOrgId());
        revision.setLines(revisionLineMapper.selectList(
                new QueryWrapper<BizOutReqRevisionLine>().eq("revision_id", id).orderByAsc("line_no")));
        return revision;
    }

    private void applyApprovedRevision(Long revisionId) {
        BizOutReqRevision revision = requireExisting(revisionId);
        if (!"PENDING".equals(revision.getStatus())) {
            throw new BizException("修订单当前状态不允许审批");
        }
        BizOutReq req = requireExistingReq(revision.getSourceReqId());
        // 🔴 计算审查 U09:原申请被取消审批(退回草稿)并大改后,旧修订单批准时以前会把整张申请恢复成"已审批",
        //    未经重新审批的改动一并生效。加锁读原申请最新状态,不是已审批类状态就不许生效。
        String srcStatus = reqMapper.selectObjs(new QueryWrapper<BizOutReq>().select("status")
                .eq("id", req.getId()).last("FOR UPDATE")).stream().findFirst().map(String::valueOf).orElse(null);
        if (!java.util.Set.of("APPROVED", "PARTIAL_OUT", "OUT").contains(srcStatus)) {
            throw new BizException("原出库申请 " + req.getDocNo() + " 当前不是已审批状态（可能已取消审批并被修改），这张修订单不能生效。请驳回后按最新申请重新提修订");
        }
        List<BizOutReqRevisionLine> lines = revisionLineMapper.selectList(
                new QueryWrapper<BizOutReqRevisionLine>().eq("revision_id", revisionId).orderByAsc("line_no"));
        int nextLineNo = lineMapper.maxLineNo(req.getId()) + 1;
        for (BizOutReqRevisionLine line : lines) {
            if (line.getReqLineId() == null) {
                BizOutReqLine newLine = new BizOutReqLine();
                newLine.setOutReqId(req.getId());
                newLine.setLineNo(nextLineNo++);
                newLine.setMaterialId(line.getMaterialId());
                newLine.setQty(line.getNewQty());
                newLine.setOriginalQty(BigDecimal.ZERO);
                newLine.setRevisedQty(line.getNewQty());
                newLine.setOutQty(BigDecimal.ZERO);
                newLine.setRemark(line.getRemark());
                lineMapper.insert(newLine);
                line.setReqLineId(newLine.getId());
                revisionLineMapper.updateById(line);
                continue;
            }

            BizOutReqLine reqLine = lineMapper.selectById(line.getReqLineId());
            if (reqLine == null || !req.getId().equals(reqLine.getOutReqId())) {
                throw new BizException("修订行对应的出库申请明细不存在");
            }
            BigDecimal usedQty = activeOutQty(reqLine);
            if (line.getNewQty().compareTo(usedQty) < 0) {
                throw new BizException("修订数量不能小于已出库或已占用数量，物料行：" + reqLine.getLineNo());
            }
            BigDecimal originalQty = reqLine.getOriginalQty() == null ? reqLine.getQty() : reqLine.getOriginalQty();
            reqLine.setOriginalQty(originalQty);
            reqLine.setQty(line.getNewQty());
            reqLine.setRevisedQty(line.getNewQty().subtract(originalQty));
            if (line.getRemark() != null && !line.getRemark().isBlank()) {
                reqLine.setRemark(line.getRemark());
            }
            lineMapper.updateById(reqLine);
        }

        BigDecimal totalQty = lineMapper.sumQtyByReqId(req.getId());
        req.setTotalQty(totalQty);
        BigDecimal outQty = req.getOutQty() == null ? BigDecimal.ZERO : req.getOutQty();
        if (outQty.compareTo(totalQty) >= 0) {
            req.setStatus("OUT");
        } else if (outQty.signum() > 0) {
            req.setStatus("PARTIAL_OUT");
        } else {
            req.setStatus("APPROVED");
        }
        reqMapper.updateById(req);

        revision.setStatus("APPROVED");
        revisionMapper.updateById(revision);
        relationService.addRelation("OREQ", req.getId(), req.getDocNo(),
                "OREQ_REV", revision.getId(), revision.getDocNo(),
                "REVISION", revision.getTotalDeltaQty(), BigDecimal.ZERO);
    }

    private BizOutReqRevisionLine buildRevisionLine(BizOutReq req, BizOutReqRevisionLine input) {
        if (input.getNewQty() == null || input.getNewQty().signum() <= 0) {
            throw new BizException("修订后数量必须大于 0");
        }
        BizOutReqRevisionLine line = new BizOutReqRevisionLine();
        line.setReqLineId(input.getReqLineId());
        line.setRemark(input.getRemark());
        if (input.getReqLineId() == null) {
            if (input.getMaterialId() == null) throw new BizException("新增修订行必须选择物料");
            orgScope.assertMaterialVisible(input.getMaterialId());
            line.setMaterialId(input.getMaterialId());
            line.setOldQty(BigDecimal.ZERO);
            line.setNewQty(input.getNewQty());
            line.setDeltaQty(input.getNewQty());
            return line;
        }

        BizOutReqLine reqLine = lineMapper.selectById(input.getReqLineId());
        if (reqLine == null || !req.getId().equals(reqLine.getOutReqId())) {
            throw new BizException("修订行不属于当前出库申请");
        }
        BigDecimal usedQty = activeOutQty(reqLine);
        if (input.getNewQty().compareTo(usedQty) < 0) {
            throw new BizException("修订后数量不能小于已出库或已占用数量，当前已占用：" + usedQty);
        }
        line.setMaterialId(reqLine.getMaterialId());
        line.setOldQty(reqLine.getQty());
        line.setNewQty(input.getNewQty());
        line.setDeltaQty(input.getNewQty().subtract(reqLine.getQty()));
        return line;
    }

    private BigDecimal activeOutQty(BizOutReqLine reqLine) {
        BigDecimal activeOutQty = outLineMapper.sumActiveOutQtyByReqLine(reqLine.getId());
        BigDecimal postedOutQty = reqLine.getOutQty() == null ? BigDecimal.ZERO : reqLine.getOutQty();
        return activeOutQty == null ? postedOutQty : activeOutQty.max(postedOutQty);
    }

    private void ensureNoActiveRevision(Long reqId) {
        Long count = revisionMapper.selectCount(new QueryWrapper<BizOutReqRevision>()
                .eq("source_req_id", reqId)
                .in("status", List.of("DRAFT", "PENDING")));
        if (count != null && count > 0) {
            throw new BizException("该出库申请已有未完成修订单，请先处理后再新增修订");
        }
    }

    private BizOutReq requireRevisableReq(Long reqId) {
        BizOutReq req = requireExistingReq(reqId);
        if (!List.of("APPROVED", "PARTIAL_OUT", "OUT").contains(req.getStatus())) {
            throw new BizException("只有已审批或已部分出库的出库申请可以修订");
        }
        return req;
    }

    private BizOutReq requireExistingReq(Long reqId) {
        BizOutReq req = reqMapper.selectById(reqId);
        if (req == null) throw new BizException("出库申请不存在");
        orgScope.assertOrgVisible(req.getOrgId());
        return req;
    }

    private BizOutReqRevision requireExisting(Long id) {
        BizOutReqRevision revision = revisionMapper.selectById(id);
        if (revision == null) throw new BizException("出库申请修订单不存在");
        orgScope.assertOrgVisible(revision.getOrgId());
        return revision;
    }
}
