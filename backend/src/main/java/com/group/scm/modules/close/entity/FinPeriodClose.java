package com.group.scm.modules.close.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fin_period_close")
public class FinPeriodClose {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String periodMonth;
    private String status;
    private Integer issueCount;
    private Integer warningCount;
    private String checkResult;
    private String remark;
    private LocalDateTime checkedAt;
    private LocalDateTime closedAt;
    private Long closedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
