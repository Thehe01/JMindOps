package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateAgentResponse {
    private String agentId;

    public CreateAgentResponse() {}
    public CreateAgentResponse(String agentId) {
        this.agentId = agentId;
    }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public static CreateAgentResponseBuilder builder() { return new CreateAgentResponseBuilder(); }
    public static class CreateAgentResponseBuilder {
        private String agentId;
        public CreateAgentResponseBuilder() {}
        public CreateAgentResponseBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public CreateAgentResponse build() { return new CreateAgentResponse(agentId); }
    }
}
