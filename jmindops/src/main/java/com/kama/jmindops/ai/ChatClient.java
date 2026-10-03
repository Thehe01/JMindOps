package com.kama.jmindops.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.framework.AopContext;

/** Small application facade over LangChain4j low-level models. Never runs tools. */
public class ChatClient {
    private final ChatModel model;
    private final StreamingChatModel streamingModel;
    public ChatClient(ChatModel model, StreamingChatModel streamingModel) {
        this.model = Objects.requireNonNull(model);
        this.streamingModel = Objects.requireNonNull(streamingModel);
    }
    public ChatClientRequestSpec prompt() { return prompt(Prompt.builder().build()); }
    public ChatClientRequestSpec prompt(Prompt prompt) {
        ChatClient invocationTarget = this;
        try { invocationTarget = (ChatClient) AopContext.currentProxy(); } catch (IllegalStateException ignored) { }
        return new ChatClientRequestSpec(prompt, invocationTarget);
    }

    public ChatResponse callModel(Prompt prompt) {
        return LangChain4jMessages.fromNative(model.chat(new ChatClientRequestSpec(prompt, this).request()));
    }

    public final class ChatClientRequestSpec {
        private final List<Message> messages;
        private final ToolCallingChatOptions options;
        private List<ToolCallback> callbacks;
        private final ChatClient invocationTarget;
        private ChatClientRequestSpec(Prompt prompt, ChatClient invocationTarget) {
            this.invocationTarget = invocationTarget;
            this.messages = new ArrayList<>(prompt.getInstructions());
            this.options = prompt.getOptions();
            this.callbacks = options == null ? List.of() : options.getToolCallbacks();
        }
        public ChatClientRequestSpec system(String text) { messages.add(new SystemMessage(text)); return this; }
        public ChatClientRequestSpec user(String text) { messages.add(new UserMessage(text)); return this; }
        public ChatClientRequestSpec toolCallbacks(ToolCallback... callbacks) { this.callbacks = List.of(callbacks); return this; }
        private ChatRequest request() {
            // Providers require a single system message. Keep both persona and per-step constraints.
            String system = messages.stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                    .filter(text -> text != null && !text.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
            List<ChatMessage> nativeMessages = new ArrayList<>();
            if (!system.isBlank()) nativeMessages.add(dev.langchain4j.data.message.SystemMessage.from(system));
            nativeMessages.addAll(LangChain4jMessages.toNative(messages.stream()
                    .filter(message -> !(message instanceof SystemMessage)).toList()));
            // A classifier may supply only a system prompt; give all providers a valid user turn.
            if (nativeMessages.stream().noneMatch(dev.langchain4j.data.message.UserMessage.class::isInstance)) {
                nativeMessages.add(dev.langchain4j.data.message.UserMessage.from("请按系统指令回答。"));
            }
            var builder = ChatRequest.builder().messages(nativeMessages).toolSpecifications(callbacks.stream()
                    .map(callback -> callback.getToolDefinition().specification()).toList());
            if (options != null) builder.temperature(options.getTemperature()).topP(options.getTopP());
            return builder.build();
        }
        public CallResponseSpec call() {
            ToolCallingChatOptions requestOptions = ToolCallingChatOptions.builder()
                    .temperature(options == null ? null : options.getTemperature())
                    .topP(options == null ? null : options.getTopP()).build();
            requestOptions.setToolCallbacks(callbacks);
            return new CallResponseSpec(invocationTarget.callModel(Prompt.builder()
                    .messages(messages).chatOptions(requestOptions).build()));
        }
        public StreamResponseSpec stream() { return new StreamResponseSpec(request()); }
    }
    public static final class CallResponseSpec {
        private final ChatResponse response;
        private CallResponseSpec(ChatResponse response) { this.response = response; }
        public String content() { return response.getResult().getOutput().getText(); }
        public ChatResponse chatResponse() { return response; }
    }
    public final class StreamResponseSpec {
        private final ChatRequest request;
        private StreamResponseSpec(ChatRequest request) { this.request = request; }
        public Flux<ChatResponse> chatResponse() {
            return Flux.create(sink -> {
                AtomicReference<StreamingHandle> handle = new AtomicReference<>();
                StringBuilder emittedText = new StringBuilder();
                sink.onCancel(() -> { var current = handle.get(); if (current != null) current.cancel(); });
                streamingModel.chat(request, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                        handle.set(context.streamingHandle());
                        if (sink.isCancelled()) { context.streamingHandle().cancel(); return; }
                        onPartialResponse(partial.text());
                    }
                    @Override public void onPartialToolCall(PartialToolCall partial, PartialToolCallContext context) {
                        handle.set(context.streamingHandle());
                        if (sink.isCancelled()) context.streamingHandle().cancel();
                    }
                    @Override public void onPartialThinking(PartialThinking partial, PartialThinkingContext context) {
                        // Capture cancellation without publishing or persisting private reasoning.
                        handle.set(context.streamingHandle());
                        if (sink.isCancelled()) context.streamingHandle().cancel();
                    }
                    @Override public void onPartialResponse(String text) {
                        if (sink.isCancelled() || text == null || text.isEmpty()) return;
                        emittedText.append(text);
                        sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
                    }
                    @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                        if (sink.isCancelled()) return;
                        AssistantMessage complete = LangChain4jMessages.fromNative(response.aiMessage());
                        // Complete tool calls are authoritative; partial arguments are never executed or concatenated twice.
                        String text = complete.getText();
                        if (!text.startsWith(emittedText.toString())) {
                            sink.error(new IllegalStateException("Provider final text does not match streamed text"));
                            return;
                        }
                        String remaining = text.substring(emittedText.length());
                        sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(remaining, complete.getToolCalls()))),
                                LangChain4jMessages.metadata(response)));
                        sink.complete();
                    }
                    @Override public void onError(Throwable error) { sink.error(error); }
                });
            });
        }
    }
}
