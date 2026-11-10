package com.group.scm.modules.outbound.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("biz_out_req_line")
public class BizOutReqLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long outReqId;
    private Integer lineNo;
    private Long materialId;
    private BigDecimal qty;
    /** 首次审批时的计划数量，用于后续修订留痕。 */
    private BigDecimal originalQty;
    /** 当前数量相对原计划的累计修订量。 */
    private BigDecimal revisedQty;
    private BigDecimal outQty;
    /** 退回待再领数量:选了「再领」的已审退料累计,已从 out_qty 扣回;算可出余量时要从已审出库行合计里减掉。 */
    private BigDecimal reissueQty;
    private String remark;
    private BigDecimal transferredQty;
    private BigDecimal closedQty;

    @TableField(exist = false)
    private BigDecimal lockedOutQty;

    /** 已审批退料数量；展示净出库使用 grossOutQty − returnQty，不使用额度进度 outQty。 */
    @TableField(exist = false)
    private BigDecimal returnQty;
    @TableField(exist = false)
    private BigDecimal grossOutQty;
    @TableField(exist = false)
    private BigDecimal netOutQty;
}
