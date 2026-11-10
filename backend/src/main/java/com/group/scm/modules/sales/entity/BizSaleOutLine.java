package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_sale_out_line")
public class BizSaleOutLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long outId;
    private Integer lineNo;
    private Long sourceSoLineId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;   // 成本单价（移动加权，审批时填入）
    private BigDecimal amount;      // 成本额
    private String remark;
}
