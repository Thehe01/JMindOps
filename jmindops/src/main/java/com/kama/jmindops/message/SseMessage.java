package com.kama.jmindops.message;

import com.kama.jmindops.model.vo.ChatMessageVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    public SseMessage() {}
    public SseMessage(Type type, Payload payload, Metadata metadata) {
        this.type = type;
        this.payload = payload;
        this.metadata = metadata;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Payload getPayload() { return payload; }
    public void setPayload(Payload payload) { this.payload = payload; }
    public Metadata getMetadata() { return metadata; }
    public void setMetadata(Metadata metadata) { this.metadata = metadata; }

    public static SseMessageBuilder builder() { return new SseMessageBuilder(); }
    public static class SseMessageBuilder {
        private Type type;
        private Payload payload;
        private Metadata metadata;
        public SseMessageBuilder() {}
        public SseMessageBuilder type(Type type) { this.type = type; return this; }
        public SseMessageBuilder payload(Payload payload) { this.payload = payload; return this; }
        public SseMessageBuilder metadata(Metadata metadata) { this.metadata = metadata; return this; }
        public SseMessage build() { return new SseMessage(type, payload, metadata); }
    }

    @Data
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;

        public Payload() {}
        public Payload(ChatMessageVO message, String statusText, Boolean done) {
            this.message = message;
            this.statusText = statusText;
            this.done = done;
        }

        public ChatMessageVO getMessage() { return message; }
        public void setMessage(ChatMessageVO message) { this.message = message; }
        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }
        public Boolean getDone() { return done; }
        public void setDone(Boolean done) { this.done = done; }

        public static PayloadBuilder builder() { return new PayloadBuilder(); }
        public static class PayloadBuilder {
            private ChatMessageVO message;
            private String statusText;
            private Boolean done;
            public PayloadBuilder() {}
            public PayloadBuilder message(ChatMessageVO message) { this.message = message; return this; }
            public PayloadBuilder statusText(String statusText) { this.statusText = statusText; return this; }
            public PayloadBuilder done(Boolean done) { this.done = done; return this; }
            public Payload build() { return new Payload(message, statusText, done); }
        }
    }

    @Data
    @Builder
    public static class Metadata {
        private String chatMessageId;
        private String generationId;

        public Metadata() {}
        public Metadata(String chatMessageId, String generationId) {
            this.chatMessageId = chatMessageId;
            this.generationId = generationId;
        }

        public String getChatMessageId() { return chatMessageId; }
        public void setChatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; }
        public String getGenerationId() { return generationId; }
        public void setGenerationId(String generationId) { this.generationId = generationId; }

        public static MetadataBuilder builder() { return new MetadataBuilder(); }
        public static class MetadataBuilder {
            private String chatMessageId;
            private String generationId;
            public MetadataBuilder() {}
            public MetadataBuilder chatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; return this; }
            public MetadataBuilder generationId(String generationId) { this.generationId = generationId; return this; }
            public Metadata build() { return new Metadata(chatMessageId, generationId); }
        }
    }

    public enum Type {
        AI_GENERATED_CONTENT,
        AI_GENERATED_CONTENT_CHUNK,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_DONE,
        AI_ERROR,
    }
}
