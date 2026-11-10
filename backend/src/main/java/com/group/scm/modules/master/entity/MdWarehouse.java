package com.group.scm.modules.master.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("md_warehouse")
public class MdWarehouse {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String warehouseType;
    private Long orgId;
    private Long managerId;
    private String address;
    private Integer status;
    @TableLogic
    private Integer deleted;
}
