package com.group.scm.modules.materialreq.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** 领料人档案（账套管理员维护，按公司/账套隔离）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("lr_picker")
public class LrPicker {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String name;
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
    /** 列表富集：公司名 */
    @TableField(exist = false)
    private String orgName;
}
