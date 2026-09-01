package com.kama.jmindops.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChatClientRegistry {

    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = Collections.unmodifiableMap(new LinkedHashMap<>(chatClients));
    }

    public ChatClient get(String key) {
        return chatClients.get(key);
    }

    public Map<String, ChatClient> getChatClients() {
        return chatClients;
    }
}
