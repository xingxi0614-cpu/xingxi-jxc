package com.group.scm.modules.toolbox.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 工具箱任务。org_id 决定归属,所有查询都必须按它过滤。 */
@Data
@TableName("tool_task")
public class ToolTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归哪家公司。⚠️ 一律由后端从登录态推出,绝不接受前端传 */
    private Long orgId;

    private String toolCode;
    private String title;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    private String params;

    /** 上传的原始文件相对路径;生成完删文件并置空 */
    private String inputPath;

    /** 结果文件 JSON:[{name,file,size}] */
    private String outputFiles;

    /** 失败原因(大白话,不含堆栈/路径) */
    private String errorMsg;

    private Integer costMs;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
