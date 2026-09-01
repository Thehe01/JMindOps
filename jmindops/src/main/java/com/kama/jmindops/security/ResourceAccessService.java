package com.kama.jmindops.security;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.AgentMapper;
import com.kama.jmindops.mapper.ChatSessionMapper;
import com.kama.jmindops.mapper.KnowledgeBaseMapper;
import com.kama.jmindops.model.entity.Agent;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.KnowledgeBase;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ResourceAccessService {
    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ChatSessionMapper chatSessionMapper;
    public ResourceAccessService(AgentMapper agentMapper, KnowledgeBaseMapper knowledgeBaseMapper, ChatSessionMapper chatSessionMapper) {
        this.agentMapper = agentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chatSessionMapper = chatSessionMapper;
    }


    public String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return user.id();
    }

    public Agent requireOwnedAgent(String agentId) {
        requireUuid(agentId, "Agent ID");
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || !currentUserId().equals(agent.getOwnerId())) {
            throw new BizException(403, "无权访问该智能体");
        }
        return agent;
    }

    public KnowledgeBase requireOwnedKnowledgeBase(String knowledgeBaseId) {
        requireUuid(knowledgeBaseId, "知识库 ID");
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null || !currentUserId().equals(knowledgeBase.getOwnerId())) {
            throw new BizException(403, "无权访问该知识库");
        }
        return knowledgeBase;
    }

    public ChatSession requireOwnedChatSession(String chatSessionId) {
        requireUuid(chatSessionId, "会话 ID");
        ChatSession chatSession = chatSessionMapper.selectById(chatSessionId);
        if (chatSession == null || !currentUserId().equals(chatSession.getOwnerId())) {
            throw new BizException(403, "无权访问该聊天会话");
        }
        return chatSession;
    }

    private void requireUuid(String value, String fieldName) {
        try {
            if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical uuid");
            }
        } catch (IllegalArgumentException e) {
            throw new BizException(400, fieldName + " 格式不正确");
        }
    }
}
