package com.kama.jmindops.model.request;

import com.kama.jmindops.model.dto.AgentDTO;
import com.kama.jmindops.validation.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class CreateAgentRequest {
    @NotBlank(message = "Agent 名称不能为空")
    @Size(max = 80, message = "Agent 名称不能超过 80 个字符")
    private String name;

    @Size(max = 500, message = "Agent 描述不能超过 500 个字符")
    private String description;

    @NotBlank(message = "系统提示词不能为空")
    @Size(max = 8_000, message = "系统提示词不能超过 8000 个字符")
    private String systemPrompt;

    @NotBlank(message = "模型不能为空")
    @Size(max = 100, message = "模型名称不能超过 100 个字符")
    private String model;

    @Size(max = 20, message = "单个 Agent 最多允许 20 个工具")
    private List<@Pattern(regexp = ValidationPatterns.TOOL_NAME, message = "工具名称格式不正确") String> allowedTools;

    @Size(max = 20, message = "单个 Agent 最多绑定 20 个知识库")
    private List<@Pattern(regexp = ValidationPatterns.UUID, message = "知识库 ID 格式不正确") String> allowedKbs;

    @Valid
    private AgentDTO.ChatOptions chatOptions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getAllowedKbs() { return allowedKbs; }
    public void setAllowedKbs(List<String> allowedKbs) { this.allowedKbs = allowedKbs; }
    public AgentDTO.ChatOptions getChatOptions() { return chatOptions; }
    public void setChatOptions(AgentDTO.ChatOptions chatOptions) { this.chatOptions = chatOptions; }
}
