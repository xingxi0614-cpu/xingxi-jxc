package com.group.scm.modules.notif.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notif")
public class Notif {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String roleCode;
    /** 业务单据所属账套/组织，按角色广播时用于账套隔离 */
    private Long orgId;
    private String type;
    /**
     * 待办性质:APPROVAL=等我审批/确认,REWORK=退回要我改。NULL=升级前的存量数据。
     *
     * 工作台「待办审批」Tab 按这个字段筛。原来靠标题文字正则猜,
     * 「领料待仓管确认」「采购计划待确认」「盘点待确认」全被漏掉(2026-07-31 审计)。
     * 新增推待办的地方【必须】明确传 kind,别让它落成 NULL。
     */
    private String todoKind;
    private String title;
    private String content;
    private String bizType;
    private Long bizId;
    private String bizNo;
    private Integer isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
