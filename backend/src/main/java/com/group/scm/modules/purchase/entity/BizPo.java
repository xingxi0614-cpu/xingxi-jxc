package com.group.scm.modules.purchase.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_po")
public class BizPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long sourcePrId;
    private String sourcePrNo;
    private Long supplierId;
    private Long warehouseId;
    private Long orgId;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    private BigDecimal totalQty;
    private BigDecimal receivedQty;
    private BigDecimal invoicedQty;
    private BigDecimal totalAmt;
    private BigDecimal taxAmt;
    /** DRAFT/PENDING/APPROVED/PARTIAL_RECEIVED/RECEIVED/CLOSED/REJECTED */
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
    private java.util.List<BizPoLine> lines;
    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮） */
    @TableField(exist = false)
    private Boolean canApprove;
    /** 瞬态(列表富集)：物料摘要(编码 名称(规格)，顿号分隔)，便于列表快速分辨 */
    @TableField(exist = false)
    private String materialSummary;
    @TableField(exist = false)
    private String materialCodes;
    @TableField(exist = false)
    private String materialNames;
    @TableField(exist = false)
    private String materialSpecs;
}
