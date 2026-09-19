package com.kama.jmindops.agent;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.config.ChatClientRegistry;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.service.ChatMessageFacadeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service

public class RouterAgent {
    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);
    private static final List<String> STRONG_RAG_SIGNALS = List.of(
            "仅根据我的知识库", "只根据我的知识库", "根据我的知识库",
            "仅根据知识库", "只根据知识库", "知识库文档", "上传的文档"
    );
    private static final List<String> RAG_SIGNALS = List.of(
            "知识库", "上传的文档", "上传文档", "文档中", "文档里", "资料中", "资料里",
            "根据我的文档", "根据文档", "引用来源", "给出来源"
    );
    private static final List<String> MCP_ACTIONS = List.of(
            "读取", "打开", "列出", "写入", "修改", "删除", "创建", "执行", "查询", "发送", "发邮件"
    );
    private static final List<String> MCP_TARGETS = List.of(
            "工作区", "文件", "目录", "数据库", "数据表", "sql", "邮件", "email", ".env"
    );
    private static final List<String> DATABASE_TARGETS = List.of(
            "数据库", "数据表", "sql"
    );
    private static final List<String> SQL_STATEMENT_SIGNALS = List.of(
            "select", "insert", "update", "delete from", "grant", "revoke", "alter table", "drop table"
    );
    private static final List<String> WEATHER_SIGNALS = List.of(
            "天气", "气温", "下雨", "降雨", "降雪", "带伞"
    );
    private static final List<String> CHAT_SIGNALS = List.of(
            "你好", "您好", "介绍一下你自己", "什么是", "解释什么是", "学习", "建议",
            "不需要查资料", "不用查资料", "无需查资料"
    );
    private static final List<String> EXPLICIT_NO_TOOL_SIGNALS = List.of(
            "不需要查询", "无需查询", "不用查询", "不要查询", "不查实时",
            "不需要查", "无需查", "不用查", "不要查",
            "不需要读取", "无需读取", "不用读取", "不要读取",
            "不需要连接", "无需连接", "不用连接", "不要连接"
    );
    private static final List<String> EXPLANATION_SIGNALS = List.of(
            "解释", "说明", "说说", "为什么", "区别", "原理", "语法", "介绍"
    );
    private static final List<String> TEXT_TRANSFORMATION_SIGNALS = List.of(
            "改写", "润色", "翻译", "改得简洁", "改得正式", "概括这句话"
    );
    private static final List<String> CONTEXTUAL_REWRITE_SIGNALS = List.of(
            "它", "这个", "那个", "上述", "上面", "前面", "刚才", "之前", "其中", "该问题",
            "这样", "继续", "接着", "然后呢", "那怎么办", "那怎么做", "为什么呢"
    );


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
            RoutingDecision policyDecision = routeByExplicitSignal(userMessage);
            if (policyDecision != null) {
                log.info("[RouterAgent] Policy routing decision: {}", policyDecision);
                return policyDecision;
            }

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
                - RAG：用户明确要求依据其知识库、上传文档或指定资料作答，并需要检索证据的问题。
                - MCP：要求操作数据库（如查询数据表、人员名单）、读写本地文件等需要外部系统/工具协助的指令。
                - CHAT：普通问候、闲聊、通用知识解释、学习建议；只要不依赖用户私有资料或外部工具，即使主题很专业也属于 CHAT。

                【边界示例】
                - “解释什么是依赖注入” => CHAT
                - “给我三个学习 Java 的建议” => CHAT
                - “根据我的知识库解释依赖注入并给出处” => RAG
                - “读取工作区 notes.md” => MCP

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

    private RoutingDecision routeByExplicitSignal(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return RoutingDecision.CHAT;
        }
        String normalized = userMessage.trim().toLowerCase();
        // 显式限定知识来源时直接锁定 RAG。问题中即使讨论 SQL、数据表等主题，
        // 也不等于要求操作真实数据库。
        if (containsAny(normalized, STRONG_RAG_SIGNALS)) {
            return RoutingDecision.RAG;
        }
        // 关键词本身不等于工具意图。“不要读取文件，只解释语法”与
        // “不用查实时天气，说明原理”都明确撤销了外部操作，应走普通对话。
        if (containsAny(normalized, EXPLICIT_NO_TOOL_SIGNALS)
                && containsAny(normalized, EXPLANATION_SIGNALS)) {
            return RoutingDecision.CHAT;
        }
        if (containsAny(normalized, TEXT_TRANSFORMATION_SIGNALS)) {
            return RoutingDecision.CHAT;
        }
        // 显式 SQL 语句代表数据库操作；普通“知识库”主题词不能把它覆盖成 RAG。
        // 若用户明确限定“根据我的知识库”，上面的 STRONG_RAG_SIGNALS 仍优先。
        if (containsAny(normalized, SQL_STATEMENT_SIGNALS)) {
            return RoutingDecision.MCP;
        }
        // “查询数据库中的知识库”描述的是数据库对象，不是要求检索知识库内容。
        // 只让明确的数据库动作覆盖 RAG 信号，仍保留知识库提示注入用例走 RAG。
        if (containsAny(normalized, MCP_ACTIONS) && containsAny(normalized, DATABASE_TARGETS)) {
            return RoutingDecision.MCP;
        }
        if (containsAny(normalized, RAG_SIGNALS)) {
            return RoutingDecision.RAG;
        }
        if (containsAny(normalized, MCP_ACTIONS) && containsAny(normalized, MCP_TARGETS)) {
            return RoutingDecision.MCP;
        }
        if (containsAny(normalized, WEATHER_SIGNALS)) {
            return RoutingDecision.WEATHER;
        }
        if (containsAny(normalized, CHAT_SIGNALS)) {
            return RoutingDecision.CHAT;
        }
        return null;
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    public String rewrite(String sessionId, String userMessage) {
        try {
            List<ChatMessageDTO> history = chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, 5);
            if (history == null || history.isEmpty()) {
                return userMessage;
            }

            List<ChatMessageDTO> contextHistory = new ArrayList<>(history);
            ChatMessageDTO latest = contextHistory.get(contextHistory.size() - 1);
            if (latest.getRole() == ChatMessageDTO.RoleType.USER
                    && sameMessage(latest.getContent(), userMessage)) {
                contextHistory.remove(contextHistory.size() - 1);
            }
            if (contextHistory.isEmpty() || !needsContextualRewrite(userMessage)) {
                return userMessage;
            }

            StringBuilder historyText = new StringBuilder();
            for (ChatMessageDTO msg : contextHistory) {
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

    private boolean needsContextualRewrite(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = userMessage.trim().toLowerCase();
        return containsAny(normalized, CONTEXTUAL_REWRITE_SIGNALS);
    }

    private boolean sameMessage(String first, String second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return first.trim().equals(second.trim());
    }
}
