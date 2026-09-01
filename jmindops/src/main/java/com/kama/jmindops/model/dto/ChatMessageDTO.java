package com.kama.jmindops.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ChatMessageDTO {
    private String id;
    private String sessionId;
    private RoleType role;
    private String content;
    private MetaData metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ChatMessageDTO() {}
    public ChatMessageDTO(String id, String sessionId, RoleType role, String content, MetaData metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return this.sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public RoleType getRole() { return this.role; }
    public void setRole(RoleType role) { this.role = role; }
    public String getContent() { return this.content; }
    public void setContent(String content) { this.content = content; }
    public MetaData getMetadata() { return this.metadata; }
    public void setMetadata(MetaData metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static ChatMessageDTOBuilder builder() { return new ChatMessageDTOBuilder(); }
    public static class ChatMessageDTOBuilder {
        private String id;
        private String sessionId;
        private RoleType role;
        private String content;
        private MetaData metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public ChatMessageDTOBuilder() {}
        public ChatMessageDTOBuilder id(String id) { this.id = id; return this; }
        public ChatMessageDTOBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public ChatMessageDTOBuilder role(RoleType role) { this.role = role; return this; }
        public ChatMessageDTOBuilder content(String content) { this.content = content; return this; }
        public ChatMessageDTOBuilder metadata(MetaData metadata) { this.metadata = metadata; return this; }
        public ChatMessageDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ChatMessageDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ChatMessageDTO build() { return new ChatMessageDTO(id, sessionId, role, content, metadata, createdAt, updatedAt); }
    }

    @Data
    @Builder
    public static class MetaData {
        private ToolResponseMessage.ToolResponse toolResponse;
        private List<AssistantMessage.ToolCall> toolCalls;
        private TokenUsage usage;
        private Long latencyMs;
        private String model;

        public MetaData() {}
        public MetaData(ToolResponseMessage.ToolResponse toolResponse, List<AssistantMessage.ToolCall> toolCalls, TokenUsage usage, Long latencyMs, String model) {
            this.toolResponse = toolResponse;
            this.toolCalls = toolCalls;
            this.usage = usage;
            this.latencyMs = latencyMs;
            this.model = model;
        }

        public ToolResponseMessage.ToolResponse getToolResponse() { return toolResponse; }
        public void setToolResponse(ToolResponseMessage.ToolResponse toolResponse) { this.toolResponse = toolResponse; }
        public List<AssistantMessage.ToolCall> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<AssistantMessage.ToolCall> toolCalls) { this.toolCalls = toolCalls; }
        public TokenUsage getUsage() { return usage; }
        public void setUsage(TokenUsage usage) { this.usage = usage; }
        public Long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public static MetaDataBuilder builder() { return new MetaDataBuilder(); }
        public static class MetaDataBuilder {
            private ToolResponseMessage.ToolResponse toolResponse;
            private List<AssistantMessage.ToolCall> toolCalls;
            private TokenUsage usage;
            private Long latencyMs;
            private String model;
            public MetaDataBuilder() {}
            public MetaDataBuilder toolResponse(ToolResponseMessage.ToolResponse toolResponse) { this.toolResponse = toolResponse; return this; }
            public MetaDataBuilder toolCalls(List<AssistantMessage.ToolCall> toolCalls) { this.toolCalls = toolCalls; return this; }
            public MetaDataBuilder usage(TokenUsage usage) { this.usage = usage; return this; }
            public MetaDataBuilder latencyMs(Long latencyMs) { this.latencyMs = latencyMs; return this; }
            public MetaDataBuilder model(String model) { this.model = model; return this; }
            public MetaData build() { return new MetaData(toolResponse, toolCalls, usage, latencyMs, model); }
        }
    }

    @Data
    @Builder
    public static class TokenUsage {
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;

        public TokenUsage() {}
        public TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public Long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
        public Long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
        public Long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }

        public static TokenUsageBuilder builder() { return new TokenUsageBuilder(); }
        public static class TokenUsageBuilder {
            private Long promptTokens;
            private Long completionTokens;
            private Long totalTokens;
            public TokenUsageBuilder() {}
            public TokenUsageBuilder promptTokens(Long promptTokens) { this.promptTokens = promptTokens; return this; }
            public TokenUsageBuilder completionTokens(Long completionTokens) { this.completionTokens = completionTokens; return this; }
            public TokenUsageBuilder totalTokens(Long totalTokens) { this.totalTokens = totalTokens; return this; }
            public TokenUsage build() { return new TokenUsage(promptTokens, completionTokens, totalTokens); }
        }
    }

    @Getter
    public enum RoleType {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        TOOL("tool");

        @JsonValue
        private final String role;

        RoleType(String role) {
            this.role = role;
        }

        public String getRole() {
            return role;
        }

        public static RoleType fromRole(String role) {
            for (RoleType value : values()) {
                if (value.role.equals(role)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }
}
