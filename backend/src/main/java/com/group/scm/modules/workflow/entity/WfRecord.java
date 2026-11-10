package com.group.scm.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wf_record")
public class WfRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private String bizNo;
    private String node;
    private Long approverId;
    private String approverName;
    /** SUBMIT / APPROVE / REJECT / CANCEL / RECALL */
    private String action;
    private String comment;
    private LocalDateTime createdAt;
}
