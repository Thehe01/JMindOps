package com.kama.jmindops.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.agent.checkpoint.CheckpointPayload;
import com.kama.jmindops.agent.tools.ToolIdempotencyResolver;
import com.kama.jmindops.event.AgentMessageGeneratedEvent;
import com.kama.jmindops.exception.NonIdempotentToolReplayException;
import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.governance.ToolApprovalSignal;
import com.kama.jmindops.governance.ToolExecutionContext;
import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
import com.kama.jmindops.model.entity.AgentCheckpoint;
import com.kama.jmindops.model.entity.AgentToolExecution;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.service.AgentCheckpointStore;
import com.kama.jmindops.service.AgentTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    // SpringAI 自带的 ChatOptions
    private ChatOptions chatOptions;
    // 事件发布器
    private ApplicationEventPublisher eventPublisher;
    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;
    // 持久化 Agent 每一步和工具调用的脱敏 Trace
    private AgentTraceStore agentTraceStore;
    // 单次模型响应流超时
    private Duration llmStreamTimeout = DEFAULT_LLM_STREAM_TIMEOUT;

    // P0-2: Checkpoint & Execution Ledger
    private AgentCheckpointStore checkpointStore;
    private ToolIdempotencyResolver toolIdempotencyResolver;
    private String workerId = "default-worker";
    private long leaseVersion = 1L;
    private long checkpointVersion = 0L;
    private List<ToolResponseMessage.ToolResponse> lastToolResponses = new ArrayList<>();

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
        this(agentId, name, description, systemPrompt, chatClient, maxMessages,
                temperature, topP, memory, availableTools, availableKbs, chatSessionId,
                generationId, eventPublisher, agentTraceStore, routingDecision,
                requiredKnowledgeQuery, executionPlan, llmStreamTimeout,
                null, null, "default-worker", 1L);
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
                     Duration llmStreamTimeout,
                     AgentCheckpointStore checkpointStore,
                     ToolIdempotencyResolver toolIdempotencyResolver,
                     String workerId,
                     long leaseVersion
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

        this.checkpointStore = checkpointStore;
        this.toolIdempotencyResolver = toolIdempotencyResolver;
        this.workerId = workerId != null ? workerId : "default-worker";
        this.leaseVersion = leaseVersion;

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

    public AgentState getAgentState() {
        return this.agentState;
    }

    public String getGenerationId() {
        return this.generationId;
    }

    public long getCumulativeTokens() {
        return this.cumulativeTokens;
    }

    public long getLeaseVersion() {
        return this.leaseVersion;
    }

    public String getWorkerId() {
        return this.workerId;
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
        if (this.agentTraceStore != null && stepTraceId != null) {
            this.agentTraceStore.completeThinking(
                    this.generationId, stepTraceId, output, usage, costTime, modelName);
        }
        logToolCalls(toolCalls);

        if (tokenBudgetExceeded) {
            throw new IllegalStateException("Agent 累计 Token 超过安全预算上限");
        }

        return !toolCalls.isEmpty();
    }

    private ToolCallback findCallback(String name) {
        if (this.availableTools != null) {
            for (ToolCallback callback : this.availableTools) {
                if (callback.getToolDefinition() != null && name.equals(callback.getToolDefinition().name())) {
                    return callback;
                }
            }
        }
        if (this.runtimeTools != null) {
            for (ToolCallback callback : this.runtimeTools) {
                if (callback.getToolDefinition() != null && name.equals(callback.getToolDefinition().name())) {
                    return callback;
                }
            }
        }
        return null;
    }

    /**
     * 执行当前步骤的所有工具调用，并基于执行账本 (Execution Ledger) 保障幂等性、崩溃恢复与审批挂起。
     * @return true 如果进入人工审批挂起 (WAITING_APPROVAL)；false 正常完成
     */
    private boolean executeToolsWithLedger(
            int stepNo,
            String stepTraceId,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }

        Set<String> allowedKnowledgeBaseIds = this.availableKbs == null
                ? Collections.emptySet()
                : this.availableKbs.stream()
                .map(KnowledgeBaseDTO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        ToolExecutionContext.set(this.chatSessionId, this.generationId, allowedKnowledgeBaseIds);

        long startedAt = System.currentTimeMillis();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();

        try {
            for (AssistantMessage.ToolCall call : toolCalls) {
                String toolCallId = call.id();
                String toolName = call.name();
                String arguments = call.arguments() == null ? "" : call.arguments();
                ToolExecutionContext.setToolCallId(toolCallId);

                // 1. 检查账本是否已有该调用的执行状态
                if (checkpointStore != null) {
                    Optional<AgentToolExecution> existingOpt = checkpointStore.findToolExecution(this.generationId, toolCallId);
                    if (existingOpt.isPresent()) {
                        AgentToolExecution existing = existingOpt.get();
                        if (existing.status() == AgentToolExecution.Status.SUCCEEDED) {
                            log.info("复用已落库工具结果（防止重复副作用）: generationId={}, toolCallId={}, tool={}",
                                    this.generationId, toolCallId, toolName);
                            responses.add(new ToolResponseMessage.ToolResponse(toolCallId, toolName, existing.result()));
                            continue;
                        } else if (existing.status() == AgentToolExecution.Status.WAITING_APPROVAL) {
                            boolean isApproved = checkpointStore.isToolApprovalGranted(this.generationId, toolCallId, toolName);
                            if (!isApproved) {
                                log.info("工具调用处于 WAITING_APPROVAL 状态且尚未获批，保持等待，不重复发起: generationId={}, toolCallId={}, tool={}",
                                        this.generationId, toolCallId, toolName);
                                this.availableTools = Collections.emptyList();
                                this.nextRequiredToolIndex = this.requiredToolSequence.size();
                                this.agentState = AgentState.WAITING_APPROVAL;
                                return true;
                            }
                        } else if (existing.status() == AgentToolExecution.Status.UNKNOWN) {
                            boolean idempotent = existing.idempotent();
                            if (!idempotent && toolIdempotencyResolver != null) {
                                idempotent = toolIdempotencyResolver.isIdempotent(toolName, findCallback(toolName));
                            }
                            if (!idempotent) {
                                log.error("非幂等外部工具处于 UNKNOWN 状态，禁止自动重试: generationId={}, toolCallId={}, tool={}",
                                        this.generationId, toolCallId, toolName);
                                throw new NonIdempotentToolReplayException(
                                        "外部工具「" + toolName + "」为非幂等工具且处于 UNKNOWN 状态，可能已产生副作用，禁止自动重放，需人工对账确认。");
                            }
                        }
                    }
                    checkpointStore.markToolExecuting(this.generationId, toolCallId, this.workerId, this.leaseVersion);
                }

                ToolCallback callback = findCallback(toolName);
                String result;
                try {
                    if (callback != null) {
                        if (checkpointStore != null) {
                            checkpointStore.assertActiveLease(this.generationId, this.workerId, this.leaseVersion);
                        }
                        org.springframework.ai.chat.model.ToolContext toolContext =
                                new org.springframework.ai.chat.model.ToolContext(Map.of(
                                        "tool_call_id", toolCallId,
                                        "generation_id", this.generationId,
                                        "idempotency_key", toolCallId
                                ));
                        result = callback.call(arguments, toolContext);
                    } else {
                        result = "错误：未找到可用的工具「" + toolName + "」";
                    }
                } catch (Exception e) {
                    if (e instanceof StaleGenerationLeaseException) {
                        throw (StaleGenerationLeaseException) e;
                    }
                    if (checkpointStore != null) {
                        boolean idempotent = toolIdempotencyResolver != null
                                && toolIdempotencyResolver.isIdempotent(toolName, callback);
                        try {
                            if (!idempotent) {
                                log.warn("Non-idempotent tool execution threw exception, recording status as UNKNOWN: generationId={}, toolCallId={}, tool={}",
                                        this.generationId, toolCallId, toolName, e);
                                checkpointStore.markToolUnknown(this.generationId, toolCallId, e.getMessage(), this.workerId, this.leaseVersion);
                            } else {
                                checkpointStore.markToolFailed(this.generationId, toolCallId, e.getMessage(), this.workerId, this.leaseVersion);
                            }
                        } catch (StaleGenerationLeaseException staleEx) {
                            throw staleEx;
                        }
                    }
                    throw e;
                }

                result = result == null ? "" : result;

                // 2. 检查是否触发审批
                if (ToolApprovalSignal.isWaitingResponse(result)) {
                    if (checkpointStore != null) {
                        checkpointStore.markToolWaitingApproval(this.generationId, toolCallId, result, this.workerId, this.leaseVersion);
                        this.checkpointVersion++;
                        commitCheckpoint(stepNo, AgentCheckpoint.Stage.WAITING_APPROVAL,
                                GenerationTask.Status.WAITING_APPROVAL, toolCalls, responses);
                    }
                    this.availableTools = Collections.emptyList();
                    this.nextRequiredToolIndex = this.requiredToolSequence.size();
                    AssistantMessage waitingMessage = new AssistantMessage(ToolApprovalSignal.userFacingWaitingMessage());
                    publishChunk(waitingMessage.getText());
                    saveMessage(waitingMessage, null, 0L, "deterministic-approval-orchestrator");
                    this.agentState = AgentState.WAITING_APPROVAL;
                    log.info("工具进入待审批状态，安全挂起执行: sessionId={}, generationId={}",
                            this.chatSessionId, this.generationId);
                    return true;
                }

                if (checkpointStore != null) {
                    checkpointStore.markToolSucceeded(this.generationId, toolCallId, result, this.workerId, this.leaseVersion);
                }
                if (KNOWLEDGE_TOOL_NAME.equals(toolName)) {
                    this.requiredKnowledgeRetrievalCompleted = true;
                    this.retrievedSourceCount = RagAnswerPolicy.countSources(result);
                }
                responses.add(new ToolResponseMessage.ToolResponse(toolCallId, toolName, result));
            }
        } finally {
            ToolExecutionContext.clear();
        }

        this.lastToolResponses = List.copyOf(responses);
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(responses)
                .build();

        this.chatMemory.add(this.chatSessionId, toolResponseMessage);

        String toolNames = responses.stream()
                .map(ToolResponseMessage.ToolResponse::name)
                .collect(Collectors.joining(","));
        log.info("工具调用完成: sessionId={}, generationId={}, toolCount={}, tools={}",
                this.chatSessionId, this.generationId, responses.size(), toolNames);

        saveMessage(toolResponseMessage, null, null, null);
        if (this.agentTraceStore != null && stepTraceId != null) {
            this.agentTraceStore.completeTools(
                    this.generationId,
                    stepTraceId,
                    toolResponseMessage,
                    System.currentTimeMillis() - startedAt
            );
        }

        advanceExecutionPlan(toolResponseMessage);

        if (responses.stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }

        return false;
    }

    private void commitCheckpoint(
            int stepNo,
            AgentCheckpoint.Stage stage,
            GenerationTask.Status status,
            List<AssistantMessage.ToolCall> pendingToolCalls,
            List<ToolResponseMessage.ToolResponse> toolResults
    ) {
        if (this.checkpointStore == null) {
            return;
        }
        List<Message> currentMessages = this.chatMemory.get(this.chatSessionId);
        String messagesPayload = CheckpointPayload.serializeMessages(currentMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                this.routingDecision != null ? this.routingDecision.name() : null,
                this.requiredToolSequence,
                this.nextRequiredToolIndex,
                this.requiredKnowledgeQuery,
                this.requiredKnowledgeRetrievalCompleted,
                this.retrievedSourceCount,
                this.cumulativeTokens,
                stepNo,
                this.plannedToolRepairAttempts
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);
        String pendingCallsJson = pendingToolCalls != null ? CheckpointPayload.serializeToolCalls(pendingToolCalls) : null;
        String resultsJson = null;
        if (toolResults != null && !toolResults.isEmpty()) {
            List<CheckpointPayload.CheckpointToolResponse> records = toolResults.stream()
                    .map(tr -> new CheckpointPayload.CheckpointToolResponse(tr.id(), tr.name(), tr.responseData()))
                    .toList();
            try {
                resultsJson = OBJECT_MAPPER.writeValueAsString(records);
            } catch (JsonProcessingException ignored) {}
        }

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                this.generationId,
                stepNo,
                this.checkpointVersion,
                stage,
                status,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                resultsJson,
                null,
                null
        );
        this.checkpointStore.saveCheckpoint(checkpoint, this.workerId, this.leaseVersion);
    }

    // 单个步骤模板
    private void step(int stepNo) {
        String stepTraceId = this.agentTraceStore == null
                ? null
                : this.agentTraceStore.startStep(this.generationId, stepNo);
        boolean toolExecutionStarted = false;
        try {
            if (think(stepTraceId)) {
                AssistantMessage output = this.lastChatResponse.getResult().getOutput();
                this.chatMemory.add(this.chatSessionId, output);
                List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

                // Safe Boundary 1: Persist model output & pending tool calls
                if (this.checkpointStore != null) {
                    for (AssistantMessage.ToolCall call : toolCalls) {
                        boolean isIdempotent = toolIdempotencyResolver != null
                                && toolIdempotencyResolver.isIdempotent(call.name(), findCallback(call.name()));
                        this.checkpointStore.recordPreparedToolCall(this.generationId, stepNo, call, isIdempotent, this.workerId, this.leaseVersion);
                    }
                    this.checkpointVersion++;
                    commitCheckpoint(stepNo, AgentCheckpoint.Stage.MODEL_OUTPUT, GenerationTask.Status.RUNNING,
                            toolCalls, null);
                }

                if (this.agentTraceStore != null && stepTraceId != null) {
                    this.agentTraceStore.markToolsRunning(this.generationId, stepTraceId);
                }
                toolExecutionStarted = true;

                // Safe Boundary 2: Execute tools with ledger
                boolean suspended = executeToolsWithLedger(stepNo, stepTraceId, toolCalls);
                if (suspended) {
                    this.agentState = AgentState.WAITING_APPROVAL;
                    return;
                }

                // Safe Boundary 3: Commit completed step
                if (this.checkpointStore != null) {
                    this.checkpointVersion++;
                    commitCheckpoint(stepNo, AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.RUNNING,
                            null, this.lastToolResponses);
                }
            } else { // 没有工具调用，生成最终回答
                AssistantMessage output = this.lastChatResponse.getResult().getOutput();
                this.chatMemory.add(this.chatSessionId, output);
                agentState = AgentState.FINISHED;
                if (this.checkpointStore != null) {
                    this.checkpointVersion++;
                    commitCheckpoint(stepNo, AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.SUCCEEDED,
                            null, null);
                }
            }
        } catch (RuntimeException exception) {
            if (exception instanceof StaleGenerationLeaseException) {
                throw exception;
            }
            if (this.agentTraceStore != null && stepTraceId != null) {
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
            if (this.agentTraceStore != null && stepTraceId != null) {
                this.agentTraceStore.completeThinking(
                        this.generationId, stepTraceId, toolCallMessage, null, 0L,
                        "deterministic-rag-orchestrator");
                this.agentTraceStore.markToolsRunning(this.generationId, stepTraceId);
            }

            // Checkpoint boundary 1
            if (this.checkpointStore != null) {
                this.checkpointStore.recordPreparedToolCall(this.generationId, stepNo, toolCall, true, this.workerId, this.leaseVersion);
                this.checkpointVersion++;
                commitCheckpoint(stepNo, AgentCheckpoint.Stage.MODEL_OUTPUT, GenerationTask.Status.RUNNING,
                        List.of(toolCall), null);
            }

            long startedAt = System.currentTimeMillis();
            toolExecutionStarted = true;
            Set<String> allowedKnowledgeBaseIds = this.availableKbs.stream()
                    .map(KnowledgeBaseDTO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            String responseData;
            ToolExecutionContext.set(this.chatSessionId, this.generationId, allowedKnowledgeBaseIds);
            try {
                if (this.checkpointStore != null) {
                    this.checkpointStore.markToolExecuting(this.generationId, toolCallId, this.workerId, this.leaseVersion);
                    this.checkpointStore.assertActiveLease(this.generationId, this.workerId, this.leaseVersion);
                }
                responseData = knowledgeCallback.call(arguments);
                responseData = responseData == null ? "" : responseData;
                if (this.checkpointStore != null) {
                    this.checkpointStore.markToolSucceeded(this.generationId, toolCallId, responseData, this.workerId, this.leaseVersion);
                }
            } catch (Exception e) {
                if (e instanceof StaleGenerationLeaseException) {
                    throw (StaleGenerationLeaseException) e;
                }
                if (this.checkpointStore != null) {
                    try {
                        this.checkpointStore.markToolFailed(this.generationId, toolCallId, e.getMessage(), this.workerId, this.leaseVersion);
                    } catch (StaleGenerationLeaseException staleEx) {
                        throw staleEx;
                    }
                }
                throw e;
            } finally {
                ToolExecutionContext.clear();
            }

            ToolResponseMessage responseMessage = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            toolCallId, KNOWLEDGE_TOOL_NAME, responseData)))
                    .build();
            this.chatMemory.add(this.chatSessionId, responseMessage);
            saveMessage(responseMessage, null, null, null);
            if (this.agentTraceStore != null && stepTraceId != null) {
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

            // Checkpoint boundary 3
            if (this.checkpointStore != null) {
                this.checkpointVersion++;
                commitCheckpoint(stepNo, AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.RUNNING,
                        null, List.of(new ToolResponseMessage.ToolResponse(toolCallId, KNOWLEDGE_TOOL_NAME, responseData)));
            }
        } catch (RuntimeException exception) {
            if (exception instanceof StaleGenerationLeaseException) {
                throw exception;
            }
            if (this.agentTraceStore != null && stepTraceId != null) {
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
        if (this.agentTraceStore != null && stepTraceId != null) {
            this.agentTraceStore.completeThinking(
                    this.generationId, stepTraceId, response, null, 0L,
                    "deterministic-rag-orchestrator");
        }
        this.agentState = AgentState.FINISHED;
        if (this.checkpointStore != null) {
            this.checkpointVersion++;
            commitCheckpoint(stepNo, AgentCheckpoint.Stage.STEP_COMPLETED, GenerationTask.Status.SUCCEEDED,
                    null, null);
        }
    }

    public void resume() {
        run();
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE && agentState != AgentState.WAITING_APPROVAL) {
            throw new IllegalStateException("Agent is not idle or waiting approval, current state: " + agentState);
        }

        try {
            int nextStep = 1;

            if (checkpointStore != null) {
                Optional<AgentCheckpoint> latestCheckpointOpt = checkpointStore.findLatestCheckpoint(this.generationId);
                if (latestCheckpointOpt.isPresent()) {
                    AgentCheckpoint latestCheckpoint = latestCheckpointOpt.get();
                    log.info("从 Checkpoint 恢复执行: generationId={}, stepNo={}, version={}, stage={}",
                            this.generationId, latestCheckpoint.stepNo(), latestCheckpoint.checkpointVersion(), latestCheckpoint.stage());

                    // 1. 恢复工具状态：将遗留在 EXECUTING 状态的外部工具调用置为 UNKNOWN
                    checkpointStore.reconcileExecutingToolsOnRecovery(this.generationId);

                    // 2. 恢复 Message 记忆（无隐藏 CoT）
                    List<Message> restoredMessages = CheckpointPayload.deserializeMessages(latestCheckpoint.messagesPayload());
                    this.chatMemory.clear(this.chatSessionId);
                    this.chatMemory.add(this.chatSessionId, restoredMessages);

                    // 3. 恢复运行时控制状态
                    CheckpointPayload.CheckpointRuntimeState runtimeState =
                            CheckpointPayload.deserializeRuntimeState(latestCheckpoint.runtimeState());
                    this.nextRequiredToolIndex = runtimeState.nextRequiredToolIndex();
                    this.cumulativeTokens = runtimeState.cumulativeTokens();
                    this.retrievedSourceCount = runtimeState.retrievedSourceCount();
                    this.requiredKnowledgeRetrievalCompleted = runtimeState.requiredKnowledgeRetrievalCompleted();
                    this.plannedToolRepairAttempts = runtimeState.plannedToolRepairAttempts();
                    this.checkpointVersion = latestCheckpoint.checkpointVersion();
                    restrictToolsToNextPlannedStep();

                    int resumeStep = latestCheckpoint.stepNo();

                    // 4. 根据最后安全 Checkpoint 阶段决定恢复入口
                    if (latestCheckpoint.stage() == AgentCheckpoint.Stage.MODEL_OUTPUT
                            || latestCheckpoint.stage() == AgentCheckpoint.Stage.WAITING_APPROVAL) {
                        // 场景 A & 场景 D: 模型已返回 tool calls 但工具未完成 / 工具曾等待审批现已恢复
                        List<AssistantMessage.ToolCall> pendingCalls =
                                CheckpointPayload.deserializeToolCalls(latestCheckpoint.pendingToolCalls());
                        String stepTraceId = this.agentTraceStore == null
                                ? null
                                : this.agentTraceStore.startStep(this.generationId, resumeStep);
                        if (this.agentTraceStore != null && stepTraceId != null) {
                            this.agentTraceStore.markToolsRunning(this.generationId, stepTraceId);
                        }

                        boolean suspended = executeToolsWithLedger(resumeStep, stepTraceId, pendingCalls);
                        if (suspended) {
                            this.agentState = AgentState.WAITING_APPROVAL;
                            return;
                        }

                        this.checkpointVersion++;
                        commitCheckpoint(resumeStep, AgentCheckpoint.Stage.STEP_COMPLETED,
                                GenerationTask.Status.RUNNING, null, this.lastToolResponses);
                        nextStep = resumeStep + 1;
                    } else if (latestCheckpoint.stage() == AgentCheckpoint.Stage.STEP_COMPLETED) {
                        nextStep = resumeStep + 1;
                    } else {
                        nextStep = 1;
                    }
                } else {
                    // 首次执行：提交 INITIAL checkpoint
                    commitCheckpoint(0, AgentCheckpoint.Stage.INITIAL, GenerationTask.Status.RUNNING, null, null);
                }
            }

            if (nextStep == 1 && shouldPrefetchKnowledge()) {
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
                 currentStep <= MAX_STEPS && agentState != AgentState.FINISHED && agentState != AgentState.WAITING_APPROVAL;
                 currentStep++) {
                step(currentStep);
                if (currentStep >= MAX_STEPS && agentState != AgentState.WAITING_APPROVAL) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }

            if (agentState != AgentState.WAITING_APPROVAL) {
                agentState = AgentState.FINISHED;
            }
        } catch (com.kama.jmindops.exception.StaleGenerationLeaseException e) {
            agentState = AgentState.ERROR;
            log.warn("Agent execution terminated due to stale generation lease: generationId={}", this.generationId, e);
            throw e;
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
