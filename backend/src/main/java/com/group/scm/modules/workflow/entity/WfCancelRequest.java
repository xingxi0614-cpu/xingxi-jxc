package com.group.scm.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wf_cancel_request")
public class WfCancelRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private Long bizId;
    private String bizNo;
    private Long applicantId;
    private Long originApproverId;
    private String reason;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private String approverComment;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
}
