package com.kama.jmindops.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LLMTraceAspectTest {

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
