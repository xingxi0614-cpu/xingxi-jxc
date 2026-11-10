package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购退货单明细。
 *
 * <p>录单时只能确定 {@code recvUnitPrice / recvAmt}（原入库价）；
 * {@code stockUnitPrice / stockAmt / diffAmt} 要等审批过账时才知道 ——
 * 库存出库走的是【过账那一刻】的移动加权均价，草稿期间均价还会被别的单据改动。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_purchase_return_line")
public class BizPurchaseReturnLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long returnId;
    private Integer lineNo;
    private Long sourceRecvLineId;
    private Long sourcePoLineId;
    private Long materialId;
    private BigDecimal qty;
    /** 原入库单价（不含税） */
    private BigDecimal recvUnitPrice;
    /** 原入库不含税额，按退货数量从入库行金额等比折算 */
    private BigDecimal recvAmt;
    /** 实际出库单价（过账时的加权均价），审批时回填 */
    private BigDecimal stockUnitPrice;
    /** 实际库存冲减额，审批时回填 */
    private BigDecimal stockAmt;
    /** 本行混批差额 = stockAmt - recvAmt */
    private BigDecimal diffAmt;
    private String remark;

    /** 下推选行时展示用（不落库） */
    @TableField(exist = false)
    private String materialCode;
    @TableField(exist = false)
    private String materialName;
    @TableField(exist = false)
    private String materialSpec;
    @TableField(exist = false)
    private String unit;
    /** 该入库行可退数量 = qty - invoiced_qty - returned_qty，再受当前库存约束 */
    @TableField(exist = false)
    private BigDecimal returnableQty;
    @TableField(exist = false)
    private BigDecimal stockQty;
}
