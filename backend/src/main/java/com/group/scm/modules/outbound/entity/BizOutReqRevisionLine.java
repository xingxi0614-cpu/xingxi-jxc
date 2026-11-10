package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_out_req_revision_line")
public class BizOutReqRevisionLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long revisionId;
    /** 为空表示本次修订新增一行材料计划。 */
    private Long reqLineId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal oldQty;
    private BigDecimal newQty;
    private BigDecimal deltaQty;
    private String remark;
}
