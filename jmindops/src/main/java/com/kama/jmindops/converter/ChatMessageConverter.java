package com.kama.jmindops.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.model.entity.ChatMessage;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.model.request.UpdateChatMessageRequest;
import com.kama.jmindops.model.vo.ChatMessageVO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class ChatMessageConverter {

    private final ObjectMapper objectMapper;
    public ChatMessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    public ChatMessage toEntity(ChatMessageDTO chatMessageDTO) throws JsonProcessingException {
        Assert.notNull(chatMessageDTO, "ChatMessageDTO cannot be null");
        Assert.notNull(chatMessageDTO.getRole(), "Role cannot be null");

        return ChatMessage.builder()
                .id(chatMessageDTO.getId())
                .sessionId(chatMessageDTO.getSessionId())
                .role(chatMessageDTO.getRole().getRole())
                .content(chatMessageDTO.getContent())
                .metadata(chatMessageDTO.getMetadata() != null
                        ? objectMapper.writeValueAsString(chatMessageDTO.getMetadata())
                        : null)
                .createdAt(chatMessageDTO.getCreatedAt())
                .updatedAt(chatMessageDTO.getUpdatedAt())
                .build();
    }

    public ChatMessageDTO toDTO(ChatMessage chatMessage) throws JsonProcessingException {
        Assert.notNull(chatMessage, "ChatMessage cannot be null");
        Assert.notNull(chatMessage.getRole(), "Role cannot be null");

        return ChatMessageDTO.builder()
                .id(chatMessage.getId())
                .sessionId(chatMessage.getSessionId())
                .role(ChatMessageDTO.RoleType.fromRole(chatMessage.getRole()))
                .content(chatMessage.getContent())
                .metadata(chatMessage.getMetadata() != null
                        ? objectMapper.readValue(chatMessage.getMetadata(), ChatMessageDTO.MetaData.class)
                        : null)
                .createdAt(chatMessage.getCreatedAt())
                .updatedAt(chatMessage.getUpdatedAt())
                .build();
    }

    public ChatMessageVO toVO(ChatMessageDTO dto) {
        return ChatMessageVO.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .role(dto.getRole())
                .content(dto.getContent())
                .metadata(dto.getMetadata())
                .build();
    }

    public ChatMessageVO toVO(ChatMessage chatMessage) throws JsonProcessingException {
        return toVO(toDTO(chatMessage));
    }

    public ChatMessageDTO toDTO(CreateChatMessageRequest request) {
        Assert.notNull(request, "CreateChatMessageRequest cannot be null");
        Assert.notNull(request.getSessionId(), "SessionId cannot be null");

        return ChatMessageDTO.builder()
                .sessionId(request.getSessionId())
                .role(ChatMessageDTO.RoleType.USER)
                .content(request.getContent())
                .metadata(null)
                .build();
    }

    public void updateDTOFromRequest(ChatMessageDTO dto, UpdateChatMessageRequest request) {
        Assert.notNull(dto, "ChatMessageDTO cannot be null");
        Assert.notNull(request, "UpdateChatMessageRequest cannot be null");

        if (request.getContent() != null) {
            dto.setContent(request.getContent());
        }
        // metadata 由服务端记录工具调用、模型和 token 用量，客户端更新时保留原值。
    }
}
