package com.group.scm.modules.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.purchase.entity.BizPr;
import com.group.scm.modules.purchase.entity.BizPrLine;
import com.group.scm.modules.purchase.mapper.BizPrLineMapper;
import com.group.scm.modules.purchase.mapper.BizPrMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PrService {

    private final BizPrMapper prMapper;
    private final BizPrLineMapper lineMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WorkflowService wfService;
    private final PoService poService;
    private final PeriodCloseService periodCloseService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;

    @Transactional(rollbackFor = Exception.class)
    public BizPr create(BizPr dto) {
        dto.setDocNo(docNoService.next("PR"));
        dto.setApplicantId(SecurityUtil.uid());
        dto.setDeptId(SecurityUtil.orgId());
        dto.setOrgId(SecurityUtil.orgId());
        validateRequired(dto);
        validateWarehouse(dto);
        periodCloseService.assertPeriodOpen(dto.getApplyDate(), dto.getOrgId(), "采购申请", dto.getDocNo());
        dto.setStatus("DRAFT");

        Totals totals = calculateLines(dto.getLines());
        dto.setTotalQty(totals.qty());
        dto.setTotalAmt(totals.amount());
        prMapper.insert(dto);

        insertLines(dto.getId(), dto.getLines());
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BizPr dto) {
        BizPr cur = requireExisting(id);
        if (!"DRAFT".equals(cur.getStatus()) && !"REJECTED".equals(cur.getStatus())) {
            throw new BizException("只有草稿或已驳回的采购申请可以修改");
        }
        if (!cur.getApplicantId().equals(SecurityUtil.uid()) && !SecurityUtil.isAdmin()) {
            throw new BizException("只有申请人或管理员可以修改该采购申请");
        }
        periodCloseService.assertPeriodOpen(cur.getApplyDate(), cur.getOrgId(), "采购申请", cur.getDocNo());
        validateRequired(dto);
        validateWarehouse(dto);
        periodCloseService.assertPeriodOpen(dto.getApplyDate(), cur.getOrgId(), "采购申请", cur.getDocNo());

        Totals totals = calculateLines(dto.getLines());
        dto.setId(id);
        dto.setDocNo(cur.getDocNo());
        dto.setStatus(cur.getStatus());
        dto.setApplicantId(cur.getApplicantId());
        dto.setDeptId(cur.getDeptId());
        dto.setOrgId(cur.getOrgId());
        dto.setTotalQty(totals.qty());
        dto.setTotalAmt(totals.amount());
        prMapper.updateById(dto);

        lineMapper.delete(new QueryWrapper<BizPrLine>().eq("pr_id", id));
        insertLines(id, dto.getLines());
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizPr p = requireExisting(id);
        if (!"DRAFT".equals(p.getStatus()) && !"REJECTED".equals(p.getStatus())) {
            throw new BizException("当前状态不允许提交：" + p.getStatus());
        }
        if (!p.getApplicantId().equals(SecurityUtil.uid())) {
            throw new BizException("只有申请人可以提交该采购申请");
        }
        periodCloseService.assertPeriodOpen(p.getApplyDate(), p.getOrgId(), "采购申请", p.getDocNo());
        // 并发防线：条件更新，双击提交只有一个成功，防止 fromPr 生成两张采购订单
        if (prMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizPr>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "APPROVED")) != 1) {
            throw new BizException("采购申请状态已变化（可能已被提交），请刷新后重试");
        }
        poService.fromPr(id, p.getWarehouseId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizPr p = requireExisting(id);
        if (!"DRAFT".equals(p.getStatus()) && !"REJECTED".equals(p.getStatus()) && !("CANCELLED".equals(p.getStatus()) && SecurityUtil.isAccountAdmin())) {
            throw new BizException("只有草稿或已驳回的采购申请可以删除");
        }
        if (!p.getApplicantId().equals(SecurityUtil.uid()) && !SecurityUtil.isAdmin()) {
            throw new BizException("只有申请人或管理员可以删除该采购申请");
        }
        periodCloseService.assertPeriodOpen(p.getApplyDate(), p.getOrgId(), "采购申请", p.getDocNo());
        lineMapper.delete(new QueryWrapper<BizPrLine>().eq("pr_id", id));
        prMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("PR", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizPr p = requireExisting(id);
        periodCloseService.assertPeriodOpen(p.getApplyDate(), p.getOrgId(), "采购申请", p.getDocNo());
        wfService.approve("PR", id, comment, (no, bizId) -> {
            BizPr pp = prMapper.selectById(bizId);
            pp.setStatus("APPROVED");
            prMapper.updateById(pp);
            poService.fromPr(bizId, pp.getWarehouseId());
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizPr p = requireExisting(id);
        periodCloseService.assertPeriodOpen(p.getApplyDate(), p.getOrgId(), "采购申请", p.getDocNo());
        wfService.reject("PR", id, comment, (no, bizId) -> {
            BizPr pp = prMapper.selectById(bizId);
            pp.setStatus("REJECTED");
            prMapper.updateById(pp);
        });
    }

    public BizPr detail(Long id) {
        BizPr p = prMapper.selectById(id);
        if (p == null) return null;
        orgScope.assertOrgVisible(p.getOrgId());
        p.setLines(lineMapper.selectList(new QueryWrapper<BizPrLine>().eq("pr_id", id).orderByAsc("line_no")));
        return p;
    }

    public IPage<BizPr> page(long current, long size, String status, String keyword, Long supplierId,
                             String applicantName, LocalDate startDate, LocalDate endDate, Long applicantId) {
        QueryWrapper<BizPr> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword);
        if (supplierId != null) qw.eq("supplier_id", supplierId);
        if (startDate != null) qw.ge("apply_date", startDate);
        if (endDate != null) qw.le("apply_date", endDate);
        if (applicantName != null && !applicantName.isBlank()) {
            String like = "%" + applicantName.trim() + "%";
            qw.apply("applicant_id IN (SELECT id FROM sys_user WHERE username LIKE {0} OR name LIKE {0})", like);
        }
        if (applicantId != null) qw.eq("applicant_id", applicantId);
        qw.orderByDesc("id");
        return prMapper.selectPage(new Page<>(current, size), qw);
    }

    public List<BizPrLine> lines(Long prId) {
        BizPr p = prMapper.selectById(prId);
        if (p == null) return List.of();
        orgScope.assertOrgVisible(p.getOrgId());
        return lineMapper.selectList(new QueryWrapper<BizPrLine>().eq("pr_id", prId).orderByAsc("line_no"));
    }

    private BizPr requireExisting(Long id) {
        BizPr p = prMapper.selectById(id);
        if (p == null) throw new BizException("采购申请不存在");
        orgScope.assertOrgVisible(p.getOrgId());
        return p;
    }

    private void validateRequired(BizPr dto) {
        if (dto.getSupplierId() == null) throw new BizException("供应商不能为空");
        if (dto.getPurpose() == null || dto.getPurpose().isBlank()) throw new BizException("用途不能为空");
        if (dto.getApplyDate() == null) throw new BizException("申请日期不能为空");
        if (dto.getApplicantId() == null && SecurityUtil.uid() == null) throw new BizException("申请人不能为空");
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("申请明细不能为空");
    }

    private void validateWarehouse(BizPr dto) {
        if (dto.getWarehouseId() == null) throw new BizException("仓库不能为空");
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
    }

    private Totals calculateLines(List<BizPrLine> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        if (lines != null) {
            for (BizPrLine line : lines) {
                validateLine(line);
                line.setAmount(line.getQty().multiply(line.getUnitPrice()));
                totalQty = totalQty.add(line.getQty());
                totalAmt = totalAmt.add(line.getAmount());
            }
        }
        if (totalQty.signum() <= 0) throw new BizException("至少需要一行有效的物料明细");
        return new Totals(totalQty, totalAmt);
    }

    private void validateLine(BizPrLine line) {
        if (line.getMaterialId() == null) throw new BizException("物料编码不能为空");
        if (line.getQty() == null || line.getQty().signum() <= 0) throw new BizException("数量必须大于 0");
        if (line.getUnitPrice() == null || line.getUnitPrice().signum() < 0) throw new BizException("单价不能为空且不能为负数");
    }

    private void insertLines(Long prId, List<BizPrLine> lines) {
        if (lines == null) return;
        int i = 1;
        for (BizPrLine line : lines) {
            line.setId(null);
            line.setPrId(prId);
            line.setLineNo(i++);
            if (line.getConvertedQty() == null) line.setConvertedQty(BigDecimal.ZERO);
            lineMapper.insert(line);
        }
    }

    private record Totals(BigDecimal qty, BigDecimal amount) {
    }
}
