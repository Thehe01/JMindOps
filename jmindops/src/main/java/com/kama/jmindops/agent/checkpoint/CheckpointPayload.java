package com.kama.jmindops.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessageFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CheckpointPayload {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?is)<(think|thought|thinking|reasoning)>(?:.*?</\\1>|.*$)");

    private CheckpointPayload() {
    }

    public record CheckpointToolCall(String id, String type, String name, String arguments) {}

    public record CheckpointToolResponse(String id, String name, String responseData) {}

    public record CheckpointMessage(
            String role,
            String text,
            List<CheckpointToolCall> toolCalls,
            List<CheckpointToolResponse> toolResponses
    ) {}

    public record CheckpointRuntimeState(
            String routingDecision,
            List<String> requiredToolSequence,
            int nextRequiredToolIndex,
            String requiredKnowledgeQuery,
            boolean requiredKnowledgeRetrievalCompleted,
            int retrievedSourceCount,
            long cumulativeTokens,
            int currentStep,
            int plannedToolRepairAttempts
    ) {}

    /**
     * 将 Spring AI 的 Message 序列化为不含隐藏私有思考 (CoT) 的安全快照。
     */
    public static String serializeMessages(List<Message> messages) {
        if (messages == null) {
            return "[]";
        }
        List<CheckpointMessage> checkpointMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                checkpointMessages.add(new CheckpointMessage("SYSTEM", systemMessage.getText(), null, null));
            } else if (message instanceof UserMessage userMessage) {
                checkpointMessages.add(new CheckpointMessage("USER", userMessage.getText(), null, null));
            } else if (message instanceof AssistantMessage assistantMessage) {
                String visibleText = stripPrivateThought(assistantMessage.getText());
                List<CheckpointToolCall> toolCalls = null;
                if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
                    toolCalls = assistantMessage.getToolCalls().stream()
                            .map(tc -> new CheckpointToolCall(tc.id(), tc.type(), tc.name(), tc.arguments()))
                            .toList();
                }
                checkpointMessages.add(new CheckpointMessage("ASSISTANT", visibleText, toolCalls, null));
            } else if (message instanceof ToolResponseMessage toolResponseMessage) {
                List<CheckpointToolResponse> responses = null;
                if (toolResponseMessage.getResponses() != null && !toolResponseMessage.getResponses().isEmpty()) {
                    responses = toolResponseMessage.getResponses().stream()
                            .map(tr -> new CheckpointToolResponse(tr.id(), tr.name(), tr.responseData()))
                            .toList();
                }
                checkpointMessages.add(new CheckpointMessage("TOOL", "", null, responses));
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(checkpointMessages);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Checkpoint 消息快照失败", e);
        }
    }

    /**
     * 将 Checkpoint 消息快照反序列化为 Spring AI Message 列表。
     */
    public static List<Message> deserializeMessages(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<CheckpointMessage> checkpointMessages = OBJECT_MAPPER.readValue(payload, new TypeReference<>() {});
            List<Message> messages = new ArrayList<>();
            for (CheckpointMessage cm : checkpointMessages) {
                switch (cm.role()) {
                    case "SYSTEM" -> messages.add(new SystemMessage(cm.text() == null ? "" : cm.text()));
                    case "USER" -> messages.add(new UserMessage(cm.text() == null ? "" : cm.text()));
                    case "ASSISTANT" -> {
                        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                        if (cm.toolCalls() != null) {
                            for (CheckpointToolCall tc : cm.toolCalls()) {
                                toolCalls.add(new AssistantMessage.ToolCall(
                                        tc.id(),
                                        tc.type() == null ? "function" : tc.type(),
                                        tc.name(),
                                        tc.arguments() == null ? "" : tc.arguments()
                                ));
                            }
                        }
                        messages.add(AssistantMessageFactory.create(
                                cm.text() == null ? "" : cm.text(),
                                Map.of(),
                                toolCalls
                        ));
                    }
                    case "TOOL" -> {
                        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                        if (cm.toolResponses() != null) {
                            for (CheckpointToolResponse tr : cm.toolResponses()) {
                                responses.add(new ToolResponseMessage.ToolResponse(
                                        tr.id(),
                                        tr.name(),
                                        tr.responseData() == null ? "" : tr.responseData()
                                ));
                            }
                        }
                        messages.add(ToolResponseMessage.builder().responses(responses).build());
                    }
                    default -> throw new IllegalStateException("未知的 Checkpoint 消息角色: " + cm.role());
                }
            }
            return messages;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 Checkpoint 消息快照失败", e);
        }
    }

    public static String serializeRuntimeState(CheckpointRuntimeState state) {
        try {
            return OBJECT_MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Checkpoint 运行时状态失败", e);
        }
    }

    public static CheckpointRuntimeState deserializeRuntimeState(String payload) {
        if (payload == null || payload.isBlank()) {
            return new CheckpointRuntimeState(null, List.of(), 0, null, false, 0, 0L, 0, 0);
        }
        try {
            return OBJECT_MAPPER.readValue(payload, CheckpointRuntimeState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 Checkpoint 运行时状态失败", e);
        }
    }

    public static String serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        List<CheckpointToolCall> records = toolCalls.stream()
                .map(tc -> new CheckpointToolCall(tc.id(), tc.type(), tc.name(), tc.arguments()))
                .toList();
        try {
            return OBJECT_MAPPER.writeValueAsString(records);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工具调用失败", e);
        }
    }

    public static List<AssistantMessage.ToolCall> deserializeToolCalls(String payload) {
        if (payload == null || payload.isBlank() || "[]".equals(payload.trim())) {
            return List.of();
        }
        try {
            List<CheckpointToolCall> records = OBJECT_MAPPER.readValue(payload, new TypeReference<>() {});
            return records.stream()
                    .map(tc -> new AssistantMessage.ToolCall(
                            tc.id(),
                            tc.type() == null ? "function" : tc.type(),
                            tc.name(),
                            tc.arguments() == null ? "" : tc.arguments()
                    ))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化工具调用失败", e);
        }
    }

    public static String stripPrivateThought(String text) {
        if (text == null) {
            return "";
        }
        return THINK_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }
}
