package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_recv")
public class BizRecv {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long sourcePoId;
    private String sourcePoNo;
    private Long supplierId;
    private Long warehouseId;
    private Long orgId;
    private LocalDate recvDate;
    /** ESTIMATE / INVOICE */
    private String recvType;
    private Long invoiceId;
    private String invoiceNo;
    private String deliveryNo;
    private String carrier;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;   // 不含税合计（存货成本口径）
    // 瞬态(detail 富集)：税额合计 + 含税合计，用于"不含税+含税"同单展示
    @TableField(exist = false) private BigDecimal taxAmt;
    @TableField(exist = false) private BigDecimal totalAmtInclTax;
    // 瞬态(列表富集)：本单物料摘要(物料名称+规格，顿号分隔)，便于列表快速分辨
    @TableField(exist = false) private String materialSummary;
    @TableField(exist = false)
    private String materialCodes;
    @TableField(exist = false)
    private String materialNames;
    @TableField(exist = false)
    private String materialSpecs;
    /** DRAFT/PENDING/APPROVED/CANCELLED */
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
    private java.util.List<BizRecvLine> lines;
    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮） */
    @TableField(exist = false)
    private Boolean canApprove;
}
