package com.group.scm.modules.auth.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeVO {
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String component;
    private String icon;
    private String permCode;
    private String type;
    private Integer sort;
    private List<MenuTreeVO> children = new ArrayList<>();
}
