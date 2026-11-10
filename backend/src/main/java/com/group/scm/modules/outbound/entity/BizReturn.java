package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_return")
public class BizReturn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long sourceOutId;
    private String sourceOutNo;
    private Long sourceReqId;
    private String sourceReqNo;
    private Long warehouseId;
    private Long deptId;
    private Long orgId;
    private Long projectId;
    private String projectName;
    private String constructionUnit;
    private String outType;
    private String businessCategory;
    private String returner;
    private LocalDate returnDate;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    /** DRAFT/PENDING/APPROVED/REJECTED */
    private String status;
    /** 退回后还要再领:1=审批时回补原出库申请可出数量;0=不再领(默认,与以前一致)。 */
    private Integer reissue;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 创建人(原始提交人)，自动填充，用于“谁提交谁删除” */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private List<BizReturnLine> lines;
}
