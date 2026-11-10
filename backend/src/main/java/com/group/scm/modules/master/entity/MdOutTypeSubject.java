package com.group.scm.modules.master.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 出库类型 → 会计科目 映射(集团统一口径,后台可维护)。 */
@Data
@TableName("md_out_type_subject")
public class MdOutTypeSubject {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 适用范围:通用/企业自定义 */
    private String category;
    /** 出库类型 */
    private String outType;
    /** 对应总账借方科目(全路径,反斜杠分层) */
    private String subject;
    private Integer sort;
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
}
