package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购退货单头：把暂估／发票入库的货整行退回供应商。
 *
 * <p>三个金额字段必须同屏列示，缺一不可 —— 本系统不出记账凭证，财务拿这三个数去外部账务系统做分录：</p>
 * <ul>
 *   <li>{@code totalStockAmt} 库存冲减额（按移动加权均价，实际从库存拿走的钱）</li>
 *   <li>{@code totalRecvAmt}  原入库不含税额（红字发票、冲暂估的钱）</li>
 *   <li>{@code totalDiffAmt}  混批差额，进当期损益</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_purchase_return")
public class BizPurchaseReturn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long sourceRecvId;
    private String sourceRecvNo;
    private Long sourcePoId;
    private String sourcePoNo;
    /** 来源入库类型 ESTIMATE=暂估 INVOICE=发票入库 */
    private String recvType;
    private Long supplierId;
    private Long warehouseId;
    private Long orgId;
    /** 业务日期：填实物实际拉走那天，不是录单那天 */
    private LocalDate returnDate;
    private String returner;
    private String reason;
    private BigDecimal totalQty;
    private BigDecimal totalStockAmt;
    private BigDecimal totalRecvAmt;
    private BigDecimal totalDiffAmt;
    /** DRAFT/PENDING/APPROVED/REJECTED */
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
    private List<BizPurchaseReturnLine> lines;
    /** 列表展示用：供应商名、仓库名 */
    @TableField(exist = false)
    private String supplierName;
    @TableField(exist = false)
    private String warehouseName;
    @TableField(exist = false)
    private String materialSummary;
    /** 当前登录人能否审批这张单（列表页按钮门控用） */
    @TableField(exist = false)
    private Boolean canApprove;
}
