package com.group.scm.modules.userpref.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户个人偏好(看板布局/模块收藏/最近打开)。严格私有,只能读写自己的。 */
@Data
@TableName("sys_user_pref")
public class SysUserPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归谁。⚠️ 一律从登录态取,绝不接受前端传值 */
    private Long userId;

    /** 偏好键,如 fav.pages、query.presets:/页面路径 */
    private String prefKey;

    /** JSON 文本,后端不解析内容 */
    private String prefValue;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
