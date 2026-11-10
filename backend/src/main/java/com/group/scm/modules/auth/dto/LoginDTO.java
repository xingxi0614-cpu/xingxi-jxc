package com.group.scm.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginDTO {
    @NotBlank(message = "用户名必填")
    private String username;

    @NotBlank(message = "密码必填")
    private String password;

    /** 验证码（可选，未来扩展） */
    private String captcha;
    private String captchaKey;

    /** 强制登录：账号已有人在线时，确认后顶掉对方再登录 */
    private boolean force;
}
