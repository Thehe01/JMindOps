package com.kama.jmindops.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.agent.checkpoint.CheckpointPayload;
import com.kama.jmindops.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jPersistenceTest {
    @Test
    void legacyCheckpointAndDatabaseToolMetadataRemainReadable() throws Exception {
        String checkpoint = """
                [{"role":"SYSTEM","text":"persona","toolCalls":null,"toolResponses":null},
                 {"role":"USER","text":"question","toolCalls":null,"toolResponses":null},
                 {"role":"ASSISTANT","text":"","toolCalls":[{"id":"c1","type":"function","name":"readFile","arguments":"{}"}],"toolResponses":null},
                 {"role":"TOOL","text":"","toolCalls":null,"toolResponses":[{"id":"c1","name":"readFile","responseData":"result"}]}]
                """;
        List<Message> restored = CheckpointPayload.deserializeMessages(checkpoint);
        var nativeMessages = LangChain4jMessages.toNative(restored);
        assertThat(nativeMessages).hasSize(4);
        var call = (dev.langchain4j.data.message.AiMessage) nativeMessages.get(2);
        var result = (dev.langchain4j.data.message.ToolExecutionResultMessage) nativeMessages.get(3);
        assertThat(call.toolExecutionRequests().get(0).id()).isEqualTo(result.id()).isEqualTo("c1");
        assertThat(CheckpointPayload.serializeMessages(LangChain4jMessages.fromNative(nativeMessages)))
                .isEqualTo(CheckpointPayload.serializeMessages(restored));

        String metadata = """
                {"toolResponse":{"id":"c1","name":"readFile","responseData":"result"},
                 "toolCalls":[{"id":"c1","type":"function","name":"readFile","arguments":"{}"}]}
                """;
        ObjectMapper mapper = new ObjectMapper();
        ChatMessageDTO.MetaData dto = mapper.readValue(metadata, ChatMessageDTO.MetaData.class);
        assertThat(dto.getToolCalls().get(0).arguments()).isEqualTo("{}");
        assertThat(mapper.readTree(mapper.writeValueAsString(dto)).get("toolResponse").get("responseData").asText())
                .isEqualTo("result");
    }

    @Test
    void windowEvictionDoesNotLeaveOrphanToolResultsAndSessionsAreIsolated() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(3).build();
        memory.add("a", new SystemMessage("persona"));
        memory.add("a", new AssistantMessage("", List.of(new AssistantMessage.ToolCall("c1", "function", "readFile", "{}"))));
        memory.add("a", ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c1", "readFile", "result"))).build());
        memory.add("b", new UserMessage("other session"));
        memory.add("a", new UserMessage("next question"));
        assertThat(memory.get("a")).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(memory.get("a")).anyMatch(message -> "persona".equals(message.getText()));
        assertThat(memory.get("b")).hasSize(1);
        assertThat(memory.get("b").get(0).getText()).isEqualTo("other session");
    }
}
