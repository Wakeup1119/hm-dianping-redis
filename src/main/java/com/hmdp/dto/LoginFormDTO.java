package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    //手机号登录
    private String phone;
    private String code;
    //密码登录
    private String password;
}
