package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 领料申请单（计划，永不动库存）。
 * 状态：SUBMITTED 待采购 → PURCHASE_OK 待仓管 → WAREHOUSE_OK 待财务 → APPROVED 已通过(已生成出库申请) / REJECTED 已驳回。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("lr_request")
public class LrRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private Long linkId;
    private Long warehouseId;
    private String applicantName;
    private Long pickerId;
    private String pickerName;
    private Long projectId;
    private String projectName;
    private String site;
    private String bizType;
    private String outType;
    private LocalDate applyDate;
    private String status;
    private String purchaseBy;
    private Long purchaseById;
    private LocalDateTime purchaseAt;
    private String warehouseBy;
    private LocalDateTime warehouseAt;
    private String financeBy;
    private LocalDateTime financeAt;
    private String rejectBy;
    private LocalDateTime rejectAt;
    private String rejectReason;
    private Long outReqId;
    private String outReqNo;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private List<LrRequestLine> lines;
    @TableField(exist = false)
    private String orgName;
    @TableField(exist = false)
    private String warehouseName;
}
