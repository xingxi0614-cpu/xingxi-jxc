package com.group.scm.modules.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("biz_pr_line")
public class BizPrLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long prId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private BigDecimal convertedQty;
    private String remark;
}
