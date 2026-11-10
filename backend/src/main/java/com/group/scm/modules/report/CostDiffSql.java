package com.group.scm.modules.report;

/**
 * 采购成本差异的"流水口径"(2026-10-16 抽出):采购成本差异调整表的明细 / 按月汇总 / 确认入账,
 * 与月末结账的"差异未确认"硬拦,四处共用这一段,永远同口径。
 *
 * <ul>
 *   <li>发票(INV):进库存部分 = ADJUST 及其冲回(REVERSE 且数量 0);进损益部分 = ADJUST_EXPENSE;</li>
 *   <li>采购退货(PRET):只有兜底差额 ADJUST_EXPENSE(正常按原入库额冲库存,差额为 0,不产生流水);</li>
 *   <li>期间:发票按开票日期、退货按退货日期(与外部账的入账期间一致,按开票日期);</li>
 *   <li>source_type:暂估开票 / 货票同到开票 / 采购退货。货票同到 = 该发票里同物料同仓的入库单当初是按"发票入库"过账的
 *       (看入库流水 IN_INVOICE;不能看 recv_type —— 暂估单全额开票后会被改成 INVOICE);</li>
 *   <li>is_reverse:取消审批产生的流水(备注以"反审"开头)。</li>
 * </ul>
 * 需要材料会计到外部账做分录、月度确认和结账硬拦只看【进损益】部分(in_stock = 0 的行);
 * 进库存部分已在库存金额里,收发存会显示,不需要确认。
 * 不含 '%',可安全拼进 String.formatted 模板(PeriodCloseService 就是这么用的)。
 */
public final class CostDiffSql {

    private CostDiffSql() {}

    public static final String FLOWS = """
            SELECT t.id, t.material_id, t.warehouse_id, t.trans_type, t.amount, t.remark, t.created_at,
                   t.biz_doc_type, t.biz_doc_id, w.org_id,
                   COALESCE(iv.invoice_date, pr.return_date) AS biz_date,
                   COALESCE(iv.doc_no, pr.doc_no) AS doc_no,
                   COALESCE(iv.supplier_id, pr.supplier_id) AS supplier_id,
                   CASE WHEN t.trans_type = 'ADJUST_EXPENSE' THEN 0 ELSE 1 END AS in_stock,
                   CASE WHEN LEFT(IFNULL(t.remark, ''), 2) = '反审' THEN 1 ELSE 0 END AS is_reverse,
                   CASE WHEN t.biz_doc_type = 'PRET' THEN '采购退货'
                        WHEN EXISTS (SELECT 1 FROM biz_invoice_line il
                                     JOIN biz_recv_line rl ON rl.id = il.recv_line_id
                                     JOIN biz_recv r ON r.id = rl.recv_id
                                     WHERE il.invoice_id = iv.id AND rl.material_id = t.material_id
                                       AND r.warehouse_id = t.warehouse_id
                                       AND (SELECT rt.trans_type FROM inv_transaction rt
                                            WHERE rt.biz_doc_type = 'RECV' AND rt.biz_doc_id = r.id
                                              AND rt.trans_type IN ('IN_ESTIMATE', 'IN_INVOICE')
                                            ORDER BY rt.id DESC LIMIT 1) = 'IN_INVOICE')
                             THEN '货票同到开票'
                        ELSE '暂估开票' END AS source_type
            FROM inv_transaction t
            JOIN md_warehouse w ON w.id = t.warehouse_id
            -- 🔴 2026-10-18(计算审查 U03):不再要求单据未删除。反审后的发票草稿被删掉,
            --    当初已经发生、要到外部账做分录的损益流水仍在,以前却从差异表和月末结账检查里一起消失。
            --    单据是逻辑删除,行还在,照样取得到日期和单号。
            LEFT JOIN biz_invoice iv ON t.biz_doc_type = 'INV' AND iv.id = t.biz_doc_id
            LEFT JOIN biz_purchase_return pr ON t.biz_doc_type = 'PRET' AND pr.id = t.biz_doc_id
            WHERE (t.biz_doc_type = 'INV' AND iv.id IS NOT NULL
                   AND (t.trans_type IN ('ADJUST_EXPENSE', 'ADJUST') OR (t.trans_type = 'REVERSE' AND t.qty = 0)))
               OR (t.biz_doc_type = 'PRET' AND pr.id IS NOT NULL AND t.trans_type = 'ADJUST_EXPENSE')
            """;
}
