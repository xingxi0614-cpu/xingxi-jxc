package com.group.scm.modules.purchase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("biz_pr")
public class BizPr {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long applicantId;
    private Long deptId;
    private Long orgId;
    private LocalDate applyDate;
    private LocalDate requiredDate;
    private String purpose;
    private Long supplierId;
    private Long warehouseId;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    /** DRAFT/PENDING/APPROVED/REJECTED/CANCELLED/CONVERTED */
    private String status;
    private String currentNode;
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
    private java.util.List<BizPrLine> lines;
}
