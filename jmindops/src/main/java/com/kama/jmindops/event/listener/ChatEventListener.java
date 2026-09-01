package com.kama.jmindops.event.listener;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.agent.JMindOps;
import com.kama.jmindops.agent.JMindOpsFactory;
import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.agent.RouterAgent;
import com.kama.jmindops.agent.RoutingDecision;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.SseService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ChatEventListener {
    private static final Logger log = LoggerFactory.getLogger(ChatEventListener.class);


    private final JMindOpsFactory jMindOpsFactory;
    private final RouterAgent routerAgent;
    private final SseService sseService;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    public ChatEventListener(JMindOpsFactory jMindOpsFactory, RouterAgent routerAgent, SseService sseService, ChatGenerationCoordinator chatGenerationCoordinator) {
        this.jMindOpsFactory = jMindOpsFactory;
        this.routerAgent = routerAgent;
        this.sseService = sseService;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
    }


    @Async
    @EventListener
    public void handle(ChatEvent event) {
        String sessionId = event.getSessionId();
        String generationId = event.getGenerationId();
        boolean claimed = false;

        try {
            if (!chatGenerationCoordinator.claim(sessionId, generationId)) {
                log.warn("Ignoring unreserved or duplicate generation: sessionId={}, generationId={}",
                        sessionId, generationId);
                return;
            }
            claimed = true;

            log.info("Received ChatEvent: sessionId={}, generationId={}, inputLength={}",
                    sessionId, generationId, event.getUserInput() == null ? 0 : event.getUserInput().length());

            // 1. 调用意图重写器，结合上下文补全省略语
            String rewrittenInput = routerAgent.rewrite(sessionId, event.getUserInput());

            // 2. 调用意图路由代理进行分类（使用重写后的文本）
            RoutingDecision decision = routerAgent.route(rewrittenInput);
            log.info("Routing decision: sessionId={}, generationId={}, decision={}",
                    sessionId, generationId, decision);

            // 3. 将路由决策传给工厂，创建并定制专门的 Agent 实例
            JMindOps jMindOps = jMindOpsFactory.create(
                    event.getAgentId(), sessionId, decision, generationId);
            jMindOps.run();
            sendTerminal(sessionId, generationId, SseMessage.Type.AI_DONE, "生成完成");
        } catch (Exception e) {
            log.error("Agent generation failed: sessionId={}, generationId={}", sessionId, generationId, e);
            sendTerminal(sessionId, generationId, SseMessage.Type.AI_ERROR, "生成失败，请稍后重试");
        } finally {
            if (claimed) {
                chatGenerationCoordinator.release(sessionId, generationId);
            }
        }
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
