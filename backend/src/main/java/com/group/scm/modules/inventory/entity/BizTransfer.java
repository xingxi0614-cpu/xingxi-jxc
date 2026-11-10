package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_transfer")
public class BizTransfer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    private Long orgId;
    private LocalDate transferDate;
    private String transferer;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    /** DRAFT/PENDING/APPROVED/REJECTED */
    private String status;
    private String remark;
    private Long sourceReqId;
    private String sourceReqNo;
    private Long handoverBy;
    private LocalDateTime handoverAt;
    private String handoverReceiver;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private List<BizTransferLine> lines;

    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮）。
     *  手机端详情页靠它决定要不要出「通过/退回」——不回填的话按钮永远不显示。 */
    @TableField(exist = false)
    private Boolean canApprove;
}
