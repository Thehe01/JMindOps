package com.kama.jmindops.agent;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.event.AgentMessageGeneratedEvent;
import com.kama.jmindops.governance.ToolExecutionContext;
import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class JMindOps {
    private static final Logger log = LoggerFactory.getLogger(JMindOps.class);

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    // 单次模型响应流超时时间
    private static final Duration LLM_STREAM_TIMEOUT = Duration.ofSeconds(60);
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
                     ApplicationEventPublisher eventPublisher
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.generationId = generationId;
        this.eventPublisher = eventPublisher;

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

    private boolean think() {
        String thinkPrompt = """
                现在你是一个智能的的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                - 使用 KnowledgeTool 的检索结果回答时，必须在相关结论后保留工具结果中的 [Source N] 引用标记；找不到依据时明确说明。
                """.formatted(this.availableKbs);

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
        java.util.Map<String, StringBuilder> toolCallArguments = new java.util.HashMap<>();
        java.util.Map<String, String> toolCallNames = new java.util.HashMap<>();
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
                    if (eventPublisher != null) {
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
        }).blockLast(LLM_STREAM_TIMEOUT);

        List<AssistantMessage.ToolCall> finalToolCalls = new ArrayList<>();
        for (String id : toolCallNames.keySet()) {
            StringBuilder arguments = toolCallArguments.get(id);
            finalToolCalls.add(new AssistantMessage.ToolCall(
                    id, "function", toolCallNames.get(id), arguments == null ? "" : arguments.toString()));
        }

        AssistantMessage aggregatedMessage = org.springframework.ai.chat.messages.AssistantMessageFactory.create(fullContent.toString(), new java.util.HashMap<>(), finalToolCalls);
        this.lastChatResponse = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(aggregatedMessage)), lastMetadata[0]);

        long costTime = System.currentTimeMillis() - startTime;

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        org.springframework.ai.chat.metadata.Usage usage = null;
        String modelName = null;
        if (this.lastChatResponse.getMetadata() != null) {
            usage = this.lastChatResponse.getMetadata().getUsage();
            modelName = this.lastChatResponse.getMetadata().getModel();
            if (usage != null && usage.getTotalTokens() != null) {
                this.cumulativeTokens += usage.getTotalTokens();
                if (this.cumulativeTokens > MAX_CUMULATIVE_TOKENS) {
                    log.warn("累计消耗 Token 超过安全预算上限 ({} > {})，触发安全熔断: sessionId={}",
                            this.cumulativeTokens, MAX_CUMULATIVE_TOKENS, this.chatSessionId);
                    this.agentState = AgentState.FINISHED;
                }
            }
        }

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        saveMessage(output, usage, costTime, modelName);
        logToolCalls(toolCalls);

        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
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

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                step();
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
