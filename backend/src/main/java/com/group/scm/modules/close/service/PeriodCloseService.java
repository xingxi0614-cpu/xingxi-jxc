package com.group.scm.modules.close.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.entity.FinPeriodClose;
import com.group.scm.modules.close.mapper.FinPeriodCloseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PeriodCloseService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JdbcTemplate jdbcTemplate;
    private final FinPeriodCloseMapper closeMapper;
    private final OrgScopeService orgScope;

    public Map<String, Object> checkPeriod(String periodMonth) {
        PeriodRange range = range(periodMonth);
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) throw new BizException("当前账号没有可检查的账套");

        List<Map<String, Object>> items = new ArrayList<>();
        addDocStatusIssues(items, "采购订单", "PO", "biz_po", "order_date", range, orgIds);
        addDocStatusIssues(items, "采购入库单", "RECV", "biz_recv", "recv_date", range, orgIds);
        addDocStatusIssues(items, "采购发票", "INV", "biz_invoice", "invoice_date", range, orgIds);
        addDocStatusIssues(items, "出库申请", "OREQ", "biz_out_req", "apply_date", range, orgIds);
        addDocStatusIssues(items, "出库申请修订", "OREQ_REV", "biz_out_req_revision", "revision_date", range, orgIds);
        addUnfinishedOutReq(items, range, orgIds);
        addPendingCancelRequests(items, range, orgIds);
        addDocStatusIssues(items, "材料出库单", "OUT", "biz_out", "out_date", range, orgIds);
        addDocStatusIssues(items, "退料单", "RETURN", "biz_return", "return_date", range, orgIds);
        addDocStatusIssues(items, "销售出库单", "SALE_OUT", "biz_sale_out", "out_date", range, orgIds);
        addDocStatusIssues(items, "销售发票", "SALE_INV", "biz_sale_invoice", "invoice_date", range, orgIds);
        addDocStatusIssues(items, "销售退货单", "SALE_RETURN", "biz_sale_return", "return_date", range, orgIds);
        addDocStatusIssues(items, "采购退货单", "PRET", "biz_purchase_return", "return_date", range, orgIds);
        addDocStatusIssues(items, "借料单", "LOAN", "biz_loan", "borrow_date", range, orgIds);
        // 盘点单与其他单据不同：草稿/已驳回也硬拦（2026-10-06 定）。
        // 原因：未完成的盘点单会冻结整个仓库，而结账后该月的盘点单既不能提交也不能确认，
        // 仓库就会一直冻结、只能删单重来——曾有公司就是这么卡住的。所以必须先做完或删除再结账。
        addStatusIssue(items, "BLOCKER", "盘点单", "STOCKTAKE", "biz_stocktake", "take_date", range, orgIds,
                List.of("DRAFT", "REJECTED", "SUBMITTED", "PENDING"),
                "存在未完成的盘点单：仓库仍处于盘点冻结中，结账后这些盘点单将无法提交或确认。"
                        + "请先录完实盘并由财务确认，或删除盘点单");
        addTransferStatusIssue(items, range, orgIds);
        addUnconfirmedEstimateDiff(items, range, orgIds);
        addNegativeInventory(items, orgIds);
        addAbnormalInventoryAmount(items, orgIds);
        addCostPoolMismatch(items, orgIds);
        addUnmatchedEstimateIssue(items, range, orgIds);
        addOverdueEstimates(items, orgIds);
        addDuplicateOutReqs(items, range, orgIds);

        int issueCount = items.stream().mapToInt(item -> "BLOCKER".equals(item.get("level")) ? (Integer) item.get("count") : 0).sum();
        int warningCount = items.stream().mapToInt(item -> "WARNING".equals(item.get("level")) ? (Integer) item.get("count") : 0).sum();
        int closedOrgCount = closedOrgCount(range.periodMonth(), orgIds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodMonth", range.periodMonth());
        result.put("checkedAt", LocalDateTime.now());
        result.put("issueCount", issueCount);
        result.put("warningCount", warningCount);
        result.put("closedOrgCount", closedOrgCount);
        result.put("orgCount", orgIds.size());
        result.put("canClose", issueCount == 0);
        result.put("items", items);
        return result;
    }

    // visibleOrgIds 在组织行锁前会普通读取；READ_COMMITTED 使等锁后检查读到刚提交的盘点。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> closePeriod(String periodMonth, String remark) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        List<Long> orgIds = orgScope.visibleOrgIds();
        lockPeriodOrganizations(orgIds);
        Map<String, Object> check = checkPeriod(periodMonth);
        int issueCount = (Integer) check.get("issueCount");
        if (issueCount > 0) {
            throw new BizException("当前期间存在 " + issueCount + " 项异常，不能结账。请先处理异常后再结账。");
        }
        String payload = JSON.toJSONString(check);
        for (Long orgId : orgIds) {
            jdbcTemplate.update("""
                    INSERT INTO fin_period_close
                      (org_id, period_month, status, issue_count, warning_count, check_result, remark,
                       checked_at, closed_at, closed_by, deleted)
                    VALUES (?, ?, 'CLOSED', ?, ?, ?, ?, NOW(), NOW(), ?, 0)
                    ON DUPLICATE KEY UPDATE
                      status = 'CLOSED',
                      issue_count = VALUES(issue_count),
                      warning_count = VALUES(warning_count),
                      check_result = VALUES(check_result),
                      remark = VALUES(remark),
                      checked_at = NOW(),
                      closed_at = NOW(),
                      closed_by = VALUES(closed_by),
                      deleted = 0
                    """, orgId, check.get("periodMonth"), issueCount, check.get("warningCount"),
                    payload, remark, SecurityUtil.uid());
        }
        check.put("closed", true);
        return check;
    }

    /** 结账专用:组织行排他锁(按 id 升序)。单据一侧用 {@link #lockPeriodShared},见 {@link #assertPeriodOpen}。调用方须在事务内。 */
    public void lockPeriodOrganizations(List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) return;
        List<Long> ordered = orgIds.stream().distinct().sorted().toList();
        jdbcTemplate.queryForList("SELECT id FROM sys_org WHERE id IN (" + placeholders(ordered.size()) + ") ORDER BY id FOR UPDATE",
                ordered.toArray());
    }

    @Transactional(rollbackFor = Exception.class)
    public void reopenPeriod(String periodMonth) {
        SecurityUtil.requireFinanceAccessOrAdmin();
        PeriodRange range = range(periodMonth);
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return;
        jdbcTemplate.update("""
                UPDATE fin_period_close
                SET status = 'REOPENED',
                    closed_at = NULL,
                    closed_by = NULL,
                    updated_at = NOW()
                WHERE period_month = ?
                  AND deleted = 0
                  AND org_id IN (%s)
                """.formatted(placeholders(orgIds.size())),
                withFirst(range.periodMonth(), orgIds));
    }

    public List<Map<String, Object>> records(String periodMonth) {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.addAll(orgIds);
        String periodSql = "";
        if (periodMonth != null && !periodMonth.isBlank()) {
            periodSql = " AND c.period_month = ?";
            args.add(range(periodMonth).periodMonth());
        }
        return jdbcTemplate.queryForList("""
                SELECT c.*, o.name AS orgName, u.name AS closedByName
                FROM fin_period_close c
                LEFT JOIN sys_org o ON o.id = c.org_id
                LEFT JOIN sys_user u ON u.id = c.closed_by
                WHERE c.deleted = 0
                  AND c.org_id IN (%s)
                  %s
                ORDER BY c.period_month DESC, o.code, c.id DESC
                """.formatted(placeholders(orgIds.size()), periodSql),
                args.toArray());
    }

    /**
     * 期间检查(全站所有单据写入共用):本单所属组织在业务月份已结账就拦下。调用方应在事务内。
     *
     * 🔴 2026-10-18(计算审查 U06):以前这里只是普通读一下,不和结账互斥——单据刚检查完"本月未结",
     *    结账就在这一瞬间提交了,单据随后照样写进已结账月份(审查实测付款能落进 CLOSED 月份)。
     *    现在先对组织行加【共享锁】,再用加锁读看最新结账状态,锁一直持有到本单提交:
     *    - 结账拿的是同一行的【排他锁】({@link #lockPeriodOrganizations}),所以结账要等在途单据提交;
     *      结账先拿到锁,单据就等结账提交后读到 CLOSED 被拦下。
     *    - 单据之间都是共享锁,互不排队,不会因为这把锁让平时的业务变慢或互相卡死。
     *    - 加锁读(LOCK IN SHARE MODE)不管调用方是什么隔离级别,都读到最新已提交的结账记录。
     *    不在事务里调用时锁会立刻释放(只剩普通检查的效果),所以发现了就记一条告警,便于补事务。
     */
    public void assertPeriodOpen(LocalDate bizDate, Long orgId, String bizName, String docNo) {
        if (bizDate == null || orgId == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("期间检查不在事务内,与结账不互斥:{} {}", bizName, docNo);
        }
        lockPeriodShared(orgId);
        String periodMonth = YearMonth.from(bizDate).format(PERIOD_FORMAT);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fin_period_close
                WHERE org_id = ?
                  AND period_month = ?
                  AND status = 'CLOSED'
                  AND deleted = 0
                LOCK IN SHARE MODE
                """, Integer.class, orgId, periodMonth);
        if (count != null && count > 0) {
            String suffix = (docNo == null || docNo.isBlank()) ? "" : "（" + docNo + "）";
            throw new BizException(periodMonth + " 已结账，" + bizName + suffix + " 不允许修改。确需调整时，请先由财务或管理员取消结账/反结账。");
        }
    }

    /** 与 {@link #assertPeriodOpen} 相同(2026-10-18 起所有期间检查都带锁,保留此名供收付款调用处)。 */
    public void assertPeriodOpenLocked(LocalDate bizDate, Long orgId, String bizName, String docNo) {
        assertPeriodOpen(bizDate, orgId, bizName, docNo);
    }

    /**
     * 单据一侧的期间锁:组织行共享锁。单据之间不互斥,只和结账的排他锁互斥。
     * 盘点建单也用它(检查在途盘点与写 CLOSED 之间不得插入新盘点,只需和结账互斥)。
     */
    public void lockPeriodShared(Long orgId) {
        if (orgId == null) return;
        jdbcTemplate.queryForList("SELECT id FROM sys_org WHERE id = ? LOCK IN SHARE MODE", orgId);
    }

    /**
     * 单据状态检查：按「会不会影响本月账」「结账后会不会永久卡死」分成两档。
     *
     * <p><b>待审批 → 异常(硬拦)</b>：结账会锁定期间，锁了这些单就再也审批不了，会永远卡住。</p>
     * <p><b>草稿 / 已驳回 → 仅提醒</b>：没提交的单不产生任何账务影响，本月账不因它错；
     * 但结账后就改不了了，所以提醒经办人先提交或作废。
     * （2026-09-02 定从硬拦降为提醒：原先一张草稿就能卡住整月结账，不合理。）</p>
     */
    private void addDocStatusIssues(List<Map<String, Object>> items, String module, String docType,
                                    String table, String dateColumn, PeriodRange range, List<Long> orgIds) {
        addStatusIssue(items, "BLOCKER", module, docType, table, dateColumn, range, orgIds,
                List.of("PENDING", "SUBMITTED"),
                "存在待审批的" + module + "：结账会锁定期间，这些单将再也无法审批，请先审完或撤回");
        addStatusIssue(items, "WARNING", module, docType, table, dateColumn, range, orgIds,
                List.of("DRAFT", "REJECTED"),
                "存在未提交或已驳回的" + module + "：不影响本月账，但结账后无法再修改，建议先提交或作废");
    }

    private void addStatusIssue(List<Map<String, Object>> items, String level, String module, String docType, String table,
                                String dateColumn, PeriodRange range, List<Long> orgIds,
                                List<String> statuses, String message) {
        String statusPlaceholders = placeholders(statuses.size());
        String sql = """
                SELECT COUNT(*)
                FROM %s
                WHERE deleted = 0
                  AND %s >= ?
                  AND %s < ?
                  AND org_id IN (%s)
                  AND status IN (%s)
                """.formatted(table, dateColumn, dateColumn, placeholders(orgIds.size()), statusPlaceholders);
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        args.addAll(statuses);
        int count = count(sql, args);
        if (count == 0) return;
        String sampleSql = """
                SELECT doc_no
                FROM %s
                WHERE deleted = 0
                  AND %s >= ?
                  AND %s < ?
                  AND org_id IN (%s)
                  AND status IN (%s)
                ORDER BY id DESC
                LIMIT 200
                """.formatted(table, dateColumn, dateColumn, placeholders(orgIds.size()), statusPlaceholders);
        addItem(items, level, module, docType, count, message, sampleSql, args);
    }

    /**
     * 工作台提醒：需要有人去干活、但系统不会自己好起来的两件事。
     *
     * <p>2026-09-02 用户要求把它们搬到工作台首页。背景：同日把「已审批未完成出库」和
     * 「暂估未核销」从结账硬拦降为提醒后，就再没有任何东西逼着人去清了，
     * 只能靠首页天天摆在眼前。</p>
     *
     * <p><b>统计范围是历史全部未处理</b>（不限当月）：按体检记录，压了好几个月的老单子
     * 才是真风险，只看当月会把它们藏起来。</p>
     *
     * @return unfinishedOutReq=已审批但没做完材料出库的申请数；estimateUnmatched=暂估入库未完全核销的单数
     */
    public Map<String, Object> reminders() {
        List<Long> orgIds = orgScope.visibleOrgIds();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("unfinishedOutReq", 0);
        r.put("estimateUnmatched", 0);
        if (orgIds.isEmpty()) return r;

        String ph = placeholders(orgIds.size());
        Object[] args = orgIds.toArray();

        r.put("unfinishedOutReq", count("""
                SELECT COUNT(*) FROM biz_out_req
                WHERE deleted = 0 AND org_id IN (%s)
                  AND status IN ('APPROVED', 'PARTIAL_OUT')
                  AND total_qty > IFNULL(out_qty, 0)
                """.formatted(ph), List.of(args)));

        r.put("estimateUnmatched", count("""
                SELECT COUNT(DISTINCT r.id)
                FROM biz_recv r
                JOIN biz_recv_line l ON l.recv_id = r.id
                WHERE r.deleted = 0 AND r.org_id IN (%s)
                  AND r.status = 'APPROVED' AND r.recv_type = 'ESTIMATE'
                  AND l.qty > IFNULL(l.invoiced_qty, 0) + IFNULL(l.returned_qty, 0)
                """.formatted(ph), List.of(args)));
        return r;
    }

    /**
     * 暂估挂账超过 90 天还没开票(2026-10-16 对标财务软件操作手册,口径见 EstimateAging):提醒,不阻断结账。
     * 按"今天"算挂账天数,与暂估清单、工作台提醒卡同一个数。
     */
    private void addOverdueEstimates(List<Map<String, Object>> items, List<Long> orgIds) {
        List<Object> args = new ArrayList<>(orgIds);
        String base = """
                FROM biz_recv r JOIN biz_recv_line rl ON rl.recv_id = r.id
                WHERE r.deleted = 0 AND r.status = 'APPROVED' AND r.recv_type = 'ESTIMATE'
                  AND r.org_id IN (%s) AND %s
                """.formatted(placeholders(orgIds.size()), com.group.scm.modules.report.EstimateAging.OVERDUE_LINE);
        int count = count("SELECT COUNT(DISTINCT r.id) " + base, args);
        if (count == 0) return;
        String amt = jdbcTemplate.queryForObject("SELECT CAST(COALESCE(SUM(" + com.group.scm.modules.report.EstimateAging.REMAIN_AMT
                + "), 0) AS CHAR) " + base, String.class, args.toArray());
        addItem(items, "WARNING", "暂估超 3 个月", "EST_AGING", count,
                "有暂估入库挂账超过 90 天还没开票，未开票金额合计 " + amt + " 元：不阻止结账，请联系供应商尽快开票，或说明长期暂估的原因",
                "SELECT MAX(r.doc_no) AS doc_no " + base + " GROUP BY r.id ORDER BY MIN(r.recv_date) LIMIT 200",
                args);
    }

    /**
     * 本月新建的工程类出库申请里,所属工程在它之前已有未关闭的申请(口径见 OutReqDuplicates):提醒,不阻断结账。
     * 只数本月新建的,不翻历史旧单;"之前那张是否未关闭"按当前状态判断。
     */
    private void addDuplicateOutReqs(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        String cat = com.group.scm.modules.outbound.service.OutReqDuplicates.CATEGORY;
        String open = com.group.scm.modules.outbound.service.OutReqDuplicates.OPEN_STATUSES;
        String base = """
                FROM biz_out_req q
                WHERE q.deleted = 0 AND q.apply_date >= ? AND q.apply_date < ? AND q.org_id IN (%s)
                  AND q.business_category = '%s' AND q.status NOT IN ('DRAFT', 'REJECTED', 'CANCELLED')
                  AND EXISTS (SELECT 1 FROM biz_out_req p
                              WHERE p.deleted = 0 AND p.project_id = q.project_id AND p.id < q.id
                                AND p.business_category = '%s' AND p.status IN %s)
                """.formatted(placeholders(orgIds.size()), cat, cat, open);
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, "WARNING", "重复出库申请", "OREQ", count,
                "本月新建的工程类出库申请中，有的所属工程已有未完成的申请：不阻止结账；同一工程的材料计划有变化，建议在原申请上做修订，不再另建新申请",
                "SELECT q.doc_no " + base + " ORDER BY q.id DESC LIMIT 200",
                args);
    }

    private void addUnfinishedOutReq(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        String base = """
                FROM biz_out_req
                WHERE deleted = 0
                  AND apply_date >= ?
                  AND apply_date < ?
                  AND org_id IN (%s)
                  AND status IN ('APPROVED', 'PARTIAL_OUT')
                  AND total_qty > IFNULL(out_qty, 0)
                """.formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        // 出库申请只是领料计划，8月批的料9月才领很正常；库存减少以「材料出库单」为准，
        // 申请单本身不动库存、不影响本月账 ⇒ 2026-09-02 定由硬拦降为提醒
        addItem(items, "WARNING", "出库申请", "OREQ", count,
                "存在尚未完成领料的申请：不阻止结账，请按实际领取，剩余到期确认继续或不再领取",
                "SELECT doc_no " + base + " ORDER BY id DESC LIMIT 200",
                args);
    }

    private void addPendingCancelRequests(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        String base = """
                FROM wf_cancel_request cr
                JOIN (
                    SELECT 'PR' AS biz_type, id AS biz_id, org_id, apply_date AS biz_date FROM biz_pr WHERE deleted = 0
                    UNION ALL SELECT 'PO', id, org_id, order_date FROM biz_po WHERE deleted = 0
                    UNION ALL SELECT 'RECV', id, org_id, recv_date FROM biz_recv WHERE deleted = 0
                    UNION ALL SELECT 'INV', id, org_id, invoice_date FROM biz_invoice WHERE deleted = 0
                    UNION ALL SELECT 'OREQ', id, org_id, apply_date FROM biz_out_req WHERE deleted = 0
                    UNION ALL SELECT 'OREQ_REV', id, org_id, revision_date FROM biz_out_req_revision WHERE deleted = 0
                    UNION ALL SELECT 'OUT', id, org_id, out_date FROM biz_out WHERE deleted = 0
                    UNION ALL SELECT 'RETURN', id, org_id, return_date FROM biz_return WHERE deleted = 0
                    UNION ALL SELECT 'PRET', id, org_id, return_date FROM biz_purchase_return WHERE deleted = 0
                    UNION ALL SELECT 'LOAN', id, org_id, borrow_date FROM biz_loan WHERE deleted = 0
                    UNION ALL SELECT 'SO', id, org_id, order_date FROM biz_so WHERE deleted = 0
                    UNION ALL SELECT 'SALE_OUT', id, org_id, out_date FROM biz_sale_out WHERE deleted = 0
                    UNION ALL SELECT 'SALE_INV', id, org_id, invoice_date FROM biz_sale_invoice WHERE deleted = 0
                    UNION ALL SELECT 'SALE_RETURN', id, org_id, return_date FROM biz_sale_return WHERE deleted = 0
                    UNION ALL SELECT 'TRANSFER', t.id, w.org_id, t.transfer_date FROM biz_transfer t JOIN md_warehouse w ON w.id = t.from_warehouse_id WHERE t.deleted = 0
                    UNION ALL SELECT 'TRANSFER', t.id, w.org_id, t.transfer_date FROM biz_transfer t JOIN md_warehouse w ON w.id = t.to_warehouse_id WHERE t.deleted = 0 AND t.to_warehouse_id <> t.from_warehouse_id

                ) d ON d.biz_type = cr.biz_type AND d.biz_id = cr.biz_id
                WHERE cr.status = 'PENDING'
                  AND d.biz_date >= ?
                  AND d.biz_date < ?
                  AND d.org_id IN (%s)
                """.formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(DISTINCT cr.id) " + base, args);
        if (count == 0) return;
        addItem(items, "BLOCKER", "取消审批", "CANCEL_REQ", count,
                "存在待处理的取消审批申请，请先处理后再结账",
                "SELECT cr.biz_no AS doc_no " + base + " GROUP BY cr.biz_no ORDER BY MAX(cr.id) DESC LIMIT 200",
                args);
    }

    /** 转库单与其他单据同口径：待审批=异常(结账后审不了)，草稿/已驳回=提醒(不影响本月账) */
    private void addTransferStatusIssue(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        addTransferStatusIssue(items, range, orgIds, "BLOCKER", List.of("PENDING"),
                "存在待审批的库存转库单：结账会锁定期间，这些单将再也无法审批，请先审完或撤回");
        addTransferStatusIssue(items, range, orgIds, "WARNING", List.of("READY"),
                "存在已批准待实际交接的领料转库；尚未动库存，请核实交接或取消本次领料");
        addTransferStatusIssue(items, range, orgIds, "WARNING", List.of("DRAFT", "REJECTED"),
                "存在未提交或已驳回的库存转库单：不影响本月账，但结账后无法再修改，建议先提交或作废");
    }

    private void addTransferStatusIssue(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds,
                                        String level, List<String> statuses, String message) {
        if (!tableExists("biz_transfer")) return;
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        args.addAll(orgIds);
        args.addAll(statuses);
        String base = """
                FROM biz_transfer t
                JOIN md_warehouse fw ON fw.id = t.from_warehouse_id AND fw.deleted = 0
                JOIN md_warehouse tw ON tw.id = t.to_warehouse_id AND tw.deleted = 0
                WHERE t.deleted = 0
                  AND t.transfer_date >= ?
                  AND t.transfer_date < ?
                  AND (fw.org_id IN (%s) OR tw.org_id IN (%s))
                  AND t.status IN (%s)
                """.formatted(placeholders(orgIds.size()), placeholders(orgIds.size()), placeholders(statuses.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, level, "库存转库单", "TRANSFER", count, message,
                "SELECT t.doc_no " + base + " ORDER BY t.id DESC LIMIT 200",
                args);
    }

    /**
     * 🔴 2026-08-21 新增拦截:本期暂估差异尚未确认入账,不许结账。
     *
     * 背景:采购成本差异进损益的部分(2026-10-16 起只剩"已领用"那部分 + 采购退货兜底差额)
     * 要材料会计到外部账务系统手工做分录,而本系统不出记账凭证 ——
     * 这笔损益要材料会计到外部账务系统手工做分录。如果只做个报表没人点,
     * 三个月后所有月份都会是「未确认」,流程等于白做。
     * 所以按用户要求把它变成绕不过去的一步:确认了才能结账。
     *
     * 判定两种情况都拦:
     *   ① 本期有差异但【从没确认过】
     *   ② 确认过,但之后又新增了差异(补录了迟到的发票)→ 金额对不上,要补做分录
     */
    private void addUnconfirmedEstimateDiff(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        // ⚠️ 整段拼接要先括起来再 formatted:不括的话 formatted 只作用在最后一段,前段的 %% 会原样进 SQL
        String base = ("""
                FROM (
                  SELECT f.org_id, DATE_FORMAT(f.biz_date, '%%Y-%%m') pm,
                         ROUND(SUM(f.amount),2) amt
                  FROM (""" + com.group.scm.modules.report.CostDiffSql.FLOWS + """
                  ) f
                  WHERE f.in_stock = 0   /* 只有进损益的部分要到外部账做分录;进库存部分已在库存金额里 */
                    AND f.biz_date >= ? AND f.biz_date < ?   /* endExclusive 是开区间 */
                    AND f.org_id IN (%s)
                  GROUP BY f.org_id, DATE_FORMAT(f.biz_date, '%%Y-%%m')
                ) d
                LEFT JOIN fin_estimate_diff_confirm c
                       ON c.org_id = d.org_id AND c.period_month = d.pm AND c.deleted = 0
                WHERE c.id IS NULL OR ABS(d.amt - c.diff_amount) > 0.005
                """).formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, "BLOCKER", "暂估差异", "EST_DIFF", count,
                "本期采购成本差异(进损益部分)尚未确认入账(或确认后又有新增),请材料会计先在【报表中心→采购成本差异调整表】完成分录并点「本月差异已入账」",
                "SELECT CONCAT(d.pm, ' 差异 ', d.amt, ' 元') AS doc_no " + base + " LIMIT 200",
                args);
    }

    private void addNegativeInventory(List<Map<String, Object>> items, List<Long> orgIds) {
        List<Object> args = new ArrayList<>(orgIds);
        String base = """
                FROM inv_balance b
                JOIN md_warehouse w ON w.id = b.warehouse_id AND w.deleted = 0
                LEFT JOIN md_material m ON m.id = b.material_id
                WHERE b.qty < 0
                  AND w.org_id IN (%s)
                """.formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, "BLOCKER", "库存余额", "BALANCE", count,
                "存在负库存，结账前必须先调整",
                "SELECT CONCAT(IFNULL(m.code, b.material_id), ' / ', w.name) AS doc_no " + base + " ORDER BY b.id DESC LIMIT 200",
                args);
    }

    /**
     * 按公司核算后的硬检查:公司成本账数量 = 各仓数量之和;金额不为负;数量为 0 时金额为 0。
     * 引擎每次过账都会校验,这里兜底发现"绕过引擎直接改库"造成的账实不符。按仓核算(切换前/回滚后)不检查。
     */
    private void addCostPoolMismatch(List<Map<String, Object>> items, List<Long> orgIds) {
        // 与 InvLedgerSupport#scope 同口径;这里直接查,不给本服务加依赖(多处测试手工构造本类)
        String scope = jdbcTemplate.query("SELECT scope_after FROM inv_cost_cutover ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : "WH");
        if (!"ORG".equals(scope)) return;
        List<Object> args = new ArrayList<>(orgIds);
        args.addAll(orgIds);
        String base = """
                FROM (
                  SELECT org_id, material_id, SUM(pq) pq, SUM(pa) pa, SUM(wq) wq FROM (
                    SELECT p.org_id, p.material_id, p.qty pq, p.amount pa, 0 wq FROM inv_cost_pool p WHERE p.org_id IN (%s)
                    UNION ALL
                    SELECT w.org_id, b.material_id, 0, 0, b.qty FROM inv_balance b
                      JOIN md_warehouse w ON w.id = b.warehouse_id WHERE w.org_id IN (%s)
                  ) u GROUP BY org_id, material_id
                ) x
                LEFT JOIN md_material m ON m.id = x.material_id
                WHERE x.pq <> x.wq OR x.pa < 0 OR (x.pq = 0 AND x.pa <> 0)
                """.formatted(placeholders(orgIds.size()), placeholders(orgIds.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, "BLOCKER", "公司成本账", "BALANCE", count,
                "公司成本账与各仓库存数量不一致或金额异常，结账前请联系管理员核对",
                "SELECT IFNULL(m.code, x.material_id) AS doc_no " + base + " LIMIT 200",
                args);
    }

    private void addAbnormalInventoryAmount(List<Map<String, Object>> items, List<Long> orgIds) {
        List<Object> args = new ArrayList<>(orgIds);
        String base = """
                FROM inv_balance b
                JOIN md_warehouse w ON w.id = b.warehouse_id AND w.deleted = 0
                LEFT JOIN md_material m ON m.id = b.material_id
                WHERE w.org_id IN (%s)
                  AND (
                    (b.qty = 0 AND ROUND(b.amount, 2) <> 0)
                    OR b.amount < 0
                    OR b.avg_price < 0
                  )
                """.formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(*) " + base, args);
        if (count == 0) return;
        addItem(items, "BLOCKER", "库存余额", "BALANCE", count,
                "存在结存数量、金额或单价异常，结账前必须先处理",
                "SELECT CONCAT(IFNULL(m.code, b.material_id), ' / ', w.name) AS doc_no " + base + " ORDER BY b.id DESC LIMIT 200",
                args);
    }

    private void addUnmatchedEstimateIssue(List<Map<String, Object>> items, PeriodRange range, List<Long> orgIds) {
        List<Object> args = new ArrayList<>();
        args.add(range.start());
        args.add(range.endExclusive());
        args.addAll(orgIds);
        String base = """
                FROM biz_recv r
                JOIN biz_recv_line l ON l.recv_id = r.id
                WHERE r.deleted = 0
                  AND r.recv_date >= ?
                  AND r.recv_date < ?
                  AND r.org_id IN (%s)
                  AND r.status = 'APPROVED'
                  AND r.recv_type = 'ESTIMATE'
                  AND l.qty > IFNULL(l.invoiced_qty, 0) + IFNULL(l.returned_qty, 0)
                """.formatted(placeholders(orgIds.size()));
        int count = count("SELECT COUNT(DISTINCT r.id) " + base, args);
        if (count == 0) return;
        // 暂估的本意就是「货到票未到，先估价入账」，发票何时到由供应商决定；
        // 硬拦等于把结账权交给供应商，且与暂估功能本身自相矛盾。
        // 月末真正必须管的是「暂估差异有没有入账」(见 addUnconfirmedEstimateDiff，那条仍是硬拦)。
        // ⇒ 2026-09-02 定由硬拦降为提醒
        addItem(items, "WARNING", "暂估入库", "RECV", count,
                "存在暂估入库尚未完全发票核销：暂估可跨月挂账，不阻断结账；请持续督促供应商开票，避免长期挂账",
                "SELECT MAX(r.doc_no) AS doc_no " + base + " GROUP BY r.id ORDER BY r.id DESC LIMIT 200",
                args);
    }

    private void addItem(List<Map<String, Object>> items, String level, String module, String docType,
                         int count, String message, String sampleSql, List<Object> args) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", level);
        item.put("module", module);
        item.put("docType", docType);
        item.put("count", count);
        item.put("message", message);
        item.put("samples", jdbcTemplate.queryForList(sampleSql, String.class, args.toArray()));
        items.add(item);
    }

    private int count(String sql, List<Object> args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private int closedOrgCount(String periodMonth, List<Long> orgIds) {
        return count("""
                SELECT COUNT(*)
                FROM fin_period_close
                WHERE period_month = ?
                  AND status = 'CLOSED'
                  AND deleted = 0
                  AND org_id IN (%s)
                """.formatted(placeholders(orgIds.size())), withFirstAsList(periodMonth, orgIds));
    }

    private PeriodRange range(String periodMonth) {
        if (periodMonth == null || periodMonth.isBlank()) {
            periodMonth = YearMonth.now().format(PERIOD_FORMAT);
        }
        try {
            YearMonth ym = YearMonth.parse(periodMonth.trim(), PERIOD_FORMAT);
            return new PeriodRange(ym.format(PERIOD_FORMAT), ym.atDay(1), ym.plusMonths(1).atDay(1));
        } catch (DateTimeParseException ex) {
            throw new BizException("期间格式不正确，应为 YYYY-MM");
        }
    }

    private String placeholders(int size) {
        if (size <= 0) return "NULL";
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] withFirst(String first, List<Long> rest) {
        Object[] args = new Object[1 + rest.size()];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    private List<Object> withFirstAsList(String first, List<Long> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args;
    }

    private record PeriodRange(String periodMonth, LocalDate start, LocalDate endExclusive) {
    }
}
