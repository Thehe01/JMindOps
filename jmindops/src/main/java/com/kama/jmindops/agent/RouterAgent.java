package com.kama.jmindops.agent;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.config.ChatClientRegistry;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.service.ChatMessageFacadeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service

public class RouterAgent {
    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);


    private final ChatClientRegistry chatClientRegistry;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final StructuredPromptExecutor structuredPromptExecutor;

    public RouterAgent(
            ChatClientRegistry chatClientRegistry,
            ChatMessageFacadeService chatMessageFacadeService,
            StructuredPromptExecutor structuredPromptExecutor
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.structuredPromptExecutor = structuredPromptExecutor;
    }

    public RoutingDecision route(String userMessage) {
        log.info("[RouterAgent] Analyzing intent, inputLength={}", userMessage == null ? 0 : userMessage.length());
        try {
            ChatClient chatClient = null;
            for (String key : chatClientRegistry.getChatClients().keySet()) {
                chatClient = chatClientRegistry.get(key);
                if (chatClient != null) {
                    break;
                }
            }

            if (chatClient == null) {
                log.warn("[RouterAgent] Cannot find any chat client for routing, defaulting to CHAT.");
                return RoutingDecision.CHAT;
            }

            String systemPrompt = """
                你是一个精准的意图识别分类器。请仔细分析用户输入，并判定其归属于以下且仅属于以下一个意图：
                - WEATHER：询问具体地点或时间的天气状况、气温、下雨等。
                - RAG：询问专业技术知识、企业规章、文档资料、算法原理等需要查阅知识库的问题。
                - MCP：要求操作数据库（如查询数据表、人员名单）、读写本地文件等需要外部系统/工具协助的指令。
                - CHAT：普通的问候、打招呼、闲聊、日常对话或通用问题。

                【输出规范】
                直接输出意图名称（WEATHER、RAG、MCP 或 CHAT）。不要包含任何多余文字、标点或引语！
                """;

            String userPrompt = "用户输入: " + (userMessage == null ? "" : userMessage);

            RoutingDecision decision = structuredPromptExecutor.executeWithRepair(
                    chatClient,
                    systemPrompt,
                    userPrompt,
                    this::parseRoutingDecision,
                    2,
                    "必须且只能输出以下四个英文大写单词之一：WEATHER, RAG, MCP, CHAT。"
            );

            if (decision == null) {
                log.warn("[RouterAgent] Routing failed after repair attempts, defaulting to CHAT.");
                return RoutingDecision.CHAT;
            }

            log.info("[RouterAgent] Final routing decision: {}", decision);
            return decision;
        } catch (Exception e) {
            log.error("[RouterAgent] Routing exception, defaulting to CHAT.", e);
            return RoutingDecision.CHAT;
        }
    }

    private RoutingDecision parseRoutingDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String clean = raw.trim().toUpperCase();
        if (clean.contains("WEATHER")) {
            return RoutingDecision.WEATHER;
        }
        if (clean.contains("RAG")) {
            return RoutingDecision.RAG;
        }
        if (clean.contains("MCP")) {
            return RoutingDecision.MCP;
        }
        if (clean.contains("CHAT")) {
            return RoutingDecision.CHAT;
        }
        throw new IllegalArgumentException("无法从输出 '" + raw + "' 中解析出有效意图 (WEATHER/RAG/MCP/CHAT)");
    }

    public String rewrite(String sessionId, String userMessage) {
        try {
            List<ChatMessageDTO> history = chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, 5);
            if (history == null || history.isEmpty()) {
                return userMessage;
            }

            StringBuilder historyText = new StringBuilder();
            for (ChatMessageDTO msg : history) {
                historyText.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }

            ChatClient chatClient = null;
            for (String key : chatClientRegistry.getChatClients().keySet()) {
                chatClient = chatClientRegistry.get(key);
                if (chatClient != null) {
                    break;
                }
            }

            if (chatClient == null) {
                return userMessage;
            }

            String promptText = """
                你是一个对话意图重写器（Query Rewriter）。
                基于以下最近几轮的对话历史，用户的最新输入可能包含省略语或指代不明的情况。
                请将用户的最新输入重写为一句完整、独立、可以直接理解的问句。

                【要求】
                1. 如果用户的输入本身已经完整，请原样返回。
                2. 如果用户的输入省略了主语、地点、动作等，请结合历史对话补全。
                3. 只输出重写后的句子，不要包含任何多余的解释、标点或引语！

                【对话历史】
                %s

                【最新输入】
                %s
                """;

            String response = chatClient.prompt()
                    .system(String.format(promptText, historyText.toString(), userMessage))
                    .call()
                    .content();

            if (response != null && !response.trim().isEmpty()) {
                String rewritten = response.trim();
                log.info("[RouterAgent] Query rewritten, originalLength={}, rewrittenLength={}",
                        userMessage == null ? 0 : userMessage.length(), rewritten.length());
                return rewritten;
            }

        } catch (Exception e) {
            log.warn("[RouterAgent] Rewrite failed, fallback to original query.", e);
        }
        return userMessage;
    }
}
