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
@TableName("biz_out_req_revision")
public class BizOutReqRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long sourceReqId;
    private String sourceReqNo;
    private Long orgId;
    private Long warehouseId;
    private Long projectId;
    private String projectName;
    private LocalDate revisionDate;
    private String reason;
    private BigDecimal totalDeltaQty;
    /** DRAFT/PENDING/APPROVED/REJECTED */
    private String status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private List<BizOutReqRevisionLine> lines;

    @TableField(exist = false)
    private Boolean canApprove;
}
