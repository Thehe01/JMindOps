package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.AgentVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class GetAgentsResponse {
    private AgentVO[] agents;

    public GetAgentsResponse() {}
    public GetAgentsResponse(AgentVO[] agents) {
        this.agents = agents;
    }

    public AgentVO[] getAgents() { return agents; }
    public void setAgents(AgentVO[] agents) { this.agents = agents; }

    public static GetAgentsResponseBuilder builder() { return new GetAgentsResponseBuilder(); }
    public static class GetAgentsResponseBuilder {
        private AgentVO[] agents;
        public GetAgentsResponseBuilder() {}
        public GetAgentsResponseBuilder agents(AgentVO[] agents) { this.agents = agents; return this; }
        public GetAgentsResponse build() { return new GetAgentsResponse(agents); }
    }
}
