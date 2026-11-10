package com.group.scm.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存余额（实时）。
 * <p>使用乐观锁 version 字段保护并发更新。</p>
 */
@Data
@TableName("inv_balance")
public class InvBalance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long materialId;
    private Long warehouseId;
    private BigDecimal qty;
    private BigDecimal amount;
    private BigDecimal avgPrice;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
