package com.kama.jmindops.config;

import com.kama.jmindops.ai.ChatClient;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** Explicit SDK construction keeps provider opt-in behavior independent of SDK auto-configuration. */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy(exposeProxy = true)
public class MultiChatClientConfig {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Bean("deepseek-chat")
    @Conditional(DeepSeekConfiguredCondition.class)
    @ConditionalOnMissingBean(name = "deepseek-chat")
    ChatClient deepSeekChatClient(Environment environment) {
        return openAiCompatible(environment, "deepseek", "https://api.deepseek.com/v1", "deepseek-chat");
    }

    @Bean("glm-4.6")
    @Conditional(ZhiPuAiConfiguredCondition.class)
    @ConditionalOnMissingBean(name = "glm-4.6")
    ChatClient zhiPuAiChatClient(Environment environment) {
        return openAiCompatible(environment, "zhipuai", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6");
    }

    @Bean("gemini-2.5")
    @Conditional(GoogleGenAiConfiguredCondition.class)
    @ConditionalOnMissingBean(name = "gemini-2.5")
    ChatClient googleGenAiChatClient(Environment environment) {
        String key = environment.getRequiredProperty("app.ai.google.genai.api-key");
        String model = environment.getProperty("app.ai.google.genai.chat.options.model", "gemini-2.5-flash");
        return new ChatClient(
                GoogleAiGeminiChatModel.builder().apiKey(key).modelName(model).timeout(TIMEOUT).maxRetries(0).build(),
                GoogleAiGeminiStreamingChatModel.builder().apiKey(key).modelName(model).timeout(TIMEOUT).build());
    }

    @Bean("ollama-qwen2.5")
    @Conditional(OllamaConfiguredCondition.class)
    @ConditionalOnMissingBean(name = "ollama-qwen2.5")
    ChatClient ollamaChatClient(Environment environment) {
        String baseUrl = environment.getProperty("app.ai.ollama.base-url", "http://localhost:11434");
        String model = environment.getProperty("app.ai.ollama.chat.options.model", "qwen2.5");
        return new ChatClient(
                OllamaChatModel.builder().baseUrl(baseUrl).modelName(model).timeout(TIMEOUT).maxRetries(0).build(),
                OllamaStreamingChatModel.builder().baseUrl(baseUrl).modelName(model).timeout(TIMEOUT).build());
    }

    private static ChatClient openAiCompatible(Environment environment, String provider, String defaultUrl, String defaultModel) {
        String prefix = "app.ai." + provider;
        String key = environment.getRequiredProperty(prefix + ".api-key");
        String baseUrl = normalizeBaseUrl(provider, environment.getProperty(prefix + ".base-url", defaultUrl));
        String model = environment.getProperty(prefix + ".chat.options.model", defaultModel);
        return new ChatClient(
                OpenAiChatModel.builder().apiKey(key).baseUrl(baseUrl).modelName(model).timeout(TIMEOUT).maxRetries(0).build(),
                OpenAiStreamingChatModel.builder().apiKey(key).baseUrl(baseUrl).modelName(model).timeout(TIMEOUT).build());
    }

    static String normalizeBaseUrl(String provider, String url) {
        String base = url.replaceAll("/+$", "");
        if ("deepseek".equals(provider) && "https://api.deepseek.com".equals(base)) return base + "/v1";
        if ("zhipuai".equals(provider) && base.endsWith("/api/paas")) return base + "/v4";
        return base;
    }

    private abstract static class ProviderConfiguredCondition implements Condition {
        private final String provider;
        private final String apiKeyProperty;
        private ProviderConfiguredCondition(String provider, String apiKeyProperty) {
            this.provider = provider;
            this.apiKeyProperty = apiKeyProperty;
        }
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (!StringUtils.hasText(context.getEnvironment().getProperty(apiKeyProperty))) return false;
            String selected = context.getEnvironment().getProperty("app.ai.model.chat");
            return !StringUtils.hasText(selected) || "none".equalsIgnoreCase(selected) || provider.equalsIgnoreCase(selected);
        }
    }
    public static final class OllamaConfiguredCondition implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return "ollama".equalsIgnoreCase(context.getEnvironment().getProperty("app.ai.model.chat"));
        }
    }
    public static final class DeepSeekConfiguredCondition extends ProviderConfiguredCondition {
        public DeepSeekConfiguredCondition() { super("deepseek", "app.ai.deepseek.api-key"); }
    }
    public static final class ZhiPuAiConfiguredCondition extends ProviderConfiguredCondition {
        public ZhiPuAiConfiguredCondition() { super("zhipuai", "app.ai.zhipuai.api-key"); }
    }
    public static final class GoogleGenAiConfiguredCondition extends ProviderConfiguredCondition {
        public GoogleGenAiConfiguredCondition() { super("google-genai", "app.ai.google.genai.api-key"); }
    }
}
