package com.group.scm.modules.report;

import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存货明细账(按公司)—— 对应 财务软件「存货明细账」(2026-10-16 定:一次一种物料、按单据日期排序、
 * 加仓库列、不显示凭证号、权限同收发存汇总)。
 *
 * <pre>
 * 取数:InvFlowSql.FLOWS(与收发存汇总同一口径)里该公司、该物料、单据日期 ≤ 截止日的全部流水,
 *       按 单据日期、记账先后 排序。
 * 期初 = 起始日之前全部流水累计;每行结存 = 上一行结存 + 本行数量/金额(收入为正、发出为负);
 * 本月合计 = 当月收入、发出之和;本年累计 = 当年 1 月 1 日起(含起始日前)收入、发出之和。
 * 收入/发出方向沿用 side:退料等"发出红字"记在发出栏、数量金额为负。
 * </pre>
 * 流水逐笔累加到今天 = 公司成本账,所以切换前的历史也能按公司看。
 */
@Service
@RequiredArgsConstructor
public class InventoryLedgerService {

    private final JdbcTemplate jdbc;
    private final OrgScopeService orgScope;

    /** 当前账号能查的公司(有仓库的组织),给页面的公司下拉用。 */
    public List<Map<String, Object>> companies() {
        List<Long> orgIds = orgScope.visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        return jdbc.queryForList("""
                SELECT o.id, o.name FROM sys_org o
                WHERE o.deleted = 0 AND o.id IN (%s)
                  AND EXISTS (SELECT 1 FROM md_warehouse w WHERE w.org_id = o.id AND w.deleted = 0)
                ORDER BY o.sort, o.id
                """.formatted(placeholders(orgIds.size())), orgIds.toArray());
    }

