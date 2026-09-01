package com.kama.jmindops.event.listener;

import com.kama.jmindops.agent.JMindOpsFactory;
import com.kama.jmindops.agent.RouterAgent;
import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.SseService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

    @Test
    void duplicateEventDoesNotReleaseTheRunningGeneration() {
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        RouterAgent routerAgent = mock(RouterAgent.class);
        SseService sseService = mock(SseService.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        ChatEventListener listener = new ChatEventListener(factory, routerAgent, sseService, coordinator);
        ChatEvent event = new ChatEvent("agent-1", "session-1", "hello", "generation-1");
        when(coordinator.claim("session-1", "generation-1")).thenReturn(false);

        listener.handle(event);

        verify(coordinator, never()).release("session-1", "generation-1");
        verifyNoInteractions(factory, routerAgent, sseService);
    }
}
