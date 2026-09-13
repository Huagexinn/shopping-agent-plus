package com.example.service.auth.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dto.auth.Request.LoginRequest;
import com.example.dto.auth.Response.LoginResponse;
import com.example.dto.auth.Request.RegisterRequest;
import com.example.dto.auth.Response.UserProfileResponse;
import com.example.entity.UserEntity;
import com.example.exception.BusinessException;
import com.example.exception.UnauthorizedException;
import com.example.mapper.UserMapper;
import com.example.security.JwtService;
import com.example.service.auth.AuthService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/** 用户注册和账号查询服务实现。 */
@Service
public class AuthServiceImpl implements AuthService {
    private static final int USERNAME_EXISTS_CODE = 4002;
    private static final int EMAIL_EXISTS_CODE = 4003;

    @Resource
    private UserMapper userMapper;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private JwtService jwtService;

    /** 创建用户账号并保存 BCrypt 密码哈希。 */
    @Override
    @Transactional
    public UserProfileResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = normalizeEmail(request.getEmail());
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, username)) > 0) {
            throw new BusinessException(USERNAME_EXISTS_CODE, "用户名已存在");
        }
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, email)) > 0) {
            throw new BusinessException(EMAIL_EXISTS_CODE, "邮箱已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setDeleted(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(4004, "用户名或邮箱已存在");
        }
        return toProfile(user);
    }

    /** 校验登录凭据并签发 JWT，不区分账号不存在和密码错误。 */
    @Override
    public LoginResponse login(LoginRequest request) {
        UserEntity user = findByIdentifier(request.getIdentifier());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        return new LoginResponse(jwtService.generateToken(
                user.getId(),
                user.getUsername()), "Bearer",
                jwtService.getExpirationSeconds(), toProfile(user));
    }

    /** 按用户名或邮箱查找未逻辑删除的用户，供后续登录流程使用。 */
    @Override
    @Transactional(readOnly = true)
    public UserEntity findByIdentifier(String identifier) {
        String normalized = identifier.trim();
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, normalized)
                .or()
                .eq(UserEntity::getEmail, normalizeEmail(normalized)));
    }

    /** 按主键查找未逻辑删除的用户。 */
    @Override
    @Transactional(readOnly = true)
    public UserEntity findById(Long id) {
        return userMapper.selectById(id);
    }

    /** 将实体转换为不含密码字段的公开摘要。 */
    @Override
    public UserProfileResponse toProfile(UserEntity user) {
        return new UserProfileResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
