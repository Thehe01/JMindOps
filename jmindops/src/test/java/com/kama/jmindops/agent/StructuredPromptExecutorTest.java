package com.kama.jmindops.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredPromptExecutorTest {

    private StructuredPromptExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new StructuredPromptExecutor();
    }

    @Test
    void succeedsOnFirstAttemptWithoutRepair() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("WEATHER");

        RoutingDecision result = executor.executeWithRepair(
                chatClient,
                "system prompt",
                "user input",
                raw -> raw.contains("WEATHER") ? RoutingDecision.WEATHER : null,
                "Format as WEATHER"
        );

        assertThat(result).isEqualTo(RoutingDecision.WEATHER);
    }

    @Test
    void selfRepairsAndSucceedsOnSecondAttemptWhenFirstFails() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        AtomicInteger callCount = new AtomicInteger(0);
        when(callSpec.content()).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // Attempt 1 returns invalid output with commentary
                return "I think this question is about weather in Beijing tomorrow!";
            } else {
                // Attempt 2 after repair prompt returns clean keyword
                return "WEATHER";
            }
        });

        RoutingDecision result = executor.executeWithRepair(
                chatClient,
                "system prompt",
                "user input",
                raw -> {
                    String trimmed = raw.trim();
                    if (trimmed.equals("WEATHER")) return RoutingDecision.WEATHER;
                    if (trimmed.equals("RAG")) return RoutingDecision.RAG;
                    throw new IllegalArgumentException("Expected single exact keyword");
                },
                2,
                "Must output exact keyword WEATHER or RAG"
        );

        assertThat(result).isEqualTo(RoutingDecision.WEATHER);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void returnsNullWhenAllAttemptsFail() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Invalid response all the time");

        RoutingDecision result = executor.executeWithRepair(
                chatClient,
                "system prompt",
                "user input",
                raw -> {
                    if ("WEATHER".equals(raw.trim())) return RoutingDecision.WEATHER;
                    return null;
                },
                2,
                "Must output WEATHER"
        );

        assertThat(result).isNull();
    }
}
