package com.kama.jmindops.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChatClientConfigConditionTest {

    @Test
    void cloudProviderRemainsAvailableWhenSelectorDefaultsToNone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "none")
                .withProperty("spring.ai.deepseek.api-key", "configured");

        assertThat(new MultiChatClientConfig.DeepSeekConfiguredCondition()
                .matches(context(environment), null)).isTrue();
    }

    @Test
    void explicitOllamaSelectionDisablesCloudProvidersAndEnablesOllama() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "ollama")
                .withProperty("spring.ai.deepseek.api-key", "configured");
        ConditionContext context = context(environment);

        assertThat(new MultiChatClientConfig.DeepSeekConfiguredCondition().matches(context, null)).isFalse();
        assertThat(new MultiChatClientConfig.OllamaConfiguredCondition().matches(context, null)).isTrue();
    }

    @Test
    void ollamaIsOptIn() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "none");

        assertThat(new MultiChatClientConfig.OllamaConfiguredCondition()
                .matches(context(environment), null)).isFalse();
    }

    private static ConditionContext context(MockEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }
}
