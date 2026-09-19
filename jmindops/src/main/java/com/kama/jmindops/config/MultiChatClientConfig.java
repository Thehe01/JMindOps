package com.kama.jmindops.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class MultiChatClientConfig {

    @Configuration(proxyBeanMethods = false)
    @Conditional(DeepSeekConfiguredCondition.class)
    @Import(DeepSeekChatAutoConfiguration.class)
    static class DeepSeekConfiguration {
        @Bean("deepseek-chat")
        @ConditionalOnMissingBean(name = "deepseek-chat")
        ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
            return ChatClient.create(deepSeekChatModel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(ZhiPuAiConfiguredCondition.class)
    @Import(ZhiPuAiChatAutoConfiguration.class)
    static class ZhiPuAiConfiguration {
        @Bean("glm-4.6")
        @ConditionalOnMissingBean(name = "glm-4.6")
        ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
            return ChatClient.create(zhiPuAiChatModel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(GoogleGenAiConfiguredCondition.class)
    @Import(GoogleGenAiChatAutoConfiguration.class)
    static class GoogleGenAiConfiguration {
        @Bean("gemini-2.5")
        @ConditionalOnMissingBean(name = "gemini-2.5")
        ChatClient googleGenAiChatClient(GoogleGenAiChatModel googleGenAiChatModel) {
            return ChatClient.create(googleGenAiChatModel);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(OllamaConfiguredCondition.class)
    @Import(OllamaChatAutoConfiguration.class)
    static class OllamaConfiguration {
        @Bean("ollama-qwen2.5")
        @ConditionalOnMissingBean(name = "ollama-qwen2.5")
        ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
            return ChatClient.create(ollamaChatModel);
        }
    }

    private abstract static class ProviderConfiguredCondition implements Condition {
        private final String provider;
        private final String apiKeyProperty;

        private ProviderConfiguredCondition(String provider, String apiKeyProperty) {
            this.provider = provider;
            this.apiKeyProperty = apiKeyProperty;
        }

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String apiKey = context.getEnvironment().getProperty(apiKeyProperty);
            if (!StringUtils.hasText(apiKey)) {
                return false;
            }
            String selectedProvider = context.getEnvironment().getProperty("spring.ai.model.chat");
            return !StringUtils.hasText(selectedProvider)
                    || "none".equalsIgnoreCase(selectedProvider)
                    || provider.equalsIgnoreCase(selectedProvider);
        }
    }

    public static final class OllamaConfiguredCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String selectedProvider = context.getEnvironment().getProperty("spring.ai.model.chat");
            return "ollama".equalsIgnoreCase(selectedProvider);
        }
    }

    public static final class DeepSeekConfiguredCondition extends ProviderConfiguredCondition {
        public DeepSeekConfiguredCondition() {
            super("deepseek", "spring.ai.deepseek.api-key");
        }
    }

    public static final class ZhiPuAiConfiguredCondition extends ProviderConfiguredCondition {
        public ZhiPuAiConfiguredCondition() {
            super("zhipuai", "spring.ai.zhipuai.api-key");
        }
    }

    public static final class GoogleGenAiConfiguredCondition extends ProviderConfiguredCondition {
        public GoogleGenAiConfiguredCondition() {
            super("google-genai", "spring.ai.google.genai.api-key");
        }
    }
}
