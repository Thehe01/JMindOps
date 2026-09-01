package com.kama.jmindops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService jwtTokenService;
    private final com.kama.jmindops.mapper.AppUserMapper appUserMapper;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, com.kama.jmindops.mapper.AppUserMapper appUserMapper) {
        this.jwtTokenService = jwtTokenService;
        this.appUserMapper = appUserMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : null;
        if (token != null && !token.isBlank()) {
            try {
                AuthenticatedUser user = jwtTokenService.parse(token);
                String currentRole = user.role();
                
                // 为了防止 Token 期间被降权，在敏感操作（拥有 ADMIN 权限）或配置了需要强验证时查库
                if ("ADMIN".equals(currentRole)) {
                    com.kama.jmindops.model.entity.AppUser dbUser = appUserMapper.selectById(user.id());
                    if (dbUser == null || !"ADMIN".equals(dbUser.getRole())) {
                        throw new IllegalArgumentException("Admin privilege revoked");
                    }
                }

                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + currentRole))
                ));
            } catch (IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
