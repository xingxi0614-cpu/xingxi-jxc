package com.group.scm.modules.docrelation.service;

import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.docrelation.entity.DocRelation;
import com.group.scm.modules.docrelation.mapper.DocRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 单据链服务：维护单据关系 + 提供链路查询。
 *
 * <p>关键能力：</p>
 * <ul>
 *   <li>{@link #addRelation} - 在下推/核销/冲销时调用</li>
 *   <li>{@link #buildChain} - 给前端单据链可视化用，返回 nodes + edges</li>
 *   <li>{@link #findDownstream} - 反审/删除时检查是否还有下游</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DocRelationService {

    private final DocRelationMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    public void addRelation(String srcType, Long srcId, String srcNo,
                            String tgtType, Long tgtId, String tgtNo,
                            String relType, BigDecimal qty, BigDecimal amount) {
        DocRelation r = new DocRelation();
        r.setSourceType(srcType); r.setSourceId(srcId); r.setSourceNo(srcNo);
        r.setTargetType(tgtType); r.setTargetId(tgtId); r.setTargetNo(tgtNo);
        r.setRelationType(relType == null ? "DOWN_PUSH" : relType);
        r.setQty(qty == null ? BigDecimal.ZERO : qty);
        r.setAmount(amount == null ? BigDecimal.ZERO : amount);
        r.setCreatedAt(LocalDateTime.now());
        r.setCreatedBy(SecurityUtil.uid());
        mapper.insert(r);
    }

    public List<DocRelation> findDownstream(String type, String no) {
        return mapper.findBySource(type, no);
    }

    public List<DocRelation> findUpstream(String type, String no) {
        return mapper.findByTarget(type, no);
    }

    /** BFS 找出所有相关单据节点 */
    public Set<String> findAllRelatedDocs(String startNo) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.push(startNo);
        while (!queue.isEmpty()) {
            String no = queue.poll();
            if (visited.contains(no)) continue;
            visited.add(no);
            // 找出所有以此 no 为 src 或 tgt 的边
            String type = relationTypeFromDocNo(no);
            for (DocRelation r : mapper.findBySource(type, no)) {
                if (!visited.contains(r.getTargetNo())) queue.push(r.getTargetNo());
            }
            for (DocRelation r : mapper.findByTarget(type, no)) {
                if (!visited.contains(r.getSourceNo())) queue.push(r.getSourceNo());
            }
        }
        return visited;
    }

    /** 给前端用的链路图：nodes + edges */
    public Map<String, Object> buildChain(String docNo) {
        orgScope.assertOrgVisible(findDocOrgId(docNo));
        Set<String> allNos = findAllRelatedDocs(docNo);
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        boolean canSeeAmount = SecurityUtil.hasFinanceAccessOrAdmin();
        for (String no : allNos) {
            if (!isDocVisible(no)) continue;
            String type = relationTypeFromDocNo(no);
            for (DocRelation r : mapper.findBySource(type, no)) {
                if (!isDocVisible(r.getTargetNo())) continue;
                String key = r.getSourceNo() + "→" + r.getTargetNo() + ":" + r.getRelationType();
                if (processed.add(key)) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", r.getSourceNo());
                    edge.put("to", r.getTargetNo());
                    edge.put("type", r.getRelationType());
                    edge.put("qty", r.getQty());
                    if (canSeeAmount) {
                        edge.put("amount", r.getAmount());
                    }
                    edges.add(edge);
                }
            }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String no : allNos) {
            if (!isDocVisible(no)) continue;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", no);
            node.put("type", relationTypeFromDocNo(no));
            Map<String, Object> meta = findDocMeta(no);
            node.put("status", meta.get("status"));
            node.put("createdAt", meta.get("created_at"));
            node.put("approval", getApprovalFlow(no));   // 审批流程：当前节点 + 审批轨迹
            nodes.add(node);
        }
        Map<String, Object> chain = new HashMap<>();
        chain.put("center", docNo);
        chain.put("nodes", nodes);
        chain.put("edges", edges);
        return chain;
    }

    private boolean isDocVisible(String docNo) {
        try {
            orgScope.assertOrgVisible(findDocOrgId(docNo));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String tableOf(String type) {
        return switch (type) {
            case "PR" -> "biz_pr";
            case "PO" -> "biz_po";
            case "RECV" -> "biz_recv";
            case "INV" -> "biz_invoice";
            case "OREQ" -> "biz_out_req";
            case "OREQ_REV" -> "biz_out_req_revision";
            case "OUT" -> "biz_out";
            case "RETURN" -> "biz_return";
            case "LOAN" -> "biz_loan";
            case "TRANSFER" -> "biz_transfer";
            default -> null;
        };
    }

    private Long findDocOrgId(String docNo) {
        if (docNo == null || !docNo.contains("-")) return null;
        String table = tableOf(relationTypeFromDocNo(docNo));
        if (table == null) return null;
        return jdbcTemplate.query(
                "SELECT org_id FROM %s WHERE doc_no = ? AND deleted = 0 LIMIT 1".formatted(table),
                rs -> rs.next() ? rs.getLong("org_id") : null,
                docNo);
    }

    /** 取单据状态与创建时间（用于单据链节点展示）。 */
    private Map<String, Object> findDocMeta(String docNo) {
        String table = tableOf(relationTypeFromDocNo(docNo));
        if (table == null) return Collections.emptyMap();
        return jdbcTemplate.query(
                "SELECT status, created_at FROM %s WHERE doc_no = ? AND deleted = 0 LIMIT 1".formatted(table),
                rs -> {
                    if (!rs.next()) return Collections.<String, Object>emptyMap();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("created_at", ts == null ? null : ts.toLocalDateTime());
                    return m;
                },
                docNo);
    }

    /** 取某张单据的审批流程：当前节点 + 历史审批轨迹（让任意有权限角色都能看到后续审批进度）。 */
    private Map<String, Object> getApprovalFlow(String docNo) {
        Map<String, Object> flow = new LinkedHashMap<>();
        Map<String, Object> inst = jdbcTemplate.query(
                "SELECT status, current_node, next_approver_role FROM wf_instance WHERE biz_no = ? ORDER BY id DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("state", rs.getString("status"));
                    m.put("currentNode", rs.getString("current_node"));
                    m.put("nextApproverRole", rs.getString("next_approver_role"));
                    return m;
                },
                docNo);
        if (inst != null) flow.putAll(inst);
        List<Map<String, Object>> records = jdbcTemplate.query(
                "SELECT node, approver_name, action, comment, created_at FROM wf_record WHERE biz_no = ? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("node", rs.getString("node"));
                    m.put("approver", rs.getString("approver_name"));
                    m.put("action", rs.getString("action"));
                    m.put("comment", rs.getString("comment"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("time", ts == null ? null : ts.toLocalDateTime());
                    return m;
                },
                docNo);
        flow.put("records", records);
        return flow;
    }

    private String relationTypeFromDocNo(String docNo) {
        if (docNo == null || !docNo.contains("-")) return "";
        String prefix = docPrefix(docNo);
        return switch (prefix) {
            case "CD" -> "PO";
            case "CR" -> "RECV";
            case "CF" -> "INV";
            case "SA" -> "OREQ";
            case "SAX" -> "OREQ_REV";
            case "CC" -> "OUT";
            case "TL" -> "RETURN";
            case "JL" -> "LOAN";
            case "ZK" -> "TRANSFER";
            case "ORV" -> "OREQ_REV";
            case "RET" -> "RETURN";
            case "TRF" -> "TRANSFER";
            default -> prefix;
        };
    }

    private String docPrefix(String docNo) {
        String[] parts = docNo.split("-");
        if (parts.length >= 4 && isBusinessPrefix(parts[1])) {
            return parts[1];
        }
        return parts[0];
    }

    private boolean isBusinessPrefix(String value) {
        return switch (value) {
            case "PR", "PO", "CD", "RECV", "CR", "INV", "CF", "OREQ", "SA", "OREQ_REV", "ORV", "SAX",
                    "OUT", "CC", "RETURN", "RET", "TL", "LOAN", "JL", "TRANSFER", "TRF", "ZK" -> true;
            default -> false;
        };
    }
}
