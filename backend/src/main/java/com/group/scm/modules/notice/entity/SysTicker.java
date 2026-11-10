package com.group.scm.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 顶栏滚动通知(跑马灯)。只有系统管理员能发;all_orgs=1 发给全部公司(含以后新开的),否则看 sys_ticker_org。 */
@Data
@TableName("sys_ticker")
public class SysTicker {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    /** NORMAL / IMPORTANT */
    private String level;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer allOrgs;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private Long updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    /** 入参/管理列表:发给哪些公司(all_orgs=0 时有值) */
    @TableField(exist = false)
    private List<Long> orgIds;
    /** 管理列表展示:「全部公司」或公司名逗号拼 */
    @TableField(exist = false)
    private String orgNames;
    @TableField(exist = false)
    private String createdByName;
}
