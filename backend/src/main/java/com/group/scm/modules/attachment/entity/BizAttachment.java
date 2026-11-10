package com.group.scm.modules.attachment.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/** 单据附件（文件在服务器附件目录，此处只存元数据；cosKey = 相对附件目录的路径）。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@TableName("biz_attachment")
public class BizAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private Long bizId;
    private Long orgId;
    private String fileName;
    private Long sizeBytes;
    private String mime;
    private String cosKey;
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
    @TableField(exist = false) private String createdByName;
}
