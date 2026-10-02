package com.kama.jmindops.ai;

import java.util.List;

public interface ChatMemory {
    void add(String sessionId, Message message);
    default void add(String sessionId, List<Message> messages) { messages.forEach(message -> add(sessionId, message)); }
    List<Message> get(String sessionId);
    void clear(String sessionId);
}
