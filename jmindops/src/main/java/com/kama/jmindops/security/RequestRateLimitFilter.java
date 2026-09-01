package com.kama.jmindops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.model.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面向单实例部署的轻量限流兜底。生产多实例部署时应替换为网关/Redis 限流。
 */
public class RequestRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final long STALE_AFTER_MILLIS = Duration.ofMinutes(5).toMillis();

    // 使用有界 LRU 缓存防止内存撑爆。生产环境更推荐使用 Caffeine。
    private final Map<String, WindowCounter> counters = Collections.synchronizedMap(
            new LinkedHashMap<String, WindowCounter>(1000, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WindowCounter> eldest) {
                    return size() > 50000;
                }
            }
    );
    private final AtomicLong requestCount = new AtomicLong();
    private final ObjectMapper objectMapper;

    public RequestRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        LimitRule rule = resolveRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        String key = rule.name + ':' + identity(request);

        WindowCounter counter = counters.computeIfAbsent(key, ignored -> new WindowCounter(now));
        if (!counter.tryAcquire(now, rule.requestsPerMinute)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(429, "请求过于频繁，请稍后再试"));
            return;
        }

        if ((requestCount.incrementAndGet() & 1023) == 0) {
            // 定期清理过期 key 以保持良好状态
            counters.entrySet().removeIf(entry -> entry.getValue().isStale(now));
        }
        filterChain.doFilter(request, response);
    }

    private LimitRule resolveRule(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) && !isSseConnect(request)) {
            return null;
        }
        String path = request.getRequestURI();
        return switch (path) {
            case "/api/auth/register" -> new LimitRule("register", 5);
            case "/api/auth/login" -> new LimitRule("login", 10);
            case "/api/chat-messages" -> new LimitRule("chat", 12);
            case "/api/documents/upload" -> new LimitRule("upload", 6);
            default -> isSseConnect(request) ? new LimitRule("sse", 20) : null;
        };
    }

    private boolean isSseConnect(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/sse/connect/");
    }

    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.id();
        }
        // Tomcat RemoteIpValve 仅为受信任内部代理改写 remoteAddr；直连请求不会信任调用方伪造的转发头。
        return "ip:" + request.getRemoteAddr();
    }

    private record LimitRule(String name, int requestsPerMinute) {
    }

    private static final class WindowCounter {
        private long windowStartedAt;
        private int requests;
        private long lastSeenAt;

        private WindowCounter(long now) {
            this.windowStartedAt = now;
            this.lastSeenAt = now;
        }

        private synchronized boolean tryAcquire(long now, int limit) {
            if (now - windowStartedAt >= WINDOW_MILLIS) {
                windowStartedAt = now;
                requests = 0;
            }
            lastSeenAt = now;
            if (requests >= limit) {
                return false;
            }
            requests++;
            return true;
        }

        private synchronized boolean isStale(long now) {
            return now - lastSeenAt >= STALE_AFTER_MILLIS;
        }
    }
}
