package com.group.scm.modules.invoice.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 付款登记（应付-物资款核销台账；只登记不做资金，金额为含税口径）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_payment")
public class BizPayment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long supplierId;
    private LocalDate payDate;
    /** 付款金额(含税口径，对应应付-物资款) */
    private BigDecimal amount;
    /** 付款方式：转账/银行承兑/商业承兑/现金/其他 */
    private String method;
    /** 银行回单号/记账凭证号 */
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
    // 瞬态(列表富集)
    @TableField(exist = false) private String supplierName;
    @TableField(exist = false) private String orgName;
    @TableField(exist = false) private String createdByName;
}
