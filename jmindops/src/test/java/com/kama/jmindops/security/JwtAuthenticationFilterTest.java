package com.kama.jmindops.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ignoresAccessTokenInQueryString() throws Exception {
        JwtTokenService tokenService = mock(JwtTokenService.class);
        com.kama.jmindops.mapper.AppUserMapper appUserMapper = mock(com.kama.jmindops.mapper.AppUserMapper.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService, appUserMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sse/connect/session-1");
        request.setParameter("access_token", "must-not-be-read");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verifyNoInteractions(tokenService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
