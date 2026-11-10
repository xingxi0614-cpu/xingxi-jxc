package com.group.scm.modules.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.master.entity.MdProject;
import com.group.scm.modules.master.entity.MdWarehouse;
import com.group.scm.modules.master.mapper.MdProjectMapper;
import com.group.scm.modules.master.mapper.MdWarehouseMapper;
import com.group.scm.modules.outbound.entity.BizLoan;
import com.group.scm.modules.outbound.entity.BizLoanLine;
import com.group.scm.modules.outbound.mapper.BizLoanLineMapper;
import com.group.scm.modules.outbound.mapper.BizLoanMapper;
import com.group.scm.modules.workflow.service.WorkflowService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanService {

    private final BizLoanMapper loanMapper;
    private final BizLoanLineMapper lineMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final MdWarehouseMapper warehouseMapper;
    private final MdProjectMapper projectMapper;
    private final DocNoService docNoService;
    private final OrgScopeService orgScope;
    private final WaInventoryService waService;
    private final PeriodCloseService periodCloseService;
    private final WorkflowService wfService;
    /** 删单时要把这张单的待办一并关掉，否则会留下点开为空的孤儿待办（2026-07-31 审计） */
    private final com.group.scm.modules.notif.service.NotifService notifService;
    private final com.group.scm.modules.inventory.service.SubmitStockGuard stockGuard;

    @Transactional(rollbackFor = Exception.class)
    public BizLoan create(BizLoan dto) {
        validateHeader(dto);
        MdWarehouse warehouse = requireWarehouse(dto.getWarehouseId());
        LocalDate borrowDate = dto.getBorrowDate() == null ? LocalDate.now() : dto.getBorrowDate();
        if (dto.getExpectedReturnDate() != null && dto.getExpectedReturnDate().isBefore(borrowDate)) {
            throw new BizException("预计归还日期不能早于借料日期");
        }

        BizLoan loan = new BizLoan();
        loan.setDocNo(docNoService.next("LOAN"));
        loan.setWarehouseId(warehouse.getId());
        loan.setDeptId(SecurityUtil.orgId());
        loan.setOrgId(warehouse.getOrgId() == null ? SecurityUtil.orgId() : warehouse.getOrgId());
        loan.setProjectId(dto.getProjectId());
        loan.setProjectName(resolveProjectName(dto.getProjectId()));
        loan.setBorrower(dto.getBorrower().trim());
        loan.setBorrowerDept(trimToNull(dto.getBorrowerDept()));
        loan.setBorrowDate(borrowDate);
        loan.setExpectedReturnDate(dto.getExpectedReturnDate());
        loan.setPurpose(dto.getPurpose().trim());
        loan.setStatus("DRAFT");   // 草稿；提交→材料会计审核通过后才扣减库存
        loan.setRemark(trimToNull(dto.getRemark()));
        loan.setTotalQty(BigDecimal.ZERO);
        loan.setReturnedQty(BigDecimal.ZERO);
        loan.setTotalAmt(BigDecimal.ZERO);

        periodCloseService.assertPeriodOpen(loan.getBorrowDate(), loan.getOrgId(), "借料登记", loan.getDocNo());
        loanMapper.insert(loan);

        BigDecimal totalQty = BigDecimal.ZERO;
        int lineNo = 1;
        for (BizLoanLine input : dto.getLines()) {
            validateLine(input);
            BizLoanLine line = new BizLoanLine();
            line.setLoanId(loan.getId());
            line.setLineNo(lineNo++);
            line.setMaterialId(input.getMaterialId());
            line.setQty(input.getQty());
            line.setReturnedQty(BigDecimal.ZERO);
            line.setUnitPrice(BigDecimal.ZERO);
            line.setAmount(BigDecimal.ZERO);
            line.setRemark(trimToNull(input.getRemark()));
            lineMapper.insert(line);
            totalQty = totalQty.add(input.getQty());
        }

        loan.setTotalQty(totalQty);
        loanMapper.updateById(loan);
        return detail(loan.getId());
    }

    /** 提交审批：草稿 → 待审批（材料会计） */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        BizLoan loan = requireExisting(id);
        if (!"DRAFT".equals(loan.getStatus()) && !"REJECTED".equals(loan.getStatus())) {
            throw new BizException("只有草稿或已驳回的借料单可以提交");
        }
        if (loan.getLines() == null || loan.getLines().isEmpty()) {
            loan.setLines(lineMapper.selectList(new QueryWrapper<BizLoanLine>().eq("loan_id", id)));
        }
        if (loan.getLines() == null || loan.getLines().isEmpty()) throw new BizException("借料单无明细，不能提交");
        periodCloseService.assertPeriodOpen(loan.getBorrowDate(), loan.getOrgId(), "借料登记", loan.getDocNo());
        // 提交时就查库存（账面 − 其他待审批占用），见 SubmitStockGuard
        stockGuard.assertAvailable(com.group.scm.modules.inventory.service.SubmitStockGuard.LOAN, id, loan.getWarehouseId(),
                loan.getLines().stream().map(l -> new com.group.scm.modules.inventory.service.SubmitStockGuard.Need(l.getMaterialId(), l.getQty())).toList(), null);
        // 并发防线：条件更新状态，双击/并发提交只有一个成功，防止重复创建审批实例
        if (loanMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizLoan>()
                .eq("id", id).in("status", "DRAFT", "REJECTED").set("status", "PENDING")) != 1) {
            throw new BizException("单据状态已变化（可能已被并发提交），请刷新后重试");
        }
        wfService.submit("LOAN", id, loan.getDocNo(), BigDecimal.ZERO);
    }

    /** 取消提交：提交人在审核通过前撤回为草稿 */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        BizLoan loan = requireExisting(id);
        if (!"PENDING".equals(loan.getStatus())) throw new BizException("只有待审批的借料单可以取消提交");
        wfService.withdraw("LOAN", id);
        loan.setStatus("DRAFT");
        loanMapper.updateById(loan);
    }

    /** 材料会计审核通过 → 此刻才按加权平均单价扣减库存，状态置「已登记」 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String comment) {
        BizLoan loan = requireExisting(id);
        periodCloseService.assertPeriodOpen(loan.getBorrowDate(), loan.getOrgId(), "借料登记", loan.getDocNo());
        wfService.approve("LOAN", id, comment, (no, bizId) -> {
            // 并发防线（业务层第二道）：过账只允许执行一次
            if (loanMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BizLoan>()
                    .eq("id", bizId).eq("status", "PENDING").set("status", "APPROVED")) != 1) {
                throw new BizException("单据已被处理，请刷新后重试");
            }
            BizLoan l = loanMapper.selectById(bizId);
            List<BizLoanLine> lines = lineMapper.selectList(new QueryWrapper<BizLoanLine>().eq("loan_id", bizId).orderByAsc("line_no"));
            BigDecimal totalAmt = BigDecimal.ZERO;
            for (BizLoanLine line : lines) {
                BigDecimal[] cost = waService.outboundWithType(line.getMaterialId(), l.getWarehouseId(), line.getQty(),
                        "LOAN", "LOAN", l.getId(), l.getDocNo(), line.getId(), "借料登记");
                line.setUnitPrice(cost[0]);
                line.setAmount(cost[1].setScale(2, RoundingMode.HALF_UP));
                lineMapper.updateById(line);
                totalAmt = totalAmt.add(line.getAmount());
            }
            l.setTotalAmt(totalAmt.setScale(2, RoundingMode.HALF_UP));
            l.setStatus("REGISTERED");
            loanMapper.updateById(l);
        });
    }

    /** 驳回 → 退回（不扣库存） */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String comment) {
        BizLoan loan = requireExisting(id);
        wfService.reject("LOAN", id, comment, (no, bizId) -> {
            BizLoan l = loanMapper.selectById(bizId);
            l.setStatus("REJECTED");
            loanMapper.updateById(l);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public BizLoan registerReturn(Long id, LoanReturnDTO dto) {
        BizLoan loan = requireExisting(id);
        if ("CLOSED".equals(loan.getStatus())) throw new BizException("借料单已全部归还");
        if (!"REGISTERED".equals(loan.getStatus())) throw new BizException("借料单未审核通过，不能归还");
        LocalDate returnDate = dto.getReturnDate() == null ? LocalDate.now() : dto.getReturnDate();
        if (loan.getBorrowDate() != null && returnDate.isBefore(loan.getBorrowDate())) {
            throw new BizException("归还日期不能早于借料日期（" + loan.getBorrowDate() + "）");
        }
        periodCloseService.assertPeriodOpen(returnDate, loan.getOrgId(), "借料归还", loan.getDocNo());
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("请填写归还明细");

        BigDecimal returnTotal = BigDecimal.ZERO;
        for (LoanReturnLineDTO input : dto.getLines()) {
            if (input.getLoanLineId() == null) throw new BizException("归还明细必须关联借料行");
            if (input.getQty() == null || input.getQty().signum() <= 0) throw new BizException("归还数量必须大于 0");
            // 计算审查 U37:超过 4 位小数时,库里已归还数被截成整数(单据关闭),成本却按原始数量回,两边对不上
            com.group.scm.common.DecimalGuard.qty(input.getQty(), "归还数量");
            BizLoanLine line = lineMapper.selectById(input.getLoanLineId());
            if (line == null || !loan.getId().equals(line.getLoanId())) {
                throw new BizException("归还明细不属于当前借料单");
            }
            BigDecimal returned = safe(line.getReturnedQty());
            BigDecimal remain = line.getQty().subtract(returned);
            if (input.getQty().compareTo(remain) > 0) {
                throw new BizException("归还数量不能超过待归还数量，剩余：" + remain);
            }
            // 并发防线：原子累加已归还数，超还时不生效——两个并发归还只有一个成功，防止重复入库
            if (lineMapper.addReturnedQty(line.getId(), input.getQty()) != 1) {
                throw new BizException("归还数量超过待归还数量（可能已被并发归还），请刷新后重试");
            }
            BigDecimal price = line.getUnitPrice() == null ? BigDecimal.ZERO : line.getUnitPrice();
            BigDecimal exact = com.group.scm.common.CostAllocation.remaining(line.getQty(), line.getAmount(),
                    returned, lineMapper.returnedAmount(line.getId()), input.getQty());
            waService.inbound(line.getMaterialId(), loan.getWarehouseId(), input.getQty(), price, exact,
                    "LOAN_RETURN", "LOAN_RETURN", loan.getId(), loan.getDocNo(), line.getId(), "借料归还");
            recordReturn(loan.getId(), line.getId(), returnDate, input.getQty(), dto.getRemark());
            returnTotal = returnTotal.add(input.getQty());
        }
        if (returnTotal.signum() <= 0) throw new BizException("归还数量必须大于 0");

        BigDecimal returnedQty = sumReturnedQty(loan.getId());
        loan.setReturnedQty(returnedQty);
        if (returnedQty.compareTo(loan.getTotalQty()) >= 0) loan.setStatus("CLOSED");
        loanMapper.updateById(loan);
        return detail(loan.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BizLoan loan = requireExisting(id);
        com.group.scm.common.service.DocumentAccess.requireDeleteOwner(loan.getCreatedBy(), loan.getStatus());
        String st = loan.getStatus();
        // 草稿/驳回：未扣库存，直接删；已登记(审核通过、已扣库存)：冲回库存后删；待审批不能删（先取消提交）
        boolean stockDeducted = "REGISTERED".equals(st) || "CLOSED".equals(st);
        if (!"DRAFT".equals(st) && !"REJECTED".equals(st) && !"REGISTERED".equals(st)) {
            throw new BizException("待审批的借料单请先「取消提交」再删除");
        }
        if (safe(loan.getReturnedQty()).signum() > 0) {
            throw new BizException("该借料单已产生归还记录，不能删除");
        }
        periodCloseService.assertPeriodOpen(loan.getBorrowDate(), loan.getOrgId(), "借料登记", loan.getDocNo());
        if (stockDeducted) {
            waService.reverse(loan.getId(), "LOAN", "删除借料登记");
        }
        lineMapper.delete(new QueryWrapper<BizLoanLine>().eq("loan_id", id));
        loanMapper.deleteById(id);
        // 删单同时关掉这张单的待办，避免留下"点开搜不到单据"的孤儿待办
        notifService.closeBusinessTodos("LOAN", id);
    }

    /**
     * 记一条归还记录(计算审查 U28,2026-10-18):归还日期以前没存,库存报表只能按记账时间归期,
     * 补录上月的归还会跑进当月。这里把归还日期、数量和刚写的那条库存流水对上,报表按归还日期归期。
     * 刚写的流水 = 本事务里这条借料行最新的 LOAN_RETURN 流水(同一事务、公司成本锁内,就是本次这条)。
     */
    private void recordReturn(Long loanId, Long lineId, LocalDate returnDate, BigDecimal qty, String remark) {
        var trans = jdbc.queryForList("""
                SELECT id, amount FROM inv_transaction
                WHERE biz_doc_type = 'LOAN_RETURN' AND biz_line_id = ?
                ORDER BY id DESC LIMIT 1
                """, lineId).stream().findFirst()
                .orElseThrow(() -> new BizException("归还未生成库存流水，请联系管理员"));
        jdbc.update("""
                INSERT INTO biz_loan_return (loan_id, loan_line_id, return_date, qty, amount, inv_trans_id, remark, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, loanId, lineId, returnDate, qty, trans.get("amount"), trans.get("id"),
                truncate(trimToNull(remark), 255),
                SecurityUtil.uid());
    }

    public BizLoan detail(Long id) {
        BizLoan loan = loanMapper.selectById(id);
        if (loan == null) return null;
        orgScope.assertOrgVisible(loan.getOrgId());
        List<BizLoanLine> lines = lineMapper.selectList(
                new QueryWrapper<BizLoanLine>().eq("loan_id", id).orderByAsc("line_no"));
        for (BizLoanLine line : lines) {
            line.setAvailableQty(line.getQty().subtract(safe(line.getReturnedQty())));
        }
        loan.setLines(lines);
        loan.setReturns(jdbc.queryForList("""
                SELECT r.id, r.loan_line_id AS loanLineId, r.return_date AS returnDate, r.qty, r.remark,
                       r.created_at AS createdAt, u.name AS createdByName
                FROM biz_loan_return r LEFT JOIN sys_user u ON u.id = r.created_by
                WHERE r.loan_id = ? ORDER BY r.return_date, r.id
                """, id));
        return loan;
    }

    public IPage<BizLoan> page(long current, long size, String status, String keyword, Long warehouseId,
                               String borrower, Long projectId, LocalDate startDate, LocalDate endDate, String sortField, String sortOrder) {
        QueryWrapper<BizLoan> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (status != null && !status.isBlank()) qw.eq("status", status);
        else qw.ne("status", "CANCELLED"); // 状态留空(全部)时默认隐藏已作废单据，下拉选「已作废」可单独查看
        var docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        if (docNos != null) qw.in("doc_no", docNos);
        else if (keyword != null && !keyword.isBlank()) qw.like("doc_no", keyword.trim());
        if (borrower != null && !borrower.isBlank()) qw.like("borrower", borrower.trim());
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            qw.eq("warehouse_id", warehouseId);
        }
        if (projectId != null) qw.eq("project_id", projectId);
        if (startDate != null) qw.ge("borrow_date", startDate);
        if (endDate != null) qw.le("borrow_date", endDate);
        // 列表排序：字段从实体自动推导，只认真实存在的列；金额对仓管脱敏时一并禁排
        if (!com.group.scm.common.SortWhitelist.applyByEntity(qw, sortField, sortOrder, BizLoan.class,
                com.group.scm.common.SortWhitelist.costExclusionForCurrentUser())) {
            qw.orderByDesc("id");
        }
        return loanMapper.selectPage(new Page<>(current, size), qw);
    }

    private void validateHeader(BizLoan dto) {
        if (dto.getWarehouseId() == null) throw new BizException("请选择借料仓库");
        if (dto.getBorrower() == null || dto.getBorrower().isBlank()) throw new BizException("请填写借料人");
        if (dto.getPurpose() == null || dto.getPurpose().isBlank()) throw new BizException("请填写借料用途");
        if (dto.getLines() == null || dto.getLines().isEmpty()) throw new BizException("请填写借料明细");
    }

    private void validateLine(BizLoanLine line) {
        if (line.getMaterialId() == null) throw new BizException("请选择物料编码");
        orgScope.assertMaterialVisible(line.getMaterialId());
        if (line.getQty() == null || line.getQty().signum() <= 0) throw new BizException("借料数量必须大于 0");
        com.group.scm.common.DecimalGuard.qty(line.getQty(), "借料数量");
    }

    private MdWarehouse requireWarehouse(Long warehouseId) {
        orgScope.assertWarehouseVisible(warehouseId);
        MdWarehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
            throw new BizException("仓库不存在或已停用");
        }
        return warehouse;
    }

    private String resolveProjectName(Long projectId) {
        if (projectId == null) return null;
        orgScope.assertProjectVisible(projectId);
        MdProject project = projectMapper.selectById(projectId);
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw new BizException("工程项目不存在或已停用");
        }
        return project.getName();
    }

    private BizLoan requireExisting(Long id) {
        BizLoan loan = loanMapper.selectById(id);
        if (loan == null) throw new BizException("借料单不存在");
        orgScope.assertOrgVisible(loan.getOrgId());
        return loan;
    }

    private BigDecimal sumReturnedQty(Long loanId) {
        List<BizLoanLine> lines = lineMapper.selectList(new QueryWrapper<BizLoanLine>().eq("loan_id", loanId));
        return lines.stream().map(line -> safe(line.getReturnedQty())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    @Data
    public static class LoanReturnDTO {
        private LocalDate returnDate;
        private List<LoanReturnLineDTO> lines;
        private String remark;
    }

    @Data
    public static class LoanReturnLineDTO {
        private Long loanLineId;
        private BigDecimal qty;
        private String remark;
    }
}
