package com.kama.jmindops.event;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.converter.ChatMessageConverter;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.vo.ChatMessageVO;
import com.kama.jmindops.service.ChatMessageFacadeService;
import com.kama.jmindops.service.SseService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
public class AgentMessageListener {
    private static final Logger log = LoggerFactory.getLogger(AgentMessageListener.class);


    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final SseService sseService;

    public AgentMessageListener(ChatMessageFacadeService chatMessageFacadeService, 
                                ChatMessageConverter chatMessageConverter, 
                                SseService sseService) {
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.sseService = sseService;
    }

    @EventListener
    public void onAgentMessageGenerated(AgentMessageGeneratedEvent event) {
        String chatSessionId = event.getChatSessionId();
        Message message = event.getGeneratedMessage();
        
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO.MetaData.MetaDataBuilder metaDataBuilder = ChatMessageDTO.MetaData.builder()
                    .toolCalls(assistantMessage.getToolCalls());

            if (event.getUsage() != null) {
                Long promptTokens = 0L;
                Long completionTokens = 0L;
                
                // Safe extraction of prompt tokens (could be Integer or Long depending on Spring AI version)
                try {
                    Object pt = event.getUsage().getClass().getMethod("getPromptTokens").invoke(event.getUsage());
                    if (pt instanceof Number num) promptTokens = num.longValue();
                } catch (Exception ignored) {}

                // Safe extraction of completion tokens (method name changed across Spring AI versions)
                try {
                    Object ct = event.getUsage().getClass().getMethod("getGenerationTokens").invoke(event.getUsage());
                    if (ct instanceof Number num) completionTokens = num.longValue();
                } catch (Exception ignored) {
                    try {
                        Object ct = event.getUsage().getClass().getMethod("getCompletionTokens").invoke(event.getUsage());
                        if (ct instanceof Number num) completionTokens = num.longValue();
                    } catch (Exception ignored2) {}
                }

                if (promptTokens > 0 || completionTokens > 0) {
                    metaDataBuilder.usage(ChatMessageDTO.TokenUsage.builder()
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .totalTokens(promptTokens + completionTokens)
                            .build());
                }
            }
            if (event.getLatencyMs() != null) {
                metaDataBuilder.latencyMs(event.getLatencyMs());
            }
            if (event.getModel() != null) {
                metaDataBuilder.model(event.getModel());
            }

            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(chatSessionId)
                    .metadata(metaDataBuilder.build())
                    .build();
            saveAndPush(chatMessageDTO, event.getGenerationId());
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                saveAndPush(chatMessageDTO, event.getGenerationId());
            }
        } else {
            log.warn("无需处理的 Message 类型: {}", message.getClass().getName());
        }
    }

    private void saveAndPush(ChatMessageDTO chatMessageDTO, String generationId) {
        // 1. 持久化
        CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(chatMessage.getChatMessageId());
        
        // 2. 转换推流
        ChatMessageVO vo = chatMessageConverter.toVO(chatMessageDTO);
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .generationId(generationId)
                        .build())
                .build();
        sseService.send(chatMessageDTO.getSessionId(), sseMessage);
    }

    @EventListener
    public void onAgentMessageChunk(AgentMessageChunkEvent event) {
        String chatSessionId = event.getChatSessionId();
        String chunkText = event.getChunkText();

        ChatMessageVO vo = ChatMessageVO.builder()
                .content(chunkText)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .build();

        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT_CHUNK)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .generationId(event.getGenerationId())
                        .build())
                .build();
        sseService.send(chatSessionId, sseMessage);
    }
}
