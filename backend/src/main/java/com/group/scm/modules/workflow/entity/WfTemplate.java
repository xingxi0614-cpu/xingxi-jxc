package com.group.scm.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wf_template")
public class WfTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private String name;
    private String approvalMode;
    /** SpEL 表达式 如 #totalAmt < 50000；为空表示无条件 */
    private String conditionExpr;
    /** JSON 数组：[{"node":"MATERIAL_ACCOUNTANT_REVIEW","approverRole":"material_accountant","name":"材料会计"}, ...] */
    private String nodesJson;
    private Integer status;
    private Integer sort;
}
