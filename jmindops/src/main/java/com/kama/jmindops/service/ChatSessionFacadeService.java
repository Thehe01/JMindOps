package com.kama.jmindops.service;

import com.kama.jmindops.model.request.CreateChatSessionRequest;
import com.kama.jmindops.model.request.UpdateChatSessionRequest;
import com.kama.jmindops.model.response.CreateChatSessionResponse;
import com.kama.jmindops.model.response.GetChatSessionResponse;
import com.kama.jmindops.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions();

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}
