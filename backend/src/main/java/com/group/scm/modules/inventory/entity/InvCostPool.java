package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 公司成本池:每家公司 × 每种物料一本移动加权账(对应财务软件的"成本域")。
 * <p>按公司核算后它是存货金额的唯一权威;各仓 inv_balance 只以数量为准,
 * 其 amount/avg_price 是由本表按数量占比分摊出来的参考值(见 OrgCostEngine#reallocate)。</p>
 */
@Data
@TableName("inv_cost_pool")
public class InvCostPool {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal amount;
    private BigDecimal avgPrice;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
