package com.kama.jmindops.agent;

import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JMindOpsDeterministicRagTest {

    @Test
    void executesKnowledgeToolExactlyOnceAndRefusesWithoutEvidence() {
        ChatClient chatClient = mock(ChatClient.class);
        ToolCallback knowledgeCallback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(definition.name()).thenReturn("KnowledgeTool");
        when(knowledgeCallback.getToolDefinition()).thenReturn(definition);
        when(knowledgeCallback.call(org.mockito.ArgumentMatchers.anyString())).thenReturn("");
        String query = "请仅根据我的知识库回答：不存在的问题";

        JMindOps runtime = new JMindOps(
                "agent", "agent", "", "", chatClient,
                20, 0.1, 0.9,
                List.of(new UserMessage(query)),
                List.of(knowledgeCallback),
                List.of(KnowledgeBaseDTO.builder().id("kb-1").name("test").build()),
                "session", "generation", publisher, null,
                RoutingDecision.RAG, query,
                AgentExecutionPolicy.plan(RoutingDecision.RAG, query),
                Duration.ofSeconds(60));

        runtime.run();

        verify(knowledgeCallback).call(argThat(arguments ->
                arguments.contains("\"kbsId\":\"kb-1\"")
                        && arguments.contains("不存在的问题")));
        verifyNoInteractions(chatClient);
    }
}
