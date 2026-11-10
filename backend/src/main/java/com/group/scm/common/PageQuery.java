package com.group.scm.common;

import lombok.Data;

/**
 * 分页查询基类。所有分页 DTO 继承此类。
 */
@Data
public class PageQuery {
    private long current = 1;
    private long size = 20;
    private String orderBy;
    private String orderDir = "desc";
}
