package com.group.scm.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group.scm.modules.inventory.entity.InvTransaction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;

public interface InvTransactionMapper extends BaseMapper<InvTransaction> {

    @Select("""
        SELECT * FROM inv_transaction
        WHERE material_id = #{matId} AND warehouse_id = #{whId}
        ORDER BY id
    """)
    List<InvTransaction> findByMatWh(@Param("matId") Long matId, @Param("whId") Long whId);

    /*
     * 出入库流水查询拆成三段共用(2026-10-19):列(SELECT_COLS)、过滤条件(FROM_WHERE)、排序分页。
     * 「最近 N 条」(手机端)、分页列表、全部结果合计三处必须用同一套条件,否则合计和列表对不上。
     * 拼在注解里的都是编译期常量,没有任何运行时拼接,参数一律 #{} 绑定。
     */
    String SELECT_COLS = """
        SELECT t.*,
               DATE_FORMAT(t.created_at, '%Y-%m-%d') AS createdAt,
               t.trans_no AS transNo,
               t.material_id AS materialId,
               t.warehouse_id AS warehouseId,
               t.trans_type AS transType,
               t.biz_doc_type AS bizDocType,
               t.biz_doc_id AS bizDocId,
               t.biz_doc_no AS bizDocNo,
               t.biz_line_id AS bizLineId,
               t.unit_price AS unitPrice,
               t.bal_qty AS balQty,
               t.bal_amount AS balAmount,
               t.bal_avg_price AS balAvgPrice,
               m.code matCode,
               m.name matName,
               m.spec matSpec,
               w.name whName,
               COALESCE(rs.name, ivs.name) AS supplierName,
               COALESCE(r.supplier_id, iv.supplier_id) AS supplierId,
               COALESCE(p.name, rp.name, o.project_name, rt.project_name) AS projectName,
               COALESCE(o.project_id, rt.project_id) AS projectId,
               COALESCE(r.source_po_no, o.source_req_no, rt.source_out_no, rt.source_req_no, iv.invoice_no, tf.doc_no) AS sourceNo,
               COALESCE(r.recv_type, o.out_type, rt.out_type, iv.invoice_no, CASE WHEN tf.id IS NOT NULL THEN 'TRANSFER' ELSE NULL END) AS bizSubType,
               COALESCE(r.recv_date, o.out_date, rt.return_date, iv.invoice_date, tf.transfer_date, DATE(t.created_at)) AS bizDate
""";

    String FROM_WHERE = """
        FROM inv_transaction t
        LEFT JOIN md_material m ON m.id = t.material_id
        LEFT JOIN md_warehouse w ON w.id = t.warehouse_id
        LEFT JOIN biz_recv r ON t.biz_doc_type = 'RECV' AND r.id = t.biz_doc_id AND r.deleted = 0
        LEFT JOIN md_supplier rs ON rs.id = r.supplier_id
        LEFT JOIN biz_out o ON t.biz_doc_type = 'OUT' AND o.id = t.biz_doc_id AND o.deleted = 0
        LEFT JOIN md_project p ON p.id = o.project_id
        LEFT JOIN biz_return rt ON t.biz_doc_type = 'RETURN' AND rt.id = t.biz_doc_id AND rt.deleted = 0
        LEFT JOIN md_project rp ON rp.id = rt.project_id
        LEFT JOIN biz_invoice iv ON t.biz_doc_type = 'INV' AND iv.id = t.biz_doc_id AND iv.deleted = 0
        LEFT JOIN md_supplier ivs ON ivs.id = iv.supplier_id
        LEFT JOIN biz_transfer tf ON t.biz_doc_type = 'TRANSFER' AND tf.id = t.biz_doc_id AND tf.deleted = 0
        WHERE (#{matId} IS NULL OR t.material_id = #{matId})
          AND (#{whId} IS NULL OR t.warehouse_id = #{whId})
          AND (#{transType} IS NULL OR #{transType} = '' OR t.trans_type = #{transType})
          AND (#{bizDocType} IS NULL OR #{bizDocType} = '' OR t.biz_doc_type = #{bizDocType})
          AND (
            #{bizDocNo} IS NULL OR #{bizDocNo} = ''
            OR t.biz_doc_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR r.source_po_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR o.source_req_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR rt.source_out_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR rt.source_req_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR iv.invoice_no LIKE CONCAT('%', #{bizDocNo}, '%')
            OR tf.doc_no LIKE CONCAT('%', #{bizDocNo}, '%')
          )
          AND (#{supplierId} IS NULL OR r.supplier_id = #{supplierId} OR iv.supplier_id = #{supplierId})
          AND (#{projectId} IS NULL OR o.project_id = #{projectId} OR rt.project_id = #{projectId})
          AND (#{startDate} IS NULL OR COALESCE(r.recv_date, o.out_date, rt.return_date, iv.invoice_date, tf.transfer_date, DATE(t.created_at)) &gt;= #{startDate})
          AND (#{endDate} IS NULL OR COALESCE(r.recv_date, o.out_date, rt.return_date, iv.invoice_date, tf.transfer_date, DATE(t.created_at)) &lt;= #{endDate})
          AND w.org_id IN
          <foreach collection="orgIds" item="orgId" open="(" separator="," close=")">
            #{orgId}
          </foreach>
          AND (
            t.biz_doc_type NOT IN ('RECV','OUT','INV','RETURN','TRANSFER')
            OR (t.biz_doc_type = 'RECV' AND r.status = 'APPROVED')
            OR (t.biz_doc_type = 'OUT' AND o.status = 'APPROVED')
            OR (t.biz_doc_type = 'INV' AND iv.status IN ('APPROVED','MATCHED'))
            OR (t.biz_doc_type = 'RETURN' AND rt.status = 'APPROVED')
            OR (t.biz_doc_type = 'TRANSFER' AND tf.status = 'APPROVED')
          )
""";