    /**
     * warehouseId(2026-10-16 定方案 A):选了仓库 = 只看这个仓库自己的收、发、结存数量(含转入转出),金额全部不返回。
     * 按公司核算后仓库没有独立成本(出库按公司单价),单看一个仓库的金额会出现"数量 0、金额 −50"这种怪数。
     * docTypes:只显示所选单据类型的行;结存列仍是包含全部单据的真实结存,本月合计/本年累计只合计所显示的类型。
     */
    public Map<String, Object> ledger(Long orgId, Long materialId, LocalDate start, LocalDate end,
                                      Long warehouseId, java.util.Set<String> docTypes) {
        if (materialId == null) throw new BizException("请选择物料");
        LocalDate today = LocalDate.now();
        if (end == null) end = today;
        if (start == null) start = end.withDayOfYear(1);
        if (start.isAfter(end)) throw new BizException("开始日期不能晚于结束日期");
        orgId = resolveCompany(orgId);
        if (warehouseId != null) {
            orgScope.assertWarehouseVisible(warehouseId);
            Long whOrg = jdbc.queryForObject("SELECT org_id FROM md_warehouse WHERE id = ?", Long.class, warehouseId);
            if (!orgId.equals(whOrg)) throw new BizException("所选仓库不属于该公司");
        }
        boolean filterType = docTypes != null && !docTypes.isEmpty();

        Map<String, Object> material = jdbc.queryForList(
                "SELECT id, code, name, spec, unit FROM md_material WHERE id = ?", materialId)
                .stream().findFirst().orElseThrow(() -> new BizException("物料不存在"));
        String orgName = jdbc.queryForList("SELECT name FROM sys_org WHERE id = ?", String.class, orgId)
                .stream().findFirst().orElse("");

        List<Map<String, Object>> flows = jdbc.queryForList("""
                SELECT f.id, f.biz_date, f.side, f.trans_type, f.biz_doc_type, f.biz_doc_id, f.biz_doc_no, f.source_no,
                       f.qty, f.unit_price, f.amount, f.tail_amt, f.remark, w.name wh_name
                FROM (%s) f
                JOIN md_warehouse w ON w.id = f.warehouse_id
                WHERE w.org_id = ? AND f.material_id = ? AND f.biz_date <= ? AND (? IS NULL OR f.warehouse_id = ?)
                ORDER BY f.biz_date, f.id
                """.formatted(InvFlowSql.FLOWS), orgId, materialId, Date.valueOf(end), warehouseId, warehouseId);

        BigDecimal balQ = BigDecimal.ZERO, balA = BigDecimal.ZERO;
        BigDecimal yInQ = BigDecimal.ZERO, yInA = BigDecimal.ZERO, yOutQ = BigDecimal.ZERO, yOutA = BigDecimal.ZERO;
        BigDecimal mInQ = BigDecimal.ZERO, mInA = BigDecimal.ZERO, mOutQ = BigDecimal.ZERO, mOutA = BigDecimal.ZERO;
        String month = null;
        // 本年累计属于哪一年:单独记,不能借"上一条区间内流水的月份"判断换年(计算审查 U39:
        // 查 2024-12~2025-01、而 2024-12 没有流水时,2025 第一条流水前 month 还是空,旧逻辑不清零,
        // 2024 年的数带进了 2025 的本年累计)
        int accYear = start.getYear();
        boolean monthShown = false; // 这个月有没有所选类型的行;没有就不出本月合计/本年累计
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> opening = null;

        for (Map<String, Object> f : flows) {
            Object bd = f.get("biz_date");
            LocalDate d = bd instanceof LocalDate ld ? ld : ((Date) bd).toLocalDate();
            BigDecimal q = num(f.get("qty")), a = num(f.get("amount"));
            boolean in = "IN".equals(f.get("side"));
            boolean shown = !filterType || docTypes.contains(String.valueOf(f.get("biz_doc_type")));
            if (d.isBefore(start)) {
                balQ = balQ.add(q); balA = balA.add(a);
                if (shown && d.getYear() == start.getYear()) {
                    if (in) { yInQ = yInQ.add(q); yInA = yInA.add(a); }
                    else { yOutQ = yOutQ.subtract(q); yOutA = yOutA.subtract(a); }
                }
                continue;
            }
            if (opening == null) opening = balance("OPENING", "期初结存", balQ, balA);
            String m = d.toString().substring(0, 7);
            if (month != null && !month.equals(m)) {
                if (monthShown) {
                    rows.add(subtotal("MONTH", "本月合计", mInQ, mInA, mOutQ, mOutA, null, null));
                    rows.add(subtotal("YEAR", "本年累计", yInQ, yInA, yOutQ, yOutA, balQ, balA));
                }
                monthShown = false;
                mInQ = mInA = mOutQ = mOutA = BigDecimal.ZERO;
            }
            // 上个月的合计/累计已经出完,再判断换年清零
            if (d.getYear() != accYear) {
                yInQ = yInA = yOutQ = yOutA = BigDecimal.ZERO;
                accYear = d.getYear();
            }
            month = m;
            balQ = balQ.add(q); balA = balA.add(a);
            if (!shown) continue;
            monthShown = true;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "ROW");
            r.put("date", d.toString());
            r.put("docNo", f.get("biz_doc_no"));
            r.put("docType", f.get("biz_doc_type"));
            r.put("docId", f.get("biz_doc_id"));
            r.put("sourceNo", f.get("source_no"));
            r.put("typeLabel", typeLabel((String) f.get("biz_doc_type"), (String) f.get("trans_type"), q));
            r.put("transType", f.get("trans_type"));
            r.put("warehouse", f.get("wh_name"));
            r.put("remark", f.get("remark"));
            r.put("tailAmt", f.get("tail_amt"));
            if (in) {
                r.put("inQty", q); r.put("inPrice", f.get("unit_price")); r.put("inAmt", a);
                mInQ = mInQ.add(q); mInA = mInA.add(a); yInQ = yInQ.add(q); yInA = yInA.add(a);
            } else {
                r.put("outQty", q.negate()); r.put("outPrice", f.get("unit_price")); r.put("outAmt", a.negate());
                mOutQ = mOutQ.subtract(q); mOutA = mOutA.subtract(a); yOutQ = yOutQ.subtract(q); yOutA = yOutA.subtract(a);
            }
            putBalance(r, balQ, balA);
            rows.add(r);
        }
        if (opening == null) opening = balance("OPENING", "期初结存", balQ, balA);
        if (month != null && monthShown) {
            rows.add(subtotal("MONTH", "本月合计", mInQ, mInA, mOutQ, mOutA, null, null));
            rows.add(subtotal("YEAR", "本年累计", yInQ, yInA, yOutQ, yOutA, balQ, balA));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", orgId);
        out.put("orgName", orgName);
        out.put("material", material);
        out.put("startDate", start.toString());
        out.put("endDate", end.toString());
        out.put("opening", opening);
        out.put("rows", rows);
        out.put("closing", balance("CLOSING", "期末结存", balQ, balA));
        out.put("qtyOnly", warehouseId != null);
        // 同收发存:仓管等岗位只看数量;选了仓库时谁都只看数量(见方法注释)
        if (warehouseId != null || !SecurityUtil.hasFinanceAccessOrAdmin()) maskCost(out);
        return out;
    }

