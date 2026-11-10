package com.group.scm.modules.report;

/**
 * 收发存汇总表与存货明细账共用的"有效库存流水"口径(2026-10-16 抽出,两张表永远同口径)。
 *
 * <ul>
 *   <li>剔除只进损益的暂估差异 ADJUST_EXPENSE(会计政策:差异全额进当期损益,不动库存);</li>
 *   <li>入库/出库/退料/转库只取已审批单据的流水(取消审批的原流水与冲回一起排除,净额为零);
 *       发票流水一律计入(反审可能部分转损益,原调整与冲回不再必然相抵,见下方 WHERE);</li>
 *   <li>biz_date:按单据日期归期(借料按借料日期、归还按归还记录日期),取不到时用记账时间(10-18 前的归还没存日期);</li>
 *   <li>side:收/发方向按单据类型(按惯例):退料/销售退货/借料归还 = 发出红字,
 *       采购退货 = 收入红字;转库/盘点等按数量正负;冲回(REVERSE)跟原单同一侧;</li>
 *   <li>source_no:来源单据号(采购订单/出库申请/原出库单/原入库单等)。</li>
 * </ul>
 * 改这里两张表一起变;E2E 第 23 章断言明细账期初+收−发=期末且与收发存同一行相等。
 */
final class InvFlowSql {

    private InvFlowSql() {}

    /** 作为子查询使用:{@code FROM (InvFlowSql.FLOWS) t}。不含 '%',可安全拼进 String.formatted 模板。 */
    static final String FLOWS = """
            SELECT t.*,
                   COALESCE(r.recv_date, o.out_date, iv.invoice_date, rt.return_date, tf.transfer_date,
                            so.out_date, sr.return_date, pr.return_date, st.take_date, ln.borrow_date, lr.return_date,
                            DATE(t.created_at)) AS biz_date,
                   CASE
                     WHEN t.biz_doc_type IN ('RETURN','SALE_RETURN','LOAN_RETURN') THEN 'OUT'
                     WHEN t.biz_doc_type = 'PRET' THEN 'IN'
                     WHEN t.biz_doc_type IN ('OUT','SALE_OUT','LOAN') THEN 'OUT'
                     WHEN t.biz_doc_type IN ('RECV','INV','OPENING') THEN 'IN'
                     WHEN t.qty = 0 THEN 'IN'
                     WHEN (t.trans_type <> 'REVERSE' AND t.qty > 0) OR (t.trans_type = 'REVERSE' AND t.qty < 0) THEN 'IN'
                     ELSE 'OUT'
                   END AS side,
                   COALESCE(r.source_po_no, o.source_req_no, rt.source_out_no, rt.source_req_no, iv.invoice_no,
                            tf.source_req_no, so.source_so_no, sr.source_out_no, pr.source_recv_no) AS source_no
            FROM inv_transaction t
            LEFT JOIN biz_recv r ON t.biz_doc_type = 'RECV' AND r.id = t.biz_doc_id AND r.deleted = 0
            LEFT JOIN biz_out o ON t.biz_doc_type = 'OUT' AND o.id = t.biz_doc_id AND o.deleted = 0
            LEFT JOIN biz_invoice iv ON t.biz_doc_type = 'INV' AND iv.id = t.biz_doc_id
            LEFT JOIN biz_return rt ON t.biz_doc_type = 'RETURN' AND rt.id = t.biz_doc_id AND rt.deleted = 0
            LEFT JOIN biz_transfer tf ON t.biz_doc_type = 'TRANSFER' AND tf.id = t.biz_doc_id AND tf.deleted = 0
            LEFT JOIN biz_sale_out so ON t.biz_doc_type = 'SALE_OUT' AND so.id = t.biz_doc_id
            LEFT JOIN biz_sale_return sr ON t.biz_doc_type = 'SALE_RETURN' AND sr.id = t.biz_doc_id
            LEFT JOIN biz_purchase_return pr ON t.biz_doc_type = 'PRET' AND pr.id = t.biz_doc_id
            LEFT JOIN biz_stocktake st ON t.biz_doc_type = 'STOCKTAKE' AND st.id = t.biz_doc_id
            -- 2026-10-18(计算审查 U28):借出按借料单的借料日期归期,以前落到记账时间,补录的借料跑到当月。
            --   2026-10-18:归还按归还记录(biz_loan_return)的归还日期归期,一条流水对一条记录;
            --   10-18 以前的归还没存日期,没有记录,仍按记账时间归期。
            LEFT JOIN biz_loan ln ON t.biz_doc_type = 'LOAN' AND ln.id = t.biz_doc_id
            LEFT JOIN biz_loan_return lr ON t.biz_doc_type = 'LOAN_RETURN' AND lr.inv_trans_id = t.id
            WHERE t.trans_type <> 'ADJUST_EXPENSE'
              -- 🔴 2026-10-18(计算审查 U01):发票(INV)流水一律按流水本身计,不再看发票当前状态。
              --    10-16 起发票反审改为"在库部分冲回库存、已领用部分转损益",原调整和冲回不再必然相抵;
              --    按头状态整组排除,反审后的发票会把仍然有效的库存调整一起漏掉(零库存显示负金额)。
              --    其它单据的反审仍是等额全冲,按状态排除与按流水计结果相同,保持原样。
              AND (
                t.biz_doc_type NOT IN ('RECV','OUT','RETURN','TRANSFER')
                OR (t.biz_doc_type = 'RECV' AND r.status = 'APPROVED')
                OR (t.biz_doc_type = 'OUT' AND o.status = 'APPROVED')
                OR (t.biz_doc_type = 'RETURN' AND rt.status = 'APPROVED')
                OR (t.biz_doc_type = 'TRANSFER' AND tf.status = 'APPROVED')
              )
            """;
}
