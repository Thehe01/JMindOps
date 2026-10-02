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
import com.kama.jmindops.ai.AssistantMessage;
import com.kama.jmindops.ai.Message;
import com.kama.jmindops.ai.ToolResponseMessage;
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
                long promptTokens = event.getUsage().getPromptTokens() == null ? 0L : event.getUsage().getPromptTokens();
                long completionTokens = event.getUsage().getGenerationTokens() == null ? 0L : event.getUsage().getGenerationTokens();

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
