package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_sale_invoice")
public class BizSaleInvoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private Long sourceOutId;
    private String sourceOutNo;
    private Long customerId;
    private String customerUnit;
    private String customerName;
    private String invoiceType;
    private String invoiceNo;
    private LocalDate invoiceDate;
    private BigDecimal totalQty;
    private BigDecimal amount;    // 不含税金额
    private BigDecimal taxAmt;    // 销项税额
    private BigDecimal totalAmt;  // 价税合计（应收）
    /** DRAFT/PENDING/APPROVED/REJECTED */
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
    private List<BizSaleInvoiceLine> lines;
    @TableField(exist = false)
    private Boolean canApprove;
}
