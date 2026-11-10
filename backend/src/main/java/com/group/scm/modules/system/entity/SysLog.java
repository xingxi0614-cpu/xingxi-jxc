package com.group.scm.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_log")
public class SysLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String module;
    private String docNo;
    private String ip;
    private String requestUri;
    private String requestBody;
    private String response;
    private Integer durationMs;
    private Integer success;
    private String errorMsg;
    private LocalDateTime createdAt;
}
