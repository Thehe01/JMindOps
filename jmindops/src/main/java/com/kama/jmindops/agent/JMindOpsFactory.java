package com.kama.jmindops.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.config.ChatClientRegistry;
import com.kama.jmindops.converter.AgentConverter;
import com.kama.jmindops.converter.ChatMessageConverter;
import com.kama.jmindops.converter.KnowledgeBaseConverter;
import com.kama.jmindops.mapper.KnowledgeBaseMapper;
import com.kama.jmindops.governance.ToolGovernanceCallback;
import com.kama.jmindops.governance.ToolGovernanceService;
import com.kama.jmindops.model.dto.AgentDTO;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
import com.kama.jmindops.model.entity.Agent;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.KnowledgeBase;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.ChatMessageFacadeService;
import com.kama.jmindops.service.SseService;
import com.kama.jmindops.service.AgentTraceStore;
import com.kama.jmindops.service.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JMindOpsFactory {

    private static final Logger log = LoggerFactory.getLogger(JMindOpsFactory.class);
    private static final String KNOWLEDGE_TOOL_NAME = "KnowledgeTool";
    private static final String TERMINATE_TOOL_NAME = "terminate";
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ApplicationEventPublisher eventPublisher;
    private final ResourceAccessService resourceAccessService;
    private final AgentTraceStore agentTraceStore;
    private final ToolGovernanceService toolGovernanceService;
    private final ExternalToolRegistry externalToolRegistry;
    private final Duration llmStreamTimeout;
    private final com.kama.jmindops.service.AgentCheckpointStore checkpointStore;
    private final com.kama.jmindops.agent.tools.ToolIdempotencyResolver toolIdempotencyResolver;

    public JMindOpsFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            ApplicationEventPublisher eventPublisher,
            ResourceAccessService resourceAccessService,
            AgentTraceStore agentTraceStore,
            ToolGovernanceService toolGovernanceService,
            ExternalToolRegistry externalToolRegistry,
            @Value("${app.agent.llm-stream-timeout-seconds:120}") long llmStreamTimeoutSeconds
    ) {
        this(chatClientRegistry, sseService, agentConverter, knowledgeBaseMapper, knowledgeBaseConverter,
                toolFacadeService, chatMessageFacadeService, chatMessageConverter, eventPublisher,
                resourceAccessService, agentTraceStore, toolGovernanceService, externalToolRegistry,
                llmStreamTimeoutSeconds, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JMindOpsFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            ApplicationEventPublisher eventPublisher,
            ResourceAccessService resourceAccessService,
            AgentTraceStore agentTraceStore,
            ToolGovernanceService toolGovernanceService,
            ExternalToolRegistry externalToolRegistry,
            @Value("${app.agent.llm-stream-timeout-seconds:120}") long llmStreamTimeoutSeconds,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.kama.jmindops.service.AgentCheckpointStore checkpointStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.kama.jmindops.agent.tools.ToolIdempotencyResolver toolIdempotencyResolver
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.eventPublisher = eventPublisher;
        this.resourceAccessService = resourceAccessService;
        this.agentTraceStore = agentTraceStore;
        this.toolGovernanceService = toolGovernanceService;
        this.externalToolRegistry = externalToolRegistry;
        this.checkpointStore = checkpointStore;
        this.toolIdempotencyResolver = toolIdempotencyResolver;
        if (llmStreamTimeoutSeconds < 1 || llmStreamTimeoutSeconds > 1800) {
            throw new IllegalArgumentException("app.agent.llm-stream-timeout-seconds 必须在 1 到 1800 之间");
        }
        this.llmStreamTimeout = Duration.ofSeconds(llmStreamTimeoutSeconds);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId, AgentDTO agentConfig) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata()
                                    .getToolCalls())
                            .build());
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO
                                    .getMetadata()
                                    .getToolResponse()))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        String currentUserId = resourceAccessService.currentUserId();
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds)
                .stream()
                .filter(knowledgeBase -> currentUserId.equals(knowledgeBase.getOwnerId()))
                .toList();
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    List<Tool> resolveRuntimeTools(AgentDTO agentConfig, RoutingDecision decision) {
        boolean hasAuthorizedKnowledgeBase = agentConfig.getAllowedKbs() != null
                && !agentConfig.getAllowedKbs().isEmpty();
        boolean exposeKnowledgeTool = decision == RoutingDecision.RAG && hasAuthorizedKnowledgeBase;

        // KnowledgeTool 虽是系统内置工具，也只能在 RAG 且存在授权知识库时暴露。
        List<Tool> runtimeTools = toolFacadeService.getFixedTools().stream()
                // Agent 已以“无工具调用”作为正常终止条件；暴露 terminate 只会增加空转步骤，
                // 并可能让拒绝危险操作的回答在输出前提前结束。
                .filter(tool -> !TERMINATE_TOOL_NAME.equals(tool.getName()))
                .filter(tool -> !KNOWLEDGE_TOOL_NAME.equals(tool.getName()) || exposeKnowledgeTool)
                .collect(Collectors.toCollection(ArrayList::new));

        // 可选工具（按 Agent 配置）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(
            List<Tool> runtimeTools,
            RoutingDecision decision,
            Set<String> explicitlyAllowedToolNames,
            ToolAccessPolicy.Decision accessDecision
    ) {
        List<ToolCallback> callbacks = new ArrayList<>();
        // 1. 添加 Java 内部工具
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.stream(toolCallbacks)
                    .map(callback -> ToolGovernanceCallback.wrapIfRequired(
                            target, callback, toolGovernanceService))
                    .filter(callback -> accessDecision.allowsCallback(
                            callback.getToolDefinition().name()))
                    .toList());
        }

        // 2. 如果决策需要外部工具交互，则挂载 MCP 外部工具
        if (decision == RoutingDecision.MCP && externalToolRegistry != null) {
            for (org.springframework.ai.tool.ToolCallbackProvider provider : externalToolRegistry.providers()) {
                ToolCallback[] mcpCallbacks = provider.getToolCallbacks();
                if (mcpCallbacks != null) {
                    List<ToolCallback> allowedCallbacks = Arrays.stream(mcpCallbacks)
                            .filter(callback -> !accessDecision.blockAllExternalCallbacks())
                            .filter(callback -> explicitlyAllowedToolNames.contains(
                                    callback.getToolDefinition().name()))
                            .filter(callback -> accessDecision.allowsCallback(
                                    callback.getToolDefinition().name()))
                            .map(callback -> ToolGovernanceCallback.wrapExternal(
                                    callback, toolGovernanceService))
                            .toList();
                    callbacks.addAll(allowedCallbacks);
                    log.info("[Multi-Agent MCP] provider={}, discovered={}, authorized={}",
                            provider.getClass().getSimpleName(), mcpCallbacks.length, allowedCallbacks.size());
                }
            }
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        // 必须保留 Spring 代理实例，才能让工具审批与审计切面在调用时生效。
        return tool;
    }

    private JMindOps buildAgentRuntime(
            Agent agent,
            AgentDTO agentConfig,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            String generationId,
            RoutingDecision decision,
            String userMessage,
            AgentExecutionPolicy.Plan executionPlan,
            String workerId,
            long leaseVersion
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JMindOps(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                agentConfig.getChatOptions().getTemperature(),
                agentConfig.getChatOptions().getTopP(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                generationId,
                eventPublisher,
                agentTraceStore,
                decision,
                userMessage,
                executionPlan,
                llmStreamTimeout,
                checkpointStore,
                toolIdempotencyResolver,
                workerId,
                leaseVersion
        );
    }

    void applyRoutingDecision(Agent agent, AgentDTO agentConfig, RoutingDecision decision) {
        log.info("[Multi-Agent Router] Applying decision: {} to Agent: {}", decision.name(), agent.getName());

        // 1. 动态覆盖人设
        String originalPrompt = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt();
        String routedPrompt = String.format("【专家角色：%s】\n角色设定：%s\n\n=== 基础设定 ===\n%s",
                decision.getRoleName(), decision.getDescription(), originalPrompt);
        agent.setSystemPrompt(routedPrompt);

        // 2. 动态过滤工具和知识库
        if (decision == RoutingDecision.CHAT) {
            // 闲聊模式，卸载所有工具和知识库，避免幻觉和浪费 Token
            agentConfig.setAllowedTools(Collections.emptyList());
            agentConfig.setAllowedKbs(Collections.emptyList());
        } else if (decision == RoutingDecision.WEATHER) {
            // 天气模式，仅保留名为 weatherTool 的工具（如果存在）
            List<String> tools = agentConfig.getAllowedTools();
            if (tools != null) {
                agentConfig.setAllowedTools(tools.stream().filter(t -> t.toLowerCase().contains("weather")).collect(Collectors.toList()));
            }
            agentConfig.setAllowedKbs(Collections.emptyList());
        } else if (decision == RoutingDecision.RAG) {
            // RAG 模式，仅保留知识库相关工具或允许知识库
            List<String> tools = agentConfig.getAllowedTools();
            if (tools != null) {
                agentConfig.setAllowedTools(tools.stream().filter(t -> t.toLowerCase().contains("document")).collect(Collectors.toList()));
            }
        } else if (decision == RoutingDecision.MCP) {
            // MCP 同时支持显式授权的 Java 工具与外部 MCP 工具，但不携带知识库。
            agentConfig.setAllowedKbs(Collections.emptyList());
        }
    }

    /**
     * 创建一个 JMindOps 实例
     */
    public JMindOps create(String agentId, String chatSessionId, RoutingDecision decision) {
        return create(agentId, chatSessionId, decision, UUID.randomUUID().toString());
    }

    public JMindOps create(String agentId, String chatSessionId, RoutingDecision decision, String generationId) {
        return create(agentId, chatSessionId, decision, generationId, null);
    }

    public JMindOps create(
            String agentId,
            String chatSessionId,
            RoutingDecision decision,
            String generationId,
            String userMessage
    ) {
        return create(agentId, chatSessionId, decision, generationId, userMessage, "default-worker", 1L);
    }

    public JMindOps create(
            String agentId,
            String chatSessionId,
            RoutingDecision decision,
            String generationId,
            String userMessage,
            String workerId,
            long leaseVersion
    ) {
        Agent agent = resourceAccessService.requireOwnedAgent(agentId);
        ChatSession chatSession = resourceAccessService.requireOwnedChatSession(chatSessionId);
        if (!Objects.equals(chatSession.getAgentId(), agentId)) {
            throw new BizException("智能体与聊天会话不匹配");
        }
        AgentDTO agentConfig = toAgentConfig(agent);
        validateAndDefaultChatOptions(agentConfig);

        Set<String> explicitlyAllowedToolNames = agentConfig.getAllowedTools() == null
                ? Collections.emptySet()
                : agentConfig.getAllowedTools().stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());

        // 【新增】应用路由决策，动态调整 Agent 配置
        applyRoutingDecision(agent, agentConfig, decision);
        ToolAccessPolicy.Decision accessDecision = ToolAccessPolicy.evaluate(decision, userMessage);
        AgentExecutionPolicy.Plan executionPlan = AgentExecutionPolicy.plan(decision, userMessage);
        if (accessDecision.blocked() && !accessDecision.allowReadOnlyFileSystem()) {
            // 安全策略已撤销工具时，不再保留一个永远无法完成的强制工具计划。
            executionPlan = AgentExecutionPolicy.Plan.none();
        }
        String executionInstruction = AgentExecutionPolicy.instruction(decision, userMessage);
        if (StringUtils.hasText(executionInstruction)) {
            agent.setSystemPrompt(agent.getSystemPrompt()
                    + "\n\n【本轮执行计划约束】\n"
                    + executionInstruction);
            log.info("[Agent Execution Policy] Applied deterministic planning guidance: route={}", decision);
        }
        if (accessDecision.allowReadOnlyFileSystem()
                && explicitlyAllowedToolNames.contains("fileSystemTool")) {
            List<String> routedTools = new ArrayList<>(Optional.ofNullable(agentConfig.getAllowedTools())
                    .orElseGet(Collections::emptyList));
            if (!routedTools.contains("fileSystemTool")) {
                routedTools.add("fileSystemTool");
            }
            agentConfig.setAllowedTools(routedTools);
        }
        if (accessDecision.blocked()) {
            agent.setSystemPrompt(agent.getSystemPrompt()
                    + "\n\n【本轮安全策略】\n"
                    + accessDecision.instruction()
                    + "不得尝试改用其他工具绕过限制，也不得声称操作已经执行。请明确拒绝危险部分并给出安全替代建议。");
            log.warn("[Agent Tool Policy] External tools withheld for current request: route={}, blockedBeans={}, blockedCallbacks={}",
                    decision, accessDecision.blockedToolBeans(), accessDecision.blockedCallbacks());
        }

        List<Message> memory = loadMemory(chatSessionId, agentConfig);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig, decision).stream()
                .filter(tool -> accessDecision.allowsToolBean(tool.getName()))
                .toList();
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(
                runtimeTools, decision, explicitlyAllowedToolNames, accessDecision);

        return buildAgentRuntime(
                agent,
                agentConfig,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId,
                generationId,
                decision,
                userMessage,
                executionPlan,
                workerId,
                leaseVersion
        );
    }

    public JMindOps createForResume(
            com.kama.jmindops.model.entity.GenerationTask task,
            String workerId,
            long leaseVersion
    ) {
        RoutingDecision decision = RoutingDecision.CHAT;
        if (checkpointStore != null) {
            java.util.Optional<com.kama.jmindops.model.entity.AgentCheckpoint> latestOpt =
                    checkpointStore.findLatestCheckpoint(task.id());
            if (latestOpt.isPresent()) {
                com.kama.jmindops.agent.checkpoint.CheckpointPayload.CheckpointRuntimeState rs =
                        com.kama.jmindops.agent.checkpoint.CheckpointPayload.deserializeRuntimeState(latestOpt.get().runtimeState());
                if (rs.routingDecision() != null) {
                    try {
                        decision = RoutingDecision.valueOf(rs.routingDecision());
                    } catch (Exception ignored) {}
                }
            }
        }
        return create(task.agentId(), task.sessionId(), decision, task.id(), task.inputContent(), workerId, leaseVersion);
    }

    private void validateAndDefaultChatOptions(AgentDTO agentConfig) {
        AgentDTO.ChatOptions options = agentConfig.getChatOptions();
        if (options == null) {
            options = AgentDTO.ChatOptions.defaultOptions();
            agentConfig.setChatOptions(options);
        }

        Double temperature = options.getTemperature();
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new BizException("Agent temperature 必须在 0 到 2 之间");
        }
        Double topP = options.getTopP();
        if (topP != null && (topP <= 0.0 || topP > 1.0)) {
            throw new BizException("Agent topP 必须大于 0 且不超过 1");
        }
        Integer messageLength = options.getMessageLength();
        if (messageLength == null) {
            options.setMessageLength(AgentDTO.ChatOptions.defaultOptions().getMessageLength());
        } else if (messageLength < 1 || messageLength > 100) {
            throw new BizException("Agent 消息窗口长度必须在 1 到 100 之间");
        }
    }
}
