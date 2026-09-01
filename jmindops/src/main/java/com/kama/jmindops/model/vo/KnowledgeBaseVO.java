package com.kama.jmindops.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeBaseVO {
    private String id;
    private String name;
    private String description;

    public KnowledgeBaseVO() {}
    public KnowledgeBaseVO(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return this.description; }
    public void setDescription(String description) { this.description = description; }
    public static KnowledgeBaseVOBuilder builder() { return new KnowledgeBaseVOBuilder(); }
    public static class KnowledgeBaseVOBuilder {
        private String id;
        private String name;
        private String description;
        public KnowledgeBaseVOBuilder() {}
        public KnowledgeBaseVOBuilder id(String id) { this.id = id; return this; }
        public KnowledgeBaseVOBuilder name(String name) { this.name = name; return this; }
        public KnowledgeBaseVOBuilder description(String description) { this.description = description; return this; }
        public KnowledgeBaseVO build() { return new KnowledgeBaseVO(id, name, description); }
    }
}

