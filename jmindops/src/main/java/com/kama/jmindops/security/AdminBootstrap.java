package com.kama.jmindops.security;

import com.kama.jmindops.mapper.AppUserMapper;
import com.kama.jmindops.model.entity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class AdminBootstrap implements CommandLineRunner {
    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    public AdminBootstrap(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
    }


    @Value("${app.bootstrap-admin.username:}")
    private String username;

    @Value("${app.bootstrap-admin.password:}")
    private String password;

    @Override
    public void run(String... args) {
        if (!StringUtils.hasText(username)) return;
        if (password == null || password.length() < 8) {
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD must contain at least 8 characters");
        }
        if (appUserMapper.selectByUsername(username) != null) return;
        appUserMapper.insert(AppUser.builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role("ADMIN")
                .createdAt(LocalDateTime.now())
                .build());
    }
}
