package com.kama.jmindops.service;

import com.kama.jmindops.agent.AgentState;
import com.kama.jmindops.agent.JMindOps;
import com.kama.jmindops.agent.JMindOpsFactory;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentResumeService {
    private static final Logger log = LoggerFactory.getLogger(AgentResumeService.class);

    private final GenerationTaskStore generationTaskStore;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    private final JMindOpsFactory jMindOpsFactory;
    private final SseService sseService;

    public AgentResumeService(
            GenerationTaskStore generationTaskStore,
            ChatGenerationCoordinator chatGenerationCoordinator,
            JMindOpsFactory jMindOpsFactory,
            SseService sseService
    ) {
        this.generationTaskStore = generationTaskStore;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
        this.jMindOpsFactory = jMindOpsFactory;
        this.sseService = sseService;
    }

    public void resume(String generationId) {
        Optional<GenerationTask> taskOpt = generationTaskStore.findExecutionTask(generationId);
        if (taskOpt.isEmpty()) {
            throw new IllegalArgumentException("Generation task not found: " + generationId);
        }
        GenerationTask task = taskOpt.get();
        String sessionId = task.sessionId();
        String workerId = "resume-worker-" + UUID.randomUUID();
        long newLeaseVersion = 0L;
        boolean claimed = false;
        SecurityContext previousContext = SecurityContextHolder.getContext();

        try {
            if (!chatGenerationCoordinator.claimForResume(sessionId, generationId)) {
                log.warn("无法获得协调锁以恢复生成: sessionId={}, generationId={}", sessionId, generationId);
                return;
            }
            claimed = true;

            newLeaseVersion = generationTaskStore.claimForResume(generationId, workerId);
            runAgentWithFencing(task, workerId, newLeaseVersion);
        } catch (Exception e) {
            handleResumeException(sessionId, generationId, workerId, newLeaseVersion, e);
            throw e;
        } finally {
            SecurityContextHolder.setContext(previousContext);
            if (claimed) {
                chatGenerationCoordinator.release(sessionId, generationId);
            }
        }
    }

    public void resumeClaimed(GenerationTask task) {
        String sessionId = task.sessionId();
        String generationId = task.id();
        String workerId = task.workerId();
        long leaseVersion = task.leaseVersion();
        boolean claimed = false;
        SecurityContext previousContext = SecurityContextHolder.getContext();

        try {
            if (!chatGenerationCoordinator.claimForResume(sessionId, generationId)) {
                log.warn("无法获得协调锁以恢复已认领生成: sessionId={}, generationId={}", sessionId, generationId);
                return;
            }
            claimed = true;

            runAgentWithFencing(task, workerId, leaseVersion);
        } catch (Exception e) {
            handleResumeException(sessionId, generationId, workerId, leaseVersion, e);
            throw e;
        } finally {
            SecurityContextHolder.setContext(previousContext);
            if (claimed) {
                chatGenerationCoordinator.release(sessionId, generationId);
            }
        }
    }

    private void runAgentWithFencing(GenerationTask task, String workerId, long leaseVersion) {
        installExecutionSecurityContext(task);
        String sessionId = task.sessionId();
        String generationId = task.id();

        log.info("开始从 Checkpoint 恢复执行 Generation: sessionId={}, generationId={}, workerId={}, leaseVersion={}",
                sessionId, generationId, workerId, leaseVersion);

        JMindOps jMindOps = jMindOpsFactory.createForResume(task, workerId, leaseVersion);
        jMindOps.run();

        if (jMindOps.getAgentState() == AgentState.WAITING_APPROVAL) {
            log.info("恢复执行的 Generation 再次进入审批挂起状态: generationId={}", generationId);
        } else if (jMindOps.getAgentState() == AgentState.FINISHED) {
            if (generationTaskStore.markSucceeded(generationId, workerId, leaseVersion)) {
                sendTerminal(sessionId, generationId, SseMessage.Type.AI_DONE, "生成完成");
            }
        }
    }

    private void handleResumeException(String sessionId, String generationId, String workerId, long leaseVersion, Exception e) {
        log.error("恢复 Agent 生成失败: sessionId={}, generationId={}", sessionId, generationId, e);
        if (!isStaleLease(e)) {
            try {
                if (leaseVersion > 0) {
                    generationTaskStore.markFailed(generationId, workerId, leaseVersion, e.getMessage());
                } else {
                    generationTaskStore.markFailed(generationId, e.getMessage());
                }
            } catch (Exception persistenceError) {
                log.error("Failed to persist generation failure: generationId={}", generationId, persistenceError);
            }
            sendTerminal(sessionId, generationId, SseMessage.Type.AI_ERROR, "生成恢复失败，请稍后重试");
        } else {
            log.warn("Worker lease expired or displaced by another worker, skipping markFailed: generationId={}, workerId={}",
                    generationId, workerId);
        }
    }

    private boolean isStaleLease(Throwable t) {
        while (t != null) {
            if (t instanceof com.kama.jmindops.exception.StaleGenerationLeaseException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void installExecutionSecurityContext(GenerationTask task) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AuthenticatedUser user = new AuthenticatedUser(task.userId(), task.username(), task.role());
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + task.role()))
        ));
        SecurityContextHolder.setContext(context);
    }

    private void sendTerminal(
            String sessionId,
            String generationId,
            SseMessage.Type type,
            String statusText
    ) {
        sseService.send(sessionId, SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .done(true)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .generationId(generationId)
                        .build())
                .build());
    }
}
