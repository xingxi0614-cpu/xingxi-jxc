package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_out")
public class BizOut {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
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
    private String materialReceiver;
    private LocalDate outDate;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    /** DRAFT/PENDING/APPROVED/CANCELLED */
    private String status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 创建人(原始提交人)，由 MetaObjectHandler 自动填充，用于“谁提交谁删除” */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private java.util.List<BizOutLine> lines;
    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮） */
    @TableField(exist = false)
    private Boolean canApprove;
    /**
     * 来源出库申请上的「用途」。biz_out 本身没有这一列,查询时从申请单读出来,
     * 见 OutService#fillFromSourceReq(为什么读而不复制,理由写在那里)。
     */
    @TableField(exist = false)
    private String purpose;
    /** 来源出库申请上的「备注」。出库单自己的 remark 一直没有录入口,仓管要看的是申请上这一段。 */
    @TableField(exist = false)
    private String reqRemark;
}
