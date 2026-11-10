package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出入库流水。每发生一次库存变动写一条，可用于追溯和重算。
 */
@Data
@TableName("inv_transaction")
public class InvTransaction {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 精确指向被冲销流水；数据库唯一约束保证只能冲销一次。 */
    private Long reversalOfId;
    private String transNo;
    private Long materialId;
    private Long warehouseId;
    /** INIT/IN_ESTIMATE/IN_INVOICE/OUT/RETURN/TRANSFER_OUT/TRANSFER_IN/ADJUST/REVERSE */
    private String transType;
    private String bizDocType;
    private Long bizDocId;
    private String bizDocNo;
    private Long bizLineId;
    /** 正数=入库，负数=出库，0=纯调差 */
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private BigDecimal balQty;
    private BigDecimal balAmount;
    private BigDecimal balAvgPrice;
    /** WH=按仓核算 / ORG=按公司核算;NULL=切换前旧流水(按仓)。 */
    private String costScope;
    /** 按公司核算时:本次完成后【公司】结存数量/金额(bal_* 仍是本仓数量、本仓参考金额、公司单价)。 */
    private BigDecimal orgBalQty;
    private BigDecimal orgBalAmount;
    /** 其中尾差 = 金额 − 数量×单价;只在"出光取余额 / 进位封顶"时非空。 */
    private BigDecimal tailAmt;
    private String remark;
    private LocalDateTime createdAt;
    private Long createdBy;
}
