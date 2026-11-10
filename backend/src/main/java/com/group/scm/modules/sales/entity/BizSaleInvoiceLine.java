package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_sale_invoice_line")
public class BizSaleInvoiceLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal taxRate;
    private BigDecimal amountExclTax;
    private BigDecimal taxAmount;
    private BigDecimal amount;      // 价税合计
    private String remark;
}
