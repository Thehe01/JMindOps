package com.kama.jmindops.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

final class LangChain4jMessages {
    private LangChain4jMessages() {}
    static List<ChatMessage> toNative(List<Message> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage system) {
                result.add(dev.langchain4j.data.message.SystemMessage.from(system.getText()));
            } else if (message instanceof UserMessage user) {
                result.add(dev.langchain4j.data.message.UserMessage.from(user.getText()));
            } else if (message instanceof AssistantMessage assistant) {
                List<ToolExecutionRequest> calls = assistant.getToolCalls().stream().map(call ->
                        ToolExecutionRequest.builder().id(call.id()).name(call.name()).arguments(call.arguments()).build()).toList();
                result.add(calls.isEmpty() ? AiMessage.from(assistant.getText()) :
                        AiMessage.from(assistant.getText().isEmpty() ? null : assistant.getText(), calls));
            } else if (message instanceof ToolResponseMessage batch) {
                batch.getResponses().forEach(response -> result.add(ToolExecutionResultMessage.from(
                        response.id(), response.name(), response.responseData())));
            } else {
                throw new IllegalArgumentException("Unsupported message: " + message.getClass());
            }
        }
        return result;
    }
    static List<Message> fromNative(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.SystemMessage system) {
                result.add(new SystemMessage(system.text()));
            } else if (message instanceof dev.langchain4j.data.message.UserMessage user) {
                result.add(new UserMessage(user.singleText()));
            } else if (message instanceof AiMessage assistant) {
                result.add(fromNative(assistant));
            } else if (message instanceof ToolExecutionResultMessage response) {
                result.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                        response.id(), response.toolName(), response.text()))).build());
            }
        }
        return result;
    }
    static AssistantMessage fromNative(AiMessage assistant) {
        return new AssistantMessage(assistant.text(), assistant.toolExecutionRequests().stream().map(call ->
                new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.arguments())).toList());
    }
    static ChatResponseMetadata metadata(dev.langchain4j.model.chat.response.ChatResponse response) {
        var tokens = response.tokenUsage();
        Usage usage = tokens == null ? null : new Usage(tokens.inputTokenCount(), tokens.outputTokenCount(), tokens.totalTokenCount());
        return new ChatResponseMetadata(usage, response.modelName());
    }
    static ChatResponse fromNative(dev.langchain4j.model.chat.response.ChatResponse response) {
        return new ChatResponse(List.of(new Generation(fromNative(response.aiMessage()))), metadata(response));
    }
}
