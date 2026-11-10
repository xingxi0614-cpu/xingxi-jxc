package com.group.scm.modules.docrelation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("doc_relation")
public class DocRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String targetType;
    private Long targetId;
    private String targetNo;
    /** DOWN_PUSH 下推 / MATCH 核销 / OFFSET 冲销 / CANCEL 级联取消 */
    private String relationType;
    private Long sourceLineId;
    private Long targetLineId;
    private BigDecimal qty;
    private BigDecimal amount;
    private LocalDateTime createdAt;
    private Long createdBy;
}
