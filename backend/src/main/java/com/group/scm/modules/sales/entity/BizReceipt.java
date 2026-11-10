package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_receipt")
public class BizReceipt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long customerId;
    private String customerName;
    private LocalDate receiptDate;
    private BigDecimal amount;
    private String method;
    private String refNo;
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
    /** 瞬态：客户单位名(列表富集) */
    @TableField(exist = false)
    private String customerUnit;
}
