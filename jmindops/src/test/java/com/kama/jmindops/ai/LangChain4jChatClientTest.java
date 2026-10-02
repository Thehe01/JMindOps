package com.kama.jmindops.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LangChain4jChatClientTest {
    @Test
    void passesPersonaStepInstructionsAndOptionsWithoutExecutingTools() {
        ChatModel model = mock(ChatModel.class);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(nativeResponse(AiMessage.from("answer")));
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(new ToolDefinition(ToolSpecification.builder().name("readFile").build()));
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().temperature(0.2).topP(0.8).build();
        String answer = new ChatClient(model, streaming).prompt(Prompt.builder().messages(List.of(
                new SystemMessage("persona"), new UserMessage("question"))).chatOptions(options).build())
                .system("step constraints").toolCallbacks(tool).call().content();

        assertThat(answer).isEqualTo("answer");
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        assertThat(request.getValue().messages()).hasSize(2);
        assertThat(((dev.langchain4j.data.message.SystemMessage) request.getValue().messages().get(0)).text())
                .isEqualTo("persona\n\nstep constraints");
        assertThat(request.getValue().temperature()).isEqualTo(0.2);
        assertThat(request.getValue().topP()).isEqualTo(0.8);
        assertThat(request.getValue().toolSpecifications()).extracting(ToolSpecification::name).containsExactly("readFile");
        verify(tool, never()).call(any());
    }

    @Test
    void streamsTextOnceAndUsesOnlyCompleteToolArgumentsAndFinalUsage() {
        ChatModel model = mock(ChatModel.class);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        ToolExecutionRequest tool = ToolExecutionRequest.builder().id("call-1").name("readFile")
                .arguments("{\"path\":\"todo.md\"}").build();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("hello ");
            handler.onPartialResponse("world");
            handler.onCompleteResponse(nativeResponse(AiMessage.from("hello world", List.of(tool))));
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        List<ChatResponse> chunks = new ChatClient(model, streaming).prompt().user("question").stream().chatResponse()
                .collectList().block(Duration.ofSeconds(2));
        assertThat(chunks).hasSize(3);
        assertThat(chunks.stream().map(chunk -> chunk.getResult().getOutput().getText())
                .collect(java.util.stream.Collectors.joining())).isEqualTo("hello world");
        assertThat(chunks.stream().flatMap(chunk -> chunk.getResult().getOutput().getToolCalls().stream()).toList())
                .containsExactly(new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"todo.md\"}"));
        assertThat(chunks.get(0).getMetadata()).isNull();
        assertThat(chunks.get(2).getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
        assertThat(chunks.get(2).getMetadata().getModel()).isEqualTo("test-model");
        verifyNoInteractions(model);
    }

    @Test
    void clearsPreviouslyConfiguredToolsWhenPlanCompletes() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(nativeResponse(AiMessage.from("done")));
        ToolCallback tool = mock(ToolCallback.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        options.setToolCallbacks(List.of(tool));
        new ChatClient(model, mock(StreamingChatModel.class)).prompt(Prompt.builder()
                .messages(List.of(new UserMessage("question"))).chatOptions(options).build())
                .toolCallbacks().call();
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        assertThat(request.getValue().toolSpecifications()).isEmpty();
        verifyNoInteractions(tool);
    }

    @Test
    void cancelsProviderStreamOnTimeoutAndPropagatesProviderErrors() {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        StreamingHandle handle = mock(StreamingHandle.class);
        AtomicReference<StreamingChatResponseHandler> callback = new AtomicReference<>();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            callback.set(handler);
            handler.onPartialResponse(new PartialResponse("partial"), new PartialResponseContext(handle));
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var flux = new ChatClient(mock(ChatModel.class), streaming).prompt().user("question").stream().chatResponse();
        assertThatThrownBy(() -> flux.blockLast(Duration.ofMillis(50))).isInstanceOf(IllegalStateException.class);
        verify(handle).cancel();

        doAnswer(invocation -> {
            ((StreamingChatResponseHandler) invocation.getArgument(1)).onError(new IllegalStateException("provider unavailable"));
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThatThrownBy(() -> flux.blockLast(Duration.ofSeconds(1))).hasMessage("provider unavailable");
    }

    private static dev.langchain4j.model.chat.response.ChatResponse nativeResponse(AiMessage message) {
        return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(message)
                .modelName("test-model").tokenUsage(new TokenUsage(10, 20)).build();
    }
}
