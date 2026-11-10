package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_transfer_line")
public class BizTransferLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long transferId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String remark;
    private Long sourceReqLineId;

    @TableField(exist = false)
    private BigDecimal currentQty;
}
