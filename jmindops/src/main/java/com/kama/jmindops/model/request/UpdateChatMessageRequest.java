package com.kama.jmindops.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateChatMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 16_000, message = "消息内容不能超过 16000 个字符")
    private String content;

    public UpdateChatMessageRequest() {}
    public UpdateChatMessageRequest(String content) {
        this.content = content;
    }
    public String getContent() { return this.content; }
    public void setContent(String content) { this.content = content; }
    public static UpdateChatMessageRequestBuilder builder() { return new UpdateChatMessageRequestBuilder(); }
    public static class UpdateChatMessageRequestBuilder {
        private String content;
        public UpdateChatMessageRequestBuilder() {}
        public UpdateChatMessageRequestBuilder content(String content) { this.content = content; return this; }
        public UpdateChatMessageRequest build() { return new UpdateChatMessageRequest(content); }
    }
}

