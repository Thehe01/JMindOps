package com.kama.jmindops.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateKnowledgeBaseRequest {
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称不能超过 100 个字符")
    private String name;
    @Size(max = 500, message = "知识库描述不能超过 500 个字符")
    private String description;

    public CreateKnowledgeBaseRequest() {}
    public CreateKnowledgeBaseRequest(String name, String description) {
        this.name = name;
        this.description = description;
    }
    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return this.description; }
    public void setDescription(String description) { this.description = description; }
    public static CreateKnowledgeBaseRequestBuilder builder() { return new CreateKnowledgeBaseRequestBuilder(); }
    public static class CreateKnowledgeBaseRequestBuilder {
        private String name;
        private String description;
        public CreateKnowledgeBaseRequestBuilder() {}
        public CreateKnowledgeBaseRequestBuilder name(String name) { this.name = name; return this; }
        public CreateKnowledgeBaseRequestBuilder description(String description) { this.description = description; return this; }
        public CreateKnowledgeBaseRequest build() { return new CreateKnowledgeBaseRequest(name, description); }
    }
}

