package com.example.service.auth;

import com.example.dto.auth.Request.LoginRequest;
import com.example.dto.auth.Response.LoginResponse;
import com.example.dto.auth.Request.RegisterRequest;
import com.example.dto.auth.Response.UserProfileResponse;
import com.example.entity.UserEntity;

/** 用户注册和账号查询服务接口。 */
public interface AuthService {
    UserProfileResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    UserEntity findByIdentifier(String identifier);

    UserEntity findById(Long id);

    UserProfileResponse toProfile(UserEntity user);
}
