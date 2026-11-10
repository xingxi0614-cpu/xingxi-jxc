package com.group.scm.modules.auth.dto;

import lombok.Data;

@Data
public class ProfileUpdateDTO {
    private String name;
    private String phone;
    private String email;
    private String avatar;
}
