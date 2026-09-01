package com.kama.jmindops.model.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateKnowledgeBaseRequest {
    @Size(min = 1, max = 100, message = "知识库名称长度必须在 1 到 100 个字符之间")
    private String name;
    @Size(max = 500, message = "知识库描述不能超过 500 个字符")
    private String description;

    public UpdateKnowledgeBaseRequest() {}
    public UpdateKnowledgeBaseRequest(String name, String description) {
        this.name = name;
        this.description = description;
    }
    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return this.description; }
    public void setDescription(String description) { this.description = description; }
    public static UpdateKnowledgeBaseRequestBuilder builder() { return new UpdateKnowledgeBaseRequestBuilder(); }
    public static class UpdateKnowledgeBaseRequestBuilder {
        private String name;
        private String description;
        public UpdateKnowledgeBaseRequestBuilder() {}
        public UpdateKnowledgeBaseRequestBuilder name(String name) { this.name = name; return this; }
        public UpdateKnowledgeBaseRequestBuilder description(String description) { this.description = description; return this; }
        public UpdateKnowledgeBaseRequest build() { return new UpdateKnowledgeBaseRequest(name, description); }
    }
}

