package com.kama.jmindops.agent.examples;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.agent.AgentState;
import com.kama.jmindops.ai.AssistantMessage;
import com.kama.jmindops.ai.Message;
import com.kama.jmindops.ai.ToolResponseMessage;
import com.kama.jmindops.ai.UserMessage;
import com.kama.jmindops.ai.ChatResponse;
import com.kama.jmindops.ai.ToolCallingChatOptions;
import com.kama.jmindops.ai.Prompt;
import com.kama.jmindops.ai.ToolCallback;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * V2 版本：添加工具调用功能（ReAct 模型）
 * 在 V1 基础上，引入工具调用，实现 ReAct（Reasoning + Acting）模型
 * 
 * 核心功能：
 * - 继承 V1 的所有功能
 * - 支持工具调用（Tool Calling）
 * - 实现 think-execute 循环
 * - 自动执行工具并处理结果
 */

public class JMindOpsV2 extends JMindOpsV1 {
    private static final Logger log = LoggerFactory.getLogger(JMindOpsV2.class);

    
    // 可用的工具列表
    protected List<ToolCallback> availableTools;
    
    // 工具调用管理器
    
    // ToolCallingChatOptions
    protected ToolCallingChatOptions chatOptions;
    
    // 最后一次的 ChatResponse
    protected ChatResponse lastChatResponse;
    
    // 最多循环次数
    private static final Integer MAX_STEPS = 20;
    
    public JMindOpsV2() {
        super();
    }
    
    public JMindOpsV2(String name,
                      String description,
                      String systemPrompt,
                      com.kama.jmindops.ai.ChatClient chatClient,
                      Integer maxMessages,
                      String sessionId,
                      List<ToolCallback> availableTools) {
        super(name, description, systemPrompt, chatClient, maxMessages, sessionId);
        this.availableTools = availableTools;
        
        // LangChain4j 只返回工具调用，由本例显式执行。
        // 我们需要手动控制工具调用的执行流程
        this.chatOptions = ToolCallingChatOptions.builder().build();
        
    }
    
    /**
     * 打印工具调用信息
     */
    protected void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }
    
    /**
     * Think 阶段：思考并决定是否需要调用工具
     * @return 如果有工具调用，返回 true；否则返回 false
     */
    protected boolean think() {
        String thinkPrompt = """
                现在你是一个智能的「决策模块」。
                请根据当前对话上下文，决定下一步的动作。
                如果需要调用工具来完成任务，请调用相应的工具。
                """;
        
        // 构建提示词
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(chatMemory.get(sessionId))
                .build();
        
        // 调用 LLM，传入工具回调
        this.lastChatResponse = chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(availableTools != null ? availableTools.toArray(new ToolCallback[0]) : new ToolCallback[0])
                .call()
                .chatResponse();
        
        Assert.notNull(lastChatResponse, "Last chat response cannot be null");
        
        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();
        
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        
        // 打印工具调用信息
        logToolCalls(toolCalls);
        
        // 如果没有工具调用，将 AI 回复添加到记忆
        // 如果有工具调用，则在 execute() 中统一处理，避免出现未完成的工具调用
        if (toolCalls.isEmpty()) {
            chatMemory.add(sessionId, output);
        }
        
        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }
    
    /**
     * Execute 阶段：执行工具调用
     */
    protected void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat response cannot be null");
        
        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }
        
        AssistantMessage assistant = lastChatResponse.getResult().getOutput();
        List<ToolResponseMessage.ToolResponse> responses = new java.util.ArrayList<>();
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            ToolCallback callback = availableTools.stream()
                    .filter(tool -> tool.getToolDefinition().name().equals(call.name()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Unknown tool: " + call.name()));
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), callback.call(call.arguments())));
        }
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();
        chatMemory.add(sessionId, assistant);
        chatMemory.add(sessionId, toolResponseMessage);

        // 打印工具调用结果
        String result = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具 " + resp.name() + " 的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));
        
//        log.info("工具调用结果：{}", result);
        
        // 检查是否有终止工具调用
        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }
    
    /**
     * 单个步骤：think -> execute（如果需要）
     */
    protected void step() {
        if (think()) {
            // 有工具调用，执行工具
            execute();
        } else {
            // 没有工具调用，直接结束
            agentState = AgentState.FINISHED;
        }
    }
    
    /**
     * 运行 Agent：处理用户输入，执行 think-execute 循环
     */
    @Override
    public String chat(String userInput) {
        Assert.notNull(userInput, "用户输入不能为空");
        
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent 状态不是 IDLE，当前状态：" + agentState);
        }
        
        try {
            agentState = AgentState.THINKING;
            
            // 添加用户消息到记忆
            UserMessage userMessage = new UserMessage(userInput);
            chatMemory.add(sessionId, userMessage);
            
            // 执行 think-execute 循环
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                step();
                if (i >= MAX_STEPS - 1) {
                    agentState = AgentState.FINISHED;
                    log.warn("达到最大步骤数，停止 Agent");
                }
            }
            
            // 获取最后的 AI 回复
            List<Message> history = chatMemory.get(sessionId);
            String aiResponse = "";
            for (int i = history.size() - 1; i >= 0; i--) {
                Message msg = history.get(i);
                if (msg instanceof AssistantMessage) {
                    aiResponse = ((AssistantMessage) msg).getText();
                    break;
                }
            }
            
//            log.info("用户输入: {}", userInput);
//            log.info("AI 回复: {}", aiResponse);
            
            agentState = AgentState.FINISHED;

            return aiResponse;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Agent 运行过程中发生错误", e);
            throw new RuntimeException("Agent 运行过程中发生错误", e);
        } finally {
            // 重置状态以便下次使用
            agentState = AgentState.IDLE;
        }
    }
}
