package com.group.scm.modules.master.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("md_material_cat")
public class MdMaterialCat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private Integer sort;
    private Integer status;
    @TableLogic
    private Integer deleted;
}
