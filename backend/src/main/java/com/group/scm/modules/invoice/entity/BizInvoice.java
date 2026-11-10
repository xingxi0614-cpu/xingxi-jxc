package com.group.scm.modules.invoice.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("biz_invoice")
public class BizInvoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private String invoiceNo;
    private Long supplierId;
    private Long orgId;
    private LocalDate invoiceDate;
    private BigDecimal totalQty;
    private BigDecimal netAmt;
    private BigDecimal taxAmt;
    private BigDecimal totalAmt;
    /** 差额调整（暂估冲销时记录） */
    private BigDecimal adjustAmt;
    /** DRAFT/PENDING/APPROVED/MATCHED/REJECTED */
    private String status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private java.util.List<BizInvoiceLine> lines;

    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮）。
     *  手机端详情页靠它决定要不要出「通过/退回」——不回填的话按钮永远不显示。 */
    @TableField(exist = false)
    private Boolean canApprove;
}
