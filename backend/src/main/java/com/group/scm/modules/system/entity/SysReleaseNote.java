package com.group.scm.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_release_note")
public class SysReleaseNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String versionNo;
    private String title;
    private String summary;
    private String content;
    private LocalDateTime publishedAt;
    private Integer popup;
    private Integer status;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
