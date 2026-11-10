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
@TableName("biz_so")
public class BizSo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private String orderType;
    private String channel;
    private Long customerId;
    private String customerUnit;
    private String customerName;
    private String customerPhone;
    private String shipAddress;
    private Long deptId;
    private String deptName;
    private String salesPerson;
    private LocalDate orderDate;
    private LocalDate planShipDate;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    private BigDecimal taxAmt;
    private BigDecimal outQty;
    private BigDecimal invoiceQty;
    private BigDecimal receiptAmount;
    private String receiptMethod;
    /** DRAFT/PENDING/APPROVED/PARTIAL_OUT/OUT/REJECTED/CANCELLED */
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
    private List<BizSoLine> lines;
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
