package com.group.scm.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_org")
public class SysOrg {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String type;
    private String approvalMode;
    private Integer sort;
    private Integer status;
    private String remark;
    @TableLogic
    private Integer deleted;
}
