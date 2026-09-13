package com.example.dto.auth.Request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户登录请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    @NotBlank(message = "用户名或邮箱不能为空")
    @Size(max = 255, message = "用户名或邮箱长度不能超过 255 个字符")
    private String identifier;

    @NotBlank(message = "密码不能为空")
    @Size(max = 72, message = "密码长度不能超过 72 个字符")
    private String password;
}
