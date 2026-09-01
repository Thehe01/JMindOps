package com.kama.jmindops.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageRequest {
    @NotBlank(message = "agentId 不能为空")
    @Size(max = 64, message = "agentId 长度不能超过 64")
    private String agentId;

    @NotBlank(message = "sessionId 不能为空")
    @Size(max = 64, message = "sessionId 长度不能超过 64")
    private String sessionId;

    // 兼容旧客户端输出结构，但禁止从请求体设置；外部消息永远由服务端标记为 USER。
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ChatMessageDTO.RoleType role;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 16_000, message = "消息内容不能超过 16000 个字符")
    private String content;

    // metadata 只允许由服务端生成，不能由客户端伪造工具调用或模型用量信息。
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ChatMessageDTO.MetaData metadata;

    public CreateChatMessageRequest() {}
    public CreateChatMessageRequest(String agentId, String sessionId, ChatMessageDTO.RoleType role, String content, ChatMessageDTO.MetaData metadata) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.metadata = metadata;
    }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return this.sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public ChatMessageDTO.RoleType getRole() { return this.role; }
    public void setRole(ChatMessageDTO.RoleType role) { this.role = role; }
    public String getContent() { return this.content; }
    public void setContent(String content) { this.content = content; }
    public ChatMessageDTO.MetaData getMetadata() { return this.metadata; }
    public void setMetadata(ChatMessageDTO.MetaData metadata) { this.metadata = metadata; }
    public static CreateChatMessageRequestBuilder builder() { return new CreateChatMessageRequestBuilder(); }
    public static class CreateChatMessageRequestBuilder {
        private String agentId;
        private String sessionId;
        private ChatMessageDTO.RoleType role;
        private String content;
        private ChatMessageDTO.MetaData metadata;
        public CreateChatMessageRequestBuilder() {}
        public CreateChatMessageRequestBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public CreateChatMessageRequestBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public CreateChatMessageRequestBuilder role(ChatMessageDTO.RoleType role) { this.role = role; return this; }
        public CreateChatMessageRequestBuilder content(String content) { this.content = content; return this; }
        public CreateChatMessageRequestBuilder metadata(ChatMessageDTO.MetaData metadata) { this.metadata = metadata; return this; }
        public CreateChatMessageRequest build() { return new CreateChatMessageRequest(agentId, sessionId, role, content, metadata); }
    }
}
