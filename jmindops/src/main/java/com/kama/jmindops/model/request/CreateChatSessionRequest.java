package com.kama.jmindops.model.request;

import com.kama.jmindops.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateChatSessionRequest {
    @NotBlank(message = "Agent ID 不能为空")
    @Pattern(regexp = ValidationPatterns.UUID, message = "Agent ID 格式不正确")
    private String agentId;
    @Size(max = 200, message = "会话标题不能超过 200 个字符")
    private String title;

    public CreateChatSessionRequest() {}
    public CreateChatSessionRequest(String agentId, String title) {
        this.agentId = agentId;
        this.title = title;
    }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return this.title; }
    public void setTitle(String title) { this.title = title; }
    public static CreateChatSessionRequestBuilder builder() { return new CreateChatSessionRequestBuilder(); }
    public static class CreateChatSessionRequestBuilder {
        private String agentId;
        private String title;
        public CreateChatSessionRequestBuilder() {}
        public CreateChatSessionRequestBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public CreateChatSessionRequestBuilder title(String title) { this.title = title; return this; }
        public CreateChatSessionRequest build() { return new CreateChatSessionRequest(agentId, title); }
    }
}
