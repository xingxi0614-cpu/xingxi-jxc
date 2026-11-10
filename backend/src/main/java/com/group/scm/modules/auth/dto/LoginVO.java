package com.group.scm.modules.auth.dto;

import lombok.Data;

@Data
public class LoginVO {
    private String token;
    private String tokenName;
    private Long userId;
    private String username;
    private String name;
    private long expiresIn;
}
