package com.kama.jmindops.service;

import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.model.request.RetryGenerationTaskRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.response.GenerationTaskResponse;
import com.kama.jmindops.model.response.AgentTraceResponse;
import com.kama.jmindops.security.ResourceAccessService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class GenerationTaskFacadeService {
    private final GenerationTaskStore generationTaskStore;
    private final ResourceAccessService resourceAccessService;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    private final ApplicationEventPublisher publisher;
    private final AgentTraceStore agentTraceStore;

    public GenerationTaskFacadeService(
            GenerationTaskStore generationTaskStore,
            ResourceAccessService resourceAccessService,
            ChatGenerationCoordinator chatGenerationCoordinator,
            ApplicationEventPublisher publisher,
            AgentTraceStore agentTraceStore
    ) {
        this.generationTaskStore = generationTaskStore;
        this.resourceAccessService = resourceAccessService;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
        this.publisher = publisher;
        this.agentTraceStore = agentTraceStore;
    }

    public GenerationTaskResponse getOwnedTask(String generationId) {
        requireUuid(generationId, "generationId");
        String userId = resourceAccessService.currentUserId();
        GenerationTask task = generationTaskStore.findOwnedById(generationId, userId)
                .orElseThrow(() -> new BizException(404, "生成任务不存在"));
        return GenerationTaskResponse.from(task);
    }

    public AgentTraceResponse getOwnedTrace(String generationId) {
        requireUuid(generationId, "generationId");
        String userId = resourceAccessService.currentUserId();
        generationTaskStore.findOwnedById(generationId, userId)
                .orElseThrow(() -> new BizException(404, "生成任务不存在"));
        return agentTraceStore.findTrace(generationId)
                .orElseThrow(() -> new BizException(404, "Agent Trace 不存在"));
    }

    @Transactional
    public CreateChatMessageResponse retry(String generationId, RetryGenerationTaskRequest request) {
        requireUuid(generationId, "generationId");
        String userId = resourceAccessService.currentUserId();
        GenerationTask failedTask = generationTaskStore.findOwnedById(generationId, userId)
                .orElseThrow(() -> new BizException(404, "生成任务不存在"));
        if (!failedTask.isRetryable()) {
            throw new BizException(409, "只有 FAILED 状态的生成任务可以重试");
        }

        ChatSession session = resourceAccessService.requireOwnedChatSession(failedTask.sessionId());
        if (!Objects.equals(session.getAgentId(), failedTask.agentId())) {
            throw new BizException(409, "原任务的智能体与会话绑定关系已变化，无法重试");
        }

        String requestId = normalizeRequestId(request == null ? null : request.getRequestId());
        String fingerprint = GenerationRequestFingerprint.create(
                failedTask.agentId(), failedTask.sessionId(), failedTask.inputContent());
        generationTaskStore.lockIdempotencyKey(userId, requestId);
        Optional<GenerationTask> replay = generationTaskStore.findByUserAndRequestId(userId, requestId);
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) {
                throw new BizException(409, "同一 requestId 不能用于不同的重试请求");
            }
            return toCreateResponse(replay.get(), true);
        }

        String newGenerationId = chatGenerationCoordinator.reserve(failedTask.sessionId());
        releaseReservationOnRollback(failedTask.sessionId(), newGenerationId);
        try {
            generationTaskStore.createPending(
                    newGenerationId,
                    failedTask.id(),
                    userId,
                    requestId,
                    fingerprint,
                    failedTask.agentId(),
                    failedTask.sessionId(),
                    failedTask.userMessageId(),
                    failedTask.inputContent()
            );
            publisher.publishEvent(new ChatEvent(
                    failedTask.agentId(),
                    failedTask.sessionId(),
                    failedTask.inputContent(),
                    newGenerationId
            ));
            return CreateChatMessageResponse.builder()
                    .chatMessageId(failedTask.userMessageId())
                    .generationId(newGenerationId)
                    .requestId(requestId)
                    .status(GenerationTask.Status.PENDING.name())
                    .idempotentReplay(false)
                    .build();
        } catch (RuntimeException exception) {
            chatGenerationCoordinator.release(failedTask.sessionId(), newGenerationId);
            throw exception;
        }
    }

    private CreateChatMessageResponse toCreateResponse(GenerationTask task, boolean idempotentReplay) {
        return CreateChatMessageResponse.builder()
                .chatMessageId(task.userMessageId())
                .generationId(task.id())
                .requestId(task.requestId())
                .status(task.status().name())
                .idempotentReplay(idempotentReplay)
                .build();
    }

    private String normalizeRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return UUID.randomUUID().toString();
        }
        requireUuid(requestId, "requestId");
        return UUID.fromString(requestId).toString();
    }

    private void requireUuid(String value, String fieldName) {
        try {
            if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical uuid");
            }
        } catch (IllegalArgumentException exception) {
            throw new BizException(400, fieldName + " 必须是标准 UUID");
        }
    }

    private void releaseReservationOnRollback(String sessionId, String generationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    chatGenerationCoordinator.release(sessionId, generationId);
                }
            }
        });
    }
}
