package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** 公司级领料申请提交链接（免登录，URL 携带 token；绑定默认仓库）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("lr_link")
public class LrLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    /** LR=领料申请提交 SO=网点销售录单(2026-08-21);老数据默认 LR */
    private String linkType;
    private String token;
    private String label;
    private Long warehouseId;
    private Integer status;
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
    /** 列表富集 */
    @TableField(exist = false)
    private String orgName;
    @TableField(exist = false)
    private String warehouseName;
}
