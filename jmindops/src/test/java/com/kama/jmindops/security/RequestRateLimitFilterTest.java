package com.kama.jmindops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsChatRequestsAfterPerUserBudgetIsExhausted() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter(new ObjectMapper());
        AuthenticatedUser user = new AuthenticatedUser("user-1", "alice", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));

        for (int index = 0; index < 12; index++) {
            MockHttpServletResponse response = invoke(filter, "/api/chat-messages");
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = invoke(filter, "/api/chat-messages");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("请求过于频繁");
    }

    @Test
    void doesNotLimitUnrelatedReadRequests() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter(new ObjectMapper());
        for (int index = 0; index < 30; index++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void keepsSeparateLoginBudgetsForDifferentClientAddresses() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter(new ObjectMapper());

        for (int index = 0; index < 10; index++) {
            assertThat(invoke(filter, "/api/auth/login", "203.0.113.10", null).getStatus()).isEqualTo(200);
        }
        assertThat(invoke(filter, "/api/auth/login", "203.0.113.10", null).getStatus()).isEqualTo(429);
        assertThat(invoke(filter, "/api/auth/login", "203.0.113.11", null).getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresSpoofedForwardedHeaderInsideTheFilter() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter(new ObjectMapper());

        for (int index = 0; index < 10; index++) {
            String spoofedAddress = "198.51.100." + index;
            assertThat(invoke(filter, "/api/auth/login", "203.0.113.20", spoofedAddress).getStatus())
                    .isEqualTo(200);
        }
        assertThat(invoke(filter, "/api/auth/login", "203.0.113.20", "198.51.100.99").getStatus())
                .isEqualTo(429);
    }

    private MockHttpServletResponse invoke(RequestRateLimitFilter filter, String path) throws Exception {
        return invoke(filter, path, "127.0.0.1", null);
    }

    private MockHttpServletResponse invoke(
            RequestRateLimitFilter filter,
            String path,
            String remoteAddress,
            String forwardedFor
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
