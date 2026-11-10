package com.group.scm.modules.master.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("md_supplier")
public class MdSupplier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String shortName;
    private Long orgId;
    private String orgName;
    private Integer isShared;
    private String mnemonicCode;
    private String supplierCategory;
    private String regionCategory;
    private Integer isCustomer;
    private String supplierType;
    private String financeOrg;
    private String taxNo;
    private String bankName;
    private String bankAccount;
    private String contactName;
    private String contactPhone;
    private String address;
    private String payTerm;
    private BigDecimal defaultTax;
    private Integer status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableLogic
    private Integer deleted;
    /** 已授权的组织名称列表（非数据库字段） */
    @TableField(exist = false)
    private List<String> scopeOrgs;
}
