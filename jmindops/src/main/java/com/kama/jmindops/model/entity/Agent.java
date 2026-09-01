package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
/**
 * @TableName agent
 */
@Data
@Builder
public class Agent {
    private String id;
    private String ownerId;
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private String allowedTools;
    private String allowedKbs;
    private String chatOptions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }
    public String getAllowedKbs() { return allowedKbs; }
    public void setAllowedKbs(String allowedKbs) { this.allowedKbs = allowedKbs; }
    public String getChatOptions() { return chatOptions; }
    public void setChatOptions(String chatOptions) { this.chatOptions = chatOptions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static AgentBuilder builder() { return new AgentBuilder(); }
    public static class AgentBuilder {
        private String id;
        private String ownerId;
        private String name;
        private String description;
        private String systemPrompt;
        private String model;
        private String allowedTools;
        private String allowedKbs;
        private String chatOptions;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public AgentBuilder() {}
        public AgentBuilder id(String id) { this.id = id; return this; }
        public AgentBuilder ownerId(String ownerId) { this.ownerId = ownerId; return this; }
        public AgentBuilder name(String name) { this.name = name; return this; }
        public AgentBuilder description(String description) { this.description = description; return this; }
        public AgentBuilder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public AgentBuilder model(String model) { this.model = model; return this; }
        public AgentBuilder allowedTools(String allowedTools) { this.allowedTools = allowedTools; return this; }
        public AgentBuilder allowedKbs(String allowedKbs) { this.allowedKbs = allowedKbs; return this; }
        public AgentBuilder chatOptions(String chatOptions) { this.chatOptions = chatOptions; return this; }
        public AgentBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public AgentBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Agent build() { return new Agent(id, ownerId, name, description, systemPrompt, model, allowedTools, allowedKbs, chatOptions, createdAt, updatedAt); }
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) return true;
        if (that == null || getClass() != that.getClass()) return false;
        Agent other = (Agent) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getName() == null ? other.getName() == null : this.getName().equals(other.getName()))
                && (this.getDescription() == null ? other.getDescription() == null : this.getDescription().equals(other.getDescription()))
                && (this.getSystemPrompt() == null ? other.getSystemPrompt() == null : this.getSystemPrompt().equals(other.getSystemPrompt()))
                && (this.getModel() == null ? other.getModel() == null : this.getModel().equals(other.getModel()))
                && (this.getAllowedTools() == null ? other.getAllowedTools() == null : this.getAllowedTools().equals(other.getAllowedTools()))
                && (this.getAllowedKbs() == null ? other.getAllowedKbs() == null : this.getAllowedKbs().equals(other.getAllowedKbs()))
                && (this.getChatOptions() == null ? other.getChatOptions() == null : this.getChatOptions().equals(other.getChatOptions()))
                && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
                && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getName() == null) ? 0 : getName().hashCode());
        result = prime * result + ((getDescription() == null) ? 0 : getDescription().hashCode());
        result = prime * result + ((getSystemPrompt() == null) ? 0 : getSystemPrompt().hashCode());
        result = prime * result + ((getModel() == null) ? 0 : getModel().hashCode());
        result = prime * result + ((getAllowedTools() == null) ? 0 : getAllowedTools().hashCode());
        result = prime * result + ((getAllowedKbs() == null) ? 0 : getAllowedKbs().hashCode());
        result = prime * result + ((getChatOptions() == null) ? 0 : getChatOptions().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }
}
