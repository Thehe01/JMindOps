package com.kama.jmindops.service.impl;

import com.kama.jmindops.converter.ChatMessageConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChatMessageMapper;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.GenerationRequestFingerprint;
import com.kama.jmindops.service.GenerationTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageFacadeIdempotencyTest {
    private static final String USER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String REQUEST_ID = "20000000-0000-0000-0000-000000000002";
    private static final String AGENT_ID = "30000000-0000-0000-0000-000000000003";
    private static final String SESSION_ID = "40000000-0000-0000-0000-000000000004";

    @Test
    void replaysTheOriginalTaskWithoutCreatingAnotherMessage() {
        Fixture fixture = fixture("hello");

        CreateChatMessageResponse response = fixture.service.createChatMessage(request("hello"));

        assertThat(response.isIdempotentReplay()).isTrue();
        assertThat(response.getGenerationId()).isEqualTo("generation-1");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        verify(fixture.coordinator, never()).reserve(SESSION_ID);
        verify(fixture.publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsReuseOfARequestIdForDifferentContent() {
        Fixture fixture = fixture("original");

        assertThatThrownBy(() -> fixture.service.createChatMessage(request("changed")))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(fixture.coordinator, never()).reserve(SESSION_ID);
    }

    private Fixture fixture(String persistedContent) {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ResourceAccessService accessService = mock(ResourceAccessService.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                mapper, converter, publisher, accessService, coordinator, taskStore);

        when(accessService.requireOwnedChatSession(SESSION_ID)).thenReturn(ChatSession.builder()
                .id(SESSION_ID).ownerId(USER_ID).agentId(AGENT_ID).build());
        when(accessService.currentUserId()).thenReturn(USER_ID);
        String fingerprint = GenerationRequestFingerprint.create(AGENT_ID, SESSION_ID, persistedContent);
        GenerationTask task = new GenerationTask(
                "generation-1", null, USER_ID, "alice", "USER", REQUEST_ID, fingerprint,
                AGENT_ID, SESSION_ID, "message-1", persistedContent, GenerationTask.Status.RUNNING,
                1, null, null, null, null, null, null, null);
        when(taskStore.findByUserAndRequestId(USER_ID, REQUEST_ID)).thenReturn(Optional.of(task));
        return new Fixture(service, publisher, coordinator);
    }

    private CreateChatMessageRequest request(String content) {
        return CreateChatMessageRequest.builder()
                .requestId(REQUEST_ID)
                .agentId(AGENT_ID)
                .sessionId(SESSION_ID)
                .content(content)
                .build();
    }

    private record Fixture(
            ChatMessageFacadeServiceImpl service,
            ApplicationEventPublisher publisher,
            ChatGenerationCoordinator coordinator
    ) {
    }
}

