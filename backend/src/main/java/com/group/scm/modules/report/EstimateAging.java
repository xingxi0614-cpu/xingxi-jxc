package com.group.scm.modules.report;

/**
 * 暂估挂账超期的口径(2026-10-16 对标财务软件操作手册:暂估超过三个月要统计出来、落实原因、督促开票)。
 * 暂估清单报表的"挂账天数"标红、工作台提醒卡、月末结账提醒三处共用,永远同口径。
 *
 * <ul>
 *   <li>只看已审批的暂估入库行(recv_type = ESTIMATE),而且还有没开票、没退货的数量;</li>
 *   <li>挂账天数 = 今天 − 入库日期;超过 {@link #DAYS} 天算超期(用户 10-16 定:三个月按 90 天算);</li>
 *   <li>金额 = 这一行还没开票的部分(入库金额 × 剩余数量 ÷ 入库数量),不是整行金额。</li>
 * </ul>
 * 表别名约定:入库单 r、入库行 rl。只作提醒,不拦任何操作。
 */
public final class EstimateAging {

    private EstimateAging() {}

    public static final int DAYS = 90;

    /** 入库行还没开票(也没退货)的数量。 */
    public static final String REMAIN_QTY = "(rl.qty - COALESCE(rl.invoiced_qty,0) - COALESCE(rl.returned_qty,0))";

    /** 这一行还没开票部分的金额。 */
    public static final String REMAIN_AMT = "ROUND(CASE WHEN rl.qty > 0 THEN rl.amount * " + REMAIN_QTY + " / rl.qty ELSE 0 END, 2)";

    /** 超期的暂估行(调用方已限定 r.deleted = 0、r.status = 'APPROVED'、r.recv_type = 'ESTIMATE')。 */
    public static final String OVERDUE_LINE = REMAIN_QTY + " > 0 AND r.recv_date < CURDATE() - INTERVAL " + DAYS + " DAY";
}
