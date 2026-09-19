package com.kama.jmindops;

import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiChatAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.zhipuai.autoconfigure.ZhiPuAiImageAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = {
        DeepSeekChatAutoConfiguration.class,
        ZhiPuAiChatAutoConfiguration.class,
        ZhiPuAiEmbeddingAutoConfiguration.class,
        ZhiPuAiImageAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class,
        OllamaChatAutoConfiguration.class
})
public class JMindOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JMindOpsApplication.class, args);
    }

}
