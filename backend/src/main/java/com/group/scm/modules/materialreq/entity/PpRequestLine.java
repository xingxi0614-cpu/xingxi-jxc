package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 采购计划明细。demand_qty=业务需求量；final_qty=采购确认的最终采购量。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("pp_request_line")
public class PpRequestLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requestId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal demandQty;
    private BigDecimal finalQty;
    private String remark;
    /** 富集：物料信息 + 当前库存 + 最低库存 + 建议采购量(=需求+最低-当前，≥0) */
    @TableField(exist = false)
    private String materialCode;
    @TableField(exist = false)
    private String materialName;
    @TableField(exist = false)
    private String materialSpec;
    @TableField(exist = false)
    private String materialUnit;
    @TableField(exist = false)
    private BigDecimal stockQty;
    @TableField(exist = false)
    private BigDecimal safetyQty;
    @TableField(exist = false)
    private BigDecimal suggestQty;
}
