package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_sale_return_line")
public class BizSaleReturnLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long returnId;
    private Integer lineNo;
    private Long sourceOutLineId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;     // 原出库成本单价
    private BigDecimal amount;        // 退货成本额
    private BigDecimal saleTaxPrice;  // 原售价含税单价
    private BigDecimal saleAmount;    // 应冲收入(价税合计)
    private String remark;
}
