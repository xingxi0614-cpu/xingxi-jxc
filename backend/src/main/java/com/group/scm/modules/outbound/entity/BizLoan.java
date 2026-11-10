package com.group.scm.modules.outbound.entity;

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
@TableName("biz_loan")
public class BizLoan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long warehouseId;
    private Long deptId;
    private Long orgId;
    private Long projectId;
    private String projectName;
    private String borrower;
    private String borrowerDept;
    private LocalDate borrowDate;
    private LocalDate expectedReturnDate;
    private String purpose;
    private BigDecimal totalQty;
    private BigDecimal returnedQty;
    private BigDecimal totalAmt;
    /** REGISTERED/CLOSED */
    private String status;
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
    private List<BizLoanLine> lines;
    /** 历次归还记录(biz_loan_return,2026-10-18 起才有;之前的归还没存日期) */
    @TableField(exist = false)
    private List<java.util.Map<String, Object>> returns;

    /** 当前登录用户是否可审批本单据的当前节点（前端据此显示审批/驳回按钮）。
     *  手机端详情页靠它决定要不要出「通过/退回」——不回填的话按钮永远不显示。 */
    @TableField(exist = false)
    private Boolean canApprove;
}
