package com.group.scm.modules.system.service;

import com.group.scm.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示账套每日自动复位（推广用）。
 *
 * <p>把演示账套(DEMO, sys_param demo.org.id)恢复成标准样例状态：清掉演示期间产生的
 * 业务单据/审批流/通知/库存流水，重建期初库存与样例单据（与迁移 20260701_003/004 同一套样例），
 * 并把 demo 账号的锁定状态复位(密码不动)。这样上一个体验者玩乱的数据不会影响下一个看演示的人。</p>
 *
 * <p>数据铁律三重保险（绝不触碰生产数据）：</p>
 * <ol>
 *   <li>demo.org.id 参数不存在 → 直接跳过；</li>
 *   <li>该 org 的 code 必须等于 DEMO，否则拒绝执行；</li>
 *   <li>所有 DELETE 均以演示 org/演示仓库 id 为条件参数化执行，无一条全表语句。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetService {

    private final JdbcTemplate jdbc;
    private final com.group.scm.modules.outbound.service.PickupService pickup;

    /** 业务单据类型 → 主表（均含 org_id）。审批流/单据关系/行表按此映射级联清理。 */
    private static final Map<String, String> BIZ_TABLES = new LinkedHashMap<>();
    /** 主表 → (行表, 行表外键列) */
    private static final Map<String, String[]> LINE_TABLES = new LinkedHashMap<>();
    static {
        BIZ_TABLES.put("PO", "biz_po");
        BIZ_TABLES.put("RECV", "biz_recv");
        BIZ_TABLES.put("INV", "biz_invoice");
        BIZ_TABLES.put("OREQ", "biz_out_req");
        BIZ_TABLES.put("OREQ_REV", "biz_out_req_revision");
        BIZ_TABLES.put("OUT", "biz_out");
        BIZ_TABLES.put("RETURN", "biz_return");
        BIZ_TABLES.put("LOAN", "biz_loan");
        BIZ_TABLES.put("TRANSFER", "biz_transfer");
        BIZ_TABLES.put("SO", "biz_so");
        BIZ_TABLES.put("SALE_OUT", "biz_sale_out");
        BIZ_TABLES.put("SALE_INV", "biz_sale_invoice");
        BIZ_TABLES.put("SALE_RETURN", "biz_sale_return");
        LINE_TABLES.put("biz_po", new String[]{"biz_po_line", "po_id"});
        LINE_TABLES.put("biz_recv", new String[]{"biz_recv_line", "recv_id"});
        LINE_TABLES.put("biz_invoice", new String[]{"biz_invoice_line", "invoice_id"});
        LINE_TABLES.put("biz_out_req", new String[]{"biz_out_req_line", "out_req_id"});
        LINE_TABLES.put("biz_out_req_revision", new String[]{"biz_out_req_revision_line", "revision_id"});
        LINE_TABLES.put("biz_out", new String[]{"biz_out_line", "out_id"});
        LINE_TABLES.put("biz_return", new String[]{"biz_return_line", "return_id"});
        LINE_TABLES.put("biz_loan", new String[]{"biz_loan_line", "loan_id"});
        LINE_TABLES.put("biz_transfer", new String[]{"biz_transfer_line", "transfer_id"});
        LINE_TABLES.put("biz_so", new String[]{"biz_so_line", "so_id"});
        LINE_TABLES.put("biz_sale_out", new String[]{"biz_sale_out_line", "out_id"});
        LINE_TABLES.put("biz_sale_invoice", new String[]{"biz_sale_invoice_line", "invoice_id"});
        LINE_TABLES.put("biz_sale_return", new String[]{"biz_sale_return_line", "return_id"});
    }

    /** 每天 04:10 自动复位（在 03:30 每日备份之后），失败只告警不影响系统。 */
    @Scheduled(cron = "0 10 4 * * *")
    public void nightlyReset() {
        try {
            reset();
        } catch (Exception e) {
            log.warn("演示账套自动复位跳过：{}", e.getMessage());
        }
    }

    /** 复位演示账套。可被定时任务或管理员手工触发。返回复位说明。 */
    @Transactional(rollbackFor = Exception.class)
    public String reset() {
        // ── 保险①：必须配置了演示账套参数 ──
        Long org = queryLong("SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1");
        if (org == null) return "未配置演示账套(demo.org.id)，跳过";

        // ── 保险②：org 代码必须是 DEMO ──
        String code = queryStr("SELECT code FROM sys_org WHERE id = ? AND deleted = 0", org);
        if (!"DEMO".equals(code)) {
            throw new BizException("demo.org.id=" + org + " 的组织代码是 " + code + "，不是 DEMO，拒绝复位");
        }
        Long wh = queryLong("SELECT id FROM md_warehouse WHERE code = 'DEMO-WH' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        Long sup = queryLong("SELECT id FROM md_supplier WHERE code = 'DEMO-SUP' AND deleted = 0 LIMIT 1");
        Long uid = queryLong("SELECT id FROM sys_user WHERE username = 'demo' AND deleted = 0 LIMIT 1");
        if (wh == null || sup == null || uid == null) {
            throw new BizException("演示仓库/供应商/账号不完整(wh=" + wh + ",sup=" + sup + ",uid=" + uid + ")，拒绝复位");
        }

        // ── 物料归位(自愈)：演示号误触发的"无主物料抢占"曾把其他账套物料属主改成演示账套——
        //    属主还原为集团默认(1)、物料授权收敛回 60 个 DEMO 演示物料。幂等，正常时零行生效。──
        jdbc.update("UPDATE md_material SET org_id = 1 WHERE deleted = 0 AND org_id = ? AND code NOT LIKE 'DEMO-M%'", org);
        jdbc.update("DELETE s FROM md_material_org_scope s JOIN md_material m ON m.id = s.material_id "
                  + "WHERE s.org_id = ? AND m.code NOT LIKE 'DEMO-M%'", org);
        jdbc.update("INSERT IGNORE INTO md_material_org_scope (material_id, org_id) "
                  + "SELECT id, ? FROM md_material WHERE code LIKE 'DEMO-M%' AND deleted = 0", org);

        // ── 清理业务单据：行表 → 审批流/撤回申请 → 单据关系 → 主表（全部以演示 org 为条件）──
        jdbc.update("DELETE e FROM biz_req_fulfillment_event e JOIN biz_out_req r ON r.id=e.req_id WHERE r.org_id=?",org);
        for (Map.Entry<String, String> e : BIZ_TABLES.entrySet()) {
            String type = e.getKey(), t = e.getValue();
            String[] line = LINE_TABLES.get(t);
            jdbc.update("DELETE l FROM " + line[0] + " l JOIN " + t + " h ON l." + line[1] + " = h.id WHERE h.org_id = ?", org);
            jdbc.update("DELETE wr FROM wf_record wr JOIN wf_instance wi ON wr.instance_id = wi.id "
                      + "JOIN " + t + " h ON wi.biz_type = ? AND wi.biz_id = h.id WHERE h.org_id = ?", type, org);
            jdbc.update("DELETE wc FROM wf_cancel_request wc JOIN " + t + " h ON wc.biz_type = ? AND wc.biz_id = h.id WHERE h.org_id = ?", type, org);
            jdbc.update("DELETE wi FROM wf_instance wi JOIN " + t + " h ON wi.biz_type = ? AND wi.biz_id = h.id WHERE h.org_id = ?", type, org);
            jdbc.update("DELETE dr FROM doc_relation dr JOIN " + t + " h ON dr.source_type = ? AND dr.source_id = h.id WHERE h.org_id = ?", type, org);
            jdbc.update("DELETE dr FROM doc_relation dr JOIN " + t + " h ON dr.target_type = ? AND dr.target_id = h.id WHERE h.org_id = ?", type, org);
            jdbc.update("DELETE FROM " + t + " WHERE org_id = ?", org);
        }

        // ── 领料申请/采购计划 + 通知待办 ──
        jdbc.update("DELETE l FROM lr_request_line l JOIN lr_request h ON l.request_id = h.id WHERE h.org_id = ?", org);
        jdbc.update("DELETE FROM lr_request WHERE org_id = ?", org);
        jdbc.update("DELETE l FROM pp_request_line l JOIN pp_request h ON l.request_id = h.id WHERE h.org_id = ?", org);
        jdbc.update("DELETE FROM pp_request WHERE org_id = ?", org);
        jdbc.update("DELETE FROM biz_receipt WHERE org_id = ?", org);
        jdbc.update("DELETE nr FROM notif_read nr JOIN notif n ON nr.notif_id = n.id WHERE n.org_id = ?", org);
        jdbc.update("DELETE FROM notif WHERE org_id = ?", org);
        // 🔴 只按 org_id 删是删不干净的:定向推给【个人】的待办(驳回、退回草稿)
        //    走的是 NotifService.pushToUser,它从不写 org_id —— 恒为 NULL,上面两条一条都删不到。
        //    而它们指向的单据行刚在上面被【物理删除】,业务侧再也不会去关它们,
        //    DataInitRunner 的启动兜底也只有重启时才跑。结果就是演示号的待办和铃铛
        //    随着一次次体验累积死条目,正好砸了"上一个体验者玩乱的数据不影响下一个"这块招牌。
        //    这里按 demo 账号 uid 兜底清一遍(演示账套只有 demo 一个用户,不会误伤别人)。
        jdbc.update("DELETE FROM notif_read WHERE user_id = ?", uid);
        jdbc.update("DELETE FROM notif WHERE user_id = ?", uid);

        // ── 库存：清空演示仓流水与结存，重建期初（前 20 个演示物料各 200 件、单价 15）──
        jdbc.update("DELETE FROM inv_transaction WHERE warehouse_id = ?", wh);
        jdbc.update("DELETE FROM inv_balance WHERE warehouse_id = ?", wh);
        jdbc.update("INSERT INTO inv_balance (material_id, warehouse_id, qty, amount, avg_price, version) "
                  + "SELECT s.material_id, ?, 200, 3000.00, 15.0000, 0 "
                  + "FROM (SELECT material_id FROM md_material_org_scope WHERE org_id = ? ORDER BY material_id LIMIT 20) s", wh, org);
        // 公司成本账跟着重建(按公司核算后它才是金额权威;按仓核算时它不参与计算,保持一致无害)
        jdbc.update("DELETE FROM inv_cost_pool WHERE org_id = ?", org);
        jdbc.update("INSERT INTO inv_cost_pool (org_id, material_id, qty, amount, avg_price, version) "
                  + "SELECT w.org_id, b.material_id, SUM(b.qty), SUM(b.amount), "
                  + "CASE WHEN SUM(b.qty) > 0 THEN ROUND(SUM(b.amount) / SUM(b.qty), 2) ELSE 0 END, 0 "
                  + "FROM inv_balance b JOIN md_warehouse w ON w.id = b.warehouse_id WHERE w.org_id = ? "
                  + "GROUP BY w.org_id, b.material_id", org);

        // ── 基础样例：项目/领料人/提交链接（先清后建，体验者改乱/删掉也能还原）──
        jdbc.update("DELETE FROM md_project WHERE org_id = ?", org);
        jdbc.update("INSERT INTO md_project (parent_id, org_id, code, name, status) VALUES (0, ?, 'DEMO-PRJ1', '演示·一号厂房改造工程', 1)", org);
        jdbc.update("INSERT INTO md_project (parent_id, org_id, code, name, status) VALUES (0, ?, 'DEMO-PRJ2', '演示·设备维修项目', 1)", org);
        Long prj = queryLong("SELECT id FROM md_project WHERE code = 'DEMO-PRJ1' AND org_id = ? AND deleted = 0 LIMIT 1", org);

        jdbc.update("DELETE FROM lr_picker WHERE org_id = ?", org);
        for (String name : List.of("张三", "李四", "王五")) {
            jdbc.update("INSERT INTO lr_picker (org_id, name, status) VALUES (?, ?, 1)", org, name);
        }
        jdbc.update("DELETE FROM lr_link WHERE org_id = ?", org);
        // 令牌每次复位随机生成:写死在代码里的令牌开源后人人可见,等于公开入口。
        jdbc.update("INSERT INTO lr_link (org_id, token, label, warehouse_id, status) VALUES (?, ?, '演示领料入口', ?, 1)",
                org, randomToken(), wh);

        // ── 样例单据（与迁移 20260701_004 同一套）──
        List<Long> ms = jdbc.queryForList("SELECT material_id FROM md_material_org_scope WHERE org_id = ? ORDER BY material_id LIMIT 3", Long.class, org);
        if (ms.size() < 3) throw new BizException("演示物料不足 3 个，拒绝复位");
        Long m1 = ms.get(0), m2 = ms.get(1), m3 = ms.get(2);

        jdbc.update("INSERT INTO biz_po (doc_no, supplier_id, warehouse_id, org_id, order_date, total_qty, received_qty, invoiced_qty, total_amt, tax_amt, status, remark, created_by) "
                  + "VALUES ('DEMO-PO-001', ?, ?, ?, CURDATE(), 250, 0, 0, 2655.50, 305.50, 'APPROVED', '演示样例采购订单', ?)", sup, wh, org, uid);
        Long po1 = queryLong("SELECT id FROM biz_po WHERE doc_no = 'DEMO-PO-001' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("INSERT INTO biz_po_line (po_id, line_no, material_id, qty, received_qty, invoiced_qty, unit_price, tax_rate, amount, amount_excl_tax, tax_amount) "
                  + "VALUES (?, 1, ?, 50, 0, 0, 15.0000, 13.00, 847.50, 750.00, 97.50)", po1, m1);
        jdbc.update("INSERT INTO biz_po_line (po_id, line_no, material_id, qty, received_qty, invoiced_qty, unit_price, tax_rate, amount, amount_excl_tax, tax_amount) "
                  + "VALUES (?, 2, ?, 200, 0, 0, 8.0000, 13.00, 1808.00, 1600.00, 208.00)", po1, m2);

        jdbc.update("INSERT INTO biz_po (doc_no, supplier_id, warehouse_id, org_id, order_date, total_qty, received_qty, invoiced_qty, total_amt, tax_amt, status, remark, created_by) "
                  + "VALUES ('DEMO-PO-002', ?, ?, ?, CURDATE(), 100, 0, 0, 2486.00, 286.00, 'APPROVED', '演示样例采购订单', ?)", sup, wh, org, uid);
        Long po2 = queryLong("SELECT id FROM biz_po WHERE doc_no = 'DEMO-PO-002' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("INSERT INTO biz_po_line (po_id, line_no, material_id, qty, received_qty, invoiced_qty, unit_price, tax_rate, amount, amount_excl_tax, tax_amount) "
                  + "VALUES (?, 1, ?, 100, 0, 0, 22.0000, 13.00, 2486.00, 2200.00, 286.00)", po2, m3);

        jdbc.update("INSERT INTO lr_request (doc_no, org_id, warehouse_id, applicant_name, picker_id, picker_name, project_id, project_name, site, biz_type, out_type, apply_date, status) "
                  + "SELECT 'DEMO-LR-001', ?, ?, '张三', p.id, '张三', ?, '演示·一号厂房改造工程', 'XX村3组', '工程类', '工程领料', CURDATE(), 'SUBMITTED' "
                  + "FROM lr_picker p WHERE p.org_id = ? AND p.name = '张三' AND p.deleted = 0 LIMIT 1", org, wh, prj, org);
        Long lr1 = queryLong("SELECT id FROM lr_request WHERE doc_no = 'DEMO-LR-001' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("INSERT INTO lr_request_line (request_id, line_no, material_id, qty) VALUES (?, 1, ?, 10)", lr1, m1);
        jdbc.update("INSERT INTO lr_request_line (request_id, line_no, material_id, qty) VALUES (?, 2, ?, 50)", lr1, m2);

        jdbc.update("INSERT INTO lr_request (doc_no, org_id, warehouse_id, applicant_name, picker_id, picker_name, project_id, project_name, site, biz_type, out_type, apply_date, status, purchase_by, purchase_at, warehouse_by, warehouse_at) "
                  + "SELECT 'DEMO-LR-002', ?, ?, '李四', p.id, '李四', ?, '演示·一号厂房改造工程', 'XX村5组', '工程类', '工程领料', CURDATE(), 'WAREHOUSE_OK', '采购员·演示', NOW(), '仓管·演示', NOW() "
                  + "FROM lr_picker p WHERE p.org_id = ? AND p.name = '李四' AND p.deleted = 0 LIMIT 1", org, wh, prj, org);
        Long lr2 = queryLong("SELECT id FROM lr_request WHERE doc_no = 'DEMO-LR-002' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("INSERT INTO lr_request_line (request_id, line_no, material_id, qty) VALUES (?, 1, ?, 30)", lr2, m3);

        jdbc.update("INSERT INTO biz_out_req (doc_no, applicant_id, dept_id, org_id, warehouse_id, project_id, project_name, out_type, business_category, material_applicant, apply_date, purpose, total_qty, out_qty, status, remark) "
                  + "VALUES ('DEMO-OREQ-001', ?, ?, ?, ?, ?, '演示·一号厂房改造工程', '工程领料', '工程类', '王五', CURDATE(), '演示·一号厂房改造工程', 20, 0, 'APPROVED', '由领料申请 DEMO-LR-003 生成（演示）')",
                uid, org, org, wh, prj);
        Long oreq = queryLong("SELECT id FROM biz_out_req WHERE doc_no = 'DEMO-OREQ-001' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("UPDATE biz_out_req SET fulfillment_mode='UNSET' WHERE id=?",oreq);
        pickup.initialized(oreq);
        jdbc.update("INSERT INTO biz_out_req_line (out_req_id, line_no, material_id, qty, original_qty, out_qty) VALUES (?, 1, ?, 20, 20, 0)", oreq, m1);

        jdbc.update("INSERT INTO lr_request (doc_no, org_id, warehouse_id, applicant_name, picker_id, picker_name, project_id, project_name, site, biz_type, out_type, apply_date, status, purchase_by, purchase_at, warehouse_by, warehouse_at, finance_by, finance_at, out_req_id, out_req_no) "
                  + "SELECT 'DEMO-LR-003', ?, ?, '王五', p.id, '王五', ?, '演示·一号厂房改造工程', 'XX村1组', '工程类', '工程领料', CURDATE(), 'APPROVED', '采购员·演示', NOW(), '仓管·演示', NOW(), '材料会计·演示', NOW(), ?, 'DEMO-OREQ-001' "
                  + "FROM lr_picker p WHERE p.org_id = ? AND p.name = '王五' AND p.deleted = 0 LIMIT 1", org, wh, prj, oreq, org);
        Long lr3 = queryLong("SELECT id FROM lr_request WHERE doc_no = 'DEMO-LR-003' AND org_id = ? AND deleted = 0 LIMIT 1", org);
        jdbc.update("INSERT INTO lr_request_line (request_id, line_no, material_id, qty) VALUES (?, 1, ?, 20)", lr3, m1);

        // ── demo 账号复位：解锁、启用(密码不动:安装时随机生成或由 SCM_DEMO_PASSWORD 指定) ──
        jdbc.update("UPDATE sys_user SET status = 1, fail_count = 0, locked_until = NULL WHERE username = 'demo' AND deleted = 0");

        log.info("✅ 演示账套(org={})已复位为标准样例", org);
        return "演示账套已复位：样例采购订单×2、领料申请×3、出库申请×1、期初库存×20，demo 账号已解锁";
    }

    /** 32 位十六进制随机令牌 */
    private static String randomToken() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private Long queryLong(String sql, Object... args) {
        List<Long> r = jdbc.queryForList(sql, Long.class, args);
        return r.isEmpty() ? null : r.get(0);
    }

    private String queryStr(String sql, Object... args) {
        List<String> r = jdbc.queryForList(sql, String.class, args);
        return r.isEmpty() ? null : r.get(0);
    }
}
