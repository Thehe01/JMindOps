package com.kama.jmindops.config;

import com.kama.jmindops.ai.ChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MultiChatClientConfig.class, McpClientConfig.class, ChatClientRegistry.class);

    @Test
    void defaultConfigurationDoesNotInitializeProvidersOrMcp() {
        runner.withPropertyValues("app.ai.model.chat=none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClientRegistry.class).getChatClients()).isEmpty();
            assertThat(context).doesNotHaveBean(McpClientConfig.McpCallbackProvider.class);
        });
    }

    @Test
    void cloudModelAliasesRemainStableAndDoNotRequireNetworkAtStartup() {
        runner.withPropertyValues("app.ai.model.chat=none", "app.ai.deepseek.api-key=test",
                "app.ai.zhipuai.api-key=test", "app.ai.google.genai.api-key=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClientRegistry.class).getChatClients().keySet())
                    .containsExactlyInAnyOrder("deepseek-chat", "glm-4.6", "gemini-2.5");
            assertThat(context).doesNotHaveBean("ollama-qwen2.5");
        });
    }

    @Test
    void explicitOllamaSelectionIsolatesTheLocalModel() {
        runner.withPropertyValues("app.ai.model.chat=ollama", "app.ai.deepseek.api-key=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChatClient.class);
            assertThat(context.getBean(ChatClientRegistry.class).getChatClients().keySet()).containsExactly("ollama-qwen2.5");
        });
    }

    @Test
    void normalizesPreviousOfficialBaseUrlsAndPreservesCustomGateways() {
        assertThat(MultiChatClientConfig.normalizeBaseUrl("deepseek", "https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(MultiChatClientConfig.normalizeBaseUrl("zhipuai", "https://open.bigmodel.cn/api/paas"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(MultiChatClientConfig.normalizeBaseUrl("deepseek", "https://gateway.example/custom/v1/"))
                .isEqualTo("https://gateway.example/custom/v1");
    }
}
