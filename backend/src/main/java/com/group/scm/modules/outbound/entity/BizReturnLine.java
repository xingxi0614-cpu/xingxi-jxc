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
@TableName("biz_return_line")
public class BizReturnLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long returnId;
    private Integer lineNo;
    private Long sourceOutLineId;
    private Long sourceReqLineId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String remark;

    @TableField(exist = false)
    private BigDecimal returnedQty;
    @TableField(exist = false)
    private BigDecimal availableQty;
}