    @Select("<script>" + SELECT_COLS + FROM_WHERE + " ORDER BY t.id DESC LIMIT #{limit} </script>")
    List<java.util.Map<String, Object>> queryRecent(@Param("matId") Long matId,
                                                     @Param("whId") Long whId,
                                                     @Param("transType") String transType,
                                                     @Param("bizDocType") String bizDocType,
                                                     @Param("bizDocNo") String bizDocNo,
                                                     @Param("supplierId") Long supplierId,
                                                     @Param("projectId") Long projectId,
                                                     @Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate,
                                                     @Param("orgIds") List<Long> orgIds,
                                                     @Param("limit") int limit);

    /** 分页列表:按记账先后倒序,第 offset 条起取 size 条 */
    @Select("<script>" + SELECT_COLS + FROM_WHERE + " ORDER BY t.id DESC LIMIT #{offset}, #{size} </script>")
    List<java.util.Map<String, Object>> queryPage(@Param("matId") Long matId,
                                                   @Param("whId") Long whId,
                                                   @Param("transType") String transType,
                                                   @Param("bizDocType") String bizDocType,
                                                   @Param("bizDocNo") String bizDocNo,
                                                   @Param("supplierId") Long supplierId,
                                                   @Param("projectId") Long projectId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("orgIds") List<Long> orgIds,
                                                   @Param("offset") long offset,
                                                   @Param("size") int size);

    /**
     * 全部查询结果的条数与合计(不只当前页)。金额在库里带正负号(出库为负),页面上显示的是绝对值,
     * 所以入库、出库分开加:数量 &gt; 0 算入库,数量 &lt; 0 算出库;数量为 0 的差额调整只进净额。
     */
    @Select("<script>SELECT COUNT(*) AS total,"
            + " COALESCE(SUM(CASE WHEN t.qty &gt; 0 THEN t.qty END), 0) AS inQty,"
            + " COALESCE(SUM(CASE WHEN t.qty &gt; 0 THEN t.amount END), 0) AS inAmount,"
            + " COALESCE(SUM(CASE WHEN t.qty &lt; 0 THEN -t.qty END), 0) AS outQty,"
            + " COALESCE(SUM(CASE WHEN t.qty &lt; 0 THEN -t.amount END), 0) AS outAmount,"
            + " COALESCE(SUM(t.qty), 0) AS netQty,"
            + " COALESCE(SUM(t.amount), 0) AS netAmount,"
            + " COALESCE(SUM(t.tail_amt), 0) AS tailAmt "
            + FROM_WHERE + "</script>")
    java.util.Map<String, Object> queryTotals(@Param("matId") Long matId,
                                              @Param("whId") Long whId,
                                              @Param("transType") String transType,
                                              @Param("bizDocType") String bizDocType,
                                              @Param("bizDocNo") String bizDocNo,
                                              @Param("supplierId") Long supplierId,
                                              @Param("projectId") Long projectId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate,
                                              @Param("orgIds") List<Long> orgIds);
}
