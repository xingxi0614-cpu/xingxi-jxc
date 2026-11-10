package com.group.scm.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("wf_instance")
public class WfInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private Long bizId;
    private String bizNo;
    /** PROCESSING / APPROVED / REJECTED / CANCELLED */
    private String status;
    private String currentNode;
    private Long nextApproverId;
    private String nextApproverRole;
    private Long templateId;
    private String nodesJson;
    private Long applicantId;
    private BigDecimal totalAmt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