    /** 不指定公司时:只能看一家就用那一家,能看多家则必须选(明细账一次只看一家公司)。 */
    private Long resolveCompany(Long orgId) {
        if (orgId != null) {
            orgScope.assertOrgVisible(orgId);
            return orgId;
        }
        List<Map<String, Object>> cs = companies();
        if (cs.size() == 1) return ((Number) cs.get(0).get("id")).longValue();
        if (cs.isEmpty()) throw new BizException(4003, "当前账号没有可查看的公司");
        throw new BizException("请选择公司");
    }

    /** 单据类型中文名(与财务软件明细账"单据类型"列对应);冲回行标"取消审批冲回"。 */
    static String typeLabel(String bizType, String transType, BigDecimal qty) {
        String base = switch (bizType == null ? "" : bizType) {
            case "RECV" -> "IN_ESTIMATE".equals(transType) ? "采购入库(暂估)" : "采购入库";
            case "INV" -> "采购发票调整";
            case "OPENING", "INIT" -> "期初导入";
            case "OUT" -> "材料出库";
            case "RETURN" -> "退料";
            case "SALE_OUT" -> "销售出库";
            case "SALE_RETURN" -> "销售退货";
            case "PRET" -> "采购退货";
            case "LOAN" -> "借料";
            case "LOAN_RETURN" -> "借料归还";
            case "STOCKTAKE" -> qty.signum() >= 0 ? "盘盈" : "盘亏";
            case "TRANSFER" -> "TRANSFER_IN".equals(transType) ? "转库入库" : "转库出库";
            default -> bizType == null ? "-" : bizType;
        };
        if ("TRANSFER".equals(bizType) && "REVERSE".equals(transType)) base = qty.signum() > 0 ? "转库出库" : "转库入库";
        return "REVERSE".equals(transType) ? base + "·取消审批冲回" : base;
    }

    private static Map<String, Object> balance(String kind, String label, BigDecimal q, BigDecimal a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        r.put("label", label);
        putBalance(r, q, a);
        return r;
    }

    private static Map<String, Object> subtotal(String kind, String label, BigDecimal inQ, BigDecimal inA,
                                                BigDecimal outQ, BigDecimal outA, BigDecimal balQ, BigDecimal balA) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        r.put("label", label);
        r.put("inQty", inQ); r.put("inAmt", inA);
        r.put("outQty", outQ); r.put("outAmt", outA);
        if (balQ != null) putBalance(r, balQ, balA);
        return r;
    }

    private static void putBalance(Map<String, Object> r, BigDecimal q, BigDecimal a) {
        r.put("balQty", q);
        r.put("balAmt", a.setScale(2, RoundingMode.HALF_UP));
        r.put("balPrice", q.signum() != 0 ? a.divide(q, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
    }

    @SuppressWarnings("unchecked")
    private static void maskCost(Map<String, Object> out) {
        List<Map<String, Object>> all = new ArrayList<>((List<Map<String, Object>>) out.get("rows"));
        all.add((Map<String, Object>) out.get("opening"));
        all.add((Map<String, Object>) out.get("closing"));
        for (Map<String, Object> r : all) {
            for (String k : List.of("inPrice", "inAmt", "outPrice", "outAmt", "balPrice", "balAmt", "tailAmt")) r.remove(k);
        }
        out.put("costMasked", true);
    }

    private static BigDecimal num(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
