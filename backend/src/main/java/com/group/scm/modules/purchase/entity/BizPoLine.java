package com.group.scm.modules.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_po_line")
public class BizPoLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long poId;
    private Integer lineNo;
    private Long sourcePrLineId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal receivedQty;
    private BigDecimal invoicedQty;
    private BigDecimal unitPrice;     // 不含税单价（= 不含税金额 / 数量，倒挤）
    private BigDecimal taxRate;       // 税率 %
    private BigDecimal amount;        // 含税金额（录入）
    private BigDecimal amountExclTax; // 不含税金额 = 含税金额 / (1 + 税率/100)
    private BigDecimal taxAmount;     // 税额 = 含税金额 - 不含税金额
    private String remark;
    /** 单价偏离历史价过大时，保存当时的历史参考含税单价（服务端计算，前端传入会被覆盖） */
    private BigDecimal priceCheckRef;
    /** 单价偏离历史价过大时，制单人填写的确认理由；未偏离的行恒为 null */
    private String priceCheckNote;
}
