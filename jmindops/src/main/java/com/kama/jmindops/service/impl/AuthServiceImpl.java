package com.kama.jmindops.service.impl;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.AppUserMapper;
import com.kama.jmindops.model.entity.AppUser;
import com.kama.jmindops.model.request.LoginRequest;
import com.kama.jmindops.model.request.RegisterRequest;
import com.kama.jmindops.model.response.AuthResponse;
import com.kama.jmindops.security.AuthenticatedUser;
import com.kama.jmindops.security.JwtTokenService;
import com.kama.jmindops.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {
    private static final String USER_ROLE = "USER";

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    public AuthServiceImpl(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }


    @Override
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.getUsername());
        validatePassword(request.getPassword());
        if (appUserMapper.selectByUsername(username) != null) {
            throw new BizException(409, "用户名已存在");
        }

        AppUser user = AppUser.builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(USER_ROLE)
                .createdAt(LocalDateTime.now())
                .build();
        if (appUserMapper.insert(user) <= 0) {
            throw new BizException("注册失败，请稍后重试");
        }
        return toResponse(user);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.getUsername());
        AppUser user = appUserMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        return toResponse(user);
    }

    private AuthResponse toResponse(AppUser user) {
        AuthenticatedUser authenticatedUser = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
        return AuthResponse.builder()
                .accessToken(jwtTokenService.createToken(authenticatedUser))
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }

    private String normalizeUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_-]{3,32}")) {
            throw new BizException("用户名应为 3-32 位字母、数字、下划线或连字符");
        }
        return username;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException("密码至少 8 个字符且 UTF-8 编码后不能超过 72 字节");
        }
    }
}
