package com.kama.jmindops.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AgentDTO {
    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private ModelType model;
    private List<String> allowedTools;
    private List<String> allowedKbs;
    private ChatOptions chatOptions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentDTO() {}
    public AgentDTO(String id, String name, String description, String systemPrompt, ModelType model, List<String> allowedTools, List<String> allowedKbs, ChatOptions chatOptions, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.allowedTools = allowedTools;
        this.allowedKbs = allowedKbs;
        this.chatOptions = chatOptions;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public ModelType getModel() { return model; }
    public void setModel(ModelType model) { this.model = model; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getAllowedKbs() { return allowedKbs; }
    public void setAllowedKbs(List<String> allowedKbs) { this.allowedKbs = allowedKbs; }
    public ChatOptions getChatOptions() { return chatOptions; }
    public void setChatOptions(ChatOptions chatOptions) { this.chatOptions = chatOptions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static AgentDTOBuilder builder() { return new AgentDTOBuilder(); }
    public static class AgentDTOBuilder {
        private String id;
        private String name;
        private String description;
        private String systemPrompt;
        private ModelType model;
        private List<String> allowedTools;
        private List<String> allowedKbs;
        private ChatOptions chatOptions;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public AgentDTOBuilder() {}
        public AgentDTOBuilder id(String id) { this.id = id; return this; }
        public AgentDTOBuilder name(String name) { this.name = name; return this; }
        public AgentDTOBuilder description(String description) { this.description = description; return this; }
        public AgentDTOBuilder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public AgentDTOBuilder model(ModelType model) { this.model = model; return this; }
        public AgentDTOBuilder allowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; return this; }
        public AgentDTOBuilder allowedKbs(List<String> allowedKbs) { this.allowedKbs = allowedKbs; return this; }
        public AgentDTOBuilder chatOptions(ChatOptions chatOptions) { this.chatOptions = chatOptions; return this; }
        public AgentDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public AgentDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public AgentDTO build() { return new AgentDTO(id, name, description, systemPrompt, model, allowedTools, allowedKbs, chatOptions, createdAt, updatedAt); }
    }

    public enum ModelType {
        DEEPSEEK_CHAT("deepseek-chat"),
        GLM_4_6("glm-4.6"),
        GEMINI_2_5("gemini-2.5");

        @JsonValue
        private final String modelName;

        ModelType(String modelName) {
            this.modelName = modelName;
        }

        public String getModelName() {
            return modelName;
        }

        public static ModelType fromModelName(String modelName) {
            for (ModelType type : ModelType.values()) {
                if (type.modelName.equals(modelName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown model type: " + modelName);
        }
    }

    @Data
    @Builder
    public static class ChatOptions {
        @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
        @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
        private Double temperature;

        @DecimalMin(value = "0.0", inclusive = false, message = "topP 必须大于 0")
        @DecimalMax(value = "1.0", message = "topP 不能大于 1")
        private Double topP;

        @Min(value = 1, message = "消息窗口长度不能小于 1")
        @Max(value = 100, message = "消息窗口长度不能大于 100")
        private Integer messageLength;

        private static final Double DEFAULT_TEMPERATURE = 0.7;
        private static final Double DEFAULT_TOP_P = 1.0;
        private static final Integer DEFAULT_MESSAGE_LENGTH = 10;

        public ChatOptions() {}
        public ChatOptions(Double temperature, Double topP, Integer messageLength) {
            this.temperature = temperature;
            this.topP = topP;
            this.messageLength = messageLength;
        }

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Double getTopP() { return topP; }
        public void setTopP(Double topP) { this.topP = topP; }
        public Integer getMessageLength() { return messageLength; }
        public void setMessageLength(Integer messageLength) { this.messageLength = messageLength; }

        public static ChatOptions defaultOptions() {
            return ChatOptions.builder()
                    .temperature(DEFAULT_TEMPERATURE)
                    .topP(DEFAULT_TOP_P)
                    .messageLength(DEFAULT_MESSAGE_LENGTH)
                    .build();
        }

        public static ChatOptionsBuilder builder() { return new ChatOptionsBuilder(); }
        public static class ChatOptionsBuilder {
            private Double temperature;
            private Double topP;
            private Integer messageLength;
            public ChatOptionsBuilder() {}
            public ChatOptionsBuilder temperature(Double temperature) { this.temperature = temperature; return this; }
            public ChatOptionsBuilder topP(Double topP) { this.topP = topP; return this; }
            public ChatOptionsBuilder messageLength(Integer messageLength) { this.messageLength = messageLength; return this; }
            public ChatOptions build() { return new ChatOptions(temperature, topP, messageLength); }
        }
    }
}
