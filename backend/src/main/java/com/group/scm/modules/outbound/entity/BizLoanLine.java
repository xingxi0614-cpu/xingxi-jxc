package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_loan_line")
public class BizLoanLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long loanId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal returnedQty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String remark;

    @TableField(exist = false)
    private BigDecimal availableQty;
}
