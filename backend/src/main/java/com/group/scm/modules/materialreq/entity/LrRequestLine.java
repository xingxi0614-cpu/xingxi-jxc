package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/** 领料申请明细行。qty=申领数量。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("lr_request_line")
public class LrRequestLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requestId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    private String remark;
    /** 富集：物料编码/名称/规格/单位 + 当前库存(仅数量) */
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
}
