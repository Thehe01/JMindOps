package com.kama.jmindops.service;

import com.kama.jmindops.model.dto.ChatMessageDTO;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.model.request.UpdateChatMessageRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
