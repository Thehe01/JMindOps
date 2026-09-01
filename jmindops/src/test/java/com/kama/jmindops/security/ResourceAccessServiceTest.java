package com.kama.jmindops.security;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.AgentMapper;
import com.kama.jmindops.mapper.ChatSessionMapper;
import com.kama.jmindops.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ResourceAccessServiceTest {

    @Test
    void rejectsMalformedIdsBeforeQueryingDatabase() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ResourceAccessService service = new ResourceAccessService(
                agentMapper, knowledgeBaseMapper, chatSessionMapper);

        assertThatThrownBy(() -> service.requireOwnedKnowledgeBase("../../other"))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("格式不正确");
                });
        verifyNoInteractions(knowledgeBaseMapper);
    }
}
