package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 采购计划单（计划，不动库存）。SUBMITTED 待采购确认 → CONFIRMED 已确认(可打印) / REJECTED 已驳回。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("pp_request")
public class PpRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private Long linkId;
    private Long warehouseId;
    private String applicantName;
    private String dept;
    private String status;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String rejectBy;
    private LocalDateTime rejectAt;
    private String rejectReason;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private List<PpRequestLine> lines;
    @TableField(exist = false)
    private String orgName;
    @TableField(exist = false)
    private String warehouseName;
}
