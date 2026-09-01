# 08 - 第 7 步：JWT 安全鉴权与 RESTful API 暴露

#JMindOps #SpringSecurity #JWT #RESTful #IDOR

> [!NOTE] 
> 目标：构建无状态 JWT 鉴权体系，配置 Spring Security 过滤器链，实现租户级防水平越权服务（ResourceAccessService），规范 RESTful API 矩阵。

---

## 1. Spring Security 核心配置 (`SecurityConfig.java`)

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                // 1. 禁用 CSRF（前后端分离 JWT 架构天然免疫 CSRF）
                .csrf(csrf -> csrf.disable())
                // 2. 设为 STATELESS 无状态会话，不使用 HttpSession
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 3. 白名单放行与接口权限控制
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                // 4. 自定义 401 和 403 的 JSON 返回（避免默认重定向到 HTML 登录页）
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, resp, ex) -> {
                            resp.setStatus(401);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(resp.getWriter(), ApiResponse.error(401, "未登录或登录已过期"));
                        })
                        .accessDeniedHandler((req, resp, ex) -> {
                            resp.setStatus(403);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(resp.getWriter(), ApiResponse.error(403, "无权访问该资源"));
                        }))
                // 5. 挂载 JWT 过滤器在最前列
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
```

---

## 2. 租户防水平越权 (`ResourceAccessService.java`)

防止用户 A 通过在 URL 替换 `sessionId` 或 `agentId` 偷看其他用户的数据：

```java
@Service
@RequiredArgsConstructor
public class ResourceAccessService {
    private final AgentMapper agentMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BizException(401, "未登录");
        }
        return user.id();
    }

    public Agent requireOwnedAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || !currentUserId().equals(agent.getOwnerId())) {
            throw new BizException(403, "无权访问该智能体");
        }
        return agent;
    }

    public ChatSession requireOwnedChatSession(String sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !currentUserId().equals(session.getOwnerId())) {
            throw new BizException(403, "无权访问该聊天会话");
        }
        return session;
    }
}
```

---

## 3. RESTful API 接口矩阵

| 模块 | Method | Path | 描述 |
| :--- | :--- | :--- | :--- |
| **Auth** | `POST` | `/api/auth/register` | 用户注册 |
| **Auth** | `POST` | `/api/auth/login` | 用户登录（返回 JWT Token） |
| **Agent** | `POST` | `/api/agents` | 创建自定义智能体 |
| **Session**| `POST` | `/api/chat-sessions` | 创建聊天会话 |
| **Message**| `POST` | `/api/chat-messages` | 发送用户消息（异步触发 Agent 推理） |
| **Approval**|`POST`| `/api/approvals/{id}/decide` | 人机协同工具审批（批准/拒绝） |
| **SSE** | `GET` | `/sse/connect/{sessionId}` | 建立流式长连接通道 |
