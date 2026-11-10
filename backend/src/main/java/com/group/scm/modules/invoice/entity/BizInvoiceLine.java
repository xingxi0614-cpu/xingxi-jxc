package com.group.scm.modules.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("biz_invoice_line")
public class BizInvoiceLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private Integer lineNo;
    /** 关联的入库明细行ID，发票行就是匹配此入库行做核销 */
    private Long recvLineId;
    private Long materialId;
    private BigDecimal qty;
    /** 不含税单价。⚠️ 2026-08-05 起它是【显示/参考值】,金额不再由它乘出来(见 totalAmt) */
    private BigDecimal unitPrice;
    /** 不含税金额 —— 由 totalAmt 倒算:totalAmt ÷ (1+税率) */
    private BigDecimal amount;
    /**
     * 本行实际用掉的原入库不含税金额(2026-10-18,计算审查 U14/U22):审批过账时锁住入库行,按"剩余"分,
     * 价差 = 本行不含税金额 − 它;取消审批后发票不再是已审批,这笔用量自动不算,金额回到剩余里。
     */
    private BigDecimal recvAmtUsed;
    private BigDecimal taxRate;
    /** 税额 —— 由 totalAmt 倒算:totalAmt − amount(保证三者恒等,不会有分位差) */
    private BigDecimal taxAmount;

    /**
     * 价税合计(录入人照发票填,可手工改)。**这是本行金额的唯一准绳。**
     * 不落库(库里存的是拆开后的 amount + taxAmount),仅用于接收前端输入与回显。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private BigDecimal totalAmt;

    /** 系统按「数量×单价×(1+税率)」算出的价税合计,留痕对照用 */
    private BigDecimal calcTotalAmt;
    /** AUTO=系统算的;MANUAL=录入人手工改过。内控留痕,报表可查 */
    private String amtSource;
}
