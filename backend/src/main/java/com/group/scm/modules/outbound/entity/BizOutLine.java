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
@TableName("biz_out_line")
public class BizOutLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long outId;
    private Integer lineNo;
    private Long sourceReqLineId;
    private Long materialId;
    private BigDecimal qty;
    /** 出库时的加权平均单价（审批通过时由 WaInventoryService 实际填入）*/
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String remark;
    /** 来源申请明细行上的备注(查询时读出,不落库),见 OutService#fillFromSourceReq */
    @TableField(exist = false)
    private String reqRemark;
}
