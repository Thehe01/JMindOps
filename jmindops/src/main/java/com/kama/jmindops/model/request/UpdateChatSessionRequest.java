package com.kama.jmindops.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateChatSessionRequest {
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 200, message = "会话标题不能超过 200 个字符")
    private String title;

    public UpdateChatSessionRequest() {}
    public UpdateChatSessionRequest(String title) {
        this.title = title;
    }
    public String getTitle() { return this.title; }
    public void setTitle(String title) { this.title = title; }
    public static UpdateChatSessionRequestBuilder builder() { return new UpdateChatSessionRequestBuilder(); }
    public static class UpdateChatSessionRequestBuilder {
        private String title;
        public UpdateChatSessionRequestBuilder() {}
        public UpdateChatSessionRequestBuilder title(String title) { this.title = title; return this; }
        public UpdateChatSessionRequest build() { return new UpdateChatSessionRequest(title); }
    }
}
