package com.group.scm.modules.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    // Bug #1 修复：密码哈希只允许写入（新建/重置时反序列化入参），
    // 永远不随任何 API 响应序列化输出，避免数据模型/哈希泄露。
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String name;
    private String avatar;
    private String phone;
    private String email;
    private Long orgId;
    private Integer status;
    // Bug #1 修复：登录失败计数属内部字段，不对客户端暴露。
    @JsonIgnore
    private Integer failCount;
    // lockedUntil 保留输出：前端用户列表据此判断锁定状态并显示“解锁”按钮。
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
    @TableField(exist = false)
    private String orgName;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    // Bug #1 修复：逻辑删除标记属内部字段，不对客户端暴露。
    @JsonIgnore
    @TableLogic
    private Integer deleted;
    @TableField(exist = false)
    private List<Long> roleIds;
    @TableField(exist = false)
    private List<String> roleCodes;
}
