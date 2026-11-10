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
@TableName("biz_sale_return")
public class BizSaleReturn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private Long sourceOutId;
    private String sourceOutNo;
    private Long warehouseId;
    private Long customerId;
    private String customerUnit;
    private String customerName;
    private LocalDate returnDate;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;   // 退货成本额
    private BigDecimal refundAmt;  // 应冲收入(价税合计)
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
    private List<BizSaleReturnLine> lines;
    @TableField(exist = false)
    private Boolean canApprove;
    @TableField(exist = false)
    private String materialSummary;
    @TableField(exist = false)
    private String materialCodes;
    @TableField(exist = false)
    private String materialNames;
    @TableField(exist = false)
    private String materialSpecs;
}
