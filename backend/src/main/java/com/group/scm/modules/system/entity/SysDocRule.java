package com.group.scm.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("sys_doc_rule")
public class SysDocRule {
    @TableId
    private String docType;
    private String prefix;
    private String pattern;
    private Long currentNo;
    private String resetFreq;   // YEAR / MONTH / NEVER
    private LocalDate lastReset;
}
