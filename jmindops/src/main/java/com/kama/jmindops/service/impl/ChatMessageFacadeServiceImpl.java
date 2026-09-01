package com.kama.jmindops.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jmindops.converter.ChatMessageConverter;
import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChatMessageMapper;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.model.entity.ChatMessage;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.model.request.UpdateChatMessageRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.response.GetChatMessagesResponse;
import com.kama.jmindops.model.vo.ChatMessageVO;
import com.kama.jmindops.service.ChatMessageFacadeService;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.model.entity.ChatSession;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ApplicationEventPublisher publisher;
    private final ResourceAccessService resourceAccessService;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    public ChatMessageFacadeServiceImpl(ChatMessageMapper chatMessageMapper, ChatMessageConverter chatMessageConverter, ApplicationEventPublisher publisher, ResourceAccessService resourceAccessService, ChatGenerationCoordinator chatGenerationCoordinator) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatMessageConverter = chatMessageConverter;
        this.publisher = publisher;
        this.resourceAccessService = resourceAccessService;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
    }


    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        resourceAccessService.requireOwnedChatSession(sessionId);
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();

        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageVO vo = chatMessageConverter.toVO(chatMessage);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BizException("查询消息数量必须在 1 到 100 之间");
        }
        resourceAccessService.requireOwnedChatSession(sessionId);
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageDTO dto = chatMessageConverter.toDTO(chatMessage);
                result.add(dto);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        validateExternalCreateRequest(request);
        ChatSession session = resourceAccessService.requireOwnedChatSession(request.getSessionId());
        if (!Objects.equals(session.getAgentId(), request.getAgentId())) {
            throw new BizException("消息所属智能体与会话不匹配");
        }
        String generationId = chatGenerationCoordinator.reserve(request.getSessionId());
        try {
            // converter 在服务端强制 USER/null metadata，不信任客户端提交的角色或运行元数据。
            ChatMessage chatMessage = doCreateChatMessage(request);
            publisher.publishEvent(new ChatEvent(
                            request.getAgentId(),
                            chatMessage.getSessionId(),
                            chatMessage.getContent(),
                            generationId
                    )
            );
            return CreateChatMessageResponse.builder()
                    .chatMessageId(chatMessage.getId())
                    .generationId(generationId)
                    .build();
        } catch (RuntimeException exception) {
            chatGenerationCoordinator.release(request.getSessionId(), generationId);
            throw exception;
        }
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        resourceAccessService.requireOwnedChatSession(chatMessageDTO.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        resourceAccessService.requireOwnedChatSession(request.getSessionId());
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 和 createChatMessage 的区别在于，Agent 创建的 chatMessage 不需要发布事件
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        // 将 CreateChatMessageRequest 转换为 ChatMessageDTO
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        // 将 ChatMessageDTO 转换为 ChatMessage 实体
        return doCreateChatMessage(chatMessageDTO);
    }

    private void validateExternalCreateRequest(CreateChatMessageRequest request) {
        if (request == null) {
            throw new BizException("消息请求不能为空");
        }
        if (!StringUtils.hasText(request.getAgentId()) || request.getAgentId().length() > 64) {
            throw new BizException("agentId 不能为空且长度不能超过 64");
        }
        if (!StringUtils.hasText(request.getSessionId()) || request.getSessionId().length() > 64) {
            throw new BizException("sessionId 不能为空且长度不能超过 64");
        }
        if (!StringUtils.hasText(request.getContent()) || request.getContent().length() > 16_000) {
            throw new BizException("消息内容不能为空且长度不能超过 16000");
        }
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        try {
            // 将 ChatMessageDTO 转换为 ChatMessage 实体
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            chatMessage.setCreatedAt(now);
            chatMessage.setUpdatedAt(now);
            // 插入数据库，ID 由数据库自动生成
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw new BizException("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天消息时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        // 查询现有的聊天消息
        ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }
        resourceAccessService.requireOwnedChatSession(existingChatMessage.getSessionId());

        // 将追加内容添加到现有内容后面
        String currentContent = existingChatMessage.getContent() != null
                ? existingChatMessage.getContent()
                : "";
        String updatedContent = currentContent + appendContent;

        // 创建更新后的消息对象
        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        // 更新数据库
        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw new BizException("追加聊天消息内容失败");
        }

        // 返回聊天消息ID
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }
        resourceAccessService.requireOwnedChatSession(chatMessage.getSessionId());

        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw new BizException("删除聊天消息失败");
        }
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        try {
            // 查询现有的聊天消息
            ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
            if (existingChatMessage == null) {
                throw new BizException("聊天消息不存在: " + chatMessageId);
            }
            resourceAccessService.requireOwnedChatSession(existingChatMessage.getSessionId());

            // 将现有 ChatMessage 转换为 ChatMessageDTO
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);

            // 使用 UpdateChatMessageRequest 更新 ChatMessageDTO
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            // 将更新后的 ChatMessageDTO 转换回 ChatMessage 实体
            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 保留原有的 ID、sessionId、role 和创建时间
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw new BizException("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天消息时发生序列化错误: " + e.getMessage());
        }
    }
}

