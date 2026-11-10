package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_recv_line")
public class BizRecvLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recvId;
    private Integer lineNo;
    private Long sourcePoLineId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    /**
     * 含税金额(2026-10-18 起入库时存下,计算审查 U32):按来源订单行含税额从"剩余"里分,本次收完订单行时拿走全部剩余,
     * 分批入库后含税额加起来 = 订单含税额,税额 = 含税 − 不含税。以前不存、每次查询按比例临时算,分批时税额会出现 −0.01。
     * 对只看数量的岗位,详情接口照样置空(RecvController#hideCost)。
     */
    private BigDecimal amountInclTax;
    private BigDecimal invoicedQty;
    /** 已退供应商数量：退掉的这部分不再等发票，不计入暂估未开票余量 */
    private BigDecimal returnedQty;
    private String remark;

    // 瞬态(detail 富集，按来源采购订单税率推算，用于"不含税+含税"同单展示)；amount/unitPrice 为不含税
    @TableField(exist = false) private BigDecimal taxRate;        // 税率%
    @TableField(exist = false) private BigDecimal taxAmount;      // 税额
}
