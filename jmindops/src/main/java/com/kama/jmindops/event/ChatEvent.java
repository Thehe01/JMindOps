package com.kama.jmindops.event;

import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class ChatEvent {
    private String agentId;
    private String sessionId;
    private String userInput;
    private String generationId;

    public ChatEvent() {}
    public ChatEvent(String agentId, String sessionId, String userInput, String generationId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.userInput = userInput;
        this.generationId = generationId;
    }

    public ChatEvent(String agentId, String sessionId, String userInput) {
        this(agentId, sessionId, userInput, null);
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
}
