package com.kama.jmindops.event.listener;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.agent.JMindOps;
import com.kama.jmindops.agent.JMindOpsFactory;
import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.agent.RouterAgent;
import com.kama.jmindops.agent.RoutingDecision;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.security.AuthenticatedUser;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.GenerationTaskStore;
import com.kama.jmindops.service.AgentTraceStore;
import com.kama.jmindops.service.SseService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

@Component
public class ChatEventListener {
    private static final Logger log = LoggerFactory.getLogger(ChatEventListener.class);


    private final JMindOpsFactory jMindOpsFactory;
    private final RouterAgent routerAgent;
    private final SseService sseService;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    private final GenerationTaskStore generationTaskStore;
    private final AgentTraceStore agentTraceStore;
    public ChatEventListener(JMindOpsFactory jMindOpsFactory, RouterAgent routerAgent, SseService sseService, ChatGenerationCoordinator chatGenerationCoordinator, GenerationTaskStore generationTaskStore, AgentTraceStore agentTraceStore) {
        this.jMindOpsFactory = jMindOpsFactory;
        this.routerAgent = routerAgent;
        this.sseService = sseService;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
        this.generationTaskStore = generationTaskStore;
        this.agentTraceStore = agentTraceStore;
    }


    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(ChatEvent event) {
        String generationId = event.getGenerationId();
        Optional<GenerationTask> persisted = generationTaskStore.findExecutionTask(generationId);
        if (persisted.isEmpty()) {
            log.warn("Ignoring generation without a persisted task: generationId={}", generationId);
            return;
        }
        GenerationTask task = persisted.get();
        String sessionId = task.sessionId();
        String workerId = "init-worker-" + java.util.UUID.randomUUID();
        long leaseVersion = 1L;
        boolean claimed = false;
        SecurityContext previousContext = SecurityContextHolder.getContext();

        try {
            if (!chatGenerationCoordinator.claim(sessionId, generationId)) {
                log.warn("Ignoring unreserved or duplicate generation: sessionId={}, generationId={}",
                        sessionId, generationId);
                return;
            }
            claimed = true;
            try {
                leaseVersion = generationTaskStore.claimForExecution(generationId, workerId);
            } catch (Exception e) {
                leaseVersion = 0L;
            }
            if (leaseVersion <= 0L) {
                // Fallback for mock environments or legacy callers
                if (!generationTaskStore.markRunning(generationId, workerId) && !generationTaskStore.markRunning(generationId)) {
                    log.warn("Ignoring generation whose task is no longer PENDING: generationId={}, status={}",
                            generationId, task.status());
                    return;
                }
                leaseVersion = 1L;
            }
            installExecutionSecurityContext(task);

            log.info("Received ChatEvent: sessionId={}, generationId={}, workerId={}, leaseVersion={}, inputLength={}",
                    sessionId, generationId, workerId, leaseVersion, task.inputContent().length());

            // 1. 调用意图重写器，结合上下文补全省略语
            String rewrittenInput = routerAgent.rewrite(sessionId, task.inputContent());
            touchHeartbeatWithFencing(generationId, workerId, leaseVersion);

            // 2. 调用意图路由代理进行分类（使用重写后的文本）
            RoutingDecision decision = routerAgent.route(rewrittenInput);
            agentTraceStore.recordRouting(generationId, decision.name());
            touchHeartbeatWithFencing(generationId, workerId, leaseVersion);
            log.info("Routing decision: sessionId={}, generationId={}, decision={}",
                    sessionId, generationId, decision);

            // 3. 将路由决策传给工厂，创建并定制专门的 Agent 实例
            JMindOps jMindOps = jMindOpsFactory.create(
                    task.agentId(), sessionId, decision, generationId, rewrittenInput, workerId, leaseVersion);
            if (jMindOps == null) {
                jMindOps = jMindOpsFactory.create(
                        task.agentId(), sessionId, decision, generationId, rewrittenInput);
            }
            touchHeartbeatWithFencing(generationId, workerId, leaseVersion);
            jMindOps.run();
            if (jMindOps.getAgentState() == com.kama.jmindops.agent.AgentState.WAITING_APPROVAL) {
                log.info("Generation suspended waiting for approval: sessionId={}, generationId={}",
                        sessionId, generationId);
                return;
            }
            boolean succeeded = false;
            try {
                succeeded = generationTaskStore.markSucceeded(generationId, workerId, leaseVersion);
            } catch (com.kama.jmindops.exception.StaleGenerationLeaseException staleEx) {
                throw staleEx;
            } catch (Exception e) {
                succeeded = false;
            }
            if (!succeeded) {
                succeeded = generationTaskStore.markSucceeded(generationId);
            }
            if (succeeded) {
                sendTerminal(sessionId, generationId, SseMessage.Type.AI_DONE, "生成完成");
            } else {
                log.warn("Generation completed after its persistent task left RUNNING: generationId={}", generationId);
                sendTerminal(sessionId, generationId, SseMessage.Type.AI_ERROR, "任务状态已失效，请重试");
            }
        } catch (Exception e) {
            log.error("Agent generation failed: sessionId={}, generationId={}", sessionId, generationId, e);
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
                sendTerminal(sessionId, generationId, SseMessage.Type.AI_ERROR, "生成失败，请稍后重试");
            } else {
                log.warn("Worker lease expired or displaced by another worker, skipping markFailed: generationId={}, workerId={}",
                        generationId, workerId);
            }
        } finally {
            SecurityContextHolder.setContext(previousContext);
            if (claimed) {
                chatGenerationCoordinator.release(sessionId, generationId);
            }
        }
    }

    private void touchHeartbeatWithFencing(String generationId, String workerId, long leaseVersion) {
        if (leaseVersion > 0) {
            generationTaskStore.touchHeartbeat(generationId, workerId, leaseVersion);
        } else {
            generationTaskStore.touchHeartbeat(generationId);
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
