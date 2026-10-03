package com.kama.jmindops.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kama.jmindops.ai.ChatResponse;
import com.kama.jmindops.ai.Prompt;
import com.kama.jmindops.ai.ChatResponseMetadata;
import com.kama.jmindops.ai.Usage;
import com.kama.jmindops.ai.ChatClient;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LLMTraceAspectTest {

    @Test
    void facadeRequestCrossesTheTraceAspectWithoutLoggingPromptContents() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("reply")).build());
        AspectJProxyFactory factory = new AspectJProxyFactory(new ChatClient(model, mock(StreamingChatModel.class)));
        factory.setExposeProxy(true);
        factory.addAspect(new LLMTraceAspect());
        ChatClient client = factory.getProxy();
        Logger logger = (Logger) LoggerFactory.getLogger(LLMTraceAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals("reply", client.prompt().system("private-system-text").user("private-user-text").call().content());
            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("LLM request started"))
                    .anyMatch(message -> message.contains("LLM response completed"))
                    .noneMatch(message -> message.contains("private-system-text") || message.contains("private-user-text"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @InjectMocks
    private LLMTraceAspect llmTraceAspect;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Prompt mockPrompt;

    @Mock
    private ChatResponse mockResponse;

    @Mock
    private ChatResponseMetadata mockMetadata;

    @Mock
    private Usage mockUsage;

    @Test
    void testTraceLLMCall_Success() throws Throwable {
        // Arrange
        Object[] args = new Object[]{mockPrompt};
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getTarget()).thenReturn(new Object());
        when(joinPoint.proceed()).thenReturn(mockResponse);
        when(mockResponse.getMetadata()).thenReturn(mockMetadata);
        when(mockMetadata.getUsage()).thenReturn(mockUsage);
        when(mockUsage.toString()).thenReturn("Prompt=10, Generation=20, Total=30");

        // Act
        Object result = llmTraceAspect.traceLLMCall(joinPoint);

        // Assert
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(joinPoint, times(1)).proceed();
    }
}
