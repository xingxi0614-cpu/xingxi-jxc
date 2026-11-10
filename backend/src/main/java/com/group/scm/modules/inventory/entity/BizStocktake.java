package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 盘点单（建单快照账面数→线下清点→录实盘→财务确认→自动盘盈/盘亏调整）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_stocktake")
public class BizStocktake {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docNo;
    private Long orgId;
    private Long warehouseId;
    private LocalDate takeDate;
    /** DRAFT/SUBMITTED/APPROVED/REJECTED */
    private String status;
    private Integer lineCnt;
    private BigDecimal gainAmt;
    private BigDecimal lossAmt;
    private String financeBy;
    private LocalDateTime financeAt;
    private String rejectReason;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableLogic
    private Integer deleted;
    // 瞬态富集
    @TableField(exist = false) private String warehouseName;
    @TableField(exist = false) private String orgName;
    @TableField(exist = false) private String createdByName;
    @TableField(exist = false) private java.util.List<BizStocktakeLine> lines;
}
