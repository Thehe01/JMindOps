package com.kama.jmindops.model.vo;

import com.kama.jmindops.model.dto.AgentDTO;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AgentVO {
    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private AgentDTO.ModelType model;
    private List<String> allowedTools;
    private List<String> allowedKbs;
    private AgentDTO.ChatOptions chatOptions;

    public AgentVO() {}
    public AgentVO(String id, String name, String description, String systemPrompt, AgentDTO.ModelType model, List<String> allowedTools, List<String> allowedKbs, AgentDTO.ChatOptions chatOptions) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.allowedTools = allowedTools;
        this.allowedKbs = allowedKbs;
        this.chatOptions = chatOptions;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public AgentDTO.ModelType getModel() { return model; }
    public void setModel(AgentDTO.ModelType model) { this.model = model; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getAllowedKbs() { return allowedKbs; }
    public void setAllowedKbs(List<String> allowedKbs) { this.allowedKbs = allowedKbs; }
    public AgentDTO.ChatOptions getChatOptions() { return chatOptions; }
    public void setChatOptions(AgentDTO.ChatOptions chatOptions) { this.chatOptions = chatOptions; }

    public static AgentVOBuilder builder() { return new AgentVOBuilder(); }
    public static class AgentVOBuilder {
        private String id;
        private String name;
        private String description;
        private String systemPrompt;
        private AgentDTO.ModelType model;
        private List<String> allowedTools;
        private List<String> allowedKbs;
        private AgentDTO.ChatOptions chatOptions;
        public AgentVOBuilder() {}
        public AgentVOBuilder id(String id) { this.id = id; return this; }
        public AgentVOBuilder name(String name) { this.name = name; return this; }
        public AgentVOBuilder description(String description) { this.description = description; return this; }
        public AgentVOBuilder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public AgentVOBuilder model(AgentDTO.ModelType model) { this.model = model; return this; }
        public AgentVOBuilder allowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; return this; }
        public AgentVOBuilder allowedKbs(List<String> allowedKbs) { this.allowedKbs = allowedKbs; return this; }
        public AgentVOBuilder chatOptions(AgentDTO.ChatOptions chatOptions) { this.chatOptions = chatOptions; return this; }
        public AgentVO build() { return new AgentVO(id, name, description, systemPrompt, model, allowedTools, allowedKbs, chatOptions); }
    }
}
