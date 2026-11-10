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
@TableName("biz_stocktake_line")
public class BizStocktakeLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stocktakeId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal bookQty;
    private BigDecimal unitPrice;
    private BigDecimal actualQty;
    private BigDecimal diffQty;
    private BigDecimal diffAmt;
    private String remark;
    // 瞬态富集
    @TableField(exist = false) private String matCode;
    @TableField(exist = false) private String matName;
    @TableField(exist = false) private String matSpec;
    @TableField(exist = false) private String matUnit;
}
