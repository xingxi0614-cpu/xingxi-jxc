package com.group.scm.modules.sales.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_so_line")
public class BizSoLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long soId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal taxPrice;      // 含税净价
    private BigDecimal taxRate;       // 税率 %
    private BigDecimal amount;        // 价税合计（录入）
    private BigDecimal amountExclTax; // 不含税金额
    private BigDecimal taxAmount;     // 税额
    private BigDecimal unitPrice;     // 不含税单价
    private LocalDate planShipDate;
    private BigDecimal outQty;
    @TableField(exist = false)
    private BigDecimal reservedOutQty;
    @TableField(exist = false)
    private BigDecimal availableOutQty;
    private BigDecimal invoiceQty;
    private String remark;
}
