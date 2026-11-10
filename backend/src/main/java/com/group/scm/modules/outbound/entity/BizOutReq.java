package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("biz_out_req")
public class BizOutReq {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long applicantId;
    private Long deptId;
    private Long orgId;
    private Long warehouseId;
    private Long projectId;
    private String projectName;
    private String constructionUnit;
    private String outType;
    private String businessCategory;
    private String materialApplicant;
    private LocalDate applyDate;
    private String purpose;
    /** 特殊事项审批单据号（友空间表单编号，选填） */
    private String approvalDocNo;
    private BigDecimal totalQty;
    private BigDecimal outQty;
    /** DRAFT/PENDING/APPROVED/PARTIAL_OUT/OUT/CANCELLED */
    private String status;
    private String remark;
    private String fulfillmentMode;
    private LocalDate pickupDueDate;
    private Integer pickupExtensions;
    private Integer pickupNoticeStage;
    @TableField(exist=false)
    private BigDecimal transferredQty;
    @TableField(exist=false)
    private BigDecimal closedQty;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private java.util.List<BizOutReqLine> lines;
    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮） */
    @TableField(exist = false)
    private Boolean canApprove;

    /** 整单已审批退料数量(展示用),同 BizOutReqLine.returnQty。 */
    @TableField(exist = false)
    private BigDecimal returnQty;
    /** 真实累计已审批出库；outQty 仍为扣除再领回补的额度进度。 */
    @TableField(exist = false)
    private BigDecimal grossOutQty;
    @TableField(exist = false)
    private BigDecimal netOutQty;
}
