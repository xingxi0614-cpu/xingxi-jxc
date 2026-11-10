package com.group.scm.modules.master.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("md_material")
public class MdMaterial {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String spec;
    private String unit;
    private Long catId;
    private String materialType;
    private Long orgId;
    private Integer isShared;
    private BigDecimal defaultTax;
    private Integer isBatch;
    private BigDecimal safetyQty;
    private BigDecimal maxQty;
    private Integer status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    /** 已授权的组织名称列表（非数据库字段） */
    @TableField(exist = false)
    private List<String> scopeOrgs;
}
