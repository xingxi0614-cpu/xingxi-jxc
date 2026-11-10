package com.group.scm.modules.workflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.service.WaInventoryService;
import com.group.scm.modules.notif.service.NotifService;
import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysUserMapper;
import com.group.scm.modules.workflow.entity.WfCancelRequest;
import com.group.scm.modules.workflow.entity.WfInstance;
import com.group.scm.modules.workflow.entity.WfRecord;
import com.group.scm.modules.workflow.entity.WfTemplate;
import com.group.scm.modules.workflow.mapper.WfCancelRequestMapper;
import com.group.scm.modules.workflow.mapper.WfInstanceMapper;
import com.group.scm.modules.workflow.mapper.WfRecordMapper;
import com.group.scm.modules.workflow.mapper.WfTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WfInstanceMapper instMapper;
    private final WfRecordMapper recordMapper;
    private final WfTemplateMapper tplMapper;
    private final WfCancelRequestMapper cancelMapper;
    private final SysUserMapper userMapper;
    private final NotifService notifService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;
    private final PeriodCloseService periodCloseService;
    private final WaInventoryService waService;
    private final com.group.scm.modules.outbound.service.OutReqIntegrity outReqIntegrity;

    @Transactional(rollbackFor = Exception.class)
    public WfInstance submit(String bizType, Long bizId, String bizNo, BigDecimal totalAmt) {
        // 并发防线①：已有审批中实例就不许再建（业务侧 DRAFT→PENDING 条件更新是第二道，双保险）
        if (instMapper.findActive(bizType, bizId) != null) {
            throw new BizException("该单据已在审批中，请勿重复提交");
        }
        // 重新提交前清掉该单据残留的待办（如上一轮驳回给发起人的待办）
        notifService.closeBusinessTodos(bizType, bizId);
        Map<String, Object> context = workflowContext(bizType, bizId, totalAmt);
        String approvalMode = approvalModeForOrg(longValue(context.get("orgId")));
        List<WfTemplate> tpls = templatesForMode(bizType, approvalMode);
        WfTemplate tpl = matchTemplate(tpls, context);
        if (tpl == null) throw new BizException("未匹配到审批流程模板：" + bizType);

        JSONArray nodes = JSON.parseArray(tpl.getNodesJson());
        if (nodes == null || nodes.isEmpty()) throw new BizException("审批流程模板未配置审批节点");
        validateUniqueApprovalNodes(nodes);

        WfInstance inst = new WfInstance();
        inst.setBizType(bizType);
        inst.setBizId(bizId);
        inst.setBizNo(bizNo);
        inst.setStatus("PROCESSING");
        inst.setApplicantId(SecurityUtil.uid());
        inst.setTotalAmt(totalAmt == null ? BigDecimal.ZERO : totalAmt);
        inst.setTemplateId(tpl.getId());
        inst.setNodesJson(tpl.getNodesJson());
        inst.setStartedAt(LocalDateTime.now());
        JSONObject first = nodes.getJSONObject(0);
        ApproverTarget firstApprover = resolveApprover(first, longValue(context.get("orgId")));
        inst.setCurrentNode(first.getString("node"));
        inst.setNextApproverId(firstApprover.userId());
        inst.setNextApproverRole(firstApprover.roleCode());
        instMapper.insert(inst);

        SysUser me = userMapper.selectById(SecurityUtil.uid());
        record(inst.getId(), bizNo, null, me, "SUBMIT", null);

        pushApprovalTodo(firstApprover, longValue(context.get("orgId")),
                "待审批：" + first.getString("name") + " - " + bizNo,
                bizType, bizNo, bizId);
        return inst;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean approve(String bizType, Long bizId, String comment, BiConsumer<String, Long> onAllApproved) {
        WfInstance inst = instMapper.findActive(bizType, bizId);
        if (inst == null || !"PROCESSING".equals(inst.getStatus())) {
            throw new BizException("单据当前不在审批中");
        }

        SysUser me = userMapper.selectById(SecurityUtil.uid());
        if (me != null && me.getId().equals(inst.getApplicantId())
                && !allowsFinanceSelfReview(bizType) && !isDemoSelfReview(me)) {
            throw new BizException("申请人不能审批自己的单据");
        }
        assertCurrentApprover(inst);

        Map<String, Object> context = workflowContext(bizType, bizId, inst.getTotalAmt());
        WfTemplate tpl = templateForInstance(inst, context);
        if (tpl == null) throw new BizException("未找到审批流程模板：" + bizType);
        JSONArray nodes = JSON.parseArray(tpl.getNodesJson());
        validateUniqueApprovalNodes(nodes);
        int curIdx = currentNodeIndex(nodes, inst.getCurrentNode());

        record(inst.getId(), inst.getBizNo(), inst.getCurrentNode(), me, "APPROVE", comment);
        notifService.closeBusinessTodos(bizType, bizId);

        boolean isLast = curIdx < 0 || curIdx == nodes.size() - 1;
        if (isLast) {
            // 并发防线：条件更新，双击/两人同审时只有一个成功；失败即中止，防止审批回调（库存过账）执行两次
            if (instMapper.finishIfProcessing(inst.getId(), "APPROVED") != 1) {
                throw new BizException("该单据刚被其他人处理，请刷新后查看");
            }
            notifService.pushToUser(inst.getApplicantId(), "单据 " + inst.getBizNo() + " 已审批通过",
                    bizType, inst.getBizNo(), bizId);
            if (onAllApproved != null) onAllApproved.accept(inst.getBizNo(), bizId);
            return true;
        }

        JSONObject next = nodes.getJSONObject(curIdx + 1);
        ApproverTarget nextApprover = resolveApprover(next, longValue(context.get("orgId")));
        // 并发防线：带当前节点比对的条件推进，两个并发审批只有一个能推进节点
        if (instMapper.advanceIfAt(inst.getId(), inst.getCurrentNode(), next.getString("node"),
                nextApprover.userId(), nextApprover.roleCode()) != 1) {
            throw new BizException("该单据刚被其他人处理，请刷新后查看");
        }
        pushApprovalTodo(nextApprover, longValue(context.get("orgId")),
                "待审批：" + next.getString("name") + " - " + inst.getBizNo(),
                bizType, inst.getBizNo(), bizId);
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(String bizType, Long bizId, String comment, BiConsumer<String, Long> onRejected) {
        WfInstance inst = instMapper.findActive(bizType, bizId);
        if (inst == null || !"PROCESSING".equals(inst.getStatus())) {
            throw new BizException("单据当前不在审批中");
        }
        SysUser me = userMapper.selectById(SecurityUtil.uid());
        if (me != null && me.getId().equals(inst.getApplicantId())
                && !allowsFinanceSelfReview(bizType) && !isDemoSelfReview(me)) {
            throw new BizException("申请人不能驳回自己的单据");
        }
        assertCurrentApprover(inst);
        record(inst.getId(), inst.getBizNo(), inst.getCurrentNode(), me, "REJECT", comment);
        notifService.closeBusinessTodos(bizType, bizId);
        // 并发防线：条件终结，防止与并发的通过/驳回/撤回互相踩
        if (instMapper.finishIfProcessing(inst.getId(), "REJECTED") != 1) {
            throw new BizException("该单据刚被其他人处理，请刷新后查看");
        }
        // 驳回通知发起人，进「待处理事项」+顶栏铃铛(TODO 才会被统计/展示)，提醒其修改后重新提交
        notifService.pushTodoToUser(inst.getApplicantId(), "单据 " + inst.getBizNo() + " 被驳回，请修改后重新提交：" + comment,
                bizType, inst.getBizNo(), bizId);
        if (onRejected != null) onRejected.accept(inst.getBizNo(), bizId);
    }

    /**
     * 取消提交（撤回）：只要单据仍在审批中（未走完/未被驳回），提交人即可整单撤回为草稿。
     * 允许在已有中间审批的情况下撤回——待审批单据尚未产生库存/成本等业务影响，撤回安全；
     * 中间审批节点会作废，但保留在审批记录中可追溯。若需在已"审批通过"的单据上撤销，请走"取消审批"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(String bizType, Long bizId) {
        WfInstance inst = instMapper.findActive(bizType, bizId);
        if (inst == null || !"PROCESSING".equals(inst.getStatus())) {
            throw new BizException("单据当前不在审批中，无法取消提交");
        }
        Long uid = SecurityUtil.uid();
        if (!Objects.equals(uid, inst.getApplicantId()) && !SecurityUtil.isAdmin()) {
            throw new BizException("只有提交人可以取消提交该单据");
        }
        SysUser me = userMapper.selectById(uid);
        record(inst.getId(), inst.getBizNo(), inst.getCurrentNode(), me, "RECALL", null);
        notifService.closeBusinessTodos(bizType, bizId);
        // 并发防线：条件终结，撤回与审批赛跑时只有一方成功
        if (instMapper.finishIfProcessing(inst.getId(), "WITHDRAWN") != 1) {
            throw new BizException("该单据刚被审批人处理，无法取消提交，请刷新后查看");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public WfCancelRequest requestCancel(String bizType, Long bizId, String bizNo, String reason) {
        if (bizType == null || bizId == null || bizNo == null || bizNo.isBlank()) {
            throw new BizException("取消审批申请缺少必要字段");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("取消审批原因不能为空");
        }
        if("OREQ".equals(bizType) && "TRANSFER".equals(jdbcTemplate.queryForObject("SELECT fulfillment_mode FROM biz_out_req WHERE id=? AND deleted=0",String.class,bizId)))
            throw new BizException("调拨申请请在领料进度处理剩余不领，不能取消审批");
        if ("OUT".equals(bizType)) assertMaterialOutHasNoReturns(bizId, false); // 申请时就拦,别等原审批人同意时才报错
        WfInstance latest = instMapper.findLatest(bizType, bizId);
        if (latest == null) {
            // 领料申请生成的出库申请没有审批实例（采购→仓管→财务三级记名确认替代审批流），
            // 走对称撤销：出库申请退回草稿 + 关联领料申请退回「待财务确认」并解除生成关联。
            WfCancelRequest direct = cancelLrGeneratedOutReq(bizType, bizId, bizNo, reason);
            if (direct != null) return direct;
            throw new BizException("未找到该单据的审批记录，无法取消审批");
        }
        if (!bizNo.equals(latest.getBizNo())) {
            throw new BizException("取消审批申请与原审批单据不匹配");
        }
        if (!"APPROVED".equals(latest.getStatus())) {
            throw new BizException("只有已审批单据可以申请取消审批");
        }
        List<WfCancelRequest> pendings = cancelMapper.selectList(new QueryWrapper<WfCancelRequest>()
                .eq("biz_type", bizType)
                .eq("biz_id", bizId)
                .eq("status", "PENDING")
                .orderByDesc("id"));
        if (!pendings.isEmpty()) {
            WfCancelRequest existing = pendings.get(0);
            Long me0 = SecurityUtil.uid();
            // 已有待处理的取消申请：若当前用户正是它的原审批人，直接同意这条已存在的申请。
            // 否则会死锁——待办一旦被标记已读就从“待处理事项”消失（/notif/my 只返回未读），
            // 而又不允许重复发起，单据再也撤不掉。发起人≠原审批人时仍提示去待办处理。
            if ((me0 != null && me0.equals(existing.getOriginApproverId())) || SecurityUtil.isAdmin()) {
                processCancel(existing.getId(), true, "原审批人确认取消", null);
                existing.setStatus("APPROVED");
                return existing;
            }
            throw new BizException("该单据已有待处理的取消审批申请，请到「待处理事项」处理");
        }
        assertCancelRequester(latest);

        WfRecord lastApprove = lastApproveRecord(bizNo);
        if (lastApprove == null) throw new BizException("未找到原审批人");

        WfCancelRequest cr = new WfCancelRequest();
        cr.setBizType(bizType);
        cr.setBizId(bizId);
        cr.setBizNo(bizNo);
        cr.setApplicantId(SecurityUtil.uid());
        cr.setOriginApproverId(lastApprove.getApproverId());
        cr.setReason(reason);
        cr.setStatus("PENDING");
        cr.setCreatedAt(LocalDateTime.now());
        cancelMapper.insert(cr);

        // 发起取消的人正好就是原审批人（单级审批下很常见）：再要他去待办点一次“同意”纯属多余，
        // 直接一步到位、当场生效；发起人 ≠ 原审批人时仍走原审批人确认，内控不丢。
        Long me = SecurityUtil.uid();
        if ((me != null && me.equals(lastApprove.getApproverId())) || SecurityUtil.isAdmin()) {
            processCancel(cr.getId(), true, "发起人即原审批人，取消审批直接生效", null);
            cr.setStatus("APPROVED"); // processCancel 改的是另一份实例，这里同步内存对象，供前端区分“已直接生效”
            return cr;
        }

        // APPROVAL：这是要原审批人做决定（同意取消 / 驳回），不是让他改单据，
        // 必须能进工作台「待办审批」Tab —— 撤销一张已过账单据是内控上最敏感的动作。
        notifService.pushTodoToUser(lastApprove.getApproverId(), NotifService.KIND_APPROVAL,
                "取消审批申请：" + bizNo + "，原因：" + reason,
                "CANCEL_REQ", bizNo, cr.getId());
        return cr;
    }

    /**
     * 领料申请生成的出库申请没有 wf_instance（三级记名确认替代审批流），取消审批走对称撤销：
     * 出库申请退回草稿（复用 cancelOutboundRequest 的关账校验与已出库拦截），关联领料申请
     * 退回「待财务确认」并解除生成关联——财务可重新确认再生成，或驳回。取消申请落库为已生效
     * 状态供审计（wf_record.instance_id 非空约束，无实例时不写审批轨迹，以取消申请记录留痕）。
     * 返回 null 表示该单不是领料生成的，由调用方按"无审批记录"报错。
     */
    private WfCancelRequest cancelLrGeneratedOutReq(String bizType, Long bizId, String bizNo, String reason) {
        if (!"OREQ".equals(bizType)) return null;
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT org_id, doc_no, applicant_id FROM biz_out_req WHERE id = ? AND deleted = 0", bizId);
        if (docs.isEmpty()) throw new BizException("出库申请不存在");
        if (!bizNo.equals(docs.get(0).get("doc_no"))) {
            throw new BizException("取消审批申请与原审批单据不匹配");
        }
        List<Map<String, Object>> lrs = jdbcTemplate.queryForList(
                "SELECT id FROM lr_request WHERE out_req_id = ? AND deleted = 0", bizId);
        if (lrs.isEmpty()) return null;
        orgScope.assertOrgVisible(((Number) docs.get(0).get("org_id")).longValue());
        if (!SecurityUtil.hasFinanceAccess() && !SecurityUtil.isAdmin()) {
            throw new BizException(4003, "该出库申请由领料申请确认生成，只有财务或管理员可以取消");
        }
        cancelOutboundRequest(bizId);
        // 🔴 计算审查 U10:以前这里把出库申请 A 退回草稿、同时把领料单退回「待财务确认」并断开关联,
        //    于是 A 可以重新提交审批,领料单又能再确认生成 B —— 同一份需求出了两份可发额度,实际发出两倍。
        //    现在 A 直接作废,只留「领料单重新确认 → 生成新申请」这一条路。
        jdbcTemplate.update("UPDATE biz_out_req SET status = 'CANCELLED' WHERE id = ? AND deleted = 0 AND status = 'DRAFT'", bizId);
        for (Map<String, Object> lr : lrs) {
            jdbcTemplate.update("""
                    UPDATE lr_request
                    SET status = 'WAREHOUSE_OK', finance_by = NULL, finance_at = NULL,
                        out_req_id = NULL, out_req_no = NULL
                    WHERE id = ? AND deleted = 0
                    """, ((Number) lr.get("id")).longValue());
        }
        WfCancelRequest cr = new WfCancelRequest();
        cr.setBizType(bizType);
        cr.setBizId(bizId);
        cr.setBizNo(bizNo);
        cr.setApplicantId(SecurityUtil.uid());
        cr.setOriginApproverId(SecurityUtil.uid());
        cr.setReason(reason);
        cr.setStatus("APPROVED");
        cr.setApproverComment("领料生成的出库申请，财务直接取消：出库申请作废，领料申请退回待财务确认，确认后重新生成出库申请");
        cr.setApprovedAt(LocalDateTime.now());
        cr.setCreatedAt(LocalDateTime.now());
        cancelMapper.insert(cr);
        // 告知经办人(采购=出库申请发起人):这张申请已作废,不用处理;领料单回到财务确认,确认后会生成新的出库申请。
        // 2026-10-18 起申请直接作废(计算审查 U10),所以发普通通知而不是待办——以前的"请修改重提或删除"会让人去碰一张作废单
        Object applicant = docs.get(0).get("applicant_id");
        if (applicant != null) {
            notifService.pushToUser(((Number) applicant).longValue(),
                    "出库申请已作废：" + bizNo + "（由领料申请生成，财务已取消；领料申请退回待财务确认，确认后会重新生成出库申请，无需处理本单）",
                    "OREQ", bizNo, bizId);
        }
        return cr;
    }

    @Transactional(rollbackFor = Exception.class)
    public void processCancel(Long cancelReqId, boolean approve, String comment, BiConsumer<String, Long> onCancelled) {
        WfCancelRequest cr = cancelMapper.selectById(cancelReqId);
        if (cr == null) throw new BizException("取消审批申请不存在");
        if (!"PENDING".equals(cr.getStatus())) throw new BizException("取消审批申请已处理");
        assertBusinessVisible(cr.getBizType(), cr.getBizId());
        if (!cr.getOriginApproverId().equals(SecurityUtil.uid()) && !SecurityUtil.isAdmin()) {
            throw new BizException("只有原审批人可以处理该取消审批申请");
        }

        // 并发防线：条件裁决，双击/并发处理时只有一个成功，防止「取消审批→反冲库存」执行两次
        if (cancelMapper.decideIfPending(cr.getId(), approve ? "APPROVED" : "REJECTED", comment) != 1) {
            throw new BizException("该取消审批申请刚被处理，请刷新后查看");
        }
        notifService.closeBusinessTodos(cr.getBizType(), cr.getBizId());
        notifService.closeBusinessTodos("CANCEL_REQ", cancelReqId);

        if (approve) {
            WfInstance inst = instMapper.findLatest(cr.getBizType(), cr.getBizId());
            if (inst != null) {
                inst.setStatus("CANCELLED");
                inst.setFinishedAt(LocalDateTime.now());
                instMapper.updateById(inst);
            }
            // 审计留痕：取消审批=退回草稿，写一条 wf_record（按 bizNo 永久保留、跨实例可查，
            // 即使单据退回草稿后被重新提交，这条“曾被取消退回”的记录依然可追溯）。
            SysUser canceller = userMapper.selectById(SecurityUtil.uid());
            String reason = blankToNull(cr.getReason());
            String note = "取消审批，退回草稿" + (reason == null ? "" : ("；原因：" + reason))
                    + (blankToNull(comment) == null ? "" : ("；处理意见：" + comment));
            record(inst != null ? inst.getId() : null, cr.getBizNo(),
                    inst != null ? inst.getCurrentNode() : null, canceller, "CANCEL", note);
            if (onCancelled != null) {
                onCancelled.accept(cr.getBizNo(), cr.getBizId());
            } else {
                cancelBusinessDocument(cr.getBizType(), cr.getBizId());
            }
            notifService.pushToUser(cr.getApplicantId(), "撤销申请通过：" + cr.getBizNo(),
                    cr.getBizType(), cr.getBizNo(), cr.getBizId());
            // 退回草稿进单据发起人待办，提醒去修改后重新提交或删除
            BizAccess biz = businessAccess(cr.getBizType(), cr.getBizId());
            if (biz != null && biz.applicantId() != null) {
                notifService.pushTodoToUser(biz.applicantId(),
                        "单据已退回草稿：" + cr.getBizNo() + "，请修改后重新提交或删除",
                        cr.getBizType(), cr.getBizNo(), cr.getBizId());
            }
        } else {
            notifService.pushToUser(cr.getApplicantId(), "撤销申请被驳回：" + cr.getBizNo() + " - " + comment,
                    cr.getBizType(), cr.getBizNo(), cr.getBizId());
        }
    }

    /**
     * 我发起的单据（跨类型聚合），按状态筛。手机端「我的草稿 / 我的退回」用。
     * 发起人字段：出库申请用 applicant_id，其余单据用 created_by。
     */
    public List<Map<String, Object>> myDocuments(String status) {
        Long uid = SecurityUtil.uid();
        if (uid == null) return java.util.Collections.emptyList();
        List<String> statuses = new java.util.ArrayList<>();
        for (String s : (status == null ? "DRAFT,REJECTED" : status).split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) statuses.add(v);
        }
        if (statuses.isEmpty()) return java.util.Collections.emptyList();
        String in = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        // bizType, doc_no, id, status, 业务日期；发起人列 + 日期列各表不同
        String[][] segs = {
                {"PO", "biz_po", "created_by", "order_date"},
                {"RECV", "biz_recv", "created_by", "recv_date"},
                {"PRET", "biz_purchase_return", "created_by", "return_date"},
                {"INV", "biz_invoice", "created_by", "invoice_date"},
                {"OUT", "biz_out", "created_by", "out_date"},
                {"RETURN", "biz_return", "created_by", "return_date"},
                {"LOAN", "biz_loan", "created_by", "borrow_date"},
                {"TRANSFER", "biz_transfer", "created_by", "transfer_date"},
                {"OREQ", "biz_out_req", "applicant_id", "apply_date"},
                {"SO", "biz_so", "created_by", "order_date"},
                {"SALE_OUT", "biz_sale_out", "created_by", "out_date"},
                {"SALE_INV", "biz_sale_invoice", "created_by", "invoice_date"},
                {"SALE_RETURN", "biz_sale_return", "created_by", "return_date"}
        };
        StringBuilder sql = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        // 各表 collation 可能不一致(老表 0900_ai_ci vs 销售表 unicode_ci),UNION 混排字符串列会报 1271。
        // 对所有字符串列(字面量 bizType / doc_no / status)统一强制 COLLATE utf8mb4_unicode_ci,与建表 collation 一致,不依赖库默认。
        String col = " COLLATE utf8mb4_unicode_ci";
        for (String[] s : segs) {
            if (sql.length() > 0) sql.append(" UNION ALL ");
            sql.append("SELECT '").append(s[0]).append("'").append(col).append(" AS bizType, ")
               .append("doc_no").append(col).append(" AS bizNo, id AS bizId, status").append(col).append(" AS status, ")
               .append(s[3]).append(" AS bizDate FROM ").append(s[1])
               .append(" WHERE ").append(s[2]).append(" = ? AND deleted = 0 AND status IN (").append(in).append(")");
            args.add(uid);
            args.addAll(statuses);
        }
        sql.append(" ORDER BY bizDate DESC, bizNo DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 待我审批的单据（手机端「待我审」的权威来源）。
     *
     * <p><b>为什么要单独做这个接口:</b>手机端原先的「待我审」是从<b>通知列表</b>里筛出来的。
     * 通知是一次性的消息，单据待审却是持续的状态——消息被读掉、被挤出前 50 条，
     * 或者某类单据提交时压根没发通知，手机上就再也看不到那张单了，可电脑上进单据列表
     * 筛「审批中」它还在。这就是用户反馈的"手机和电脑对不上"。</p>
     *
     * <p><b>改成查单据本身:</b>先把各业务表里 PENDING 的单和 wf_instance 里正在流转的实例对上，
     * 再<b>逐条用 {@link #canApprove} 复核</b>——口径与单据详情页的审批按钮完全一致，
     * 不会出现"列出来了却审不了"或"能审却没列出来"。</p>
     *
     * <p><b>账套:</b>按可见账套过滤，且 fail-closed（取不到可见账套就返回空，不是返回全部）。</p>
     */
    public List<Map<String, Object>> pendingApprovals() {
        Long uid = SecurityUtil.uid();
        if (uid == null) return java.util.Collections.emptyList();
        List<Long> orgIds = orgScope.visibleOrgIds();
        // fail-closed：宁可什么都不显示，也不能把别家公司的单据列出来
        if (orgIds == null || orgIds.isEmpty()) return java.util.Collections.emptyList();

        // bizType, 表名, 业务日期列, 金额列(没有的填 NULL)
        String[][] segs = {
                {"PO", "biz_po", "order_date", "total_amt"},
                {"RECV", "biz_recv", "recv_date", "total_amt"},
                {"PRET", "biz_purchase_return", "return_date", "total_recv_amt"},
                {"INV", "biz_invoice", "invoice_date", "total_amt"},
                {"OUT", "biz_out", "out_date", "total_amt"},
                {"RETURN", "biz_return", "return_date", "total_amt"},
                {"LOAN", "biz_loan", "borrow_date", "total_amt"},
                {"TRANSFER", "biz_transfer", "transfer_date", "total_amt"},
                {"OREQ", "biz_out_req", "apply_date", "NULL"},
                {"SO", "biz_so", "order_date", "total_amt"},
                {"SALE_OUT", "biz_sale_out", "out_date", "total_amt"},
                {"SALE_INV", "biz_sale_invoice", "invoice_date", "total_amt"},
                {"SALE_RETURN", "biz_sale_return", "return_date", "total_amt"}
        };
        // 各表 collation 不一致时 UNION 字符串列会报 1271，统一强制 utf8mb4_unicode_ci（与 myDocuments 同一套路）
        String col = " COLLATE utf8mb4_unicode_ci";
        StringBuilder union = new StringBuilder();
        for (String[] seg : segs) {
            if (union.length() > 0) union.append(" UNION ALL ");
            union.append("SELECT '").append(seg[0]).append("'").append(col).append(" AS bizType, ")
                 .append("doc_no").append(col).append(" AS bizNo, id AS bizId, ")
                 .append(seg[2]).append(" AS bizDate, org_id AS orgId, ")
                 .append(seg[3]).append(" AS totalAmt FROM ").append(seg[1])
                 .append(" WHERE status = 'PENDING' AND deleted = 0");
        }

        String in = String.join(",", java.util.Collections.nCopies(orgIds.size(), "?"));
        String sql = "SELECT t.bizType, t.bizNo, t.bizId, t.bizDate, t.orgId, t.totalAmt, "
                + "w.applicant_id AS applicantId, w.current_node AS currentNode, w.started_at AS startedAt, "
                + "u.name AS applicantName, o.name AS orgName "
                + "FROM (" + union + ") t "
                + "JOIN wf_instance w ON w.biz_type" + col + " = t.bizType AND w.biz_id = t.bizId "
                + "  AND w.status = 'PROCESSING' "
                + "LEFT JOIN sys_user u ON u.id = w.applicant_id "
                + "LEFT JOIN sys_org o ON o.id = t.orgId "
                + "WHERE t.orgId IN (" + in + ") "
                // 等得最久的排最前面：审批人一眼看到哪张单压得久
                + "ORDER BY w.started_at ASC LIMIT 200";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, orgIds.toArray());
        // 金额按角色脱敏：仓管等没有财务权限的角色一律看不到金额，与单据列表同口径。
        // 不能因为"这是待办列表"就把金额漏出去——绕过脱敏的口子往往就是这么开的。
        boolean seeAmount = SecurityUtil.hasFinanceAccessOrAdmin();
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("bizId");
            if (idObj == null) continue;
            String bizType = String.valueOf(row.get("bizType"));
            long bizId = ((Number) idObj).longValue();
            // 权威判断：与详情页审批按钮同一把尺子（含"自己提的不能自己审"和岗位角色校验）
            if (!canApprove(bizType, bizId)) continue;
            if (!seeAmount) row.put("totalAmt", null);
            mine.add(row);
        }
        return mine;
    }

    public List<WfRecord> getRecords(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException("单据号不能为空");
        }
        WfInstance latest = instMapper.findLatestByBizNo(bizNo);
        // 没走过审批的单据(草稿、演示样例、导入的期初单据)就是没有记录:返回空列表,别弹红色报错。
        // 单号不存在也同样返回空,不借此透露"这个单号存不存在"。
        if (latest == null) return List.of();
        assertWorkflowReadable(latest);
        return recordMapper.findByBizNo(bizNo);
    }

    /**
     * 单据完整审批流：每个节点的「已通过 / 当前待审 / 未开始」+ 申请人。
     * 供各单据详情页随手查看审批进度（审批到哪、还有谁没批）。
     */
    public Map<String, Object> getApprovalFlow(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) throw new BizException("单据号不能为空");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        WfInstance inst = instMapper.findLatestByBizNo(bizNo);
        if (inst == null) {
            result.put("state", "NONE");
            result.put("steps", java.util.Collections.emptyList());
            return result;
        }
        assertWorkflowReadable(inst);
        List<WfRecord> records = recordMapper.findByBizNo(bizNo);

        for (WfRecord r : records) {
            if ("SUBMIT".equals(r.getAction())) {
                result.put("applicant", r.getApproverName());
                result.put("submittedAt", r.getCreatedAt());
                break;
            }
        }
        result.put("state", inst.getStatus());
        result.put("currentNode", inst.getCurrentNode());
        result.put("nextApproverRole", inst.getNextApproverRole());

        Map<String, WfRecord> approveByNode = new java.util.HashMap<>();
        WfRecord rejectRec = null;
        for (WfRecord r : records) {
            if ("APPROVE".equals(r.getAction()) && r.getNode() != null) approveByNode.put(r.getNode(), r);
            if ("REJECT".equals(r.getAction())) rejectRec = r;
        }
        boolean approved = "APPROVED".equals(inst.getStatus());
        boolean rejected = "REJECTED".equals(inst.getStatus());

        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        JSONArray nodes = inst.getNodesJson() == null ? null : JSON.parseArray(inst.getNodesJson());
        boolean markedCurrent = false;
        if (nodes != null) {
            for (int i = 0; i < nodes.size(); i++) {
                JSONObject n = nodes.getJSONObject(i);
                String code = n.getString("node");
                Map<String, Object> step = new java.util.LinkedHashMap<>();
                step.put("node", code);
                step.put("name", n.getString("name"));
                step.put("role", n.getString("approverRole"));
                WfRecord rec = approveByNode.get(code);
                boolean isCurrent = code != null && code.equals(inst.getCurrentNode());
                if (rec != null) {
                    step.put("status", "DONE");
                    step.put("approver", rec.getApproverName());
                    step.put("comment", rec.getComment());
                    step.put("time", rec.getCreatedAt());
                } else if (approved) {
                    step.put("status", "DONE");
                } else if (rejected && isCurrent) {
                    step.put("status", "REJECTED");
                    if (rejectRec != null) {
                        step.put("approver", rejectRec.getApproverName());
                        step.put("comment", rejectRec.getComment());
                        step.put("time", rejectRec.getCreatedAt());
                    }
                } else if (isCurrent && !markedCurrent) {
                    step.put("status", "CURRENT");
                    markedCurrent = true;
                } else {
                    step.put("status", "PENDING");
                }
                steps.add(step);
            }
        }
        result.put("steps", steps);

        // 完整操作历史（提交/审批/驳回/撤回/取消退回，按 bizNo 跨实例全留痕，供追溯）
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (WfRecord r : records) {
            Map<String, Object> h = new java.util.LinkedHashMap<>();
            h.put("action", r.getAction());
            h.put("approver", r.getApproverName());
            h.put("time", r.getCreatedAt());
            h.put("comment", r.getComment());
            h.put("node", r.getNode());
            history.add(h);
        }
        result.put("history", history);
        return result;
    }

    private List<WfTemplate> templatesForMode(String bizType, String approvalMode) {
        String mode = normalizeApprovalMode(approvalMode);
        List<WfTemplate> templates = tplMapper.findByBizTypeAndMode(bizType, mode);
        if ((templates == null || templates.isEmpty()) && !"STANDARD".equals(mode)) {
            templates = tplMapper.findByBizTypeAndMode(bizType, "STANDARD");
        }
        if (templates == null || templates.isEmpty()) {
            templates = tplMapper.findByBizType(bizType);
        }
        return templates;
    }

    private WfTemplate templateForInstance(WfInstance inst, Map<String, Object> context) {
        if (inst == null) return null;
        String snapshot = blankToNull(inst.getNodesJson());
        if (snapshot != null) {
            WfTemplate tpl = new WfTemplate();
            tpl.setId(inst.getTemplateId());
            tpl.setBizType(inst.getBizType());
            tpl.setName("INSTANCE_SNAPSHOT");
            tpl.setNodesJson(snapshot);
            return tpl;
        }
        if (inst.getTemplateId() != null) {
            WfTemplate stored = tplMapper.selectById(inst.getTemplateId());
            if (stored != null && blankToNull(stored.getNodesJson()) != null) {
                return stored;
            }
        }

        // Older processing instances did not store a node snapshot. They were created before the
        // per-account switch existed, so keep them on the standard chain even if the account changes mode later.
        List<WfTemplate> standardTemplates = templatesForMode(inst.getBizType(), "STANDARD");
        WfTemplate matched = matchTemplate(standardTemplates, context);
        if (matched != null && templateContainsNode(matched, inst.getCurrentNode())) return matched;

        WfTemplate currentNodeTemplate = templateContainingNode(standardTemplates, inst.getCurrentNode());
        if (currentNodeTemplate != null) return currentNodeTemplate;
        if (matched != null) return matched;
        return matchTemplate(tplMapper.findByBizType(inst.getBizType()), context);
    }

    private WfTemplate templateContainingNode(List<WfTemplate> templates, String currentNode) {
        if (templates == null || currentNode == null || currentNode.isBlank()) return null;
        for (WfTemplate tpl : templates) {
            JSONArray nodes = JSON.parseArray(tpl.getNodesJson());
            if (currentNodeIndex(nodes, currentNode) >= 0) return tpl;
        }
        return null;
    }

    private boolean templateContainsNode(WfTemplate tpl, String currentNode) {
        if (tpl == null || currentNode == null || currentNode.isBlank()) return true;
        return currentNodeIndex(JSON.parseArray(tpl.getNodesJson()), currentNode) >= 0;
    }

    private String approvalModeForOrg(Long orgId) {
        if (orgId == null) return "STANDARD";
        Map<Long, WorkflowOrgRef> orgs = loadWorkflowOrgRefs();
        WorkflowOrgRef current = orgs.get(orgId);
        if (current == null) return "STANDARD";
        WorkflowOrgRef root = orgs.get(accountRoot(current, orgs));
        return normalizeApprovalMode(root == null ? current.approvalMode() : root.approvalMode());
    }

    private String normalizeApprovalMode(String value) {
        return "SIMPLE".equalsIgnoreCase(blankToNull(value)) ? "SIMPLE" : "STANDARD";
    }

    private WfTemplate matchTemplate(List<WfTemplate> tpls, Map<String, Object> variables) {
        if (tpls == null || tpls.isEmpty()) return null;
        SpelExpressionParser parser = new SpelExpressionParser();
        for (WfTemplate t : tpls) {
            if (t.getConditionExpr() == null || t.getConditionExpr().isBlank()) return t;
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            Map<String, Object> vars = variables == null ? Map.of() : variables;
            vars.forEach(ctx::setVariable);
            try {
                Boolean r = parser.parseExpression(t.getConditionExpr()).getValue(ctx, Boolean.class);
                if (Boolean.TRUE.equals(r)) return t;
            } catch (Exception ignored) {
            }
        }
        return tpls.stream()
                .filter(t -> t.getConditionExpr() == null || t.getConditionExpr().isBlank())
                .findFirst()
                .orElse(tpls.get(0));
    }

    private void record(Long instId, String bizNo, String node, SysUser approver, String action, String comment) {
        WfRecord r = new WfRecord();
        r.setInstanceId(instId);
        r.setBizNo(bizNo);
        r.setNode(node);
        if (approver != null) {
            r.setApproverId(approver.getId());
            r.setApproverName(approver.getName());
        }
        r.setAction(action);
        r.setComment(comment);
        r.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(r);
    }

    private int currentNodeIndex(JSONArray nodes, String currentNode) {
        if (nodes == null || currentNode == null) return -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.getJSONObject(i).getString("node").equals(currentNode)) return i;
        }
        return -1;
    }

    /**
     * 当前登录用户是否可审批指定单据的当前节点。
     * 用于前端按行/按单据控制"审批/驳回"按钮可见性，避免非审批人(如采购员)也看到审批按钮。
     */
    public boolean canApprove(String bizType, Long bizId) {
        Long uid = SecurityUtil.uid();
        if (uid == null || bizType == null || bizId == null) return false;
        WfInstance inst = instMapper.findActive(bizType, bizId);
        if (inst == null || !"PROCESSING".equals(inst.getStatus())) return false;
        if (uid.equals(inst.getApplicantId()) && !allowsFinanceSelfReview(bizType)) return false;
        Long nextId = inst.getNextApproverId();
        String role = blankToNull(inst.getNextApproverRole());
        if (nextId != null) return uid.equals(nextId);
        if (role != null) return SecurityUtil.roles().contains(role);
        return false;
    }

    private void assertCurrentApprover(WfInstance inst) {
        Long userId = SecurityUtil.uid();
        if (inst.getNextApproverId() != null && !Objects.equals(userId, inst.getNextApproverId())) {
            throw new BizException("当前账号不是本节点审批人");
        }
        String roleCode = blankToNull(inst.getNextApproverRole());
        if (roleCode != null && !SecurityUtil.roles().contains(roleCode)) {
            throw new BizException("当前节点需要岗位角色：" + roleCode);
        }
        if (inst.getNextApproverId() == null && roleCode == null) {
            throw new BizException("审批流程当前节点缺少审批人配置");
        }
    }

    private ApproverTarget resolveApprover(JSONObject node, Long bizOrgId) {
        if (node == null) throw new BizException("审批节点配置为空");
        String roleCode = blankToNull(node.getString("approverRole"));
        Long userId = node.getLong("approverUserId");
        String approverName = blankToNull(node.getString("approverName"));

        SysUser approver = null;
        if (userId != null) {
            approver = userMapper.selectById(userId);
            assertActiveApprover(approver, node);
        } else if (approverName != null) {
            approver = findUniqueActiveUserByName(approverName, bizOrgId);
            if (approver == null) {
                throw new BizException("未找到审批人：" + approverName);
            }
        }

        if (approver != null) {
            if (roleCode != null && !userHasRole(approver.getId(), roleCode)) {
                throw new BizException("审批人 " + approver.getName() + " 未绑定岗位角色：" + roleCode);
            }
            return new ApproverTarget(approver.getId(), roleCode, approver.getName());
        }

        if (roleCode == null) {
            throw new BizException("审批节点缺少审批人或岗位角色：" + node.getString("name"));
        }
        return new ApproverTarget(null, roleCode, node.getString("name"));
    }

    private void assertActiveApprover(SysUser user, JSONObject node) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || Integer.valueOf(1).equals(user.getDeleted())) {
            throw new BizException("审批人不可用：" + node.getString("name"));
        }
    }

    private SysUser findUniqueActiveUserByName(String name, Long bizOrgId) {
        QueryWrapper<SysUser> scoped = new QueryWrapper<SysUser>()
                .eq("name", name)
                .eq("status", 1);
        List<Long> orgIds = accountOrgIds(bizOrgId);
        if (!orgIds.isEmpty()) scoped.in("org_id", orgIds);
        List<SysUser> users = userMapper.selectList(scoped.last("LIMIT 2"));
        if (users.size() > 1) {
            throw new BizException("审批人姓名重复，请改用审批人ID：" + name);
        }
        return users.isEmpty() ? null : users.get(0);
    }

    private boolean userHasRole(Long userId, String roleCode) {
        if (userId == null || roleCode == null || roleCode.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_user_role ur
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                  AND r.code = ?
                  AND r.deleted = 0
                  AND r.status = 1
                """, Integer.class, userId, roleCode);
        return count != null && count > 0;
    }

    /** 方法名就叫 ApprovalTodo，这里推的一律是 APPROVAL（等我审批），别用默认值 */
    private void pushApprovalTodo(ApproverTarget target, Long orgId, String title, String bizType, String bizNo, Long bizId) {
        if (target.userId() != null) {
            // 审批节点直接指到具体人时也要标 APPROVAL —— 不标就会落成 REWORK（默认值），
            // 工作台「待办审批」Tab 里就看不到它了
            notifService.pushTodoToUser(target.userId(), NotifService.KIND_APPROVAL, title, bizType, bizNo, bizId);
            return;
        }
        if (target.roleCode() != null) {
            // 按角色广播时带上单据账套，查询端据此做账套隔离
            notifService.pushToRole(target.roleCode(), orgId, title, bizType, bizNo, bizId);
            return;
        }
        throw new BizException("审批待办缺少接收人");
    }

    private void validateUniqueApprovalNodes(JSONArray nodes) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; nodes != null && i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String approverName = blankToNull(node.getString("approverName"));
            String roleCode = blankToNull(node.getString("approverRole"));
            String key = approverName != null ? "USER:" + approverName : "ROLE:" + roleCode;
            if (!seen.add(key)) {
                throw new BizException("审批流程存在重复审批节点：" + (approverName != null ? approverName : roleCode));
            }
        }
    }

    private Map<String, Object> workflowContext(String bizType, Long bizId, BigDecimal totalAmt) {
        Map<String, Object> ctx = new HashMap<>();
        BigDecimal amount = totalAmt == null ? BigDecimal.ZERO : totalAmt;
        ctx.put("totalAmt", amount);
        ctx.put("amt", amount);
        BizAccess access = businessAccess(bizType, bizId);
        if (access != null) {
            ctx.put("orgId", access.orgId());
            ctx.put("applicantId", access.applicantId());
            ctx.put("docNo", access.docNo());
        }
        switch (normalizeType(bizType)) {
            case "OREQ" -> ctx.putAll(queryWorkflowFields("""
                    SELECT out_type, business_category
                    FROM biz_out_req
                    WHERE id = ? AND deleted = 0
                    """, bizId));
            case "OUT" -> ctx.putAll(queryWorkflowFields("""
                    SELECT out_type, business_category
                    FROM biz_out
                    WHERE id = ? AND deleted = 0
                    """, bizId));
            case "RETURN" -> ctx.putAll(queryWorkflowFields("""
                    SELECT out_type, business_category
                    FROM biz_return
                    WHERE id = ? AND deleted = 0
                    """, bizId));
            default -> {
            }
        }
        return ctx;
    }

    private Map<String, Object> queryWorkflowFields(String sql, Long bizId) {
        if (bizId == null) return Map.of();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, bizId);
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> ctx = new HashMap<>();
        Map<String, Object> row = rows.get(0);
        ctx.put("outType", row.get("out_type"));
        ctx.put("businessCategory", row.get("business_category"));
        return ctx;
    }

    private WfRecord lastApproveRecord(String bizNo) {
        List<WfRecord> records = recordMapper.findByBizNo(bizNo);
        for (int i = records.size() - 1; i >= 0; i--) {
            if ("APPROVE".equals(records.get(i).getAction())) return records.get(i);
        }
        return null;
    }

    private boolean allowsFinanceSelfReview(String bizType) {
        return "INV".equals(normalizeType(bizType)) && SecurityUtil.hasFinanceAccess();
    }

    /** 演示账套放开自审：演示号自己提交自己审批，方便单账号推广演示（仅 demo.org.id 指向的账套，生产不受影响）。 */
    private boolean isDemoSelfReview(SysUser me) {
        if (me == null || me.getOrgId() == null) return false;
        try {
            Long demoOrg = jdbcTemplate.query(
                    "SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id'",
                    rs -> rs.next() ? rs.getLong(1) : null);
            return demoOrg != null && demoOrg.equals(me.getOrgId());
        } catch (Exception e) {
            return false;
        }
    }

    private void assertWorkflowReadable(WfInstance inst) {
        BizAccess access = businessAccess(inst.getBizType(), inst.getBizId());
        if (access == null) throw new BizException("业务单据不存在");
        orgScope.assertOrgVisible(access.orgId());
        if (SecurityUtil.isAdmin()) return;

        Long userId = SecurityUtil.uid();
        if (Objects.equals(userId, access.applicantId()) || Objects.equals(userId, inst.getApplicantId())) return;
        if (Objects.equals(userId, inst.getNextApproverId())) return;
        if (inst.getNextApproverRole() != null && SecurityUtil.hasRole(inst.getNextApproverRole())) return;
        if (canReadBusinessType(inst.getBizType())) return;

        throw new BizException(4003, "无权查看该审批记录");
    }

    private void assertCancelRequester(WfInstance inst) {
        BizAccess access = businessAccess(inst.getBizType(), inst.getBizId());
        if (access == null) throw new BizException("业务单据不存在");
        orgScope.assertOrgVisible(access.orgId());
        Long userId = SecurityUtil.uid();
        if (SecurityUtil.isAdmin()
                || SecurityUtil.hasFinanceAccess()
                || Objects.equals(userId, access.applicantId())
                || Objects.equals(userId, inst.getApplicantId())) {
            return;
        }
        throw new BizException(4003, "只有申请人、财务或管理员可以申请取消审批");
    }

    public List<Map<String, Object>> pendingCancelRequests(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        List<String> docNos = com.group.scm.common.DocNoQuery.asDocNoList(keyword);
        if (docNos == null) docNos = List.of(keyword.trim());
        if (docNos.size() > 200) throw new BizException("一次最多查询200张单据");
        List<Long> visible = orgScope.visibleOrgIds();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WfCancelRequest cr : cancelMapper.selectList(new QueryWrapper<WfCancelRequest>()
                .eq("status", "PENDING").in("biz_no", docNos).orderByDesc("id"))) {
            BizAccess access = businessAccess(cr.getBizType(), cr.getBizId());
            if (access == null || !visible.contains(access.orgId())) continue;
            boolean canProcess = SecurityUtil.isAdmin() || Objects.equals(cr.getOriginApproverId(), SecurityUtil.uid());
            if (!canProcess && !SecurityUtil.hasFinanceAccess()
                    && !Objects.equals(cr.getApplicantId(), SecurityUtil.uid())) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", cr.getId()); row.put("bizNo", cr.getBizNo());
            row.put("bizType", cr.getBizType()); row.put("reason", cr.getReason());
            row.put("createdAt", cr.getCreatedAt()); row.put("canProcess", canProcess);
            rows.add(row);
        }
        return rows;
    }

    private void assertBusinessVisible(String bizType, Long bizId) {
        BizAccess access = businessAccess(bizType, bizId);
        if (access == null) throw new BizException("业务单据不存在");
        orgScope.assertOrgVisible(access.orgId());
    }

    private void cancelBusinessDocument(String bizType, Long bizId) {
        switch (normalizeType(bizType)) {
            case "PR" -> cancelPurchaseRequest(bizId);
            case "PO" -> cancelPurchaseOrder(bizId);
            case "RECV" -> cancelReceive(bizId);
            case "PRET" -> cancelPurchaseReturn(bizId);
            case "INV" -> cancelInvoice(bizId);
            case "OREQ" -> cancelOutboundRequest(bizId);
            case "OUT" -> cancelMaterialOut(bizId);
            case "TRANSFER" -> cancelTransfer(bizId);
            case "SO" -> cancelSaleOrder(bizId);
            case "SALE_OUT" -> cancelSaleOut(bizId);
            case "SALE_INV" -> cancelSaleInvoice(bizId);
            case "SALE_RETURN" -> cancelSaleReturn(bizId);
            default -> throw new BizException("该单据类型需要使用专门的取消审批流程");
        }
    }

    private void cancelPurchaseRequest(Long bizId) {
        assertDocPeriodOpen("biz_pr", "apply_date", bizId, "采购申请", "采购申请不存在");
        Integer downstream = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_po WHERE source_pr_id = ? AND deleted = 0",
                Integer.class, bizId);
        if (downstream != null && downstream > 0) {
            throw new BizException("采购申请已下推采购订单，不能直接取消审批");
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_pr SET status = 'DRAFT' WHERE id = ? AND deleted = 0 AND status = 'APPROVED'",
                bizId);
        if (updated == 0) throw new BizException("采购申请当前状态不允许取消审批");
    }

    private void cancelPurchaseOrder(Long bizId) {
        DocPeriod doc = docPeriod("biz_po", "order_date", bizId, "采购订单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "采购订单", doc.docNo());
        BigDecimal receivedQty = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(received_qty), 0)
                FROM biz_po_line
                WHERE po_id = ?
                """, BigDecimal.class, bizId);
        if (receivedQty != null && receivedQty.signum() > 0) {
            throw new BizException("采购订单已产生入库记录，不能直接取消审批");
        }
        // 计算审查 U05:还有待审批的入库单时先撤回,否则订单退回草稿后它们就成了过不了账的死单
        //(最终把关在入库过账时复核订单状态,见 RecvService.approve)
        List<String> pendingRecv = jdbcTemplate.queryForList(
                "SELECT doc_no FROM biz_recv WHERE source_po_id = ? AND deleted = 0 AND status = 'PENDING' ORDER BY id",
                String.class, bizId);
        if (!pendingRecv.isEmpty()) {
            throw new BizException("该采购订单还有待审批的入库单 " + String.join("、", pendingRecv) + "，请先撤回或驳回后再取消订单审批");
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_po SET status = 'DRAFT' WHERE id = ? AND deleted = 0 AND status = 'APPROVED'",
                bizId);
        if (updated == 0) throw new BizException("采购订单当前状态不允许取消审批");

        Long sourcePrId = queryLong("SELECT source_pr_id FROM biz_po WHERE id = ? AND deleted = 0", bizId);
        if (sourcePrId != null) {
            DocPeriod prDoc = docPeriod("biz_pr", "apply_date", sourcePrId, "来源采购申请不存在");
            periodCloseService.assertPeriodOpen(prDoc.bizDate(), prDoc.orgId(), "采购申请", prDoc.docNo());
            jdbcTemplate.update("""
                    UPDATE biz_pr
                    SET status = 'APPROVED', updated_at = NOW()
                    WHERE id = ? AND deleted = 0 AND status = 'CONVERTED'
                    """, sourcePrId);
            jdbcTemplate.update("""
                    UPDATE biz_pr_line
                    SET converted_qty = 0
                    WHERE pr_id = ?
                    """, sourcePrId);
        }
    }

    private void cancelOutboundRequest(Long bizId) {
        String mode=jdbcTemplate.queryForObject("SELECT fulfillment_mode FROM biz_out_req WHERE id=? FOR UPDATE",String.class,bizId);
        if("TRANSFER".equals(mode)) throw new BizException("调拨申请已有执行记录，请通过剩余不领处理，不能直接取消审批");
        outReqIntegrity.assertNoDownstream(bizId);
        assertDocPeriodOpen("biz_out_req", "apply_date", bizId, "出库申请", "出库申请不存在");
        BigDecimal outQty = jdbcTemplate.queryForObject("""
                SELECT COALESCE(out_qty, 0)
                FROM biz_out_req
                WHERE id = ? AND deleted = 0
                """, BigDecimal.class, bizId);
        if (outQty != null && outQty.signum() > 0) {
            throw new BizException("出库申请已产生材料出库记录，不能直接取消审批");
        }
        // 计算审查 U09:还有在办的修订单时不许取消原申请(否则原申请改完,旧修订批准会把它一并恢复成已审批)
        List<String> openRev = jdbcTemplate.queryForList("""
                SELECT doc_no FROM biz_out_req_revision
                WHERE source_req_id = ? AND deleted = 0 AND status IN ('DRAFT','PENDING','REJECTED')
                ORDER BY id
                """, String.class, bizId);
        if (!openRev.isEmpty()) {
            throw new BizException("该出库申请还有未完成的修订单 " + String.join("、", openRev) + "，请先删除或审完修订单再取消申请审批");
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_out_req SET status = 'DRAFT' WHERE id = ? AND deleted = 0 AND status = 'APPROVED'",
                bizId);
        if (updated == 0) throw new BizException("出库申请当前状态不允许取消审批");
    }

    private void cancelReceive(Long bizId) {
        DocPeriod doc = docPeriod("biz_recv", "recv_date", bizId, "采购入库单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "采购入库单", doc.docNo());
        String status = queryString("SELECT status FROM biz_recv WHERE id = ? AND deleted = 0", bizId);
        if (!"APPROVED".equals(status)) {
            throw new BizException("采购入库单当前状态不允许取消审批");
        }
        Integer invoiceRefs = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM biz_invoice_line il
                JOIN biz_invoice i ON i.id = il.invoice_id AND i.deleted = 0 AND i.status <> 'CANCELLED'
                JOIN biz_recv_line rl ON rl.id = il.recv_line_id
                WHERE rl.recv_id = ?
                """, Integer.class, bizId);
        if (invoiceRefs != null && invoiceRefs > 0) {
            throw new BizException("采购入库单已被发票核销，需先取消发票审批后再取消入库审批");
        }
        // 已退货的入库单：库存早被退货单减掉了，直接反审会去冲一批已经不在账上的货 →
        // 要么触发负库存报错(信息晦涩)，要么把别的批次的货冲掉。先退掉退货单才能反审。
        Integer returnRefs = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM biz_purchase_return pr
                WHERE pr.source_recv_id = ? AND pr.deleted = 0 AND pr.status = 'APPROVED'
                """, Integer.class, bizId);
        if (returnRefs != null && returnRefs > 0) {
            throw new BizException("采购入库单已产生采购退货单，需先取消退货单审批后再取消入库审批");
        }

        Long sourcePoId = queryLong("SELECT source_po_id FROM biz_recv WHERE id = ? AND deleted = 0", bizId);

        waService.reverse(bizId, "RECV", "取消入库审批");
        for (Map<String, Object> line : jdbcTemplate.queryForList("""
                SELECT qty, source_po_line_id
                FROM biz_recv_line
                WHERE recv_id = ?
                """, bizId)) {
            Long poLineId = longValue(line.get("source_po_line_id"));
            if (poLineId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_po_line
                        SET received_qty = GREATEST(COALESCE(received_qty, 0) - ?, 0)
                        WHERE id = ?
                        """, decimal(line.get("qty")), poLineId);
            }
        }
        int updated = jdbcTemplate.update("""
                UPDATE biz_recv
                SET status = 'DRAFT', updated_at = NOW()
                WHERE id = ? AND deleted = 0 AND status = 'APPROVED'
                """, bizId);
        if (updated == 0) throw new BizException("采购入库单当前状态不允许取消审批");
        recomputePurchaseOrderReceive(sourcePoId);
    }

    /**
     * 取消采购退货单审批：把过账时做的三件事对称还原。
     *
     * <p>正向过账做了：①登记 returned_qty ②按加权均价出库 ③回退采购订单已入库数。
     * 这里逐条反向，顺序相反：先把库存补回来，再退还 returned_qty 额度，最后恢复订单已入库数。</p>
     *
     * <p>库存按【原出库金额】补回（{@code waService.reverse} 用的是流水里的原始金额），
     * 不是按当前均价 —— 期间若有别的单据改动过均价，按当前均价补会凭空改变库存总额。</p>
     */
    private void cancelPurchaseReturn(Long bizId) {
        DocPeriod doc = docPeriod("biz_purchase_return", "return_date", bizId, "采购退货单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "采购退货单", doc.docNo());
        String status = queryString("SELECT status FROM biz_purchase_return WHERE id = ? AND deleted = 0", bizId);
        if (!"APPROVED".equals(status)) {
            throw new BizException("采购退货单当前状态不允许取消审批");
        }

        waService.reverse(bizId, "PRET", "取消采购退货审批");

        Long sourcePoId = null;
        for (Map<String, Object> line : jdbcTemplate.queryForList("""
                SELECT l.id, l.qty, l.source_recv_line_id, l.source_po_line_id, r.source_po_id
                FROM biz_purchase_return_line l
                JOIN biz_purchase_return r ON r.id = l.return_id
                WHERE l.return_id = ?
                """, bizId)) {
            BigDecimal qty = decimal(line.get("qty"));
            Long recvLineId = longValue(line.get("source_recv_line_id"));
            Long poLineId = longValue(line.get("source_po_line_id"));
            if (sourcePoId == null) sourcePoId = longValue(line.get("source_po_id"));
            if (recvLineId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_recv_line
                        SET returned_qty = GREATEST(COALESCE(returned_qty, 0) - ?, 0)
                        WHERE id = ?
                        """, qty, recvLineId);
            }
            if (poLineId != null) {
                // 🔴 计算审查 U33:退货释放出来的订单额度可能已被后续补入库用掉;以前无条件加回,
                //    出现「订单 10、已入 15」,突破了正常入库强制执行的订单数量上限。
                //    条件更新:加回后不能超过订单数量,否则整笔取消审批回滚(含上面已做的库存冲回)。
                int ok = jdbcTemplate.update("""
                        UPDATE biz_po_line
                        SET received_qty = COALESCE(received_qty, 0) + ?
                        WHERE id = ? AND COALESCE(received_qty, 0) + ? <= qty
                        """, qty, poLineId, qty);
                if (ok != 1) {
                    throw new BizException("这张退货释放出来的订单额度已经被后续入库用掉了，取消退货审批会让订单已入库数超过订单数量。"
                            + "如确需更正，请先取消或删除后续的补入库单，再取消本退货的审批");
                }
            }
            // 过账时回填的实际出库价/金额/差额随之作废，避免退回草稿后仍显示上一次过账的数
            jdbcTemplate.update("""
                    UPDATE biz_purchase_return_line
                    SET stock_unit_price = NULL, stock_amt = NULL, diff_amt = NULL
                    WHERE id = ?
                    """, longValue(line.get("id")));
        }

        int updated = jdbcTemplate.update("""
                UPDATE biz_purchase_return
                SET status = 'DRAFT', total_stock_amt = 0, total_diff_amt = 0, updated_at = NOW()
                WHERE id = ? AND deleted = 0 AND status = 'APPROVED'
                """, bizId);
        if (updated == 0) throw new BizException("采购退货单当前状态不允许取消审批");
        recomputePurchaseOrderReceive(sourcePoId);
    }

    private void cancelInvoice(Long bizId) {
        DocPeriod doc = docPeriod("biz_invoice", "invoice_date", bizId, "采购发票不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "采购发票", doc.docNo());
        String status = queryString("SELECT status FROM biz_invoice WHERE id = ? AND deleted = 0", bizId);
        if (!"MATCHED".equals(status) && !"APPROVED".equals(status)) {
            throw new BizException("采购发票当前状态不允许取消审批");
        }

        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                SELECT il.qty, il.recv_line_id,
                       rl.recv_id, rl.source_po_line_id,
                       r.source_po_id
                FROM biz_invoice_line il
                JOIN biz_recv_line rl ON rl.id = il.recv_line_id
                JOIN biz_recv r ON r.id = rl.recv_id AND r.deleted = 0
                WHERE il.invoice_id = ?
                """, bizId);
        if (lines.isEmpty()) throw new BizException("采购发票缺少核销明细，不能取消审批");

        // 反写入库行/订单行的已开票数只是进度计数，不动入库单所在期间的账 → 不校验来源入库单的期间
        Set<Long> recvIds = new LinkedHashSet<>();
        Set<Long> poIds = new LinkedHashSet<>();
        for (Map<String, Object> line : lines) {
            Long recvId = longValue(line.get("recv_id"));
            Long poId = longValue(line.get("source_po_id"));
            if (recvId != null) recvIds.add(recvId);
            if (poId != null) poIds.add(poId);
        }

        waService.reverse(bizId, "INV", "取消发票审批");

        for (Map<String, Object> line : lines) {
            BigDecimal qty = decimal(line.get("qty"));
            Long recvLineId = longValue(line.get("recv_line_id"));
            Long poLineId = longValue(line.get("source_po_line_id"));
            if (recvLineId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_recv_line
                        SET invoiced_qty = GREATEST(COALESCE(invoiced_qty, 0) - ?, 0)
                        WHERE id = ?
                        """, qty, recvLineId);
            }
            if (poLineId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_po_line
                        SET invoiced_qty = GREATEST(COALESCE(invoiced_qty, 0) - ?, 0)
                        WHERE id = ?
                        """, qty, poLineId);
            }
        }
        int updated = jdbcTemplate.update("""
                UPDATE biz_invoice
                SET status = 'DRAFT', updated_at = NOW()
                WHERE id = ? AND deleted = 0 AND status IN ('MATCHED', 'APPROVED')
                """, bizId);
        if (updated == 0) throw new BizException("采购发票当前状态不允许取消审批");
        // 暂估入库单全额开票后 recv_type 会被改成 INVOICE(InvoiceService#markRecvInvoicedIfComplete),取消发票要改回暂估。
        // 🔴 2026-10-16:以前用"这张发票有没有 ADJUST 流水"判断原来是不是暂估 —— 08-21 起暂估差异全额进损益、不再写 ADJUST,
        //    全额开票的暂估单取消发票后一直停在 INVOICE;10-16 起货票同到开票也会写 ADJUST,又会被误改成暂估。
        //    改为看这张入库单当初入库过账的流水类型,与发票怎么记差额无关。
        for (Long recvId : recvIds) recomputeReceiveInvoiceState(recvId, originallyEstimate(recvId));
        for (Long poId : poIds) recomputePurchaseOrderInvoice(poId);
    }

    private void cancelMaterialOut(Long bizId) {
        outReqIntegrity.lockSourceForOut(bizId);
        DocPeriod doc = docPeriod("biz_out", "out_date", bizId, "材料出库单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "材料出库单", doc.docNo());
        // 先锁出库单再查退料:与退料建单/提交/审批(ReturnService.lockSourceOutsApproved)同一把锁,两边排队
        String status = queryString("SELECT status FROM biz_out WHERE id = ? AND deleted = 0 FOR UPDATE", bizId);
        if (!"APPROVED".equals(status)) {
            throw new BizException("材料出库单当前状态不允许取消审批");
        }
        Long sourceReqId = queryLong("SELECT source_req_id FROM biz_out WHERE id = ? AND deleted = 0", bizId);
        assertMaterialOutHasNoReturns(bizId, true);

        waService.reverse(bizId, "OUT", "取消材料出库审批");
        for (Map<String, Object> line : jdbcTemplate.queryForList("""
                SELECT qty, source_req_line_id
                FROM biz_out_line
                WHERE out_id = ?
                """, bizId)) {
            Long reqLineId = longValue(line.get("source_req_line_id"));
            if (reqLineId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_out_req_line
                        SET out_qty = GREATEST(COALESCE(out_qty, 0) - ?, 0)
                        WHERE id = ?
                        """, decimal(line.get("qty")), reqLineId);
            }
        }
        int updated = jdbcTemplate.update("""
                UPDATE biz_out
                SET status = 'DRAFT', updated_at = NOW()
                WHERE id = ? AND deleted = 0 AND status = 'APPROVED'
                """, bizId);
        if (updated == 0) throw new BizException("材料出库单当前状态不允许取消审批");
        recomputeOutboundRequest(sourceReqId);
    }

    private void cancelTransfer(Long bizId) {
        DocPeriod doc = docPeriod("biz_transfer", "transfer_date", bizId, "转库单不存在");
        Map<String, Object> head = jdbcTemplate.queryForList("""
                SELECT from_warehouse_id, to_warehouse_id, source_req_id, status
                FROM biz_transfer
                WHERE id = ? AND deleted = 0
                """, bizId).get(0);
        if (head.get("source_req_id") != null) {
            // 关联领料生成的转库单审批后状态是 READY（交接后才过账），撤销走「取消本次领料」
            throw new BizException("该转库单由领料申请生成，请通过「取消本次领料」处理，不能直接取消审批");
        }
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "库存转库单", doc.docNo());
        // 与 TransferService.assertTransferPeriodOpen 同口径：转入仓库所属账套期间也要开（两仓可跨账套）
        Long toOrgId = queryLong("SELECT org_id FROM md_warehouse WHERE id = ?", longValue(head.get("to_warehouse_id")));
        if (toOrgId != null && !toOrgId.equals(doc.orgId())) {
            periodCloseService.assertPeriodOpen(doc.bizDate(), toOrgId, "库存转库单", doc.docNo());
        }
        if (!"APPROVED".equals(head.get("status"))) {
            throw new BizException("转库单当前状态不允许取消审批");
        }

        waService.reverse(bizId, "TRANSFER", "取消转库审批");
        // 过账时回填的明细单价/金额随之作废（列 NOT NULL，置 0 回到建单时的默认态），避免退回草稿后仍显示上一次过账的数
        jdbcTemplate.update("""
                UPDATE biz_transfer_line
                SET unit_price = 0, amount = 0
                WHERE transfer_id = ?
                """, bizId);
        int updated = jdbcTemplate.update("""
                UPDATE biz_transfer
                SET status = 'DRAFT', total_amt = 0, updated_at = NOW()
                WHERE id = ? AND deleted = 0 AND status = 'APPROVED'
                """, bizId);
        if (updated == 0) throw new BizException("转库单当前状态不允许取消审批");
    }

    private void cancelSaleOrder(Long bizId) {
        DocPeriod doc = docPeriod("biz_so", "order_date", bizId, "销售订单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "销售订单", doc.docNo());
        BigDecimal outQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(out_qty,0) FROM biz_so WHERE id = ? AND deleted = 0", BigDecimal.class, bizId);
        if (outQty != null && outQty.signum() > 0) {
            throw new BizException("销售订单已产生出库记录，请先取消对应销售出库单的审批");
        }
        BigDecimal invQty = jdbcTemplate.queryForObject(
                "SELECT COALESCE(invoice_qty,0) FROM biz_so WHERE id = ? AND deleted = 0", BigDecimal.class, bizId);
        if (invQty != null && invQty.signum() > 0) {
            throw new BizException("销售订单已产生开票记录，请先取消对应销售发票的审批");
        }
        // 计算审查 U07:还有待审批的销售出库单时先撤回(最终把关在出库过账时复核订单状态,见 SaleOutService.approve)
        List<String> pendingOut = jdbcTemplate.queryForList(
                "SELECT doc_no FROM biz_sale_out WHERE source_so_id = ? AND deleted = 0 AND status = 'PENDING' ORDER BY id",
                String.class, bizId);
        if (!pendingOut.isEmpty()) {
            throw new BizException("该销售订单还有待审批的出库单 " + String.join("、", pendingOut) + "，请先撤回或驳回后再取消订单审批");
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_so SET status = 'DRAFT', updated_at = NOW() WHERE id = ? AND deleted = 0 AND status = 'APPROVED'", bizId);
        if (updated == 0) throw new BizException("销售订单当前状态不允许取消审批");
    }

    /**
     * 材料出库单已有退料单(除已作废外都算,含已驳回——驳回的能重提)时不许取消审批(代码复查)。
     * 否则:出库冲回把数量全部加回库存,退料又加过一次 → 库存虚增;选了"退回后还要再领"的还会在原申请上
     * 残留可再领额度 → 可能超量领料。退料单目前不能取消审批,所以已审批退料的出库单只能保持已审批。
     * lock=true 时用加锁读:读到别人刚提交的退料单,不受本事务早先快照的影响(可重复读下普通查询会看旧数据)。
     */
    private void assertMaterialOutHasNoReturns(Long outId, boolean lock) {
        List<String> docs = jdbcTemplate.queryForList("""
                SELECT r.doc_no
                FROM biz_return r
                JOIN biz_return_line rl ON rl.return_id = r.id
                JOIN biz_out_line ol ON ol.id = rl.source_out_line_id
                WHERE ol.out_id = ? AND r.deleted = 0 AND r.status <> 'CANCELLED'
                ORDER BY r.doc_no
                """ + (lock ? " LOCK IN SHARE MODE" : ""), String.class, outId).stream().distinct().toList();
        if (!docs.isEmpty()) {
            throw new BizException("该出库单已有退料单 " + String.join("、", docs)
                    + "，不能取消审批。草稿、已驳回的退料单请先删除，待审批的先撤回再删除；已审批的退料不能撤销，如确需更正请联系财务处理");
        }
    }

    private void cancelSaleOut(Long bizId) {
        DocPeriod doc = docPeriod("biz_sale_out", "out_date", bizId, "销售出库单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "销售出库单", doc.docNo());
        String status = queryString("SELECT status FROM biz_sale_out WHERE id = ? AND deleted = 0", bizId);
        if (!"APPROVED".equals(status)) throw new BizException("销售出库单当前状态不允许取消审批");
        Integer inv = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_sale_invoice WHERE source_out_id = ? AND deleted = 0 AND status <> 'REJECTED'", Integer.class, bizId);
        if (inv != null && inv > 0) throw new BizException("该出库单存在关联销售发票(含草稿)，请先取消其审批并删除后再操作");
        Integer ret = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_sale_return WHERE source_out_id = ? AND deleted = 0 AND status <> 'REJECTED'", Integer.class, bizId);
        if (ret != null && ret > 0) throw new BizException("该出库单存在关联销售退货单(含草稿)，请先取消其审批并删除后再操作");

        waService.reverse(bizId, "SALE_OUT", "取消销售出库审批");
        Long soId = queryLong("SELECT source_so_id FROM biz_sale_out WHERE id = ? AND deleted = 0", bizId);
        for (Map<String, Object> line : jdbcTemplate.queryForList(
                "SELECT qty, source_so_line_id FROM biz_sale_out_line WHERE out_id = ?", bizId)) {
            Long soLineId = longValue(line.get("source_so_line_id"));
            if (soLineId != null) {
                jdbcTemplate.update(
                        "UPDATE biz_so_line SET out_qty = GREATEST(COALESCE(out_qty,0) - ?, 0) WHERE id = ?",
                        decimal(line.get("qty")), soLineId);
            }
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_sale_out SET status = 'DRAFT', updated_at = NOW() WHERE id = ? AND deleted = 0 AND status = 'APPROVED'", bizId);
        if (updated == 0) throw new BizException("销售出库单当前状态不允许取消审批");
        recomputeSaleOrderOut(soId);
    }

    /** 反审销售出库后按行重算订单已出数量与状态（与采购订单入库回算同款口径）。 */
    private void recomputeSaleOrderOut(Long soId) {
        if (soId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_so so
                SET out_qty = (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_so_line l WHERE l.so_id = so.id),
                    status = CASE
                      WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_so_line l WHERE l.so_id = so.id) <= 0 THEN 'APPROVED'
                      WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_so_line l WHERE l.so_id = so.id) >= so.total_qty THEN 'OUT'
                      ELSE 'PARTIAL_OUT'
                    END,
                    updated_at = NOW()
                WHERE so.id = ? AND so.deleted = 0
                  AND so.status IN ('APPROVED', 'PARTIAL_OUT', 'OUT')
                """, soId);
    }

    private void cancelSaleInvoice(Long bizId) {
        DocPeriod doc = docPeriod("biz_sale_invoice", "invoice_date", bizId, "销售发票不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "销售发票", doc.docNo());
        String status = queryString("SELECT status FROM biz_sale_invoice WHERE id = ? AND deleted = 0", bizId);
        if (!"APPROVED".equals(status)) throw new BizException("销售发票当前状态不允许取消审批");
        Long outId = queryLong("SELECT source_out_id FROM biz_sale_invoice WHERE id = ? AND deleted = 0", bizId);
        Long soId = outId == null ? null : queryLong("SELECT source_so_id FROM biz_sale_out WHERE id = ? AND deleted = 0", outId);
        if (outId != null) {
            for (Map<String, Object> line : jdbcTemplate.queryForList(
                    "SELECT qty, source_so_line_id FROM biz_sale_out_line WHERE out_id = ?", outId)) {
                Long soLineId = longValue(line.get("source_so_line_id"));
                if (soLineId != null) {
                    jdbcTemplate.update(
                            "UPDATE biz_so_line SET invoice_qty = GREATEST(COALESCE(invoice_qty,0) - ?, 0) WHERE id = ?",
                            decimal(line.get("qty")), soLineId);
                }
            }
            if (soId != null) {
                jdbcTemplate.update("""
                        UPDATE biz_so so
                        SET invoice_qty = (SELECT COALESCE(SUM(l.invoice_qty), 0) FROM biz_so_line l WHERE l.so_id = so.id),
                            updated_at = NOW()
                        WHERE so.id = ? AND so.deleted = 0
                        """, soId);
            }
        }
        int updated = jdbcTemplate.update(
                "UPDATE biz_sale_invoice SET status = 'DRAFT', updated_at = NOW() WHERE id = ? AND deleted = 0 AND status = 'APPROVED'", bizId);
        if (updated == 0) throw new BizException("销售发票当前状态不允许取消审批");
    }

    private void cancelSaleReturn(Long bizId) {
        DocPeriod doc = docPeriod("biz_sale_return", "return_date", bizId, "销售退货单不存在");
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), "销售退货单", doc.docNo());
        String status = queryString("SELECT status FROM biz_sale_return WHERE id = ? AND deleted = 0", bizId);
        if (!"APPROVED".equals(status)) throw new BizException("销售退货单当前状态不允许取消审批");
        Long whId = queryLong("SELECT warehouse_id FROM biz_sale_return WHERE id = ? AND deleted = 0", bizId);
        for (Map<String, Object> line : jdbcTemplate.queryForList(
                "SELECT material_id, qty FROM biz_sale_return_line WHERE return_id = ?", bizId)) {
            BigDecimal cur = jdbcTemplate.queryForObject(
                    "SELECT COALESCE((SELECT qty FROM inv_balance WHERE material_id = ? AND warehouse_id = ?), 0)",
                    BigDecimal.class, longValue(line.get("material_id")), whId);
            if (cur == null || cur.compareTo(decimal(line.get("qty"))) < 0) {
                throw new BizException("当前库存不足以冲回退货入库，不能取消审批（该批货可能已再次出库）");
            }
        }
        waService.reverse(bizId, "SALE_RETURN", "取消销售退货审批");
        int updated = jdbcTemplate.update(
                "UPDATE biz_sale_return SET status = 'DRAFT', updated_at = NOW() WHERE id = ? AND deleted = 0 AND status = 'APPROVED'", bizId);
        if (updated == 0) throw new BizException("销售退货单当前状态不允许取消审批");
    }

    private void recomputePurchaseOrderReceive(Long poId) {
        if (poId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_po po
                SET received_qty = (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id),
                    status = CASE
                      WHEN (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id) <= 0 THEN 'APPROVED'
                      WHEN (SELECT COALESCE(SUM(l.received_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id) >= po.total_qty THEN 'RECEIVED'
                      ELSE 'PARTIAL_RECEIVED'
                    END,
                    updated_at = NOW()
                WHERE po.id = ? AND po.deleted = 0
                  AND po.status IN ('APPROVED', 'PARTIAL_RECEIVED', 'RECEIVED')
                """, poId);
    }

    private void recomputePurchaseOrderInvoice(Long poId) {
        if (poId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_po po
                SET invoiced_qty = (SELECT COALESCE(SUM(l.invoiced_qty), 0) FROM biz_po_line l WHERE l.po_id = po.id),
                    updated_at = NOW()
                WHERE po.id = ? AND po.deleted = 0
                """, poId);
    }

    /** 入库单当初是不是暂估入库:取它最近一次入库过账流水的类型(IN_ESTIMATE / IN_INVOICE)。 */
    private boolean originallyEstimate(Long recvId) {
        if (recvId == null) return false;
        String t = jdbcTemplate.query("""
                SELECT trans_type FROM inv_transaction
                WHERE biz_doc_type = 'RECV' AND biz_doc_id = ? AND trans_type IN ('IN_ESTIMATE', 'IN_INVOICE')
                ORDER BY id DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, recvId);
        return "IN_ESTIMATE".equals(t);
    }

    private void recomputeReceiveInvoiceState(Long recvId, boolean originallyEstimate) {
        if (recvId == null) return;
        BigDecimal totalQty = decimal(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(qty), 0)
                FROM biz_recv_line
                WHERE recv_id = ?
                """, Object.class, recvId));
        BigDecimal invoicedQty = decimal(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(invoiced_qty), 0)
                FROM biz_recv_line
                WHERE recv_id = ?
                """, Object.class, recvId));
        List<Map<String, Object>> invoices = jdbcTemplate.queryForList("""
                SELECT i.id, i.doc_no
                FROM biz_invoice i
                JOIN biz_invoice_line il ON il.invoice_id = i.id
                JOIN biz_recv_line rl ON rl.id = il.recv_line_id
                WHERE rl.recv_id = ?
                  AND i.deleted = 0
                  AND i.status <> 'CANCELLED'
                ORDER BY i.id DESC
                LIMIT 1
                """, recvId);
        if (invoices.isEmpty()) {
            if (originallyEstimate) {
                jdbcTemplate.update("""
                        UPDATE biz_recv
                        SET invoice_id = NULL, invoice_no = NULL, recv_type = 'ESTIMATE', updated_at = NOW()
                        WHERE id = ? AND deleted = 0
                        """, recvId);
            } else {
                jdbcTemplate.update("""
                        UPDATE biz_recv
                        SET invoice_id = NULL, invoice_no = NULL, updated_at = NOW()
                        WHERE id = ? AND deleted = 0
                        """, recvId);
            }
            return;
        }
        Map<String, Object> latest = invoices.get(0);
        boolean complete = totalQty.signum() > 0 && invoicedQty.compareTo(totalQty) >= 0;
        String recvType = originallyEstimate && !complete ? "ESTIMATE" : null;
        if (recvType == null && complete) recvType = "INVOICE";
        if (recvType == null) {
            jdbcTemplate.update("""
                    UPDATE biz_recv
                    SET invoice_id = ?, invoice_no = ?, updated_at = NOW()
                    WHERE id = ? AND deleted = 0
                    """, longValue(latest.get("id")), stringValue(latest.get("doc_no")), recvId);
        } else {
            jdbcTemplate.update("""
                    UPDATE biz_recv
                    SET invoice_id = ?, invoice_no = ?, recv_type = ?, updated_at = NOW()
                    WHERE id = ? AND deleted = 0
                    """, longValue(latest.get("id")), stringValue(latest.get("doc_no")), recvType, recvId);
        }
    }

    private void recomputeOutboundRequest(Long reqId) {
        if (reqId == null) return;
        jdbcTemplate.update("""
                UPDATE biz_out_req r
                SET out_qty = (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id),
                    status = CASE
                      WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id) <= 0 THEN 'APPROVED'
                      WHEN (SELECT COALESCE(SUM(l.out_qty), 0) FROM biz_out_req_line l WHERE l.out_req_id = r.id) >= r.total_qty THEN 'OUT'
                      ELSE 'PARTIAL_OUT'
                    END,
                    updated_at = NOW()
                WHERE r.id = ? AND r.deleted = 0
                  AND r.status IN ('APPROVED', 'PARTIAL_OUT', 'OUT')
                """, reqId);
    }

    private void assertDocPeriodOpen(String table, String dateColumn, Long id, String bizName, String missingMessage) {
        DocPeriod doc = docPeriod(table, dateColumn, id, missingMessage);
        periodCloseService.assertPeriodOpen(doc.bizDate(), doc.orgId(), bizName, doc.docNo());
    }

    private DocPeriod docPeriod(String table, String dateColumn, Long id, String missingMessage) {
        return jdbcTemplate.query("""
                SELECT org_id, doc_no, %s AS biz_date
                FROM %s
                WHERE id = ? AND deleted = 0
                """.formatted(dateColumn, table), rs -> {
            if (!rs.next()) throw new BizException(missingMessage);
            Long orgId = rs.getObject("org_id") == null ? null : rs.getLong("org_id");
            java.sql.Date date = rs.getDate("biz_date");
            return new DocPeriod(orgId, date == null ? null : date.toLocalDate(), rs.getString("doc_no"));
        }, id);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private Long queryLong(String sql, Object... args) {
        List<Long> values = jdbcTemplate.queryForList(sql, Long.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        return new BigDecimal(value.toString());
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalDate localDateValue(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(value.toString());
    }

    private List<Long> accountOrgIds(Long orgId) {
        if (orgId == null) return List.of();
        Map<Long, WorkflowOrgRef> orgs = loadWorkflowOrgRefs();
        WorkflowOrgRef current = orgs.get(orgId);
        if (current == null) return List.of(orgId);
        Long rootId = accountRoot(current, orgs);
        return descendantOrgIds(rootId, orgs);
    }

    private Map<Long, WorkflowOrgRef> loadWorkflowOrgRefs() {
        return jdbcTemplate.query("""
                        SELECT id, parent_id, type, approval_mode
                        FROM sys_org
                        WHERE deleted = 0 AND status = 1
                        """,
                rs -> {
                    Map<Long, WorkflowOrgRef> map = new HashMap<>();
                    while (rs.next()) {
                        Long id = rs.getLong("id");
                        Long parentId = rs.getLong("parent_id");
                        map.put(id, new WorkflowOrgRef(id, parentId, rs.getString("type"), rs.getString("approval_mode")));
                    }
                    return map;
                });
    }

    private Long accountRoot(WorkflowOrgRef current, Map<Long, WorkflowOrgRef> orgs) {
        WorkflowOrgRef cursor = current;
        WorkflowOrgRef company = "COMPANY".equalsIgnoreCase(current.type()) ? current : null;
        while (cursor.parentId() != null && cursor.parentId() != 0 && orgs.containsKey(cursor.parentId())) {
            cursor = orgs.get(cursor.parentId());
            if ("COMPANY".equalsIgnoreCase(cursor.type())) {
                company = cursor;
            }
        }
        return company == null ? current.id() : company.id();
    }

    private List<Long> descendantOrgIds(Long rootId, Map<Long, WorkflowOrgRef> orgs) {
        if (rootId == null) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        ids.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long parentId = queue.removeFirst();
            for (WorkflowOrgRef org : orgs.values()) {
                if (parentId.equals(org.parentId()) && ids.add(org.id())) {
                    queue.add(org.id());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private boolean canReadBusinessType(String bizType) {
        return switch (normalizeType(bizType)) {
            case "PR", "PO" -> SecurityUtil.hasRole("purchaser") || SecurityUtil.hasFinanceAccess() || hasApprovalBusinessRole();
            case "RECV", "OUT", "OREQ_REV", "RETURN", "TRANSFER" -> SecurityUtil.hasRole("warehouse") || SecurityUtil.hasFinanceAccess() || hasApprovalBusinessRole();
            case "PRET" -> SecurityUtil.hasRole("warehouse") || SecurityUtil.hasRole("purchaser") || SecurityUtil.hasFinanceAccess() || hasApprovalBusinessRole();
            case "OREQ" -> SecurityUtil.hasRole("purchaser") || SecurityUtil.hasRole("warehouse") || SecurityUtil.hasFinanceAccess() || hasApprovalBusinessRole();
            case "INV" -> SecurityUtil.hasFinanceAccess();
            default -> false;
        };
    }

    private boolean hasApprovalBusinessRole() {
        return SecurityUtil.hasRole("material_accountant")
                || SecurityUtil.hasRole("finance_manager")
                || SecurityUtil.hasRole("deputy_general_manager")
                || SecurityUtil.hasRole("general_manager")
                || SecurityUtil.hasRole("field_manager")
                || SecurityUtil.hasRole("tech_manager")
                || SecurityUtil.hasRole("service_staff")
                || SecurityUtil.hasRole("operation_repair")
                || SecurityUtil.hasRole("branch_manager")
                || SecurityUtil.hasRole("area_manager")
                || SecurityUtil.hasRole("operation_leader")
                || SecurityUtil.hasRole("engineering_leader")
                || SecurityUtil.hasRole("market_leader");
    }

    private BizAccess businessAccess(String bizType, Long bizId) {
        if (bizType == null || bizId == null) return null;
        String sql = switch (normalizeType(bizType)) {
            case "PR" -> "SELECT org_id, applicant_id, doc_no FROM biz_pr WHERE id = ? AND deleted = 0";
            case "PO" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_po WHERE id = ? AND deleted = 0";
            case "RECV" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_recv WHERE id = ? AND deleted = 0";
            case "PRET" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_purchase_return WHERE id = ? AND deleted = 0";
            case "INV" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_invoice WHERE id = ? AND deleted = 0";
            case "OREQ" -> "SELECT org_id, applicant_id, doc_no FROM biz_out_req WHERE id = ? AND deleted = 0";
            case "OREQ_REV" -> """
                    SELECT v.org_id, r.applicant_id, v.doc_no
                    FROM biz_out_req_revision v
                    LEFT JOIN biz_out_req r ON r.id = v.source_req_id AND r.deleted = 0
                    WHERE v.id = ? AND v.deleted = 0
                    """;
            case "OUT" -> """
                    SELECT o.org_id, r.applicant_id, o.doc_no
                    FROM biz_out o
                    LEFT JOIN biz_out_req r ON r.id = o.source_req_id AND r.deleted = 0
                    WHERE o.id = ? AND o.deleted = 0
                    """;
            case "RETURN" -> """
                    SELECT ret.org_id, r.applicant_id, ret.doc_no
                    FROM biz_return ret
                    LEFT JOIN biz_out_req r ON r.id = ret.source_req_id AND r.deleted = 0
                    WHERE ret.id = ? AND ret.deleted = 0
                    """;
            case "TRANSFER" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_transfer WHERE id = ? AND deleted = 0";
            case "LOAN" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_loan WHERE id = ? AND deleted = 0";
            case "SO" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_so WHERE id = ? AND deleted = 0";
            case "SALE_OUT" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_sale_out WHERE id = ? AND deleted = 0";
            case "SALE_INV" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_sale_invoice WHERE id = ? AND deleted = 0";
            case "SALE_RETURN" -> "SELECT org_id, created_by AS applicant_id, doc_no FROM biz_sale_return WHERE id = ? AND deleted = 0";
            default -> null;
        };
        if (sql == null) throw new BizException("不支持的业务类型：" + bizType);
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) return null;
            Long orgId = rs.getObject("org_id") == null ? null : rs.getLong("org_id");
            Long applicantId = rs.getObject("applicant_id") == null ? null : rs.getLong("applicant_id");
            return new BizAccess(normalizeType(bizType), bizId, orgId, applicantId, rs.getString("doc_no"));
        }, bizId);
    }

    private String normalizeType(String bizType) {
        return bizType == null ? "" : bizType.trim().toUpperCase();
    }

    private record BizAccess(String bizType, Long bizId, Long orgId, Long applicantId, String docNo) {
    }

    private record ApproverTarget(Long userId, String roleCode, String displayName) {
    }

    private record WorkflowOrgRef(Long id, Long parentId, String type, String approvalMode) {
    }

    private record DocPeriod(Long orgId, LocalDate bizDate, String docNo) {
    }
}
