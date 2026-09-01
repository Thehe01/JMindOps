package com.kama.jmindops.agent;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 结构化 Prompt 执行器与自愈重试组件。
 * 当大模型输出未遵循格式规范（JSON 错误、枚举不匹配、多余标点等）时，
 * 自动捕获错误并将“上次模型输出 + 失败原因 + 格式约束”注入 Repair Prompt 执行自愈重试。
 */
@Component

public class StructuredPromptExecutor {
    private static final Logger log = LoggerFactory.getLogger(StructuredPromptExecutor.class);


    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    /**
     * 带自愈重试的结构化 Prompt 执行方法
     *
     * @param chatClient        Spring AI ChatClient 实例
     * @param systemPrompt      初始系统提示词
     * @param userPrompt        用户输入提示词
     * @param parser            结构化解析函数（解析失败需抛出异常或返回 null）
     * @param formatInstruction 严格格式规范说明（供修复重试时强化提示）
     * @param <T>               返回的目标类型
     * @return 解析成功的目标对象
     */
    public <T> T executeWithRepair(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            Function<String, T> parser,
            String formatInstruction
    ) {
        return executeWithRepair(chatClient, systemPrompt, userPrompt, parser, DEFAULT_MAX_ATTEMPTS, formatInstruction);
    }

    /**
     * 带自定义最大重试次数的结构化 Prompt 执行方法
     */
    public <T> T executeWithRepair(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            Function<String, T> parser,
            int maxAttempts,
            String formatInstruction
    ) {
        if (chatClient == null) {
            throw new IllegalArgumentException("ChatClient must not be null");
        }

        int attempts = 0;
        String lastResponse = "";
        String lastErrorReason = "";

        while (attempts < maxAttempts) {
            attempts++;
            try {
                String currentSystemPrompt;
                if (attempts == 1) {
                    currentSystemPrompt = systemPrompt;
                } else {
                    currentSystemPrompt = buildRepairSystemPrompt(systemPrompt, lastResponse, lastErrorReason, formatInstruction);
                    log.warn("[StructuredPromptExecutor] Triggering self-repair retry attempt {}/{}", attempts, maxAttempts);
                }

                String response = chatClient.prompt()
                        .system(currentSystemPrompt)
                        .user(userPrompt == null ? "" : userPrompt)
                        .call()
                        .content();

                lastResponse = response == null ? "" : response.trim();
                T result = parser.apply(lastResponse);
                if (result != null) {
                    if (attempts > 1) {
                        log.info("[StructuredPromptExecutor] Successfully self-repaired on attempt {}", attempts);
                    }
                    return result;
                }
                lastErrorReason = "解析函数返回空值，未能提取有效数据";
            } catch (Exception e) {
                lastErrorReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("[StructuredPromptExecutor] Attempt {} failed to parse output: error={}", attempts, lastErrorReason);
            }
        }

        log.error("[StructuredPromptExecutor] All {} attempts failed. Last response: '{}', last error: '{}'",
                maxAttempts, lastResponse, lastErrorReason);
        return null;
    }

    private String buildRepairSystemPrompt(
            String originalSystemPrompt,
            String lastResponse,
            String lastErrorReason,
            String formatInstruction
    ) {
        String truncatedResponse = lastResponse.length() > 300
                ? lastResponse.substring(0, 300) + "..."
                : lastResponse;

        return String.format("""
                %s

                【系统自愈修复指令 - 重要】
                你上一轮的输出未通过校验，导致系统解析失败！
                - 上一轮输出内容：
                ```
                %s
                ```
                - 解析失败原因：%s

                【强制格式要求】
                %s
                请立刻修正上述错误，直接输出最终有效结果，绝对不要包含任何解释、说明或问候语！
                """,
                originalSystemPrompt,
                truncatedResponse,
                lastErrorReason,
                formatInstruction == null ? "必须严格按照要求的格式输出。" : formatInstruction
        );
    }
}
