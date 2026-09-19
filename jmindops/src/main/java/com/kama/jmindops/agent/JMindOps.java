package com.kama.jmindops.agent;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.event.AgentMessageGeneratedEvent;
import com.kama.jmindops.governance.ToolExecutionContext;
import com.kama.jmindops.governance.ToolApprovalSignal;
import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
import com.kama.jmindops.service.AgentTraceStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class JMindOps {
    private static final Logger log = LoggerFactory.getLogger(JMindOps.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String KNOWLEDGE_TOOL_NAME = "KnowledgeTool";

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final Duration DEFAULT_LLM_STREAM_TIMEOUT = Duration.ofSeconds(120);
    // 单次生成最大累计 Token 熔断阈值（防死循环消耗海量 Token）
    private static final long MAX_CUMULATIVE_TOKENS = 64_000L;
    private long cumulativeTokens = 0L;

    // 智能体 ID
    private String agentId;
    // 名称
    private String name;
    // 描述
    private String description;
    // 默认系统提示词
    private String systemPrompt;
    // 交互实例
    private ChatClient chatClient;
    // 状态
    private AgentState agentState;
    // 可用的工具
    private List<ToolCallback> availableTools;
    // 本轮经路由和安全策略过滤后的完整工具集，供确定性执行计划逐步开放。
    private List<ToolCallback> runtimeTools;
    private List<String> requiredToolSequence = List.of();
    private int nextRequiredToolIndex = 0;
    private int plannedToolRepairAttempts = 0;
    private RoutingDecision routingDecision;
    private String requiredKnowledgeQuery;
    private boolean requiredKnowledgeRetrievalCompleted;
    private int retrievedSourceCount;
    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;
    // 工具调用管理器
    private ToolCallingManager toolCallingManager;
    // 模型的聊天记录
    private ChatMemory chatMemory;
    // 模型的聊天会话 ID
    private String chatSessionId;
    // 单次生成标识，用于隔离同一会话中的 SSE 事件
    private String generationId;
    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;
    // 事件发布器
    private ApplicationEventPublisher eventPublisher;
    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;
    // 持久化 Agent 每一步和工具调用的脱敏 Trace
    private AgentTraceStore agentTraceStore;
    // 单次模型响应流超时；云端推理的尾延迟可能明显高于本地模型。
    private Duration llmStreamTimeout = DEFAULT_LLM_STREAM_TIMEOUT;

    public JMindOps() {
    }

    public JMindOps(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     Double temperature,
                     Double topP,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     String generationId,
                     ApplicationEventPublisher eventPublisher,
                     AgentTraceStore agentTraceStore,
                     RoutingDecision routingDecision,
                     String requiredKnowledgeQuery,
                     AgentExecutionPolicy.Plan executionPlan,
                     Duration llmStreamTimeout
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.runtimeTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.availableTools = new ArrayList<>(this.runtimeTools);
        this.availableKbs = availableKbs;
        this.routingDecision = routingDecision;
        this.requiredKnowledgeQuery = requiredKnowledgeQuery;
        this.requiredToolSequence = executionPlan == null
                ? List.of()
                : executionPlan.requiredToolSequence();
        restrictToolsToNextPlannedStep();

        this.chatSessionId = chatSessionId;
        this.generationId = generationId;
        this.eventPublisher = eventPublisher;
        this.agentTraceStore = agentTraceStore;
        this.llmStreamTimeout = llmStreamTimeout == null
                ? DEFAULT_LLM_STREAM_TIMEOUT
                : llmStreamTimeout;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        var optionsBuilder = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        if (topP != null) {
            optionsBuilder.topP(topP);
        }
        this.chatOptions = optionsBuilder.build();
        synchronizeToolCallbacks();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d] name=%s, callId=%s",
                            i + 1,
                            call.name(),
                            call.id()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    private void saveMessage(Message message, org.springframework.ai.chat.metadata.Usage usage, Long latencyMs, String modelName) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new AgentMessageGeneratedEvent(
                    this, this.chatSessionId, this.generationId, message, usage, latencyMs, modelName));
        }
    }

    private boolean think(String stepTraceId) {
        // ChatClient 的请求构建器会合并工具回调；每一步先显式覆盖底层选项，
        // 确保计划完成后的空工具集不会沿用上一步的回调。
        synchronizeToolCallbacks();
        String ragGroundingInstruction = routingDecision == RoutingDecision.RAG
                ? RagAnswerPolicy.groundingInstruction()
                : "";
        String plannedStepInstruction = hasPendingRequiredTool()
                ? "- 当前执行计划的下一步必须调用且只能调用工具 " + nextRequiredToolName()
                + "。不得跳过、替换、并行调用或在工具执行前输出最终答案。"
                + (plannedToolRepairAttempts > 0 ? " 上一次没有按计划调用工具，本次必须纠正。" : "")
                : "";
        String thinkPrompt = """
                现在你是一个智能的的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                - 使用 KnowledgeTool 的检索结果回答时，必须在相关结论后保留工具结果中的 [Source N] 引用标记；找不到依据时明确说明。
                %s
                %s
                """.formatted(this.availableKbs, ragGroundingInstruction, plannedStepInstruction);

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        long startTime = System.currentTimeMillis();

        reactor.core.publisher.Flux<ChatResponse> responseFlux = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .stream()
                .chatResponse();

        StringBuilder fullContent = new StringBuilder();
        java.util.Map<String, StringBuilder> toolCallArguments = new LinkedHashMap<>();
        java.util.Map<String, String> toolCallNames = new LinkedHashMap<>();
        org.springframework.ai.chat.metadata.ChatResponseMetadata[] lastMetadata = new org.springframework.ai.chat.metadata.ChatResponseMetadata[1];

        // 设定单次模型推流 60 秒超时限制，彻底杜绝工作线程永久卡死
        responseFlux.doOnNext(chunk -> {
            if (chunk.getMetadata() != null) {
                lastMetadata[0] = chunk.getMetadata();
            }
            if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                AssistantMessage output = chunk.getResult().getOutput();
                if (output.getText() != null && !output.getText().isEmpty()) {
                    fullContent.append(output.getText());
                    if (eventPublisher != null && !shouldBufferRagAnswer() && !shouldBufferPlannedAction()) {
                        eventPublisher.publishEvent(new com.kama.jmindops.event.AgentMessageChunkEvent(
                                this, this.chatSessionId, this.generationId, output.getText()));
                    }
                }
                if (output.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                        if (tc.name() != null && !tc.name().isEmpty()) {
                            toolCallNames.put(tc.id(), tc.name());
                        }
                        if (tc.arguments() != null && !tc.arguments().isEmpty()) {
                            toolCallArguments.computeIfAbsent(tc.id(), k -> new StringBuilder()).append(tc.arguments());
                        }
                    }
                }
            }
        }).blockLast(llmStreamTimeout);

        List<AssistantMessage.ToolCall> finalToolCalls = new ArrayList<>();
        for (String id : toolCallNames.keySet()) {
            StringBuilder arguments = toolCallArguments.get(id);
            finalToolCalls.add(new AssistantMessage.ToolCall(
                    id, "function", toolCallNames.get(id), arguments == null ? "" : arguments.toString()));
        }

        String finalContent = fullContent.toString();
        if (finalToolCalls.isEmpty() && shouldBufferPlannedAction()) {
            if (plannedToolRepairAttempts < 1) {
                plannedToolRepairAttempts++;
                log.warn("Agent 未执行必需工具，触发一次受限重试: sessionId={}, generationId={}, tool={}",
                        this.chatSessionId, this.generationId, nextRequiredToolName());
                return think(stepTraceId);
            }
            throw new IllegalStateException("Agent 在完成必需工具步骤前提前生成了最终答案: "
                    + nextRequiredToolName());
        }
        if (shouldBufferRagAnswer() && finalToolCalls.isEmpty()) {
            if (hasPendingRequiredTool()) {
                throw new IllegalStateException("Agent 在完成必需工具步骤前提前生成了最终答案: "
                        + nextRequiredToolName());
            }
            finalContent = RagAnswerPolicy.enforceValidCitations(finalContent, retrievedSourceCount);
            publishChunk(finalContent);
        }
        AssistantMessage aggregatedMessage = org.springframework.ai.chat.messages.AssistantMessageFactory.create(finalContent, new java.util.HashMap<>(), finalToolCalls);
        this.lastChatResponse = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(aggregatedMessage)), lastMetadata[0]);

        long costTime = System.currentTimeMillis() - startTime;

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        org.springframework.ai.chat.metadata.Usage usage = null;
        String modelName = null;
        boolean tokenBudgetExceeded = false;
        if (this.lastChatResponse.getMetadata() != null) {
            usage = this.lastChatResponse.getMetadata().getUsage();
            modelName = this.lastChatResponse.getMetadata().getModel();
            if (usage != null && usage.getTotalTokens() != null) {
                this.cumulativeTokens += usage.getTotalTokens();
                if (this.cumulativeTokens > MAX_CUMULATIVE_TOKENS) {
                    log.warn("累计消耗 Token 超过安全预算上限 ({} > {})，触发安全熔断: sessionId={}",
                            this.cumulativeTokens, MAX_CUMULATIVE_TOKENS, this.chatSessionId);
                    this.agentState = AgentState.FINISHED;
                    tokenBudgetExceeded = true;
                }
            }
        }

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        saveMessage(output, usage, costTime, modelName);
        if (this.agentTraceStore != null) {
            this.agentTraceStore.completeThinking(
                    this.generationId, stepTraceId, output, usage, costTime, modelName);
        }
        logToolCalls(toolCalls);

        if (tokenBudgetExceeded) {
            throw new IllegalStateException("Agent 累计 Token 超过安全预算上限");
        }

        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute(String stepTraceId) {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        Set<String> allowedKnowledgeBaseIds = this.availableKbs == null
                ? Collections.emptySet()
                : this.availableKbs.stream()
                .map(KnowledgeBaseDTO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        ToolExecutionContext.set(this.chatSessionId, allowedKnowledgeBaseIds);
        ToolExecutionResult toolExecutionResult;
        long startedAt = System.currentTimeMillis();
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        } finally {
            ToolExecutionContext.clear();
        }

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String toolNames = toolResponseMessage.getResponses()
                .stream()
                .map(ToolResponseMessage.ToolResponse::name)
                .collect(Collectors.joining(","));

        log.info("工具调用完成: sessionId={}, generationId={}, toolCount={}, tools={}",
                this.chatSessionId, this.generationId, toolResponseMessage.getResponses().size(), toolNames);

        // 保存工具调用
        saveMessage(toolResponseMessage, null, null, null);
        if (this.agentTraceStore != null) {
            this.agentTraceStore.completeTools(
                    this.generationId,
                    stepTraceId,
                    toolResponseMessage,
                    System.currentTimeMillis() - startedAt
            );
        }

        boolean waitingForApproval = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> ToolApprovalSignal.isWaitingResponse(response.responseData()));
        if (waitingForApproval) {
            this.availableTools = Collections.emptyList();
            this.nextRequiredToolIndex = this.requiredToolSequence.size();
            AssistantMessage waitingMessage = new AssistantMessage(ToolApprovalSignal.userFacingWaitingMessage());
            publishChunk(waitingMessage.getText());
            saveMessage(waitingMessage, null, 0L, "deterministic-approval-orchestrator");
            this.agentState = AgentState.FINISHED;
            log.info("工具进入待审批状态，本轮撤销后续工具: sessionId={}, generationId={}",
                    this.chatSessionId, this.generationId);
        } else {
            advanceExecutionPlan(toolResponseMessage);
        }

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void step(int stepNo) {
        String stepTraceId = this.agentTraceStore == null
                ? null
                : this.agentTraceStore.startStep(this.generationId, stepNo);
        boolean toolExecutionStarted = false;
        try {
            if (think(stepTraceId)) {
                if (this.agentTraceStore != null) {
                    this.agentTraceStore.markToolsRunning(this.generationId, stepTraceId);
                }
                toolExecutionStarted = true;
                execute(stepTraceId);
            } else { // 没有工具调用
                agentState = AgentState.FINISHED;
            }
        } catch (RuntimeException exception) {
            if (this.agentTraceStore != null) {
                try {
                    this.agentTraceStore.failStep(
                            this.generationId,
                            stepTraceId,
                            exception.getMessage(),
                            toolExecutionStarted
                    );
                } catch (RuntimeException traceException) {
                    exception.addSuppressed(traceException);
                    log.error("Failed to persist Agent step failure: generationId={}, stepNo={}",
                            this.generationId, stepNo, traceException);
                }
            }
            throw exception;
        }
    }

    private void advanceExecutionPlan(ToolResponseMessage responseMessage) {
        if (!hasPendingRequiredTool()) {
            return;
        }
        String expected = nextRequiredToolName();
        boolean completed = responseMessage.getResponses().stream()
                .anyMatch(response -> expected.equals(response.name()));
        if (completed) {
            nextRequiredToolIndex++;
            plannedToolRepairAttempts = 0;
            restrictToolsToNextPlannedStep();
        }
    }

    private void restrictToolsToNextPlannedStep() {
        if (requiredToolSequence == null || requiredToolSequence.isEmpty()) {
            return;
        }
        if (!hasPendingRequiredTool()) {
            this.availableTools = Collections.emptyList();
            synchronizeToolCallbacks();
            return;
        }
        String nextTool = nextRequiredToolName();
        this.availableTools = this.runtimeTools.stream()
                .filter(callback -> nextTool.equals(callback.getToolDefinition().name()))
                .toList();
        synchronizeToolCallbacks();
    }

    private void synchronizeToolCallbacks() {
        if (this.chatOptions instanceof ToolCallingChatOptions toolCallingOptions) {
            toolCallingOptions.setToolCallbacks(List.copyOf(this.availableTools));
        }
    }

    private boolean hasPendingRequiredTool() {
        return nextRequiredToolIndex < requiredToolSequence.size();
    }

    private String nextRequiredToolName() {
        return hasPendingRequiredTool() ? requiredToolSequence.get(nextRequiredToolIndex) : null;
    }

    private boolean shouldBufferRagAnswer() {
        return routingDecision == RoutingDecision.RAG && requiredKnowledgeRetrievalCompleted;
    }

    private boolean shouldBufferPlannedAction() {
        return hasPendingRequiredTool();
    }

    private void publishChunk(String content) {
        if (eventPublisher != null && StringUtils.hasText(content)) {
            eventPublisher.publishEvent(new com.kama.jmindops.event.AgentMessageChunkEvent(
                    this, this.chatSessionId, this.generationId, content));
        }
    }

    private boolean shouldPrefetchKnowledge() {
        return KNOWLEDGE_TOOL_NAME.equals(nextRequiredToolName());
    }

    private boolean canPrefetchKnowledge() {
        return this.availableKbs != null
                && !this.availableKbs.isEmpty()
                && this.runtimeTools.stream().anyMatch(callback ->
                KNOWLEDGE_TOOL_NAME.equals(callback.getToolDefinition().name()));
    }

    private void executeRequiredKnowledgeRetrieval(int stepNo) {
        String stepTraceId = this.agentTraceStore == null
                ? null
                : this.agentTraceStore.startStep(this.generationId, stepNo);
        boolean toolExecutionStarted = false;
        try {
            ToolCallback knowledgeCallback = this.runtimeTools.stream()
                    .filter(callback -> KNOWLEDGE_TOOL_NAME.equals(callback.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("RAG 路由未配置可用的 KnowledgeTool"));
            KnowledgeBaseDTO knowledgeBase = selectKnowledgeBase(requiredKnowledgeQuery);
            String toolCallId = UUID.randomUUID().toString();
            String arguments = knowledgeArguments(knowledgeBase.getId(), requiredKnowledgeQuery);
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    toolCallId, "function", KNOWLEDGE_TOOL_NAME, arguments);
            AssistantMessage toolCallMessage = org.springframework.ai.chat.messages.AssistantMessageFactory.create(
                    "", new java.util.HashMap<>(), List.of(toolCall));

            this.chatMemory.add(this.chatSessionId, toolCallMessage);
            saveMessage(toolCallMessage, null, 0L, "deterministic-rag-orchestrator");
            if (this.agentTraceStore != null) {
                this.agentTraceStore.completeThinking(
                        this.generationId, stepTraceId, toolCallMessage, null, 0L,
                        "deterministic-rag-orchestrator");
                this.agentTraceStore.markToolsRunning(this.generationId, stepTraceId);
            }

            long startedAt = System.currentTimeMillis();
            toolExecutionStarted = true;
            Set<String> allowedKnowledgeBaseIds = this.availableKbs.stream()
                    .map(KnowledgeBaseDTO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            String responseData;
            ToolExecutionContext.set(this.chatSessionId, allowedKnowledgeBaseIds);
            try {
                responseData = knowledgeCallback.call(arguments);
            } finally {
                ToolExecutionContext.clear();
            }
            responseData = responseData == null ? "" : responseData;
            ToolResponseMessage responseMessage = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            toolCallId, KNOWLEDGE_TOOL_NAME, responseData)))
                    .build();
            this.chatMemory.add(this.chatSessionId, responseMessage);
            saveMessage(responseMessage, null, null, null);
            if (this.agentTraceStore != null) {
                this.agentTraceStore.completeTools(
                        this.generationId, stepTraceId, responseMessage,
                        System.currentTimeMillis() - startedAt);
            }
            this.retrievedSourceCount = RagAnswerPolicy.countSources(responseData);
            this.requiredKnowledgeRetrievalCompleted = true;
            this.nextRequiredToolIndex++;
            restrictToolsToNextPlannedStep();
            log.info("RAG 强制检索完成: sessionId={}, generationId={}, knowledgeBaseId={}, sourceCount={}",
                    this.chatSessionId, this.generationId, knowledgeBase.getId(), retrievedSourceCount);
        } catch (RuntimeException exception) {
            if (this.agentTraceStore != null) {
                this.agentTraceStore.failStep(
                        this.generationId, stepTraceId, exception.getMessage(), toolExecutionStarted);
            }
            throw exception;
        }
    }

    private KnowledgeBaseDTO selectKnowledgeBase(String query) {
        if (this.availableKbs == null || this.availableKbs.isEmpty()) {
            throw new IllegalStateException("RAG 路由没有已授权知识库");
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return this.availableKbs.stream()
                .max(java.util.Comparator.comparingInt(kb -> knowledgeBaseMatchScore(kb, normalizedQuery)))
                .orElseThrow();
    }

    private int knowledgeBaseMatchScore(KnowledgeBaseDTO knowledgeBase, String normalizedQuery) {
        int score = 0;
        if (StringUtils.hasText(knowledgeBase.getName())
                && normalizedQuery.contains(knowledgeBase.getName().toLowerCase(Locale.ROOT))) {
            score += 2;
        }
        if (StringUtils.hasText(knowledgeBase.getDescription())
                && normalizedQuery.contains(knowledgeBase.getDescription().toLowerCase(Locale.ROOT))) {
            score++;
        }
        return score;
    }

    private String knowledgeArguments(String knowledgeBaseId, String query) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "kbsId", knowledgeBaseId,
                    "query", query == null ? "" : query));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法构造知识库检索参数", exception);
        }
    }

    private void finishWithoutEvidence(int stepNo) {
        String stepTraceId = this.agentTraceStore == null
                ? null
                : this.agentTraceStore.startStep(this.generationId, stepNo);
        AssistantMessage response = new AssistantMessage(RagAnswerPolicy.INSUFFICIENT_EVIDENCE_MESSAGE);
        publishChunk(response.getText());
        saveMessage(response, null, 0L, "deterministic-rag-orchestrator");
        if (this.agentTraceStore != null) {
            this.agentTraceStore.completeThinking(
                    this.generationId, stepTraceId, response, null, 0L,
                    "deterministic-rag-orchestrator");
        }
        this.agentState = AgentState.FINISHED;
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            int nextStep = 1;
            if (shouldPrefetchKnowledge()) {
                if (!canPrefetchKnowledge()) {
                    finishWithoutEvidence(nextStep);
                    return;
                }
                executeRequiredKnowledgeRetrieval(nextStep++);
                if (retrievedSourceCount == 0) {
                    finishWithoutEvidence(nextStep);
                    return;
                }
            }
            for (int currentStep = nextStep;
                 currentStep <= MAX_STEPS && agentState != AgentState.FINISHED;
                 currentStep++) {
                step(currentStep);
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        }
    }

    @Override
    public String toString() {
        return "JMindOps {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
