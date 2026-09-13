package com.example.controller;

import com.example.common.ApiResponse;
import com.example.common.Const;
import com.example.dto.auth.Request.LoginRequest;
import com.example.dto.auth.Response.LoginResponse;
import com.example.dto.auth.Request.RegisterRequest;
import com.example.dto.auth.Response.UserProfileResponse;
import com.example.entity.UserEntity;
import com.example.exception.UnauthorizedException;
import com.example.service.auth.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户注册接口。 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {
    @Resource
    private AuthService authService;

    /** 创建用户账号，不在注册成功时自动登录。 */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserProfileResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.register(request)));
    }

    /** 使用用户名或邮箱登录并签发 Bearer JWT。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    /** 获取当前 JWT 对应的用户摘要。 */
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("请先登录");
        }
        UserEntity user = authService.findById(userId);
        if (user == null) {
            throw new UnauthorizedException("当前登录用户不存在");
        }
        return ApiResponse.success(authService.toProfile(user));
    }
}
